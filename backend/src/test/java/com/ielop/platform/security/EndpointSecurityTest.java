package com.ielop.platform.security;

import com.ielop.platform.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * SEC-001 over every mapped endpoint (T31): each one but login and health answers 401 without a
 * token. The list comes from Spring MVC itself, so a new endpoint is covered without editing this test.
 */
@AutoConfigureMockMvc
class EndpointSecurityTest extends IntegrationTest {

    static final Set<String> PUBLIC = Set.of("POST /api/auth/login", "GET /api/health");
    static final Map<String, String> VARIABLES = Map.of("flowRef", "REQ-20260515-000944", "userId", "usr-1042", "id", UUID.randomUUID().toString(),
            "requestId", UUID.randomUUID().toString());

    @Autowired
    private MockMvc mvc;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    void everyEndpointButLoginAndHealthAnswers401WithoutAToken() throws Exception {
        List<String> checked = new ArrayList<>();
        List<String> open = new ArrayList<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                String path = pattern;
                for (var v : VARIABLES.entrySet()) {
                    path = path.replace("{" + v.getKey() + "}", v.getValue());
                }
                for (RequestMethod m : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    String key = m + " " + pattern;
                    if (PUBLIC.contains(key)) {
                        continue;
                    }
                    int status = mvc.perform(request(org.springframework.http.HttpMethod.valueOf(m.name()), path)
                            .contentType("application/json").content("{}")).andReturn().getResponse().getStatus();
                    checked.add(key);
                    if (status != 401) {
                        open.add(key + " -> " + status);
                    }
                }
            }
        }
        assertThat(open).isEmpty();
        assertThat(checked).contains("GET /api/anomalies", "PATCH /api/anomalies/{id}", "POST /api/traces/{flowRef}/ai-summary",
                "POST /api/assistant/conversations", "GET /api/stats/overview", "POST /api/admin/reassemble", "GET /api/audit", "GET /api/jobs/{id}");
    }
}
