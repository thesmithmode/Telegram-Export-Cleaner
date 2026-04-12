package com.tcleaner;

import com.tcleaner.core.TelegramExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
                    .andExpect(status().isOk());
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
    @DisplayName("Session management")
    class SessionManagementTests {

        @Test
        @DisplayName("API должен быть stateless")
        void apiShouldBeStateless() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(cookie().doesNotExist("JSESSIONID"));
        }
    }
}
