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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@RestController
public class SlackOptionsController {
    private static final Logger LOGGER = Logger.getLogger(SlackOptionsController.class.getName());
    private final WebClient jiraWebClient = WebClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${jira.base-url}")
    private String jiraBaseUrl;

    @Value("${jira.email}")
    private String jiraEmail;

    @Value("${jira.api-token}")
    private String jiraApiToken;

    @Value("${jira.project-key}") // Fallback or for filtering
    private String fallbackProjectKey;

    private Mono<ResponseEntity<String>> handleOptions(String payload, java.util.function.Function<String, Mono<List<Option>>> searchFunction) {
        try {
            JsonNode json = objectMapper.readTree(payload);
            String query = json.get("value").asText(); // Search query from user typing
            return searchFunction.apply(query)
                    .map(options -> {
                        String optionsJson = options.stream()
                                .map(opt -> "{\"text\": {\"type\": \"plain_text\", \"text\": \"" + opt.label + "\"}, \"value\": \"" + opt.value + "\"}")
                                .collect(Collectors.joining(", "));
                        return "{\"options\": [" + optionsJson + "]}";
                    })
                    .map(ResponseEntity::ok);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error loading options: " + e.getMessage());
            return Mono.just(ResponseEntity.ok("{\"options\": []}"));
        }
    }

    @PostMapping("/slack/options/epics")
    public Mono<ResponseEntity<String>> loadEpics(@RequestBody String payload) {
        return handleOptions(payload, query -> searchJira("issuetype = Epic AND summary ~ \"" + query + "\" ORDER BY created DESC"));
    }

    @PostMapping("/slack/options/components")
    public Mono<ResponseEntity<String>> loadComponents(@RequestBody String payload) {
        return handleOptions(payload, query -> getJiraComponents(fallbackProjectKey)); // Use selected project if passed in context
    }

    @PostMapping("/slack/options/labels")
    public Mono<ResponseEntity<String>> loadLabels(@RequestBody String payload) {
        return handleOptions(payload, query -> searchJiraLabels(query));
    }

    @PostMapping("/slack/options/teams")
    public Mono<ResponseEntity<String>> loadTeams(@RequestBody String payload) {
        return handleOptions(payload, query -> searchJiraTeams(query));
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
        // Example: Fetch from /rest/api/3/label (but it's not search-enabled; implement actual logic)
        options.add(new Option(query, query)); // Allow creation by returning the query as a new option
        return Mono.just(options);
    }

    private Mono<List<Option>> searchJiraTeams(String query) {
        String auth = Base64.getEncoder().encodeToString((jiraEmail + ":" + jiraApiToken).getBytes(StandardCharsets.UTF_8));

        // Fallback: Search issues with teams assigned and extract unique team name/ID from customfield_10001
        String jql = "Team IS NOT EMPTY AND (summary ~ \\\"" + query + "\\\" OR key ~ \\\"" + query + "\\\") ORDER BY created DESC";
        String payload = "{\"jql\": \"" + jql + "\", \"maxResults\": 20, \"fields\": [\"customfield_10001\"]}";

        return jiraWebClient.post()
                .uri(jiraBaseUrl + "/rest/api/3/search")
                .header("Authorization", "Basic " + auth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> {
                    Set<Option> options = new HashSet<>(); // Use Set to avoid duplicates
                    try {
                        JsonNode json = objectMapper.readTree(response);
                        JsonNode issues = json.get("issues");
                        if (issues != null && issues.isArray()) {
                            for (JsonNode issue : issues) {
                                JsonNode teamField = issue.get("fields").get("customfield_10001");
                                if (teamField != null && teamField.has("id") && teamField.has("name")) {
                                    String id = teamField.get("id").asText();
                                    String name = teamField.get("name").asText();
                                    options.add(new Option(name, id));
                                }
                            }
                        }
                        LOGGER.log(Level.INFO, "Loaded " + options.size() + " teams for query: " + query);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error parsing Jira teams fallback: " + e.getMessage());
                    }
                    return new ArrayList<>(options); // Convert back to List
                });
    }

    private static class Option {
        String label;
        String value;

        Option(String label, String value) {
            this.label = label;
            this.value = value;
        }

        // For Set uniqueness (based on ID)
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
