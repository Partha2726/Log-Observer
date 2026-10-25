package com.ielop.platform.common.filter;

import com.ielop.platform.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequestIdFilterTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void generatesRequestIdWhenNotProvidedInHeader() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER));
    }

    @Test
    void echoesProvidedValidRequestId() throws Exception {
        String customRequestId = "test-req-123456";
        mockMvc.perform(get("/api/health")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, customRequestId))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, customRequestId));
    }

    @Test
    void echoesProvidedCorrelationIdIfRequestIdMissing() throws Exception {
        String customCorrelationId = "corr-abc-789";
        mockMvc.perform(get("/api/health")
                        .header(RequestIdFilter.CORRELATION_ID_HEADER, customCorrelationId))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, customCorrelationId));
    }
}
