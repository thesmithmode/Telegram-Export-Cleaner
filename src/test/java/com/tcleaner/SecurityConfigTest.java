package com.tcleaner;

import com.tcleaner.api.TelegramController;
import com.tcleaner.core.TelegramExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Тесты для SecurityConfig.
 *
 * Проверяет корректность конфигурации безопасности, заголовков и аутентификации.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("SecurityConfig")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TelegramExporter mockExporter;

    @Nested
    @DisplayName("Публичные endpoints")
    class PublicEndpointsTests {

        @Test
        @DisplayName("Health endpoint доступен без аутентификации")
        void healthIsPublic() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("/api/health должен вернуть JSON")
        void healthReturnsJson() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(content().contentType("application/json"));
        }
    }

    @Nested
    @DisplayName("Защита от CSRF")
    class CsrfProtectionTests {

        @Test
        @DisplayName("CSRF токены не требуются для stateless API")
        void csrfNotRequiredForApi() throws Exception {
            // POST без CSRF токена должен работать для API
            mockMvc.perform(post("/api/convert"))
                    .andExpect(status().isUnauthorized());  // Unauthorized по API key, не CSRF
        }
    }

    @Nested
    @DisplayName("Заголовки безопасности")
    class SecurityHeadersTests {

        @Test
        @DisplayName("должен присутствовать заголовок X-Frame-Options")
        void shouldHaveXFrameOptionsHeader() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(header().exists("X-Frame-Options"));
        }

        @Test
        @DisplayName("X-Frame-Options должен быть DENY")
        void xFrameOptionsShouldBeDeny() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(header().string("X-Frame-Options", "DENY"));
        }

        @Test
        @DisplayName("должен присутствовать Content-Security-Policy заголовок")
        void shouldHaveContentSecurityPolicy() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(header().exists("Content-Security-Policy"));
        }

        @Test
        @DisplayName("CSP должен быть strict (default-src 'none')")
        void cspShouldBeStrict() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(header().string("Content-Security-Policy",
                            org.hamcrest.Matchers.containsString("default-src 'none'")));
        }

        @Test
        @DisplayName("должен присутствовать X-Content-Type-Options")
        void shouldHaveXContentTypeOptions() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(header().exists("X-Content-Type-Options"));
        }

        @Test
        @DisplayName("X-Content-Type-Options должен быть nosniff")
        void xContentTypeShouldBeNosniff() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }
    }

    @Nested
    @DisplayName("Аутентификация API")
    class ApiAuthenticationTests {

        @Test
        @DisplayName("POST /api/convert требует аутентификации")
        void convertRequiresAuth() throws Exception {
            mockMvc.perform(post("/api/convert"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /api/health НЕ требует аутентификации")
        void healthDoesNotRequireAuth() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Неверный API ключ должен вернуть 401")
        void invalidApiKeyReturns401() throws Exception {
            mockMvc.perform(post("/api/convert")
                    .header("X-API-Key", "wrong-key"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Session management")
    class SessionManagementTests {

        @Test
        @DisplayName("API должен быть stateless")
        void apiShouldBeStateless() throws Exception {
            // Stateless означает что нет JSESSIONID cookie
            mockMvc.perform(get("/api/health"))
                    .andExpect(cookie().doesNotExist("JSESSIONID"));
        }
    }
}
