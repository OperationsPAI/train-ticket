package com.trainticket.platformkit.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.http.CanonicalErrorWriter;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.mock.web.MockHttpServletResponse;

class IdempotencyFilterTest {
    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
    private final IdempotencyFilter filter = new IdempotencyFilter(store, new CanonicalErrorWriter(new ObjectMapper()));

    @Test
    void rejectsMissingOrNonV7Key() throws Exception {
        MockHttpServletResponse response = execute("not-a-v7", "{\"a\":1}", okChain("created"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("VALIDATION_FAILED");

        MockHttpServletResponse v4 = execute("550e8400-e29b-41d4-a716-446655440000", "{\"a\":1}", okChain("created"));
        assertThat(v4.getStatus()).isEqualTo(400);
        assertThat(v4.getContentAsString()).contains("VALIDATION_FAILED");
    }

    @Test
    void replaysOriginalResponseAndRejectsDifferentBody() throws Exception {
        String key = UuidV7.generate();

        MockHttpServletResponse first = execute(key, "{\"a\":1}", okChain("created"));
        MockHttpServletResponse replay = execute(key, "{\"a\":1}", okChain("different"));
        MockHttpServletResponse reused = execute(key, "{\"a\":2}", okChain("different"));

        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(first.getContentAsString()).isEqualTo("created");
        assertThat(replay.getStatus()).isEqualTo(201);
        assertThat(replay.getContentAsString()).isEqualTo("created");
        assertThat(reused.getStatus()).isEqualTo(422);
        assertThat(reused.getContentAsString()).contains("IDEMPOTENCY_KEY_REUSED");
    }

    private MockHttpServletResponse execute(String key, String body, FilterChain chain) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payments");
        if (key != null) {
            request.addHeader(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, key);
        }
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static FilterChain okChain(String body) {
        return (request, response) -> {
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus(201);
            httpResponse.setContentType("application/json");
            httpResponse.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        };
    }
}
