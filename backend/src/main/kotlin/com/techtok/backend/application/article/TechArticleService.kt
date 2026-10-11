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
import org.springframework.http.HttpHeaders
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.util.UriComponentsBuilder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

@Service
class TechArticleService(
    private val techArticleRepository: TechArticleRepository,
    private val webClient: WebClient,
    private val openAiService: OpenAiService,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(TechArticleService::class.java)

    @Value("\${app.random-refresh-limit}")
    private var randomRefreshLimit: Int = 0

    @Value("\${app.qiita.access-token:}")
    private var qiitaAccessToken: String = ""

    companion object {
        private const val QIITA_API_URL = "https://qiita.com/api/v2/items"
        private val ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        private val CURSOR_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        private const val PER_PAGE = 100
        private const val POPULAR_QUERY = "stocks:>=50"
        private const val POPULAR_MAX_PAGES = 3
        private const val MAX_PAGES = 100
        private const val DEFAULT_LIMIT = 20
    }

    @Scheduled(fixedRateString = "\${app.fetch-rate-ms}")
    fun fetchAndSaveArticles() {
        try {
            logger.info("Fetching articles from Qiita API (latest)")
            val latestPublishedAt = techArticleRepository.findTopByOrderByPublishedAtDesc()?.publishedAt
            val latestArticles = fetchQiitaItems(null, latestPublishedAt)
            latestArticles.forEach { qiitaArticle ->
                upsertFromQiita(qiitaArticle)
            }

            logger.info("Fetching articles from Qiita API (popular)")
            val popularArticles = fetchQiitaItems(POPULAR_QUERY, null, POPULAR_MAX_PAGES)
            popularArticles.forEach { qiitaArticle ->
                upsertFromQiita(qiitaArticle)
            }

            logger.info("Finished processing articles from Qiita API")
        } catch (e: Exception) {
            logger.error("Error fetching articles from Qiita API", e)
        }
    }

    @Scheduled(cron = "\${app.random-refresh-cron}")
    @Transactional
    fun refreshRandomKeys() {
        val updatedRows = techArticleRepository.refreshRandomKeys(randomRefreshLimit)
        logger.info("Refreshed random_key for $updatedRows articles")
    }

    private fun fetchQiitaItems(
        query: String?,
        stopAtPublishedAt: LocalDateTime?,
        maxPages: Int = MAX_PAGES,
    ): List<QiitaArticle> {
        val results = mutableListOf<QiitaArticle>()
        var page = 1
        var hasNext = true

        while (hasNext) {
            val uriBuilder =
                UriComponentsBuilder
                    .fromHttpUrl(QIITA_API_URL)
                    .queryParam("page", page)
                    .queryParam("per_page", PER_PAGE)
            if (!query.isNullOrBlank()) {
                uriBuilder.queryParam("query", query)
            }

            var request =
                webClient
                    .get()
                    .uri(uriBuilder.build().encode().toUri())
            if (qiitaAccessToken.isNotBlank()) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer $qiitaAccessToken")
            }
            val response =
                request
                    .retrieve()
                    .toEntityList(QiitaArticle::class.java)
                    .block()

            val items = response?.body ?: emptyList()
            results.addAll(items)

            hasNext = hasNextPage(response?.headers)
            if (!items.isEmpty() && stopAtPublishedAt != null) {
                val oldestPublishedAt = LocalDateTime.parse(items.last().createdAt, ISO_FORMATTER)
                if (oldestPublishedAt.isBefore(stopAtPublishedAt)) {
                    hasNext = false
                }
            }
            if (page >= maxPages) {
                hasNext = false
            }
            page += 1
        }

        return results
    }

    private fun hasNextPage(headers: HttpHeaders?): Boolean {
        val linkHeader = headers?.getFirst(HttpHeaders.LINK) ?: return false
        return linkHeader.contains("rel=\"next\"")
    }

    private fun upsertFromQiita(qiitaArticle: QiitaArticle) {
        val sourceUrl = qiitaArticle.url
        val qiitaId = qiitaArticle.id
        val updatedAt = LocalDateTime.parse(qiitaArticle.updatedAt, ISO_FORMATTER)
        val publishedAt = LocalDateTime.parse(qiitaArticle.createdAt, ISO_FORMATTER)

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

        val summary = openAiService.generateSummary(qiitaArticle.renderedBody)
        val techArticle =
            TechArticle(
                sourceUrl = sourceUrl,
                qiitaId = qiitaId,
                title = qiitaArticle.title,
                author = qiitaArticle.user.name + qiitaArticle.user.id,
                publishedAt = publishedAt,
                updatedAt = updatedAt,
                stocksCount = qiitaArticle.stocksCount,
                summary = summary,
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
