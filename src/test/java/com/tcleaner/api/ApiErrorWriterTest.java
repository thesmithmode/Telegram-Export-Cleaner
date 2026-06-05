package com.tcleaner.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorWriterTest {

    @Test
    void writesJsonWithEscapedCharacters() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ApiErrorWriter.writeJson(response, 400, "bad\"code", "line1\nline2\rline3\tend\\tail");

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getContentAsString()).isEqualTo(
            "{\"code\":\"bad\\\"code\",\"message\":\"line1\\nline2\\rline3\\tend\\\\tail\"}"
        );
    }

    @Test
    void writesJsonWithNullCodeAndMessage() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ApiErrorWriter.writeJson(response, 400, null, null);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getContentAsString()).isEqualTo(
            "{\"code\":null,\"message\":null}"
        );
    }

    @Test
    void writesJsonWithPartialNullValues() throws Exception {
        MockHttpServletResponse responseWithNullCode = new MockHttpServletResponse();
        MockHttpServletResponse responseWithNullMessage = new MockHttpServletResponse();

        ApiErrorWriter.writeJson(responseWithNullCode, 400, null, "only-message");
        ApiErrorWriter.writeJson(responseWithNullMessage, 400, "only-code", null);

        assertThat(responseWithNullCode.getStatus()).isEqualTo(400);
        assertThat(responseWithNullCode.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(responseWithNullCode.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(responseWithNullCode.getContentAsString()).isEqualTo(
            "{\"code\":null,\"message\":\"only-message\"}"
        );

        assertThat(responseWithNullMessage.getStatus()).isEqualTo(400);
        assertThat(responseWithNullMessage.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(responseWithNullMessage.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(responseWithNullMessage.getContentAsString()).isEqualTo(
            "{\"code\":\"only-code\",\"message\":null}"
        );
    }

    @Test
    void writesJsonWithNonAsciiCharacters() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ApiErrorWriter.writeJson(response, 500, "ошибка", "emoji 🤖 and accented é");

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getContentAsString()).isEqualTo(
            "{\"code\":\"ошибка\",\"message\":\"emoji 🤖 and accented é\"}"
        );
    }
}
