package com.techtok.backend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class WebClientConfig {
    @Bean
    fun webClient(): WebClient =
        WebClient
            .builder()
            .codecs { configurer ->
                configurer.defaultCodecs().maxInMemorySize(20 * 1024 * 1024) // 20 MiB per JSON object
            }.build()
}
