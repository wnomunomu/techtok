package com.techtok.backend.domain.techarticle

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface TechArticleRepository : JpaRepository<TechArticle, Long> {
    @Query(
        "SELECT new com.techtok.backend.domain.techarticle.ArticleView(t.id, t.title, t.summary, t.author, t.sourceUrl, t.publishedAt, t.updatedAt, t.stocksCount, t.randomKey, t.createdAt) FROM TechArticle t WHERE t.summary IS NOT NULL ORDER BY t.createdAt DESC",
    )
    fun findAllOrderByCreatedAtDesc(pageable: Pageable): Page<ArticleView>

    @Query(
        "SELECT new com.techtok.backend.domain.techarticle.ArticleView(t.id, t.title, t.summary, t.author, t.sourceUrl, t.publishedAt, t.updatedAt, t.stocksCount, t.randomKey, t.createdAt) FROM TechArticle t WHERE t.summary IS NOT NULL ORDER BY t.publishedAt DESC",
    )
    fun findAllOrderByPublishedAtDesc(pageable: Pageable): Page<ArticleView>

    @Query(
        "SELECT new com.techtok.backend.domain.techarticle.ArticleView(t.id, t.title, t.summary, t.author, t.sourceUrl, t.publishedAt, t.updatedAt, t.stocksCount, t.randomKey, t.createdAt) FROM TechArticle t WHERE t.summary IS NOT NULL AND t.createdAt <= :untilDate ORDER BY t.createdAt DESC",
    )
    fun findByCreatedAtUntil(
        @Param("untilDate") untilDate: LocalDateTime,
        pageable: Pageable,
    ): Page<ArticleView>

    @Query(
        "SELECT new com.techtok.backend.domain.techarticle.ArticleView(t.id, t.title, t.summary, t.author, t.sourceUrl, t.publishedAt, t.updatedAt, t.stocksCount, t.randomKey, t.createdAt) FROM TechArticle t WHERE t.summary IS NOT NULL AND t.publishedAt <= :untilDate ORDER BY t.publishedAt DESC",
    )
    fun findByPublishedAtUntil(
        @Param("untilDate") untilDate: LocalDateTime,
        pageable: Pageable,
    ): Page<ArticleView>

    fun existsBySourceUrl(sourceUrl: String): Boolean

    fun existsByQiitaId(qiitaId: String): Boolean

    fun findBySourceUrl(sourceUrl: String): TechArticle?

    fun findByQiitaId(qiitaId: String): TechArticle?

    fun findTopByOrderByPublishedAtDesc(): TechArticle?

    @Query(
        "SELECT new com.techtok.backend.domain.techarticle.ArticleView(t.id, t.title, t.summary, t.author, t.sourceUrl, t.publishedAt, t.updatedAt, t.stocksCount, t.randomKey, t.createdAt) FROM TechArticle t WHERE t.summary IS NOT NULL ORDER BY t.publishedAt DESC, t.id DESC",
    )
    fun findLatestForFeed(pageable: org.springframework.data.domain.Pageable): List<ArticleView>

    @Query(
        """
            SELECT new com.techtok.backend.domain.techarticle.ArticleView(t.id, t.title, t.summary, t.author, t.sourceUrl, t.publishedAt, t.updatedAt, t.stocksCount, t.randomKey, t.createdAt) FROM TechArticle t
            WHERE t.summary IS NOT NULL AND (t.publishedAt < :publishedAt
                OR (t.publishedAt = :publishedAt AND t.id < :id))
            ORDER BY t.publishedAt DESC, t.id DESC
            """,
    )
    fun findLatestForFeedBefore(
        @Param("publishedAt") publishedAt: LocalDateTime,
        @Param("id") id: Long,
        pageable: org.springframework.data.domain.Pageable,
    ): List<ArticleView>

    @Query(
        """
            SELECT new com.techtok.backend.domain.techarticle.ArticleView(t.id, t.title, t.summary, t.author, t.sourceUrl, t.publishedAt, t.updatedAt, t.stocksCount, t.randomKey, t.createdAt) FROM TechArticle t
            WHERE t.summary IS NOT NULL AND t.updatedAt IS NOT NULL
            ORDER BY t.updatedAt DESC, t.id DESC
            """,
    )
    fun findUpdatedForFeed(pageable: org.springframework.data.domain.Pageable): List<ArticleView>

    @Query(
        """
            SELECT new com.techtok.backend.domain.techarticle.ArticleView(t.id, t.title, t.summary, t.author, t.sourceUrl, t.publishedAt, t.updatedAt, t.stocksCount, t.randomKey, t.createdAt) FROM TechArticle t
            WHERE t.summary IS NOT NULL AND t.updatedAt IS NOT NULL AND (
                t.updatedAt < :updatedAt
                OR (t.updatedAt = :updatedAt AND t.id < :id)
            )
            ORDER BY t.updatedAt DESC, t.id DESC
            """,
    )
    fun findUpdatedForFeedBefore(
        @Param("updatedAt") updatedAt: LocalDateTime,
        @Param("id") id: Long,
        pageable: org.springframework.data.domain.Pageable,
    ): List<ArticleView>

    @Query(
        "SELECT new com.techtok.backend.domain.techarticle.ArticleView(t.id, t.title, t.summary, t.author, t.sourceUrl, t.publishedAt, t.updatedAt, t.stocksCount, t.randomKey, t.createdAt) FROM TechArticle t WHERE t.summary IS NOT NULL ORDER BY t.stocksCount DESC, t.publishedAt DESC, t.id DESC",
    )
    fun findPopularForFeed(pageable: org.springframework.data.domain.Pageable): List<ArticleView>

    @Query(
        """
            SELECT new com.techtok.backend.domain.techarticle.ArticleView(t.id, t.title, t.summary, t.author, t.sourceUrl, t.publishedAt, t.updatedAt, t.stocksCount, t.randomKey, t.createdAt) FROM TechArticle t
            WHERE t.summary IS NOT NULL AND (t.stocksCount < :stocksCount
                OR (
                    t.stocksCount = :stocksCount
                    AND (
                        t.publishedAt < :publishedAt
                        OR (t.publishedAt = :publishedAt AND t.id < :id)
                    )
                ))
            ORDER BY t.stocksCount DESC, t.publishedAt DESC, t.id DESC
            """,
    )
    fun findPopularForFeedBefore(
        @Param("stocksCount") stocksCount: Int,
        @Param("publishedAt") publishedAt: LocalDateTime,
        @Param("id") id: Long,
        pageable: org.springframework.data.domain.Pageable,
    ): List<ArticleView>

    @Query(
        "SELECT new com.techtok.backend.domain.techarticle.ArticleView(t.id, t.title, t.summary, t.author, t.sourceUrl, t.publishedAt, t.updatedAt, t.stocksCount, t.randomKey, t.createdAt) FROM TechArticle t WHERE t.summary IS NOT NULL ORDER BY t.randomKey ASC, t.id ASC",
    )
    fun findRandomForFeed(pageable: org.springframework.data.domain.Pageable): List<ArticleView>

    @Query(
        """
            SELECT new com.techtok.backend.domain.techarticle.ArticleView(t.id, t.title, t.summary, t.author, t.sourceUrl, t.publishedAt, t.updatedAt, t.stocksCount, t.randomKey, t.createdAt) FROM TechArticle t
            WHERE t.summary IS NOT NULL AND (t.randomKey > :randomKey
                OR (t.randomKey = :randomKey AND t.id > :id))
            ORDER BY t.randomKey ASC, t.id ASC
            """,
    )
    fun findRandomForFeedAfter(
        @Param("randomKey") randomKey: Double,
        @Param("id") id: Long,
        pageable: org.springframework.data.domain.Pageable,
    ): List<ArticleView>

    @Modifying
    @Query(
        value = """
            UPDATE tech_articles
            SET random_key = random()
            WHERE id IN (
                SELECT id
                FROM tech_articles
                ORDER BY random()
                LIMIT :limit
            )
            """,
        nativeQuery = true,
    )
    fun refreshRandomKeys(
        @Param("limit") limit: Int,
    ): Int

    @Query(
        "SELECT new com.techtok.backend.domain.techarticle.ArticleView(t.id, t.title, t.summary, t.author, t.sourceUrl, t.publishedAt, t.updatedAt, t.stocksCount, t.randomKey, t.createdAt) FROM TechArticle t WHERE t.summary IS NOT NULL ORDER BY t.createdAt DESC",
    )
    fun findAllByOrderByCreatedAtDesc(): List<ArticleView>

    @Query(
        "SELECT new com.techtok.backend.domain.techarticle.ArticleView(t.id, t.title, t.summary, t.author, t.sourceUrl, t.publishedAt, t.updatedAt, t.stocksCount, t.randomKey, t.createdAt) FROM TechArticle t WHERE t.summary IS NOT NULL AND t.id = :id",
    )
    fun findCompletedById(
        @Param("id") id: Long,
    ): ArticleView?

    @Query(
        "SELECT new com.techtok.backend.domain.techarticle.PendingSummary(t.id, t.summarySource) FROM TechArticle t WHERE t.summary IS NULL AND t.summarySource IS NOT NULL AND t.id > :afterId ORDER BY t.id",
    )
    fun findPendingSummaries(
        @Param("afterId") afterId: Long,
        pageable: Pageable,
    ): List<PendingSummary>

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE TechArticle t SET t.summary = :summary WHERE t.id = :id AND t.summary IS NULL")
    fun saveSummary(
        @Param("id") id: Long,
        @Param("summary") summary: String,
    ): Int

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM TechArticle t WHERE t.publishedAt < :cutoff")
    fun deleteExpired(
        @Param("cutoff") cutoff: LocalDateTime,
    ): Int
}
