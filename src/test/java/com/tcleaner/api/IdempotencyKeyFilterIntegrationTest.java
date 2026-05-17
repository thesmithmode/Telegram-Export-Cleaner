package com.tcleaner.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class IdempotencyKeyFilterIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private IdempotencyKeyFilter filter;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        filter = new IdempotencyKeyFilter(redis, true);
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    @Test
    void firstRequestPasses() throws Exception {
        MockHttpServletRequest req = post("/api/convert", "integ-first-1234567890abcd");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, res, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void duplicateRequestReturns409() throws Exception {
        String key = "integ-dup-1234567890abcdef";
        MockFilterChain chain1 = new MockFilterChain();
        filter.doFilterInternal(post("/api/convert", key), new MockHttpServletResponse(), chain1);
        assertThat(chain1.getRequest()).isNotNull();

        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilterInternal(post("/api/convert", key), res2, new MockFilterChain());

        assertThat(res2.getStatus()).isEqualTo(409);
        assertThat(res2.getContentAsString()).contains("duplicate_request");
    }

    @Test
    void sameKeyDifferentUrisAreIndependent() throws Exception {
        String key = "integ-uri-diff-1234567890abcd";

        MockFilterChain chain1 = new MockFilterChain();
        filter.doFilterInternal(post("/api/convert", key), new MockHttpServletResponse(), chain1);
        assertThat(chain1.getRequest()).isNotNull();

        MockHttpServletResponse res2 = new MockHttpServletResponse();
        MockFilterChain chain2 = new MockFilterChain();
        filter.doFilterInternal(post("/dashboard/api/me", key), res2, chain2);

        assertThat(chain2.getRequest()).isNotNull();
        assertThat(res2.getStatus()).isEqualTo(200);
    }

    @Test
    void setIfAbsentIsAtomic() throws Exception {
        String key = "integ-atomic-1234567890abcdef";
        MockFilterChain chain1 = new MockFilterChain();
        MockFilterChain chain2 = new MockFilterChain();

        filter.doFilterInternal(post("/api/convert", key), new MockHttpServletResponse(), chain1);
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilterInternal(post("/api/convert", key), res2, chain2);

        assertThat(chain1.getRequest()).isNotNull();
        assertThat(chain2.getRequest()).isNull();
        assertThat(res2.getStatus()).isEqualTo(409);
    }

    @Test
    void failClosedReturns503WhenRedisDown() throws Exception {
        LettuceConnectionFactory brokenFactory =
                new LettuceConnectionFactory("localhost", 19999);
        brokenFactory.afterPropertiesSet();
        StringRedisTemplate brokenRedis = new StringRedisTemplate(brokenFactory);
        IdempotencyKeyFilter failClosed = new IdempotencyKeyFilter(brokenRedis, false);

        MockHttpServletResponse res = new MockHttpServletResponse();
        failClosed.doFilterInternal(post("/api/convert", "fail-closed-valid-12345678"), res, new MockFilterChain());

        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getHeader("Retry-After")).isEqualTo("5");
        assertThat(res.getContentAsString()).contains("idempotency_backend_unavailable");

        brokenFactory.destroy();
    }

    private static MockHttpServletRequest post(String uri, String idempotencyKey) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.addHeader("Idempotency-Key", idempotencyKey);
        return req;
    }
}
