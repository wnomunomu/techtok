package com.techtok.backend.domain.techarticle

import java.time.LocalDateTime

data class ArticleView(
    val id: Long?,
    val title: String,
    val summary: String,
    val author: String,
    val sourceUrl: String,
    val publishedAt: LocalDateTime,
    val updatedAt: LocalDateTime?,
    val stocksCount: Int,
    val randomKey: Double,
    val createdAt: LocalDateTime,
)

data class PendingSummary(
    val id: Long,
    val summarySource: String,
)
