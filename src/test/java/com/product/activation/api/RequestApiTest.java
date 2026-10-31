package com.product.activation.api;

import com.product.activation.application.ActivationRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequestApiTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ActivationRepository repository;
    private static final String BODY = """
            {"externalId":"EXT-1","customerId":"CUST-1","productCode":"INTERNET-500"}
            """;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor token(String subject, String scope) {
        return jwt().jwt(jwt -> jwt.subject(subject)).authorities(new SimpleGrantedAuthority("SCOPE_requests." + scope));
    }

    @Test
    void createsReplaysAndReadsWithoutDuplicates() throws Exception {
        String location = mvc.perform(post("/api/v1/requests").with(token("a", "write"))
                        .contentType("application/json").content(BODY)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING")).andReturn().getResponse().getHeader("Location");
        mvc.perform(post("/api/v1/requests").with(token("a", "write")).contentType("application/json").content(BODY))
                .andExpect(status().isOk()).andExpect(header().string("Location", location));
        assertThat(repository.count()).isEqualTo(1);
        mvc.perform(get(location).with(token("a", "read"))).andExpect(status().isOk()).andExpect(jsonPath("$.externalId").value("EXT-1"));
        mvc.perform(get(location).with(token("b", "read"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/requests").with(token("b", "read"))).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/requests?status=PENDING&customerId=CUST-1").with(token("a", "read")))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(post("/api/v1/requests").with(token("a", "write")).contentType("application/json")
                .content(BODY.replace("INTERNET-500", "OTHER"))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/requests").with(token("b", "write")).contentType("application/json").content(BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void validatesInputAndReturnsSafeErrors() throws Exception {
        mvc.perform(post("/api/v1/requests").with(token("a", "write")).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").isArray()).andExpect(jsonPath("$.traceId").isNotEmpty());
        mvc.perform(get("/api/v1/requests/00000000-0000-0000-0000-000000000000").with(token("a", "read")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/requests?size=101").with(token("a", "read"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/requests").with(token("a", "write")).contentType("application/json").content("x".repeat(8193)))
                .andExpect(status().is(413));
    }

    @Test
    void requiresAuthenticationAndScopes() throws Exception {
        mvc.perform(get("/api/v1/requests")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/requests").header("Authorization", "Bearer malformed")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/requests").with(token("a", "write"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/requests").with(token("a", "read")).contentType("application/json").content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsPaginationBeyondJpaOffsetLimit() throws Exception {
        mvc.perform(get("/api/v1/requests?page=2147483647&size=100").with(token("a", "read")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void rejectsAmbiguousJsonDocuments() throws Exception {
        for (String body : new String[]{BODY + "{}", BODY.replace("\"EXT-1\"", "\"EXT-1\",\"externalId\":\"EXT-2\"")}) {
            mvc.perform(post("/api/v1/requests").with(token("a", "write"))
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        assertThat(repository.count()).isZero();
    }
}
