package com.flowops;

import com.flowops.worker.JobWorker;
import com.flowops.worker.OutboxDispatcher;
import com.flowops.worker.SlaScanner;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class PaginationApiTest extends AbstractIntegrationTest {

    // These contexts exercise commands/API reads, not scheduled delivery. Other cached
    // contexts share PostgreSQL, so polling here would race the manual failure drills.
    @MockitoBean
    JobWorker backgroundJobs;

    @MockitoBean
    OutboxDispatcher backgroundOutbox;

    @MockitoBean
    SlaScanner backgroundSla;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @ParameterizedTest
    @ValueSource(strings = { "users", "departments", "roles", "permissions", "workflows", "request-types", "requests",
            "requests/queue", "notifications", "notifications?unread=true" })
    void allPagedEndpointsUseOneBasedPageNumbers(String endpoint) throws Exception {
        var login = mvc
            .perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
            .andExpect(status().isOk())
            .andReturn();
        String bearer = "Bearer " + mapper.readTree(login.getResponse().getContentAsString()).get("token").asText();
        String url = "/api/v1/" + endpoint;
        mvc.perform(get(url).header("Authorization", bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.first").value(true));
        mvc.perform(get(url).param("page", "1").param("size", "1").header("Authorization", bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.first").value(true));
        mvc.perform(get(url).param("page", "2").param("size", "1").header("Authorization", bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(2))
            .andExpect(jsonPath("$.first").value(false));
        mvc.perform(get(url).param("page", "0").header("Authorization", bearer)).andExpect(status().isBadRequest());
        mvc.perform(get(url).param("page", "-1").header("Authorization", bearer)).andExpect(status().isBadRequest());
    }

}
