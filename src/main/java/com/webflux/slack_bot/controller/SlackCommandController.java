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
        String command = params.get("command");  // e.g., "/jira"
        String text = params.get("text");        // e.g., "create bug in module X"
        String teamId = params.get("team_id");
        String userId = params.get("user_id");

        // TODO: Verify signing secret first (see step 2 below)

        // Simple response for now
        if (text.startsWith("create")) {
            // Open modal for ticket creation
            String botToken = TokenStore.getToken(teamId);
            if (botToken == null) {
                return ResponseEntity.badRequest().body("Bot not authorized. Please install via OAuth first.");
            }
            openJiraModal(params.get("trigger_id"), botToken);
            return ResponseEntity.ok("Opening JIRA ticket form...");
        } else {
            return ResponseEntity.ok("Unknown command: " + text);
        }
    }

    private void openJiraModal(String triggerId, String botToken) {
        // Modal JSON with prompts for each field (customize options based on your JIRA)
        String modalPayload = "{ \"type\": \"modal\", \"callback_id\": \"jira_ticket_modal\", \"title\": { \"type\": \"plain_text\", \"text\": \"Create JIRA Ticket\" }, \"submit\": { \"type\": \"plain_text\", \"text\": \"Submit\" }, \"blocks\": [ " +
                "{ \"type\": \"input\", \"block_id\": \"issue_type_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Issue Type\" }, \"element\": { \"type\": \"static_select\", \"action_id\": \"issue_type\", \"options\": [ { \"text\": { \"type\": \"plain_text\", \"text\": \"Bug\" }, \"value\": \"Bug\" }, { \"text\": { \"type\": \"plain_text\", \"text\": \"Task\" }, \"value\": \"Task\" }, { \"text\": { \"type\": \"plain_text\", \"text\": \"Story\" }, \"value\": \"Story\" } ] } }, " +
                "{ \"type\": \"input\", \"block_id\": \"summary_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Summary\" }, \"element\": { \"type\": \"plain_text_input\", \"action_id\": \"summary\" } }, " +
                "{ \"type\": \"input\", \"block_id\": \"description_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Description\" }, \"element\": { \"type\": \"plain_text_input\", \"action_id\": \"description\", \"multiline\": true } }, " +
                "{ \"type\": \"input\", \"block_id\": \"priority_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Priority\" }, \"element\": { \"type\": \"static_select\", \"action_id\": \"priority\", \"options\": [ { \"text\": { \"type\": \"plain_text\", \"text\": \"High\" }, \"value\": \"High\" }, { \"text\": { \"type\": \"plain_text\", \"text\": \"Medium\" }, \"value\": \"Medium\" }, { \"text\": { \"type\": \"plain_text\", \"text\": \"Low\" }, \"value\": \"Low\" } ] } }, " +
                "{ \"type\": \"input\", \"block_id\": \"assignee_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Assignee (username or email)\" }, \"element\": { \"type\": \"plain_text_input\", \"action_id\": \"assignee\" }, \"optional\": true }, " +
                "{ \"type\": \"input\", \"block_id\": \"labels_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Labels (comma-separated)\" }, \"element\": { \"type\": \"plain_text_input\", \"action_id\": \"labels\" }, \"optional\": true } " +
                // Add more fields here if needed (e.g., components, custom fields from your manual ticket creation)
                "] }";

        slackWebClient.post()
                .uri("/views.open")
                .header("Authorization", "Bearer " + botToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"trigger_id\": \"" + triggerId + "\", \"view\": " + modalPayload + "}")
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(response -> {
                    // Optional: Log the response for debugging (e.g., System.out.println(response))
                });
    }
}
