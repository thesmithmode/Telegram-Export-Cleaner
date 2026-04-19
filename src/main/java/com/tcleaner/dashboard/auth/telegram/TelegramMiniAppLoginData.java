package com.tcleaner.dashboard.auth.telegram;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public record TelegramMiniAppLoginData(Map<String, String> params, Map<String, Object> user) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> USER_TYPE =
            new TypeReference<>() { };

    public static TelegramMiniAppLoginData parse(String initData) {
        Map<String, String> map = new TreeMap<>();
        for (String pair : initData.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                map.put(key, value);
            }
        }
        Map<String, Object> user = Map.of();
        String userJson = map.get("user");
        if (userJson != null && !userJson.isBlank()) {
            try {
                user = MAPPER.readValue(userJson, USER_TYPE);
            } catch (Exception ignored) {
                user = Map.of();
            }
        }
        return new TelegramMiniAppLoginData(map, user);
    }

    public String toDataCheckString() {
        return params.entrySet().stream()
                .filter(e -> !"hash".equals(e.getKey()))
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));
    }

    public long id() {
        Object v = user.get("id");
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    public String firstName() {
        return stringField("first_name");
    }

    public String lastName() {
        return stringField("last_name");
    }

    public String username() {
        return stringField("username");
    }

    private String stringField(String key) {
        Object v = user.get(key);
        return v == null ? null : v.toString();
    }

    public long authDate() {
        return Long.parseLong(params.getOrDefault("auth_date", "0"));
    }

    public String hash() {
        return params.get("hash");
    }
}
