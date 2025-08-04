package com.webflux.slack_bot.controller;

import com.webflux.slack_bot.util.TokenStore;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.Map;

@RestController
public class SlackCommandController {
    private final WebClient slackWebClient = WebClient.create("https://slack.com/api");

    @PostMapping("/slack/command")
    public ResponseEntity<String> handleCommand(@RequestParam Map<String, String> params) {
        System.out.println("Received command: " + params.get("command") + " with text: " + params.get("text"));
        try {
            String command = params.get("command");  // e.g., "/jira"
            String text = params.get("text");        // e.g., "create"
            String teamId = params.get("team_id");
            String userId = params.get("user_id");

            // TODO: Verify signing secret

            if (text.startsWith("create")) {
                String botToken = TokenStore.getToken(teamId);
                if (botToken == null) {
                    return ResponseEntity.ok("Bot not authorized. Please install via OAuth first.");
                }
                // Open modal async and return 200 immediately
                Mono.fromRunnable(() -> openJiraModal(params.get("trigger_id"), botToken))
                        .subscribe();
                return ResponseEntity.ok("Opening JIRA ticket form...");
            } else {
                return ResponseEntity.ok("Unknown command: " + text);
            }
        } catch (Exception e) {
            System.out.println("Error in handleCommand: " + e.getMessage() + " - Params: " + params);
            return ResponseEntity.ok("Error processing command - check logs"); // Return 200 with error message for Slack
        }
    }

    private void openJiraModal(String triggerId, String botToken) {
        // Modal JSON (same as before)
        String modalPayload = "{ \"type\": \"modal\", \"callback_id\": \"jira_ticket_modal\", \"title\": { \"type\": \"plain_text\", \"text\": \"Create JIRA Ticket\" }, \"submit\": { \"type\": \"plain_text\", \"text\": \"Submit\" }, \"blocks\": [ " +
                "{ \"type\": \"input\", \"block_id\": \"issue_type_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Issue Type\" }, \"element\": { \"type\": \"static_select\", \"action_id\": \"issue_type\", \"options\": [ { \"text\": { \"type\": \"plain_text\", \"text\": \"Bug\" }, \"value\": \"Bug\" }, { \"text\": { \"type\": \"plain_text\", \"text\": \"Task\" }, \"value\": \"Task\" } ] } }, " +
                "{ \"type\": \"input\", \"block_id\": \"summary_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Summary\" }, \"element\": { \"type\": \"plain_text_input\", \"action_id\": \"summary\" } }, " +
                "{ \"type\": \"input\", \"block_id\": \"description_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Description\" }, \"element\": { \"type\": \"plain_text_input\", \"action_id\": \"description\", \"multiline\": true } }, " +
                "{ \"type\": \"input\", \"block_id\": \"priority_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Priority\" }, \"element\": { \"type\": \"static_select\", \"action_id\": \"priority\", \"options\": [ { \"text\": { \"type\": \"plain_text\", \"text\": \"High\" }, \"value\": \"High\" }, { \"text\": { \"type\": \"plain_text\", \"text\": \"Medium\" }, \"value\": \"Medium\" } ] } }, " +
                "{ \"type\": \"input\", \"block_id\": \"assignee_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Assignee (email)\" }, \"element\": { \"type\": \"plain_text_input\", \"action_id\": \"assignee\" }, \"optional\": true }, " +
                "{ \"type\": \"input\", \"block_id\": \"labels_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Labels (comma-separated)\" }, \"element\": { \"type\": \"plain_text_input\", \"action_id\": \"labels\" }, \"optional\": true } " +
                "] }";

        slackWebClient.post()
                .uri("/views.open")
                .header("Authorization", "Bearer " + botToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"trigger_id\": \"" + triggerId + "\", \"view\": " + modalPayload + "}")
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(response -> {
                    System.out.println("Modal open response: " + response);
                }, error -> {
                    System.out.println("Error opening modal: " + error.getMessage());
                });
    }
}
