package com.webflux.slack_bot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webflux.slack_bot.util.TokenStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@RestController
public class SlackInteractiveController {
    private static final Logger LOGGER = Logger.getLogger(SlackInteractiveController.class.getName());
    private final WebClient jiraWebClient = WebClient.create();
    private final WebClient slackWebClient = WebClient.create("https://slack.com/api");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${jira.base-url}")
    private String jiraBaseUrl;

    @Value("${jira.email}")
    private String jiraEmail;

    @Value("${jira.api-token}")
    private String jiraApiToken;

    @Value("${jira.project-key}") // Fallback if not selected in modal
    private String fallbackProjectKey;

    @PostMapping("/slack/interactive")
    public Mono<ResponseEntity<String>> handleInteractive(@RequestBody String rawPayload) {
        try {
            // Step 1: Decode the URL-encoded raw payload from Slack
            String decodedPayload = URLDecoder.decode(rawPayload, StandardCharsets.UTF_8.name());
            LOGGER.log(Level.INFO, "Decoded payload: {0}", decodedPayload);

            // Step 2: Extract the JSON string (remove "payload=" prefix if present)
            String jsonString = decodedPayload.startsWith("payload=") ? decodedPayload.substring(8) : decodedPayload;
            LOGGER.log(Level.INFO, "Extracted JSON string: {0}", jsonString);

            // Step 3: Parse the JSON
            JsonNode json = objectMapper.readTree(jsonString);
            String type = json.get("type").asText();
            LOGGER.log(Level.INFO, "Parsed JSON type: {0}", type);

            if ("view_submission".equals(type) && "jira_ticket_modal".equals(json.get("view").get("callback_id").asText())) {
                JsonNode values = json.get("view").get("state").get("values");
                LOGGER.log(Level.INFO, "Extracted values: {0}", values.toString());

                String teamId = json.get("team").get("id").asText(); // For bot token
                String projectKey = getSafeValue(values, "project_block", "project", fallbackProjectKey, false);
                String issueType = getSafeValue(values, "issue_type_block", "issue_type", "Bug", false);
                String summary = getSafeValue(values, "summary_block", "summary", "", false);
                String description = getSafeValue(values, "description_block", "description", "", true);
                String priority = getSafeValue(values, "priority_block", "priority", "Medium", true);
                String assigneeUserId = getSafeSlackUserId(values, "assignee_block", "assignee"); // Specific extraction for users_select
                String parentEpic = getSafeValue(values, "parent_epic_block", "parent_epic", "", true);
                List<String> components = getSafeListValue(values, "components_block", "components");
                String labelsInput = getSafeValue(values, "labels_block", "labels", "", true);
                List<String> labels = List.of(labelsInput.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
                String startDate = getSafeValue(values, "start_date_block", "start_date", "", true);
                String dueDate = getSafeValue(values, "due_date_block", "due_date", "", true);
                String team = getSafeValue(values, "team_block", "team", "", true); // Extract selected team ID

                if (projectKey.isEmpty() || summary.isEmpty()) {
                    String errorMsg = projectKey.isEmpty() ? "{\"response_action\": \"errors\", \"errors\": { \"project_block\": \"Project is required\" }}" : "{\"response_action\": \"errors\", \"errors\": { \"summary_block\": \"Summary is required\" }}";
                    return Mono.just(ResponseEntity.ok(errorMsg));
                }

                // Map assignee Slack User ID to Jira accountId (async)
                Mono<String> assigneeAccountIdMono = (assigneeUserId == null || assigneeUserId.isEmpty())
                        ? Mono.just("")
                        : getSlackUserEmail(teamId, assigneeUserId)
                        .flatMap(email -> {
                            if (email.isEmpty()) {
                                LOGGER.log(Level.WARNING, "No email found for Slack user: " + assigneeUserId);
                                return Mono.just("");
                            }
                            return getJiraAccountIdByEmail(email)
                                    .map(accountId -> {
                                        if (accountId.isEmpty()) {
                                            LOGGER.log(Level.WARNING, "No Jira account found for email: " + email);
                                        }
                                        return accountId;
                                    });
                        });

                return assigneeAccountIdMono.flatMap(assigneeAccountId ->
                        createJiraTicket(projectKey, issueType, summary, description, priority, assigneeAccountId, parentEpic, components, labels, startDate, dueDate, team) // Pass team
                                .map(url -> ResponseEntity.ok("{\"response_action\": \"update\", \"view\": { \"type\": \"modal\", \"title\": { \"type\": \"plain_text\", \"text\": \"Ticket Created\" }, \"blocks\": [ { \"type\": \"section\", \"text\": { \"type\": \"mrkdwn\", \"text\": \"Your ticket is ready: <" + url + "|View Ticket>\" } } ] }}"))
                                .onErrorResume(e -> {
                                    LOGGER.log(Level.SEVERE, "Error creating ticket: " + e.getMessage(), e);
                                    return Mono.just(ResponseEntity.ok("{\"response_action\": \"errors\", \"errors\": { \"summary_block\": \"Failed to create ticket: " + e.getMessage() + "\" }}"));
                                }));
            } else if ("block_suggestion".equals(type)) {
                // Handle options loading for external_select
                String actionId = json.get("action_id").asText();
                String query = json.get("value").asText(); // User's typed query
                LOGGER.log(Level.INFO, "Handling block_suggestion for action_id: {0}, query: {1}", new Object[]{actionId, query});

                Mono<List<Option>> optionsMono;
                switch (actionId) {
                    case "team":
                        optionsMono = searchJiraTeams(query);
                        break;
                    case "parent_epic":
                        optionsMono = searchJira("issuetype = Epic AND summary ~ \"" + query + "\" ORDER BY created DESC");
                        break;
                    case "components":
                        // TODO: Dynamically use projectKey from modal context if available (json may have view.state.values)
                        optionsMono = getJiraComponents(fallbackProjectKey);
                        break;
                    case "labels":
                        optionsMono = searchJiraLabels(query);
                        break;
                    default:
                        LOGGER.log(Level.WARNING, "Unknown action_id: {0}", actionId);
                        optionsMono = Mono.just(new ArrayList<>());
                }

                return optionsMono.map(options -> {
                    String optionsJson = options.stream()
                            .map(opt -> "{\"text\": {\"type\": \"plain_text\", \"text\": \"" + opt.label + "\"}, \"value\": \"" + opt.value + "\"}")
                            .collect(Collectors.joining(", "));
                    return "{\"options\": [" + optionsJson + "]}";
                }).map(ResponseEntity::ok);
            }

            // Fallback for unhandled types
            return Mono.just(ResponseEntity.ok("{}"));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error handling interactive payload: " + e.getMessage(), e);
            return Mono.just(ResponseEntity.badRequest().body("Error handling modal: " + e.getMessage()));
        }
    }

    // Updated to handle different element types (e.g., selected_user for users_select)
    private String getSafeValue(JsonNode values, String blockId, String actionId, String defaultValue, boolean isOptional) {
        try {
            JsonNode block = values.get(blockId);
            if (block == null) return defaultValue;
            JsonNode action = block.get(actionId);
            if (action == null) return defaultValue;

            // Handle different structures
            if (action.has("selected_option")) {
                return action.get("selected_option").get("value").asText();
            } else if (action.has("value")) {
                return action.get("value").asText();
            } else if (action.has("selected_date")) {
                return action.get("selected_date").asText();
            } else if (action.has("selected_user")) {  // For users_select
                return action.get("selected_user").asText();
            } else if (action.has("selected_options")) {  // For multi_select (e.g., labels, components)
                JsonNode selectedOptions = action.get("selected_options");
                if (selectedOptions != null && selectedOptions.isArray()) {
                    return selectedOptions.findValuesAsText("value").stream().collect(Collectors.joining(","));
                }
            }
            return defaultValue;
        } catch (Exception e) {
            if (!isOptional) LOGGER.log(Level.WARNING, "Missing required field: " + blockId + "/" + actionId, e);
            return defaultValue;
        }
    }

    // Wrapper for assignee extraction (uses getSafeValue under the hood)
    private String getSafeSlackUserId(JsonNode values, String blockId, String actionId) {
        return getSafeValue(values, blockId, actionId, "", true);
    }

    private List<String> getSafeListValue(JsonNode values, String blockId, String actionId) {
        List<String> list = new ArrayList<>();
        try {
            JsonNode block = values.get(blockId);
            if (block == null) return list;
            JsonNode action = block.get(actionId);
            if (action == null) return list;
            JsonNode selectedOptions = action.get("selected_options");
            if (selectedOptions != null && selectedOptions.isArray()) {
                for (JsonNode opt : selectedOptions) {
                    list.add(opt.get("value").asText());
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Missing list field: " + blockId + "/" + actionId, e);
        }
        return list;
    }

    private Mono<String> getSlackUserEmail(String teamId, String userId) {
        String botToken = TokenStore.getToken(teamId);
        if (botToken == null) return Mono.just(""); // Fallback if token missing

        return slackWebClient.post()
                .uri("/users.info")
                .header("Authorization", "Bearer " + botToken)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("user=" + userId)
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> {
                    try {
                        JsonNode json = objectMapper.readTree(response);
                        if (json.get("ok").asBoolean()) {
                            return json.get("user").get("profile").get("email").asText();
                        }
                        return "";
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error getting Slack user email: " + e.getMessage());
                        return "";
                    }
                });
    }

    private Mono<String> getJiraAccountIdByEmail(String email) {
        if (email.isEmpty()) return Mono.just("");

        String auth = Base64.getEncoder().encodeToString((jiraEmail + ":" + jiraApiToken).getBytes(StandardCharsets.UTF_8));

        return jiraWebClient.get()
                .uri(jiraBaseUrl + "/rest/api/3/user/search?query=" + email)
                .header("Authorization", "Basic " + auth)
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> {
                    try {
                        JsonNode json = objectMapper.readTree(response);
                        if (json.isArray() && json.size() > 0) {
                            return json.get(0).get("accountId").asText();
                        }
                        return "";
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error getting Jira accountId: " + e.getMessage());
                        return "";
                    }
                });
    }

    private Mono<String> createJiraTicket(String projectKey, String issueType, String summary, String description, String priority, String assigneeAccountId,
                                          String parentEpic, List<String> components, List<String> labels, String startDate, String dueDate, String team) { // Added team param for team assignment
        String auth = Base64.getEncoder().encodeToString((jiraEmail + ":" + jiraApiToken).getBytes(StandardCharsets.UTF_8));

        // Build payload as JSON object to avoid string concatenation errors
        Map<String, Object> fields = new HashMap<>();
        fields.put("project", Map.of("key", projectKey));
        fields.put("summary", summary);
        fields.put("issuetype", Map.of("name", issueType));

        // ADF for description
        Map<String, Object> descriptionADF = Map.of(
                "type", "doc",
                "version", 1,
                "content", List.of(
                        Map.of(
                                "type", "paragraph",
                                "content", List.of(
                                        Map.of("text", description, "type", "text")
                                )
                        )
                )
        );
        fields.put("description", descriptionADF);

        if (!priority.isEmpty()) fields.put("priority", Map.of("name", priority));
        if (!assigneeAccountId.isEmpty()) fields.put("assignee", Map.of("accountId", assigneeAccountId));
        if (!parentEpic.isEmpty()) fields.put("parent", Map.of("key", parentEpic));
        if (!components.isEmpty()) fields.put("components", components.stream().map(c -> Map.of("name", c)).collect(Collectors.toList()));
        if (!labels.isEmpty()) fields.put("labels", labels);
        if (!startDate.isEmpty()) fields.put("customfield_10015", startDate); // REPLACE with actual ID
        if (!dueDate.isEmpty()) fields.put("duedate", dueDate);
        if (!team.isEmpty()) fields.put("customfield_10001", team); // Assign the selected team ID

        Map<String, Object> payloadMap = Map.of("fields", fields);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(payloadMap);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error building JSON payload: " + e.getMessage(), e);
            return Mono.error(new RuntimeException("Payload build error: " + e.getMessage()));
        }
        LOGGER.log(Level.INFO, "Sending JIRA payload: " + payload);

        return jiraWebClient.post()
                .uri(jiraBaseUrl + "/rest/api/3/issue")
                .header("Authorization", "Basic " + auth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .onStatus(HttpStatus::is4xxClientError, response -> response.bodyToMono(String.class)
                        .flatMap(errorBody -> {
                            LOGGER.log(Level.SEVERE, "Jira 400 error response: " + errorBody);
                            return Mono.error(new RuntimeException("Jira API error: " + errorBody));
                        }))
                .bodyToMono(String.class)
                .map(response -> {
                    LOGGER.log(Level.INFO, "JIRA response: " + response);
                    try {
                        JsonNode json = objectMapper.readTree(response);
                        String key = json.get("key").asText();
                        return jiraBaseUrl + "/browse/" + key;
                    } catch (Exception e) {
                        throw new RuntimeException("Parse error: " + e.getMessage() + " - Response: " + response);
                    }
                });
    }

    private Mono<List<Option>> searchJira(String jql) {
        String auth = Base64.getEncoder().encodeToString((jiraEmail + ":" + jiraApiToken).getBytes(StandardCharsets.UTF_8));
        String payload = "{\"jql\": \"" + jql + "\", \"maxResults\": 10, \"fields\": [\"key\", \"summary\"]}";

        return jiraWebClient.post()
                .uri(jiraBaseUrl + "/rest/api/3/search")
                .header("Authorization", "Basic " + auth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> {
                    List<Option> options = new ArrayList<>();
                    try {
                        JsonNode json = objectMapper.readTree(response);
                        JsonNode issues = json.get("issues");
                        for (JsonNode issue : issues) {
                            String key = issue.get("key").asText();
                            String summary = issue.get("fields").get("summary").asText();
                            options.add(new Option(summary + " (" + key + ")", key));
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error parsing Jira search: " + e.getMessage());
                    }
                    return options;
                });
    }

    private Mono<List<Option>> getJiraComponents(String projectKey) {
        String auth = Base64.getEncoder().encodeToString((jiraEmail + ":" + jiraApiToken).getBytes(StandardCharsets.UTF_8));

        return jiraWebClient.get()
                .uri(jiraBaseUrl + "/rest/api/3/project/" + projectKey + "/components")
                .header("Authorization", "Basic " + auth)
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> {
                    List<Option> options = new ArrayList<>();
                    try {
                        JsonNode json = objectMapper.readTree(response);
                        for (JsonNode comp : json) {
                            String name = comp.get("name").asText();
                            options.add(new Option(name, name));
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error parsing Jira components: " + e.getMessage());
                    }
                    return options;
                });
    }

    private Mono<List<Option>> searchJiraLabels(String query) {
        // Note: Jira doesn't have a direct /label/search; simulate by searching issues or use a fixed list. Customize as needed.
        List<Option> options = new ArrayList<>();
        options.add(new Option(query, query)); // Allow creation by returning the query as a new option
        return Mono.just(options);
    }

    private Mono<List<Option>> searchJiraTeams(String query) {
        String auth = Base64.getEncoder().encodeToString((jiraEmail + ":" + jiraApiToken).getBytes(StandardCharsets.UTF_8));
        // IMPROVED: Fetch up to 100 recent issues with teams (decoupled from query for reliability)
        String jql = "Team IS NOT EMPTY ORDER BY created DESC";
        String payload = "{\"jql\": \"" + jql + "\", \"maxResults\": 100, \"fields\": [\"customfield_10001\"]}"; // Increase maxResults if you have many teams

        return jiraWebClient.post()
                .uri(jiraBaseUrl + "/rest/api/3/search")
                .header("Authorization", "Basic " + auth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> {
                    Set<Option> uniqueOptions = new HashSet<>();
                    try {
                        JsonNode json = objectMapper.readTree(response);
                        JsonNode issues = json.get("issues");
                        if (issues != null && issues.isArray()) {
                            for (JsonNode issue : issues) {
                                JsonNode teamField = issue.get("fields").get("customfield_10001");
                                if (teamField != null && teamField.has("id") && teamField.has("name")) {
                                    String id = teamField.get("id").asText();
                                    String name = teamField.get("name").asText();
                                    uniqueOptions.add(new Option(name, id));
                                }
                            }
                        }
                        LOGGER.log(Level.INFO, "Loaded {0} unique teams from Jira", uniqueOptions.size());
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error parsing Jira teams: " + e.getMessage());
                    }
                    // Filter locally by query (case-insensitive) and sort
                    String lowerQuery = query.toLowerCase();
                    return uniqueOptions.stream()
                            .filter(opt -> opt.label.toLowerCase().contains(lowerQuery))
                            .sorted(Comparator.comparing(opt -> opt.label))
                            .limit(50) // Slack recommends <=100 options
                            .collect(Collectors.toList());
                });
    }

    private static class Option {
        String label;
        String value;

        Option(String label, String value) {
            this.label = label;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Option option = (Option) o;
            return value.equals(option.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }
    }
}
