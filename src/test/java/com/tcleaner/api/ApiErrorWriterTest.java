package com.tcleaner.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorWriterTest {

    @Test
    void writesJsonWithEscapedCharacters() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ApiErrorWriter.writeJson(response, 400, "bad\"code", "line1\nline2\\tail");

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).isEqualTo(
            "{\"code\":\"bad\\\"code\",\"message\":\"line1\\nline2\\\\tail\"}"
        );
    }
}
