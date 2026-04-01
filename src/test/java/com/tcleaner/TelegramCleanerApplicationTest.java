package com.tcleaner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тест запуска Spring-контекста.
 *
 * <p>Проверяет что приложение поднимается без ошибок,
 * UserDetailsService не регистрируется (аутентификация отключена).</p>
 */
@SpringBootTest
@Testcontainers
@DisplayName("TelegramCleanerApplication — контекст Spring")
class TelegramCleanerApplicationTest {

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Контекст Spring загружается без ошибок")
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("UserDetailsService не зарегистрирован в контексте")
    void noUserDetailsServiceBean() {
        assertThat(context.getBeanNamesForType(
                org.springframework.security.core.userdetails.UserDetailsService.class))
                .isEmpty();
    }

    @Test
    @DisplayName("SecurityConfig присутствует в контексте")
    void securityConfigBeanExists() {
        assertThat(context.getBean(SecurityConfig.class)).isNotNull();
    }
}
