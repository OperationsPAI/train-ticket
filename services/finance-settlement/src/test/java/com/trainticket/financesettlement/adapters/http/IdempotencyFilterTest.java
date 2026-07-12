package com.trainticket.financesettlement.adapters.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.http.CanonicalErrorWriter;
import com.trainticket.platformkit.idempotency.IdempotencyFilter;
import com.trainticket.platformkit.idempotency.InMemoryIdempotencyStore;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class IdempotencyFilterTest {
    @Test
    void replayReturnsOriginalResult() throws ServletException, IOException {
        IdempotencyFilter filter = new IdempotencyFilter(new InMemoryIdempotencyStore(), new CanonicalErrorWriter(new ObjectMapper()));
        MockHttpServletRequest first = request("/api/v1/example");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, new RespondingChain(201, "{\"id\":\"first\"}"));

        MockHttpServletRequest replay = request("/api/v1/example");
        MockHttpServletResponse replayResponse = new MockHttpServletResponse();
        filter.doFilter(replay, replayResponse, new RespondingChain(500, "{\"id\":\"second\"}"));

        assertEquals(201, replayResponse.getStatus());
        assertEquals("{\"id\":\"first\"}", replayResponse.getContentAsString());
    }

    @Test
    void missingKeyReturnsValidationError() throws ServletException, IOException {
        IdempotencyFilter filter = new IdempotencyFilter(new InMemoryIdempotencyStore(), new CanonicalErrorWriter(new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/example");
        request.addHeader("X-Correlation-Id", "corr-idempotency");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(400, response.getStatus());
        assertEquals(true, response.getContentAsString().contains("VALIDATION_FAILED"));
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.addHeader(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, "0194f2e0-7b3e-7610-8284-5c26e8b0c111");
        return request;
    }

    private static final class RespondingChain extends MockFilterChain {
        private final int status;
        private final String body;

        private RespondingChain(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) throws IOException {
            ((jakarta.servlet.http.HttpServletResponse) response).setStatus(status);
            response.setContentType("application/json");
            response.getWriter().write(body);
        }
    }
}
