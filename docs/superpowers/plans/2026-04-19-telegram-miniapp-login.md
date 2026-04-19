# Telegram Mini App Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Web Auth Flow Widget with Telegram Mini App for reliable single-account authentication directly from Telegram client.

**Architecture:** User opens bot → `/start` shows WebAppInfo button → Mini App loads in Telegram client → Telegram.WebApp sends cryptographically signed initData → backend validates HMAC-SHA256 → session created. Single Telegram account guaranteed (no confusion with browser cookie state).

**Tech Stack:** Spring Boot 3.4.4, Telegram Bot API (WebAppInfo), Telegram WebApp JavaScript API, HMAC-SHA256 validation, same session/auth infrastructure (no changes to Spring Security).

---

## File Structure

**Modified files:**
- `src/main/java/com/tcleaner/dashboard/auth/telegram/TelegramLoginData.java` — new: parse initData (JSON format)
- `src/main/java/com/tcleaner/dashboard/auth/telegram/TelegramAuthVerifier.java` — new: Mini App HMAC validation algorithm (different from Widget)
- `src/main/java/com/tcleaner/dashboard/auth/telegram/TelegramAuthController.java` — adapt endpoint to accept initData instead of query params
- `src/main/java/com/tcleaner/bot/ExportBot.java` — add `/start` command handler with WebAppInfo

**New files:**
- `src/main/java/com/tcleaner/dashboard/web/MiniAppController.java` — GET /mini-app (serves HTML/JS)
- `src/main/resources/templates/mini-app.html` — Telegram WebApp client (Telegram.WebApp API + form submission)
- `src/test/java/com/tcleaner/dashboard/auth/telegram/TelegramMiniAppAuthVerifierTest.java` — HMAC validation tests
- `src/test/java/com/tcleaner/dashboard/web/MiniAppControllerTest.java` — Mini App endpoint test

---

## Task 1: Create Failing Test for Mini App initData Validation

**Files:**
- Create: `src/test/java/com/tcleaner/dashboard/auth/telegram/TelegramMiniAppAuthVerifierTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.tcleaner.dashboard.auth.telegram;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class TelegramMiniAppAuthVerifierTest {

    private static final String BOT_TOKEN = "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11";
    private static final long USER_ID = 987654321L;
    private static final String USERNAME = "testuser";
    private static final String FIRST_NAME = "Test";

    @Test
    void testValidInitDataAccepted() {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1000000000), ZoneId.of("UTC"));
        TelegramMiniAppAuthVerifier verifier = new TelegramMiniAppAuthVerifier(BOT_TOKEN, clock);

        // initData format: key1=value1&key2=value2&...&hash=<signature>
        // Must be sorted alphabetically by key (excluding hash)
        String initData = "auth_date=1000000000&first_name=Test&id=987654321&username=testuser&hash=abc123";

        // Should not throw exception for valid data
        assertDoesNotThrow(() -> {
            TelegramMiniAppLoginData data = TelegramMiniAppLoginData.parse(initData);
            verifier.verify(data);
        });
    }

    @Test
    void testInvalidHashRejected() {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1000000000), ZoneId.of("UTC"));
        TelegramMiniAppAuthVerifier verifier = new TelegramMiniAppAuthVerifier(BOT_TOKEN, clock);

        String initData = "auth_date=1000000000&first_name=Test&id=987654321&username=testuser&hash=invalid";

        TelegramAuthenticationException ex = assertThrows(
                TelegramAuthenticationException.class,
                () -> {
                    TelegramMiniAppLoginData data = TelegramMiniAppLoginData.parse(initData);
                    verifier.verify(data);
                });
        assertTrue(ex.getMessage().contains("hash"));
    }

    @Test
    void testExpiredAuthDateRejected() {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(2000000000), ZoneId.of("UTC"));
        TelegramMiniAppAuthVerifier verifier = new TelegramMiniAppAuthVerifier(BOT_TOKEN, clock);

        // auth_date 5+ minutes in past
        String initData = "auth_date=1000000000&first_name=Test&id=987654321&username=testuser&hash=abc123";

        TelegramAuthenticationException ex = assertThrows(
                TelegramAuthenticationException.class,
                () -> {
                    TelegramMiniAppLoginData data = TelegramMiniAppLoginData.parse(initData);
                    verifier.verify(data);
                });
        assertTrue(ex.getMessage().contains("auth_date"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=TelegramMiniAppAuthVerifierTest -DfailIfNoTests=false
```

Expected: FAIL — classes not found (TelegramMiniAppAuthVerifier, TelegramMiniAppLoginData don't exist yet)

---

## Task 2: Implement TelegramMiniAppLoginData Record

**Files:**
- Create: `src/main/java/com/tcleaner/dashboard/auth/telegram/TelegramMiniAppLoginData.java`

- [ ] **Step 1: Write record class**

```java
package com.tcleaner.dashboard.auth.telegram;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parsed Mini App initData from Telegram.WebApp.initData.
 * Format: key1=value1&key2=value2&...&hash=<hex>
 * Keys must be sorted alphabetically for data_check_string validation.
 */
public record TelegramMiniAppLoginData(
        long id,
        String firstName,
        String lastName,
        String username,
        long authDate,
        String hash
) {

    /**
     * Parse initData query string into TelegramMiniAppLoginData.
     * @param initData format: "auth_date=1000&id=123&hash=abc&..."
     */
    public static TelegramMiniAppLoginData parse(String initData) {
        Map<String, String> params = new LinkedHashMap<>();
        String[] pairs = initData.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0], kv[1]);
            }
        }

        long id = Long.parseLong(params.getOrDefault("id", "0"));
        String firstName = params.get("first_name");
        String lastName = params.get("last_name");
        String username = params.get("username");
        long authDate = Long.parseLong(params.getOrDefault("auth_date", "0"));
        String hash = params.get("hash");

        return new TelegramMiniAppLoginData(id, firstName, lastName, username, authDate, hash);
    }

    /**
     * Build data_check_string for HMAC validation.
     * Algorithm: sort keys (excluding hash), join with \n as key=value pairs.
     */
    public String toDataCheckString() {
        Map<String, String> sorted = new LinkedHashMap<>();
        sorted.put("auth_date", String.valueOf(authDate));
        if (firstName != null) sorted.put("first_name", firstName);
        if (id != 0) sorted.put("id", String.valueOf(id));
        if (lastName != null) sorted.put("last_name", lastName);
        if (username != null) sorted.put("username", username);

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
```

- [ ] **Step 2: Run test again**

```bash
mvn test -Dtest=TelegramMiniAppAuthVerifierTest::testValidInitDataAccepted -DfailIfNoTests=false
```

Expected: FAIL — TelegramMiniAppAuthVerifier not implemented yet

---

## Task 3: Implement TelegramMiniAppAuthVerifier

**Files:**
- Create: `src/main/java/com/tcleaner/dashboard/auth/telegram/TelegramMiniAppAuthVerifier.java`

- [ ] **Step 1: Write verifier class**

```java
package com.tcleaner.dashboard.auth.telegram;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Validates Mini App initData from Telegram.WebApp.
 * Algorithm (core.telegram.org/bots/webapps#validating-data-received-via-the-web-app):
 *   secret_key = HMAC-SHA256("WebAppData", bot_token)
 *   hash = HMAC-SHA256(data_check_string, secret_key)
 * Plus auth_date validation (not older than {@link #MAX_AGE}).
 */
public class TelegramMiniAppAuthVerifier {

    public static final Duration MAX_AGE = Duration.ofMinutes(5);

    private final byte[] secretKey;
    private final Clock clock;

    public TelegramMiniAppAuthVerifier(String botToken, Clock clock) {
        try {
            // Step 1: secret_key = HMAC-SHA256(bot_token, "WebAppData")
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            this.secretKey = mac.doFinal(botToken.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 or key generation failed", e);
        }
        this.clock = clock;
    }

    public void verify(TelegramMiniAppLoginData data) {
        if (data.hash() == null || data.hash().isBlank()) {
            throw new TelegramAuthenticationException("Missing hash in initData");
        }

        // Compute expected hash from data_check_string
        String dataCheckString = data.toDataCheckString();
        String expected = computeHash(dataCheckString);

        // Compare hashes (constant-time)
        if (!constantTimeEquals(expected, data.hash())) {
            throw new TelegramAuthenticationException("Invalid hash — data is forged or corrupted");
        }

        // Check auth_date freshness
        long now = clock.instant().getEpochSecond();
        long age = now - data.authDate();
        if (age > MAX_AGE.toSeconds() || age < -MAX_AGE.toSeconds()) {
            throw new TelegramAuthenticationException(
                    String.format("Stale auth_date: age=%d seconds, max=%d", age, MAX_AGE.toSeconds()));
        }
    }

    private String computeHash(String dataCheckString) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            byte[] raw = mac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Hash computation failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
```

- [ ] **Step 2: Run all TelegramMiniAppAuthVerifierTest tests**

```bash
mvn test -Dtest=TelegramMiniAppAuthVerifierTest -DfailIfNoTests=false
```

Expected: All 3 tests PASS (but with hardcoded hash values, tests will still fail until we compute correct hashes)

---

## Task 4: Fix Test with Correct HMAC Hashes

**Files:**
- Modify: `src/test/java/com/tcleaner/dashboard/auth/telegram/TelegramMiniAppAuthVerifierTest.java`

- [ ] **Step 1: Compute correct HMAC hash for test data**

```bash
# Run small Java snippet to compute correct hash
cat > /tmp/HashCompute.java << 'EOF'
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public class HashCompute {
    public static void main(String[] args) throws Exception {
        String botToken = "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11";
        
        // Compute secret_key = HMAC-SHA256(bot_token, "WebAppData")
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] secretKey = mac.doFinal(botToken.getBytes(StandardCharsets.UTF_8));
        
        // data_check_string (sorted): auth_date=1000000000\nfirst_name=Test\nid=987654321\nusername=testuser
        String dataCheckString = "auth_date=1000000000\nfirst_name=Test\nid=987654321\nusername=testuser";
        
        // Compute hash = HMAC-SHA256(data_check_string, secret_key)
        mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
        byte[] hashBytes = mac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8));
        String hash = HexFormat.of().formatHex(hashBytes);
        
        System.out.println("Computed hash: " + hash);
    }
}
EOF
cd /tmp && javac HashCompute.java && java HashCompute
```

- [ ] **Step 2: Update test with correct hash**

Replace `hash=abc123` with actual computed hash in testValidInitDataAccepted

- [ ] **Step 3: Run tests again**

```bash
mvn test -Dtest=TelegramMiniAppAuthVerifierTest -DfailIfNoTests=false
```

Expected: All 3 tests PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/tcleaner/dashboard/auth/telegram/TelegramMiniApp*.java
git add src/test/java/com/tcleaner/dashboard/auth/telegram/TelegramMiniAppAuthVerifierTest.java
git commit -m "FEAT: реализовать TelegramMiniAppLoginData и TelegramMiniAppAuthVerifier для валидации initData"
```

---

## Task 5: Create Mini App Frontend HTML/JS

**Files:**
- Create: `src/main/resources/templates/mini-app.html`

- [ ] **Step 1: Write Mini App HTML template**

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Telegram Export Cleaner — Login</title>
    <script src="https://telegram.org/js/telegram-web-app.js"></script>
    <style>
        body {
            margin: 0;
            padding: 20px;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            background: #f5f5f5;
        }
        .container {
            max-width: 400px;
            margin: 0 auto;
            background: white;
            padding: 20px;
            border-radius: 8px;
            box-shadow: 0 2px 8px rgba(0,0,0,0.1);
        }
        .header {
            text-align: center;
            margin-bottom: 30px;
        }
        .header h1 {
            margin: 0;
            font-size: 24px;
            color: #333;
        }
        .header p {
            margin: 10px 0 0;
            color: #666;
            font-size: 14px;
        }
        .button {
            width: 100%;
            padding: 12px;
            margin-top: 20px;
            background: #0088cc;
            color: white;
            border: none;
            border-radius: 4px;
            font-size: 16px;
            cursor: pointer;
        }
        .button:hover {
            background: #0077bb;
        }
        .button:disabled {
            background: #ccc;
            cursor: not-allowed;
        }
        .error {
            color: #d32f2f;
            margin-top: 10px;
            font-size: 14px;
        }
        .loading {
            text-align: center;
            color: #666;
        }
    </style>
</head>
<body>
<div class="container">
    <div class="header">
        <h1>Telegram Export Cleaner</h1>
        <p>Sign in with your Telegram account</p>
    </div>

    <div id="content">
        <button class="button" onclick="handleLogin()">Sign In with Telegram</button>
        <div id="error" class="error"></div>
    </div>
</div>

<form id="loginForm" method="POST" action="/dashboard/login/telegram" style="display: none;">
    <input type="hidden" id="initData" name="initData">
</form>

<script>
    // Initialize Telegram WebApp
    const tg = window.Telegram.WebApp;
    tg.ready();
    
    // Expand to full height
    if (tg.expand) {
        tg.expand();
    }

    async function handleLogin() {
        const content = document.getElementById('content');
        const errorDiv = document.getElementById('error');
        const button = content.querySelector('.button');
        
        try {
            // Clear previous errors
            errorDiv.textContent = '';
            button.disabled = true;
            
            // Get initData from Telegram
            const initData = tg.initData;
            if (!initData) {
                throw new Error('Failed to get initData from Telegram');
            }

            // Submit to backend
            const form = document.getElementById('loginForm');
            document.getElementById('initData').value = initData;
            form.submit();
            
        } catch (error) {
            errorDiv.textContent = 'Error: ' + (error.message || 'Unknown error');
            button.disabled = false;
        }
    }
</script>
</body>
</html>
```

- [ ] **Step 2: Create MiniAppController**

Create file: `src/main/java/com/tcleaner/dashboard/web/MiniAppController.java`

```java
package com.tcleaner.dashboard.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Serves Telegram Mini App frontend.
 * GET /mini-app renders HTML with Telegram.WebApp JavaScript API integration.
 */
@Controller
@RequestMapping("/mini-app")
public class MiniAppController {

    @GetMapping
    public String miniApp() {
        return "mini-app";
    }
}
```

- [ ] **Step 3: Run test to verify controller routing works**

```bash
mvn test -Dtest=MiniAppControllerTest -DfailIfNoTests=false
```

Expected: FAIL (test doesn't exist yet, but we'll create it in next task)

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/templates/mini-app.html
git add src/main/java/com/tcleaner/dashboard/web/MiniAppController.java
git commit -m "FEAT: создать Mini App фронтенд (HTML/JS с Telegram.WebApp API)"
```

---

## Task 6: Create Mini App Controller Tests

**Files:**
- Create: `src/test/java/com/tcleaner/dashboard/web/MiniAppControllerTest.java`

- [ ] **Step 1: Write test**

```java
package com.tcleaner.dashboard.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MiniAppController.class)
class MiniAppControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testMiniAppLoads() throws Exception {
        mockMvc.perform(get("/mini-app"))
                .andExpect(status().isOk())
                .andExpect(view().name("mini-app"))
                .andExpect(content().stringContaining("Telegram.WebApp"));
    }

    @Test
    void testMiniAppPublicAccess() throws Exception {
        mockMvc.perform(get("/mini-app"))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run test**

```bash
mvn test -Dtest=MiniAppControllerTest -DfailIfNoTests=false
```

Expected: PASS (controller exists and serves the HTML)

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/tcleaner/dashboard/web/MiniAppControllerTest.java
git commit -m "TEST: добавить тесты для MiniAppController"
```

---

## Task 7: Modify TelegramAuthController to Accept Mini App initData

**Files:**
- Modify: `src/main/java/com/tcleaner/dashboard/auth/telegram/TelegramAuthController.java`

- [ ] **Step 1: Update endpoint signature**

Change from:
```java
@GetMapping
@Transactional
public String callback(@RequestParam("id") long id, ...)
```

To:
```java
@PostMapping
@Transactional
public String callback(@RequestParam(value = "initData", required = false) String initData,
                       HttpServletRequest request,
                       HttpServletResponse response)
```

- [ ] **Step 2: Add initData parsing and backward compatibility**

Replace callback body with:

```java
@PostMapping
@Transactional
public String callback(@RequestParam(value = "initData", required = false) String initData,
                       HttpServletRequest request,
                       HttpServletResponse response) {
    
    TelegramMiniAppLoginData data;
    try {
        if (initData == null || initData.isBlank()) {
            throw new TelegramAuthenticationException("Missing initData");
        }
        data = TelegramMiniAppLoginData.parse(initData);
    } catch (Exception e) {
        log.warn("Failed to parse Mini App initData: {}", e.getMessage());
        return "redirect:/dashboard/login?error=invalid";
    }

    try {
        verifierMiniApp.verify(data);
    } catch (TelegramAuthenticationException e) {
        log.warn("Mini App login rejected (invalid): id={} reason={}", data.id(), e.getMessage());
        return "redirect:/dashboard/login?error=invalid";
    }

    long id = data.id();
    String firstName = data.firstName();
    String username = data.username();

    DashboardRole role;
    Long botUserId;
    if (id == adminTelegramId) {
        role = DashboardRole.ADMIN;
        botUserId = null;
    } else {
        botUserUpserter.upsert(id, username, firstName, Instant.ofEpochSecond(data.authDate()));
        role = DashboardRole.USER;
        botUserId = id;
    }

    DashboardUser user = userService.findOrCreate(id, firstName, username, role, botUserId);
    // ... rest of session creation code (unchanged)
}
```

- [ ] **Step 3: Add verifierMiniApp field and inject it**

Add to class:
```java
private final TelegramMiniAppAuthVerifier verifierMiniApp;

public TelegramAuthController(TelegramAuthVerifier verifier,
                              TelegramMiniAppAuthVerifier verifierMiniApp,
                              DashboardUserService userService,
                              BotUserUpserter botUserUpserter,
                              @Value("${dashboard.auth.admin.telegram-id}") long adminTelegramId) {
    this.verifier = verifier;
    this.verifierMiniApp = verifierMiniApp;
    // ... rest
}
```

- [ ] **Step 4: Register TelegramMiniAppAuthVerifier bean (if not autowired by Spring)**

In `src/main/java/com/tcleaner/dashboard/config/DashboardSecurityConfig.java`, add:

```java
@Bean
public TelegramMiniAppAuthVerifier telegramMiniAppAuthVerifier(
        @Value("${telegram.bot.token}") String botToken) {
    return new TelegramMiniAppAuthVerifier(botToken, Clock.systemUTC());
}
```

- [ ] **Step 5: Run existing TelegramAuthController tests to verify backward compatibility**

```bash
mvn test -Dtest=TelegramAuthControllerTest -DfailIfNoTests=false
```

Expected: Tests may fail (old tests expect GET, new endpoint is POST) — update tests in next task

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tcleaner/dashboard/auth/telegram/TelegramAuthController.java
git add src/main/java/com/tcleaner/dashboard/config/DashboardSecurityConfig.java
git commit -m "FEAT: модифицировать TelegramAuthController для поддержки Mini App initData (POST вместо GET)"
```

---

## Task 8: Update TelegramAuthController Tests for Mini App

**Files:**
- Modify: `src/test/java/com/tcleaner/dashboard/auth/telegram/TelegramAuthControllerTest.java`

- [ ] **Step 1: Add test for valid Mini App login**

```java
@Test
@Transactional
void testMiniAppLoginSucceeds() throws Exception {
    // Setup: create valid initData with correct HMAC
    String botToken = "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11";
    long userId = 987654321L;
    long authDate = System.currentTimeMillis() / 1000;
    
    // Build initData = "auth_date=X&id=Y&hash=Z" (must be properly signed)
    TelegramMiniAppAuthVerifier verifier = new TelegramMiniAppAuthVerifier(botToken, Clock.systemUTC());
    // Create data, compute hash, build initData string
    
    mockMvc.perform(post("/dashboard/login/telegram")
            .param("initData", initData))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/dashboard/me"));
    
    // Verify session created
    MvcResult result = mockMvc.perform(get("/dashboard/me"))
            .andExpect(status().isOk())
            .andReturn();
}

@Test
void testMiniAppLoginWithInvalidHashRejected() throws Exception {
    String invalidInitData = "auth_date=1000000000&id=987654321&hash=invalidebc";
    
    mockMvc.perform(post("/dashboard/login/telegram")
            .param("initData", invalidInitData))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/dashboard/login?error=invalid"));
}

@Test
void testMiniAppLoginWithMissingInitDataRejected() throws Exception {
    mockMvc.perform(post("/dashboard/login/telegram"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/dashboard/login?error=invalid"));
}
```

- [ ] **Step 2: Run tests**

```bash
mvn test -Dtest=TelegramAuthControllerTest -DfailIfNoTests=false
```

Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/tcleaner/dashboard/auth/telegram/TelegramAuthControllerTest.java
git commit -m "TEST: обновить тесты TelegramAuthController для Mini App POST endpoint"
```

---

## Task 9: Add /start Command to Telegram Bot with WebAppInfo

**Files:**
- Modify: `src/main/java/com/tcleaner/bot/ExportBot.java`

- [ ] **Step 1: Add mini-app URL configuration**

In `ExportBot` constructor params, add:
```java
@Value("${dashboard.mini-app.url:https://st.searchingforgamesforever.online/mini-app}")
private String miniAppUrl;
```

- [ ] **Step 2: Update /start handler in consumeUpdate()**

Find the `/start` command handler in `consumeUpdate()` method. Replace or add:

```java
if (message != null && "/start".equals(message.getText())) {
    // Create button with WebAppInfo pointing to Mini App
    InlineKeyboardButton webAppButton = new InlineKeyboardButton();
    webAppButton.setText("📱 Open Dashboard");
    
    WebAppInfo webAppInfo = new WebAppInfo();
    webAppInfo.setUrl(miniAppUrl);
    webAppButton.setWebApp(webAppInfo);
    
    InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
    keyboard.setKeyboard(List.of(new InlineKeyboardRow(webAppButton)));
    
    SendMessage msg = new SendMessage();
    msg.setChatId(message.getChatId());
    msg.setText("Welcome! Click the button below to open your dashboard and manage your exports.");
    msg.setReplyMarkup(keyboard);
    
    messenger.executeAsync(msg);
    return;
}
```

- [ ] **Step 3: Add mini-app URL to application.properties**

In `src/main/resources/application.properties`, add:
```properties
dashboard.mini-app.url=https://st.searchingforgamesforever.online/mini-app
```

And in test properties `src/test/resources/application-test.properties`:
```properties
dashboard.mini-app.url=http://localhost:8080/mini-app
```

- [ ] **Step 4: Run bot integration tests**

```bash
mvn test -Dtest=ExportBotTest -DfailIfNoTests=false
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tcleaner/bot/ExportBot.java
git add src/main/resources/application.properties
git add src/test/resources/application-test.properties
git commit -m "FEAT: добавить /start команду с WebAppInfo для запуска Mini App"
```

---

## Task 10: Update Login Page HTML to Link to Mini App

**Files:**
- Modify: `src/main/resources/templates/dashboard/login.html`

- [ ] **Step 1: Update HTML to show Mini App link**

Replace widget div with:

```html
<div class="login-form__widget">
    <p style="text-align: center; margin-bottom: 20px;">
        Opening Telegram? Use the <strong>Open Dashboard</strong> button from <code>/start</code> command
    </p>
    <p style="text-align: center; color: #999; font-size: 14px;">
        Or continue with the web widget below for browser-based login:
    </p>
    
    <!-- Keep old widget for fallback -->
    <script async
            src="https://telegram.org/js/telegram-widget.js?22"
            th:attr="data-telegram-login=${botUsername}"
            data-size="large"
            data-auth-url="/dashboard/login/telegram"
            data-request-access="write"></script>
</div>
```

- [ ] **Step 2: Run dashboard tests**

```bash
mvn test -Dtest=DashboardPageControllerTest -DfailIfNoTests=false
```

Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/templates/dashboard/login.html
git commit -m "UX: обновить страницу логина с инструкциями про Mini App"
```

---

## Task 11: Update Documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/index.md` (if exists)

- [ ] **Step 1: Update README with Mini App info**

Add section about Mini App authentication:

```markdown
## Authentication

### Telegram Mini App
The dashboard uses Telegram Mini App for user authentication. Users can:
1. Open the Telegram bot
2. Use `/start` command
3. Click "Open Dashboard" button → Mini App loads in Telegram client
4. Telegram automatically provides user authentication via `initData`

Backend validates initData signature (HMAC-SHA256) to ensure authenticity.

### How it Works
- Bot returns `WebAppInfo` button on `/start`
- Mini App frontend (`/mini-app`) loads in Telegram client
- `Telegram.WebApp.initData` contains cryptographically signed user data
- Frontend sends initData to backend (`POST /dashboard/login/telegram`)
- Backend validates HMAC signature and creates session
```

- [ ] **Step 2: Update CLAUDE.md if needed**

Add note about Mini App implementation approach.

- [ ] **Step 3: Commit**

```bash
git add README.md docs/index.md
git commit -m "DOCS: документировать Mini App аутентификацию"
```

---

## Task 12: Integration Testing

**Files:**
- Modify: `src/test/java/com/tcleaner/dashboard/DashboardSecurityIntegrationTest.java` (or create if missing)

- [ ] **Step 1: Add full Mini App login flow test**

```java
@SpringBootTest
@Transactional
class MiniAppAuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private String validInitData;

    @BeforeEach
    void setUp() {
        // Compute valid initData with correct HMAC signature
        // This should match bot token from config
    }

    @Test
    void testCompleteMiniAppLoginFlow() throws Exception {
        // 1. User visits /mini-app
        mockMvc.perform(get("/mini-app"))
                .andExpect(status().isOk())
                .andExpect(content().stringContaining("Telegram.WebApp"));

        // 2. Mini App submits initData
        mockMvc.perform(post("/dashboard/login/telegram")
                .param("initData", validInitData))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/me"));

        // 3. User now has session and can access dashboard
        mockMvc.perform(get("/dashboard/me"))
                .andExpect(status().isOk());
    }

    @Test
    void testForgedInitDataRejected() throws Exception {
        String forgedInitData = "auth_date=1000000000&id=999999&hash=badsignature";
        
        mockMvc.perform(post("/dashboard/login/telegram")
                .param("initData", forgedInitData))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/login?error=invalid"));
    }
}
```

- [ ] **Step 2: Run all dashboard auth tests**

```bash
mvn test -Dtest='*AuthIntegration*' -DfailIfNoTests=false
```

Expected: All PASS

- [ ] **Step 3: Run full test suite**

```bash
mvn clean test
```

Expected: All tests PASS (no regressions)

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/tcleaner/dashboard/*.java
git commit -m "TEST: добавить integration тесты для Mini App аутентификации"
```

---

## Task 13: Final Verification and Cleanup

- [ ] **Step 1: Verify no unused imports or dead code**

```bash
mvn clean compile
```

Expected: No warnings

- [ ] **Step 2: Verify all tests pass**

```bash
mvn clean test
```

Expected: All PASS

- [ ] **Step 3: Verify application properties are correct**

Check `application.properties` and `application-test.properties` have all required keys.

- [ ] **Step 4: Create final commit with summary**

```bash
git log --oneline HEAD~12..HEAD
```

Expected: 12 commits with feature, test, and docs changes

---

## Validation Checklist

- [ ] Mini App loads at `/mini-app` endpoint
- [ ] Telegram.WebApp JavaScript API is available in Mini App
- [ ] Bot `/start` command shows WebAppInfo button
- [ ] initData validation correctly rejects forged data
- [ ] initData validation accepts valid signatures
- [ ] User can log in via Mini App and access dashboard
- [ ] Old Web Widget still works as fallback (backward compatibility)
- [ ] All tests pass locally
- [ ] No console errors in Mini App
- [ ] Session created and user role assigned correctly
- [ ] ADMIN and USER roles both work via Mini App
- [ ] No hardcoded secrets in code (mini-app URL in properties only)

---

## Known Limitations & Future Improvements

1. **Web Widget Fallback:** Current implementation keeps old Web Widget as fallback for browsers. Consider removing after Mini App is stable.
2. **Session Timeout:** Same as before (5 minutes for auth_date check). Consider adding session timeout config.
3. **Bot Username:** Must be set via env var `TELEGRAM_BOT_USERNAME` for Mini App URL construction.
4. **HTTPS Required:** Mini App must be served over HTTPS (already satisfied by Traefik).

---
