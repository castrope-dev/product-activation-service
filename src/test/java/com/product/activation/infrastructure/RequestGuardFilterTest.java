package com.product.activation.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

import static org.assertj.core.api.Assertions.*;

class RequestGuardFilterTest {
    @Test
    void rejectsOversizedBodyWithoutContentLengthAndCleansCorrelation() throws Exception {
        var filter = new RequestGuardFilter(new ObjectMapper(), 8, 10);
        var request = new MockHttpServletRequest("POST", "/api/v1/requests");
        request.setContent("123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var unknownLength = new jakarta.servlet.http.HttpServletRequestWrapper(request) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(unknownLength, response, chain);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
        assertThat(org.slf4j.MDC.get("correlationId")).isNull();
    }

    @Test
    void boundsCallsAndReturnsCorrelation() throws Exception {
        var filter = new RequestGuardFilter(new ObjectMapper(), 8192, 1);
        var request = new MockHttpServletRequest("GET", "/api/v1/requests");
        request.addHeader("X-Correlation-ID", "safe-id");
        var first = new MockHttpServletResponse();
        filter.doFilter(request, first, new MockFilterChain());
        var second = new MockHttpServletResponse();
        filter.doFilter(request, second, new MockFilterChain());
        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(429);
        assertThat(second.getHeader("Retry-After")).isEqualTo("60");
        assertThat(second.getHeader("X-Correlation-ID")).isEqualTo("safe-id");
    }
}
