package com.tcleaner.dashboard.auth.telegram;

import java.util.Map;
import java.util.TreeMap;

/**
 * Данные от Telegram Login Widget (GET-параметры callback).
 * {@link #hash} — HMAC-SHA256 для проверки подлинности.
 * {@link #toDataCheckString()} строит строку по спецификации core.telegram.org/widgets/login.
 */
public record TelegramLoginData(
        long id,
        String firstName,
        String lastName,
        String username,
        String photoUrl,
        long authDate,
        String hash
) {

    public String toDataCheckString() {
        Map<String, String> sorted = new TreeMap<>();
        sorted.put("id", Long.toString(id));
        sorted.put("auth_date", Long.toString(authDate));
        if (firstName != null) sorted.put("first_name", firstName);
        if (lastName != null) sorted.put("last_name", lastName);
        if (username != null) sorted.put("username", username);
        if (photoUrl != null) sorted.put("photo_url", photoUrl);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
