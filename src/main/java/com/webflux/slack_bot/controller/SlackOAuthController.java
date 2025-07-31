package com.webflux.slack_bot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.webflux.slack_bot.util.TokenStore;
import org.springframework.beans.factory.annotation.Value;  // Add this import
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@RestController
public class SlackOAuthController {
    private final WebClient webClient = WebClient.create("https://slack.com");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${slack.client-id}")
    private String clientId;

    @Value("${slack.client-secret}")
    private String clientSecret;

    @Value("${slack.redirect-uri}")
    private String redirectUri;

    @GetMapping("/slack/oauth/callback")
    public Mono<ResponseEntity<String>> handleOAuthCallback(
            @RequestParam String code,
            @RequestParam(required = false) String state) {

        return webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/oauth.v2.access")
                        .queryParam("code", code)
                        .queryParam("client_id", clientId)
                        .queryParam("client_secret", clientSecret)
                        .queryParam("redirect_uri", redirectUri)
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .map(responseBody -> {
                    try {
                        JsonNode json = objectMapper.readTree(responseBody);
                        if (json.has("ok") && json.get("ok").asBoolean()) {
                            String accessToken = json.get("access_token").asText();
                            String teamId = json.get("team").get("id").asText();
                            TokenStore.storeToken(teamId, accessToken);
                            return ResponseEntity.ok("OAuth successful for team " + teamId);
                        } else {
                            return ResponseEntity.badRequest().body("OAuth error: " + responseBody);
                        }
                    } catch (Exception e) {
                        return ResponseEntity.status(500).body("OAuth response parsing error");
                    }
                })
                .onErrorReturn(ResponseEntity.status(500).body("OAuth error"));
    }
}
