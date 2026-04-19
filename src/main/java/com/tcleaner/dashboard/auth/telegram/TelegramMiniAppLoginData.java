package com.tcleaner.dashboard.auth.telegram;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public record TelegramMiniAppLoginData(Map<String, String> params) {

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
        return new TelegramMiniAppLoginData(map);
    }

    public String toDataCheckString() {
        return params.entrySet().stream()
                .filter(e -> !"hash".equals(e.getKey()))
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));
    }

    public long id() {
        return Long.parseLong(params.getOrDefault("id", "0"));
    }

    public String firstName() {
        return params.get("first_name");
    }

    public String lastName() {
        return params.get("last_name");
    }

    public String username() {
        return params.get("username");
    }

    public long authDate() {
        return Long.parseLong(params.getOrDefault("auth_date", "0"));
    }

    public String hash() {
        return params.get("hash");
    }
}
