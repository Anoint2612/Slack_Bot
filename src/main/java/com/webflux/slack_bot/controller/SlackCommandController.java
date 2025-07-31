package com.webflux.slack_bot.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class SlackCommandController {

    @PostMapping("/slack/command")
    public ResponseEntity<String> handleCommand(@RequestParam Map<String, String> params) {
        String command = params.get("command");  // e.g., "/jira"
        String text = params.get("text");        // e.g., "create bug in module X"
        String teamId = params.get("team_id");
        String userId = params.get("user_id");

        // TODO: Verify signing secret first (see step 2 below)

        // Simple response for now
        if (text.startsWith("create")) {
            // Prompt user for more details (e.g., via bot message) and raise Jira ticket
            return ResponseEntity.ok("Got it! Creating Jira ticket for: " + text);
        } else {
            return ResponseEntity.ok("Unknown command: " + text);
        }
    }
}
     