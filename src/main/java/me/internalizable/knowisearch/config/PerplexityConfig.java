package me.internalizable.knowisearch.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class PerplexityConfig {

    @Value("${perplexity.api.key}")
    private String apiKey;

    @Value("${perplexity.api.url}")
    private String apiUrl;

    @Bean
    public RestClient perplexityRestClient() {
        return RestClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}

