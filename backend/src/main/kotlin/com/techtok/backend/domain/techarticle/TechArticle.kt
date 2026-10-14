package com.techtok.backend.domain.techarticle

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

@Entity
@Table(name = "tech_articles")
data class TechArticle(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "title", nullable = false, length = 500)
    @NotBlank(message = "タイトルは必須です")
    @Size(max = 200, message = "タイトルは200文字以内で入力してください")
    val title: String,
    @Column(name = "summary", columnDefinition = "TEXT")
    val summary: String?,
    @Column(name = "summary_source", columnDefinition = "TEXT")
    val summarySource: String? = null,
    @Column(name = "author", nullable = false, length = 100)
    @NotBlank(message = "著者は必須です")
    @Size(max = 100, message = "著者は100文字以内で入力してください")
    val author: String,
    @Column(name = "source_url", nullable = false, length = 1000, unique = true)
    @NotBlank(message = "ソースURLは必須です")
    @Size(max = 1000, message = "ソースURLは1000文字以内で入力してください")
    @Pattern(regexp = "https?://.*", message = "有効なURLを入力してください")
    val sourceUrl: String,
    @Column(name = "qiita_id", length = 64, unique = true)
    val qiitaId: String? = null,
    @Column(name = "published_at", nullable = false)
    @NotNull(message = "公開日時は必須です")
    val publishedAt: LocalDateTime,
    @Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null,
    @Column(name = "stocks_count", nullable = false)
    val stocksCount: Int = 0,
    @Column(name = "random_key", nullable = false)
    val randomKey: Double = Math.random(),
    @Column(name = "created_at", nullable = false)
    @NotNull(message = "作成日時は必須です")
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    constructor() : this(
        id = null,
        title = "",
        summary = "",
        author = "",
        sourceUrl = "",
        qiitaId = null,
        publishedAt = LocalDateTime.now(),
        updatedAt = null,
        stocksCount = 0,
        randomKey = Math.random(),
        createdAt = LocalDateTime.now(),
    )
}
