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
        try {
            String command = params.get("command");  // e.g., "/botjira"
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
            return ResponseEntity.ok("Error processing command - check logs"); // Return 200 for Slack
        }
    }

    private void openJiraModal(String triggerId, String botToken) {
        // Modal with EXACT fields from JIRA Cloud for Slack (using Block Kit)
        // Updated to use external_select for dynamic fields (Parent Epic, Components), but hardcoded static for Labels
        String modalPayload = "{ \"type\": \"modal\", \"callback_id\": \"jira_ticket_modal\", \"title\": { \"type\": \"plain_text\", \"text\": \"Create JIRA Ticket\" }, \"submit\": { \"type\": \"plain_text\", \"text\": \"Submit\" }, \"blocks\": [ " +
                "{ \"type\": \"section\", \"text\": { \"type\": \"plain_text\", \"text\": \"Issue is being created for jiratesting2612.atlassian.net\" } }, " +
                "{ \"type\": \"input\", \"block_id\": \"project_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Project\" }, \"element\": { \"type\": \"static_select\", \"action_id\": \"project\", \"placeholder\": { \"type\": \"plain_text\", \"text\": \"Which project would you like to create an issue in?\" }, \"options\": [ { \"text\": { \"type\": \"plain_text\", \"text\": \"Bot Demo Project (BDP)\" }, \"value\": \"BDP\" }, { \"text\": { \"type\": \"plain_text\", \"text\": \"Jira Testing (SCRUM)\" }, \"value\": \"SCRUM\" } ] } }, " +
                "{ \"type\": \"input\", \"block_id\": \"issue_type_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Issue type\" }, \"element\": { \"type\": \"static_select\", \"action_id\": \"issue_type\", \"placeholder\": { \"type\": \"plain_text\", \"text\": \"What type of issue is it?\" }, \"options\": [ { \"text\": { \"type\": \"plain_text\", \"text\": \"New Feature\" }, \"value\": \"New Feature\" }, { \"text\": { \"type\": \"plain_text\", \"text\": \"Bug\" }, \"value\": \"Bug\" }, { \"text\": { \"type\": \"plain_text\", \"text\": \"Task\" }, \"value\": \"Task\" } ] } }, " +
                "{ \"type\": \"input\", \"block_id\": \"summary_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Summary\" }, \"element\": { \"type\": \"plain_text_input\", \"action_id\": \"summary\", \"placeholder\": { \"type\": \"plain_text\", \"text\": \"Write something\" } } }, " +
                "{ \"type\": \"input\", \"block_id\": \"description_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Description (optional)\" }, \"optional\": true, \"element\": { \"type\": \"plain_text_input\", \"action_id\": \"description\", \"multiline\": true, \"placeholder\": { \"type\": \"plain_text\", \"text\": \"Write something\" } } }, " +
                "{ \"type\": \"input\", \"block_id\": \"assignee_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Assignee (optional)\" }, \"optional\": true, \"element\": { \"type\": \"users_select\", \"action_id\": \"assignee\", \"placeholder\": { \"type\": \"plain_text\", \"text\": \"Pick an option\" } } }, " +
                "{ \"type\": \"input\", \"block_id\": \"parent_epic_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Parent Epic (optional)\" }, \"optional\": true, \"element\": { \"type\": \"external_select\", \"action_id\": \"parent_epic\", \"placeholder\": { \"type\": \"plain_text\", \"text\": \"Start typing to search and pick your option\" }, \"min_query_length\": 3 } }, " +
                "{ \"type\": \"input\", \"block_id\": \"components_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Components (optional)\" }, \"optional\": true, \"element\": { \"type\": \"multi_external_select\", \"action_id\": \"components\", \"placeholder\": { \"type\": \"plain_text\", \"text\": \"Pick your options\" }, \"min_query_length\": 0 } }, " +
                "{ \"type\": \"input\", \"block_id\": \"priority_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Priority (optional)\" }, \"optional\": true, \"element\": { \"type\": \"static_select\", \"action_id\": \"priority\", \"placeholder\": { \"type\": \"plain_text\", \"text\": \"Medium\" }, \"options\": [ { \"text\": { \"type\": \"plain_text\", \"text\": \"Highest\" }, \"value\": \"Highest\" }, { \"text\": { \"type\": \"plain_text\", \"text\": \"High\" }, \"value\": \"High\" }, { \"text\": { \"type\": \"plain_text\", \"text\": \"Medium\" }, \"value\": \"Medium\" }, { \"text\": { \"type\": \"plain_text\", \"text\": \"Low\" }, \"value\": \"Low\" }, { \"text\": { \"type\": \"plain_text\", \"text\": \"Lowest\" }, \"value\": \"Lowest\" } ] } }, " +
                "{ \"type\": \"input\", \"block_id\": \"labels_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Labels (optional)\" }, \"optional\": true, \"element\": { \"type\": \"multi_static_select\", \"action_id\": \"labels\", \"placeholder\": { \"type\": \"plain_text\", \"text\": \"Pick one or more labels\" }, \"options\": [ " +  // Changed to multi_static_select with hardcoded options
                "{ \"text\": { \"type\": \"plain_text\", \"text\": \"bug\" }, \"value\": \"bug\" }, " +
                "{ \"text\": { \"type\": \"plain_text\", \"text\": \"feature\" }, \"value\": \"feature\" }, " +
                "{ \"text\": { \"type\": \"plain_text\", \"text\": \"urgent\" }, \"value\": \"urgent\" }, " +
                "{ \"text\": { \"type\": \"plain_text\", \"text\": \"documentation\" }, \"value\": \"documentation\" }, " +
                "{ \"text\": { \"type\": \"plain_text\", \"text\": \"enhancement\" }, \"value\": \"enhancement\" } " +
                "] } }, " +
                "{ \"type\": \"input\", \"block_id\": \"start_date_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Start date (optional)\" }, \"optional\": true, \"element\": { \"type\": \"datepicker\", \"action_id\": \"start_date\", \"placeholder\": { \"type\": \"plain_text\", \"text\": \"Select a date\" } } }, " +
                "{ \"type\": \"input\", \"block_id\": \"due_date_block\", \"label\": { \"type\": \"plain_text\", \"text\": \"Due date (optional)\" }, \"optional\": true, \"element\": { \"type\": \"datepicker\", \"action_id\": \"due_date\", \"placeholder\": { \"type\": \"plain_text\", \"text\": \"Select a date\" } } } " +
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
