package com.techtok.backend.application.openai

import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono

@Service
class OpenAiService(
    private val webClient: WebClient,
) {
    private val logger = LoggerFactory.getLogger(OpenAiService::class.java)

    @Value("\${openai.api.key}")
    private lateinit var apiKey: String

    @Value("\${openai.model}")
    private lateinit var model: String

    companion object {
        private const val OPENAI_API_URL = "https://api.openai.com/v1/responses"
    }

    fun generateSummary(summarySource: String): String? {
        try {
            logger.info("API Key loaded: ${if (apiKey.isNotEmpty()) "Yes (${apiKey.length} chars)" else "No - empty or null"}")

            val requestBody =
                OpenAiRequest(
                    model = model,
                    input =
                        listOf(
                            OpenAiMessage(
                                role = "user",
                                content =
                                    "以下の本文を2〜3文で簡潔に要約してください：" +
                                        "\n<条件>\n" +
                                        "- 敬語は使わない（だ・である）。\n" +
                                        "- 前置き（例:「このブログは」「この記事では」など）は禁止。\n" +
                                        "- 冗長な表現を避け、要点を出力。" +
                                        "\n<本文>\n" +
                                        summarySource,
                            ),
                        ),
                    maxOutputTokens = 250,
                    reasoning = OpenAiReasoning(effort = "none"),
                    text = OpenAiText(verbosity = "low"),
                )

            val response =
                webClient
                    .post()
                    .uri(OPENAI_API_URL)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono<OpenAiResponse>()
                    .block()

            return extractOutputText(response)
        } catch (e: Exception) {
            if (e is WebClientResponseException) {
                logger.error(
                    "OpenAI API error: status={}, body={}",
                    e.statusCode.value(),
                    e.responseBodyAsString,
                )
            }
            logger.error("Error generating summary with OpenAI", e)
            return null
        }
    }

    private fun extractOutputText(response: OpenAiResponse?): String? {
        if (response == null) {
            return null
        }

        return response
            .output
            .orEmpty()
            .asSequence()
            .flatMap { it.content.orEmpty().asSequence() }
            .firstOrNull { !it.text.isNullOrBlank() }
            ?.text
            ?.trim()
    }
}

data class OpenAiRequest(
    val model: String,
    val input: List<OpenAiMessage>,
    @JsonProperty("max_output_tokens") val maxOutputTokens: Int,
    val reasoning: OpenAiReasoning,
    val text: OpenAiText? = null,
)

data class OpenAiReasoning(
    val effort: String,
)

data class OpenAiText(
    val verbosity: String,
)

data class OpenAiMessage(
    val role: String,
    val content: String,
)

data class OpenAiResponse(
    val output: List<OpenAiOutput>? = null,
)

data class OpenAiOutput(
    val content: List<OpenAiContent>? = null,
)

data class OpenAiContent(
    val type: String,
    val text: String? = null,
)
