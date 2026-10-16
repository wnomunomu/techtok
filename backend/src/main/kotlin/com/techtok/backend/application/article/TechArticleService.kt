package com.techtok.backend.application.article

import com.fasterxml.jackson.databind.ObjectMapper
import com.techtok.backend.application.article.response.ArticleFeedResponse
import com.techtok.backend.application.article.response.FeedCursor
import com.techtok.backend.application.article.response.LatestCursor
import com.techtok.backend.application.article.response.PopularCursor
import com.techtok.backend.application.article.response.QiitaArticle
import com.techtok.backend.application.article.response.RandomCursor
import com.techtok.backend.application.article.response.TechArticleResponse
import com.techtok.backend.application.article.response.UpdatedCursor
import com.techtok.backend.application.openai.OpenAiService
import com.techtok.backend.domain.techarticle.ArticleView
import com.techtok.backend.domain.techarticle.TechArticle
import com.techtok.backend.domain.techarticle.TechArticleRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

@Service
class TechArticleService(
    private val techArticleRepository: TechArticleRepository,
    private val webClient: WebClient,
    private val openAiService: OpenAiService,
    private val objectMapper: ObjectMapper,
    @Value("\${app.article-retention-days}") private val retentionDays: Long,
    @Value("\${app.summary-source-word-limit}") private val summarySourceWordLimit: Int,
    @Value("\${app.fetch-interval-days}") private val fetchIntervalDays: Long,
    @Value("\${app.article-cleanup-interval-days}") private val cleanupIntervalDays: Long,
) {
    private val logger = LoggerFactory.getLogger(TechArticleService::class.java)

    @Value("\${app.random-refresh-limit}")
    private var randomRefreshLimit: Int = 0

    @Value("\${app.qiita.access-token:}")
    private var qiitaAccessToken: String = ""

    private val batchRunning = AtomicBoolean(false)

    init {
        require(retentionDays > 0) { "APP_ARTICLE_RETENTION_DAYS must be positive" }
        require(summarySourceWordLimit > 0) { "APP_SUMMARY_SOURCE_WORD_LIMIT must be positive" }
        require(fetchIntervalDays > 0) { "APP_FETCH_INTERVAL_DAYS must be positive" }
        require(cleanupIntervalDays > 0) { "APP_ARTICLE_CLEANUP_INTERVAL_DAYS must be positive" }
    }

    companion object {
        private const val QIITA_API_URL = "https://qiita.com/api/v2/items"
        private val ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        private val CURSOR_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        private const val PER_PAGE = 100
        private const val SUMMARY_BATCH_SIZE = 100
        private const val SUMMARY_CONCURRENCY = 3
        private val ARTICLE_ZONE = ZoneId.of("Asia/Tokyo")
        private const val MAX_PAGES = 100
        private const val DEFAULT_LIMIT = 20
    }

    @Scheduled(fixedRateString = "\${app.fetch-interval-days}d")
    fun fetchAndSaveArticles() {
        if (!batchRunning.compareAndSet(false, true)) {
            return
        }
        try {
            try {
                logger.info("Fetching articles from Qiita API")
                fetchQiitaItems(retentionCutoff())
                logger.info("Finished fetching articles from Qiita API")
            } catch (e: Exception) {
                logger.error("Error fetching articles from Qiita API", e)
            }
            summarizePendingArticles()
        } catch (e: Exception) {
            logger.error("Error processing pending article summaries", e)
        } finally {
            batchRunning.set(false)
        }
    }

    internal fun retentionCutoff(now: LocalDateTime = LocalDateTime.now(ARTICLE_ZONE)): LocalDateTime = now.minusDays(retentionDays)

    @Scheduled(fixedRateString = "\${app.article-cleanup-interval-days}d")
    fun deleteExpiredArticles() {
        try {
            val deletedRows = techArticleRepository.deleteExpired(retentionCutoff())
            logger.info("Deleted $deletedRows expired articles")
        } catch (e: Exception) {
            logger.error("Error deleting expired articles", e)
        }
    }

    private fun summarizePendingArticles() {
        Executors.newFixedThreadPool(SUMMARY_CONCURRENCY).use { executor ->
            var afterId = 0L
            while (true) {
                val articles = techArticleRepository.findPendingSummaries(afterId, PageRequest.of(0, SUMMARY_BATCH_SIZE))
                if (articles.isEmpty()) {
                    return
                }
                val tasks =
                    articles.map { article ->
                        executor.submit {
                            try {
                                val summary = openAiService.generateSummary(article.summarySource)
                                if (!summary.isNullOrBlank()) {
                                    techArticleRepository.saveSummary(article.id, summary)
                                    logger.info("Summarized article: ${article.id}")
                                }
                            } catch (e: Exception) {
                                logger.error("Error summarizing article: ${article.id}", e)
                            }
                        }
                    }
                tasks.forEach { it.get() }
                afterId = articles.last().id
            }
        }
    }

    @Scheduled(cron = "\${app.random-refresh-cron}")
    @Transactional
    fun refreshRandomKeys() {
        val updatedRows = techArticleRepository.refreshRandomKeys(randomRefreshLimit)
        logger.info("Refreshed random_key for $updatedRows articles")
    }

    private fun fetchQiitaItems(cutoff: LocalDateTime) {
        fetchQiitaRange(cutoff.toLocalDate(), LocalDate.now(ARTICLE_ZONE), cutoff)
    }

    private fun fetchQiitaRange(
        from: LocalDate,
        through: LocalDate,
        cutoff: LocalDateTime,
    ) {
        for (page in 1..MAX_PAGES) {
            val uri =
                UriComponentsBuilder
                    .fromHttpUrl(QIITA_API_URL)
                    .queryParam("page", page)
                    .queryParam("per_page", PER_PAGE)
                    .queryParam("query", "created:>=$from created:<=$through")
                    .build()
                    .encode()
                    .toUri()
            var request = webClient.get().uri(uri)
            if (qiitaAccessToken.isNotBlank()) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer $qiitaAccessToken")
            }
            val response =
                try {
                    request.retrieve().toEntityList(QiitaArticle::class.java).block()
                } catch (e: WebClientResponseException) {
                    if (generateSequence<Throwable>(e) { it.cause }.none { it is DataBufferLimitException }) {
                        throw e
                    }
                    logger.error("Skipping oversized Qiita response: range=$from through $through, page=$page, uri=$uri", e)
                    // Preserve pagination headers so a decoding failure does not abort older pages.
                    ResponseEntity(emptyList<QiitaArticle>(), e.headers, e.statusCode)
                }
            val totalCount = response?.headers?.getFirst("Total-Count")?.toLongOrNull()
            if (totalCount != null && totalCount > PER_PAGE * MAX_PAGES && from < through) {
                splitQiitaRange(from, through, cutoff)
                return
            }
            response?.body.orEmpty().forEach { article ->
                try {
                    if (!parseQiitaDate(article.createdAt).isBefore(cutoff)) {
                        upsertFromQiita(article)
                    }
                } catch (e: Exception) {
                    logger.error("Skipping Qiita article: id=${article.id}, url=${article.url}", e)
                }
            }
            // A capped API response may omit the next link even when articles remain.
            if (page == MAX_PAGES && response?.body.orEmpty().size == PER_PAGE && totalCount != (PER_PAGE * MAX_PAGES).toLong()) {
                splitQiitaRange(from, through, cutoff)
                return
            }
            if (!hasNextPage(response?.headers)) {
                return
            }
        }
    }

    private fun splitQiitaRange(
        from: LocalDate,
        through: LocalDate,
        cutoff: LocalDateTime,
    ) {
        check(from < through) { "Qiita API page limit reached for a single day: $from; some articles could not be fetched" }
        val midpoint = from.plusDays(ChronoUnit.DAYS.between(from, through) / 2)
        logger.info("Splitting Qiita fetch range: $from through $through")
        fetchQiitaRange(midpoint.plusDays(1), through, cutoff)
        fetchQiitaRange(from, midpoint, cutoff)
    }

    // Existing Qiita timestamps were stored as JST wall-clock values without an offset.
    private fun parseQiitaDate(value: String): LocalDateTime =
        OffsetDateTime.parse(value, ISO_FORMATTER).atZoneSameInstant(ARTICLE_ZONE).toLocalDateTime()

    internal fun extractSummarySource(text: String): String =
        text
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(" ")
            .take(summarySourceWordLimit)
            .joinToString(" ")

    private fun hasNextPage(headers: HttpHeaders?): Boolean {
        val linkHeader = headers?.getFirst(HttpHeaders.LINK) ?: return false
        return linkHeader.contains("rel=\"next\"")
    }

    private fun upsertFromQiita(qiitaArticle: QiitaArticle) {
        val sourceUrl = qiitaArticle.url
        val qiitaId = qiitaArticle.id
        val updatedAt = parseQiitaDate(qiitaArticle.updatedAt)
        val publishedAt = parseQiitaDate(qiitaArticle.createdAt)

        val existing =
            techArticleRepository.findByQiitaId(qiitaId)
                ?: techArticleRepository.findBySourceUrl(sourceUrl)

        if (existing != null) {
            val updatedArticle =
                existing.copy(
                    qiitaId = qiitaId,
                    updatedAt = updatedAt,
                    stocksCount = qiitaArticle.stocksCount,
                )
            techArticleRepository.save(updatedArticle)
            return
        }

        val techArticle =
            TechArticle(
                sourceUrl = sourceUrl,
                qiitaId = qiitaId,
                title = qiitaArticle.title,
                author = qiitaArticle.user.name + qiitaArticle.user.id,
                publishedAt = publishedAt,
                updatedAt = updatedAt,
                stocksCount = qiitaArticle.stocksCount,
                summary = null,
                summarySource = extractSummarySource(qiitaArticle.renderedBody),
            )

        techArticleRepository.save(techArticle)
        logger.info("Saved new article: ${techArticle.title}")
    }

    fun getAllArticles(): List<TechArticleResponse> =
        techArticleRepository
            .findAllByOrderByCreatedAtDesc()
            .map { article -> toResponse(article) }

    fun getMixedArticles(
        limit: Int?,
        cursor: String?,
    ): ArticleFeedResponse {
        val safeLimit = (limit ?: DEFAULT_LIMIT).coerceIn(1, 100)
        val baseBucketSize = safeLimit / 4
        val remainder = safeLimit % 4
        val latestLimit = baseBucketSize + remainder
        val updatedLimit = baseBucketSize
        val popularLimit = baseBucketSize
        val randomLimit = baseBucketSize

        val decodedCursor = decodeCursor(cursor)
        val seed = decodedCursor?.seed ?: UUID.randomUUID().toString()

        val latest =
            fetchLatestArticles(
                latestLimit * 2,
                decodedCursor?.latest,
            )
        val updated =
            fetchUpdatedArticles(
                updatedLimit * 2,
                decodedCursor?.updated,
            )
        val popular =
            fetchPopularArticles(
                popularLimit * 2,
                decodedCursor?.popular,
            )
        val random =
            fetchRandomArticles(
                randomLimit * 2,
                decodedCursor?.random,
            )

        val combined = LinkedHashMap<Long, ArticleView>()
        val allBuckets = listOf(latest, updated, popular, random)
        allBuckets.forEach { bucket ->
            bucket.forEach { article ->
                article.id?.let { combined.putIfAbsent(it, article) }
            }
        }

        var mixed = combined.values.toList()
        mixed = mixed.shuffled(Random(seed.hashCode()))
        var limited = mixed.take(safeLimit)

        if (limited.size < safeLimit) {
            val fallbackCursor = latest.lastOrNull()?.let { toLatestCursor(it) } ?: decodedCursor?.latest
            val fallback =
                fetchLatestArticles(
                    safeLimit - limited.size,
                    fallbackCursor,
                )
            fallback.forEach { article ->
                article.id?.let { combined.putIfAbsent(it, article) }
            }
            mixed = combined.values.toList().shuffled(Random(seed.hashCode()))
            limited = mixed.take(safeLimit)
        }

        val nextCursor =
            if (limited.isEmpty()) {
                null
            } else {
                encodeCursor(
                    FeedCursor(
                        latest = latest.lastOrNull()?.let { toLatestCursor(it) } ?: decodedCursor?.latest,
                        updated = updated.lastOrNull()?.let { toUpdatedCursor(it) } ?: decodedCursor?.updated,
                        popular = popular.lastOrNull()?.let { toPopularCursor(it) } ?: decodedCursor?.popular,
                        random = random.lastOrNull()?.let { toRandomCursor(it) } ?: decodedCursor?.random,
                        seed = seed,
                    ),
                )
            }

        return ArticleFeedResponse(
            items = limited.map { article -> toResponse(article) },
            nextCursor = nextCursor,
        )
    }

    fun getArticleById(id: Long): TechArticleResponse? =
        techArticleRepository.findCompletedById(id)?.let { article ->
            toResponse(article)
        }

    private fun toResponse(article: ArticleView): TechArticleResponse =
        TechArticleResponse(
            id = article.id,
            title = article.title,
            author = article.author,
            summary = article.summary,
            sourceUrl = article.sourceUrl,
            publishedAt = article.publishedAt,
            updatedAt = article.updatedAt,
            stocksCount = article.stocksCount,
            createdAt = article.createdAt,
        )

    private fun fetchLatestArticles(
        limit: Int,
        cursor: LatestCursor?,
    ): List<ArticleView> {
        val pageable =
            org.springframework.data.domain.PageRequest
                .of(0, limit)
        if (cursor == null) {
            return techArticleRepository.findLatestForFeed(pageable)
        }
        val publishedAt = LocalDateTime.parse(cursor.publishedAt, CURSOR_FORMATTER)
        return techArticleRepository.findLatestForFeedBefore(publishedAt, cursor.id, pageable)
    }

    private fun fetchUpdatedArticles(
        limit: Int,
        cursor: UpdatedCursor?,
    ): List<ArticleView> {
        val pageable =
            org.springframework.data.domain.PageRequest
                .of(0, limit)
        if (cursor == null) {
            return techArticleRepository.findUpdatedForFeed(pageable)
        }
        val updatedAt =
            cursor.updatedAt
                .takeIf { it.isNotBlank() }
                ?.let { LocalDateTime.parse(it, CURSOR_FORMATTER) }
                ?: return techArticleRepository.findUpdatedForFeed(pageable)
        return techArticleRepository.findUpdatedForFeedBefore(updatedAt, cursor.id, pageable)
    }

    private fun fetchPopularArticles(
        limit: Int,
        cursor: PopularCursor?,
    ): List<ArticleView> {
        val pageable =
            org.springframework.data.domain.PageRequest
                .of(0, limit)
        if (cursor == null) {
            return techArticleRepository.findPopularForFeed(pageable)
        }
        val publishedAt = LocalDateTime.parse(cursor.publishedAt, CURSOR_FORMATTER)
        return techArticleRepository.findPopularForFeedBefore(cursor.stocksCount, publishedAt, cursor.id, pageable)
    }

    private fun fetchRandomArticles(
        limit: Int,
        cursor: RandomCursor?,
    ): List<ArticleView> {
        val pageable =
            org.springframework.data.domain.PageRequest
                .of(0, limit)
        val firstBatch =
            if (cursor == null) {
                techArticleRepository.findRandomForFeed(pageable)
            } else {
                techArticleRepository.findRandomForFeedAfter(cursor.randomKey, cursor.id, pageable)
            }
        if (firstBatch.size >= limit || cursor == null) {
            return firstBatch
        }
        val remaining = limit - firstBatch.size
        val wrapBatch =
            techArticleRepository.findRandomForFeed(
                org.springframework.data.domain.PageRequest
                    .of(0, remaining),
            )
        return firstBatch + wrapBatch
    }

    private fun decodeCursor(cursor: String?): FeedCursor? {
        if (cursor.isNullOrBlank()) {
            return null
        }
        val json = String(Base64.getUrlDecoder().decode(cursor))
        return objectMapper.readValue(json, FeedCursor::class.java)
    }

    private fun encodeCursor(cursor: FeedCursor): String {
        val json = objectMapper.writeValueAsString(cursor)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }

    private fun toLatestCursor(article: ArticleView): LatestCursor =
        LatestCursor(
            publishedAt = article.publishedAt.format(CURSOR_FORMATTER),
            id = article.id ?: 0,
        )

    private fun toUpdatedCursor(article: ArticleView): UpdatedCursor =
        UpdatedCursor(
            updatedAt = article.updatedAt?.format(CURSOR_FORMATTER) ?: "",
            id = article.id ?: 0,
        )

    private fun toPopularCursor(article: ArticleView): PopularCursor =
        PopularCursor(
            stocksCount = article.stocksCount,
            publishedAt = article.publishedAt.format(CURSOR_FORMATTER),
            id = article.id ?: 0,
        )

    private fun toRandomCursor(article: ArticleView): RandomCursor =
        RandomCursor(
            randomKey = article.randomKey,
            id = article.id ?: 0,
        )
}
