package com.webflux.slack_bot.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TokenStore {
    private static final Map<String, String> tokens = new ConcurrentHashMap<>();

    public static void storeToken(String teamId, String accessToken) {
        tokens.put(teamId, accessToken);
    }
    public static String getToken(String teamId) {
        return tokens.get(teamId);
    }
}
