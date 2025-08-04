package com.webflux.slack_bot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.Base64;
import java.util.logging.Logger;

@RestController
public class SlackInteractiveController {
    private static final Logger LOGGER = Logger.getLogger(SlackInteractiveController.class.getName());
    private final WebClient jiraWebClient = WebClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${jira.base-url}")
    private String jiraBaseUrl;

    @Value("${jira.email}")
    private String jiraEmail;

    @Value("${jira.api-token}")
    private String jiraApiToken;

    @Value("${jira.project-key}")
    private String jiraProjectKey;

    @PostMapping("/slack/interactive")
    public Mono<ResponseEntity<String>> handleInteractive(@RequestBody String payload) {
        LOGGER.info("Received interactive payload: " + payload);
        try {
            JsonNode json = objectMapper.readTree(payload);
            if (json.has("type") && "view_submission".equals(json.get("type").asText()) && json.has("view") && "jira_ticket_modal".equals(json.get("view").get("callback_id").asText())) {
                JsonNode values = json.get("view").get("state").get("values");

                // Extract fields safely
                String issueType = getSafeValue(values, "issue_type_block", "issue_type", "Bug");
                String summary = getSafeValue(values, "summary_block", "summary", "");
                String description = getSafeValue(values, "description_block", "description", "");
                String priority = getSafeValue(values, "priority_block", "priority", "Medium");
                String assignee = getSafeValue(values, "assignee_block", "assignee", "");
                String labels = getSafeValue(values, "labels_block", "labels", "");

                if (summary.isEmpty()) {
                    return Mono.just(ResponseEntity.ok("{\"response_action\": \"errors\", \"errors\": { \"summary_block\": \"Summary is required\" }}"));
                }

                return createJiraTicket(issueType, summary, description, priority, assignee, labels)
                        .map(url -> ResponseEntity.ok("{\"response_action\": \"update\", \"view\": { \"type\": \"modal\", \"title\": { \"type\": \"plain_text\", \"text\": \"Ticket Created\" }, \"blocks\": [ { \"type\": \"section\", \"text\": { \"type\": \"mrkdwn\", \"text\": \"Your ticket is ready: <" + url + "|View Ticket>\" } } ] }}"))
                        .onErrorResume(e -> {
                            LOGGER.error("Error: " + e.getMessage(), e);
                            return Mono.just(ResponseEntity.ok("{\"response_action\": \"errors\", \"errors\": { \"summary_block\": \"Failed to create ticket: " + e.getMessage() + "\" }}"));
                        });
            }
            return Mono.just(ResponseEntity.ok("{}"));
        } catch (Exception e) {
            LOGGER.error("Payload handling error: " + e.getMessage(), e);
            return Mono.just(ResponseEntity.badRequest().body("Error handling modal"));
        }
    }

    private String getSafeValue(JsonNode values, String blockId, String actionId, String defaultValue) {
        try {
            JsonNode block = values.get(blockId);
            if (block == null) return defaultValue;
            JsonNode action = block.get(actionId);
            if (action == null) return defaultValue;
            JsonNode selected = action.get("selected_option");
            if (selected != null) return selected.get("value").asText();
            return action.get("value").asText();
        } catch (Exception e) {
            LOGGER.warning("Missing field: " + blockId + "/" + actionId);
            return defaultValue;
        }
    }

    private Mono<String> createJiraTicket(String issueType, String summary, String description, String priority, String assignee, String labels) {
        String auth = Base64.getEncoder().encodeToString((jiraEmail + ":" + jiraApiToken).getBytes());

        String payload = "{ \"fields\": { \"project\": { \"key\": \"" + jiraProjectKey + "\" }, \"issuetype\": { \"name\": \"" + issueType + "\" }, \"summary\": \"" + summary + "\", \"description\": \"" + description + "\", \"priority\": { \"name\": \"" + priority + "\" }, \"assignee\": { \"name\": \"" + assignee + "\" }, \"labels\": [\"" + labels.replace(",", "\", \"") + "\"] } }";

        LOGGER.info("Sending JIRA payload: " + payload);

        return jiraWebClient.post()
                .uri(jiraBaseUrl + "/rest/api/2/issue")
                .header("Authorization", "Basic " + auth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> {
                    LOGGER.info("JIRA response: " + response);
                    try {
                        JsonNode json = objectMapper.readTree(response);
                        String key = json.get("key").asText();
                        return jiraBaseUrl + "/browse/" + key;
                    } catch (Exception e) {
                        throw new RuntimeException("Parse error: " + e.getMessage());
                    }
                });
    }
}
