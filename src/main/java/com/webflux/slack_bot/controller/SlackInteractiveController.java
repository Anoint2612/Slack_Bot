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

@RestController
public class SlackInteractiveController {
    private final WebClient jiraWebClient = WebClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${jira.base-url}") // e.g., https://yourpersonalname.atlassian.net
    private String jiraBaseUrl;

    @Value("${jira.email}") // Your personal email
    private String jiraEmail;

    @Value("${jira.api-token}") // Personal token
    private String jiraApiToken;

    @Value("${jira.project-key}") // e.g., "PST" from your personal project
    private String jiraProjectKey;

    @PostMapping("/slack/interactive")
    public Mono<ResponseEntity<String>> handleInteractive(@RequestBody String payload) {
        try {
            JsonNode json = objectMapper.readTree(payload);
            if ("view_submission".equals(json.get("type").asText()) && "jira_ticket_modal".equals(json.get("view").get("callback_id").asText())) {
                JsonNode values = json.get("view").get("state").get("values");

                String issueType = values.get("issue_type_block").get("issue_type").get("selected_option").get("value").asText();
                String summary = values.get("summary_block").get("summary").get("value").asText();
                String description = values.get("description_block").get("description").get("value").asText();
                String priority = values.get("priority_block").get("priority").get("selected_option").get("value").asText();
                String assignee = values.get("assignee_block").get("assignee").get("value").asText();
                String labels = values.get("labels_block").get("labels").get("value").asText();

                // Call JIRA API
                return createJiraTicket(issueType, summary, description, priority, assignee, labels)
                        .map(url -> ResponseEntity.ok("{\"response_action\": \"update\", \"view\": { \"type\": \"modal\", \"title\": { \"type\": \"plain_text\", \"text\": \"Ticket Created\" }, \"blocks\": [ { \"type\": \"section\", \"text\": { \"type\": \"mrkdwn\", \"text\": \"Your ticket is ready: <" + url + "|View Ticket>\" } } ] }}"))
                        .onErrorReturn(ResponseEntity.ok("{\"response_action\": \"errors\", \"errors\": { \"summary_block\": \"Error creating ticket - check details\" }}"));
            }
            return Mono.just(ResponseEntity.ok("{}"));
        } catch (Exception e) {
            return Mono.just(ResponseEntity.badRequest().body("Error handling modal"));
        }
    }

    private Mono<String> createJiraTicket(String issueType, String summary, String description, String priority, String assignee, String labels) {
        String auth = Base64.getEncoder().encodeToString((jiraEmail + ":" + jiraApiToken).getBytes());

        // Payload with fields (customize if your personal JIRA requires more)
        String payload = "{ \"fields\": { \"project\": { \"key\": \"" + jiraProjectKey + "\" }, \"issuetype\": { \"name\": \"" + issueType + "\" }, \"summary\": \"" + summary + "\", \"description\": \"" + description + "\", \"priority\": { \"name\": \"" + priority + "\" }, \"assignee\": { \"accountId\": \"" + assignee + "\" }, \"labels\": [\" " + labels.replace(",", "\", \"") + " \"] } }";

        return jiraWebClient.post()
                .uri(jiraBaseUrl + "/rest/api/2/issue")
                .header("Authorization", "Basic " + auth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> {
                    try {
                        JsonNode json = objectMapper.readTree(response);
                        String key = json.get("key").asText();
                        return jiraBaseUrl + "/browse/" + key;
                    } catch (Exception e) {
                        throw new RuntimeException("Error parsing JIRA response");
                    }
                });
    }
}
   