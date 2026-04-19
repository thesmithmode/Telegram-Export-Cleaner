package com.tcleaner.dashboard.auth.telegram;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

class TelegramAuthTestUtils {

    static String computeHash(String botToken, String dataCheckString) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] secretKey = mac.doFinal(botToken.getBytes(StandardCharsets.UTF_8));
        mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Собирает валидный Telegram Mini App initData с user=JSON (реальный формат).
     * Используется всеми тестами Mini App auth.
     */
    static String buildMiniAppInitData(String botToken, long id, String firstName,
                                       String username, long authDate) throws Exception {
        StringBuilder userJson = new StringBuilder("{\"id\":").append(id);
        if (firstName != null) {
            userJson.append(",\"first_name\":\"").append(firstName).append("\"");
        }
        if (username != null) {
            userJson.append(",\"username\":\"").append(username).append("\"");
        }
        userJson.append("}");

        TreeMap<String, String> decoded = new TreeMap<>();
        decoded.put("auth_date", String.valueOf(authDate));
        decoded.put("user", userJson.toString());
        String dcs = decoded.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));
        String hash = computeHash(botToken, dcs);

        return "auth_date=" + authDate
                + "&user=" + URLEncoder.encode(userJson.toString(), StandardCharsets.UTF_8)
                + "&hash=" + hash;
    }

    /**
     * Собирает initData из произвольных top-level полей (legacy Login Widget формат).
     * Оставлен для edge-case тестов (tamper, missing hash).
     */
    static String buildInitData(String botToken, Map<String, String> fields) throws Exception {
        TreeMap<String, String> params = new TreeMap<>(fields);
        String dcs = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));
        params.put("hash", computeHash(botToken, dcs));
        return params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
    }
}
