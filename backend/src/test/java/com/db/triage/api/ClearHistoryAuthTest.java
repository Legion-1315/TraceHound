package com.db.triage.api;

import com.db.triage.live.LiveChaosService;
import com.db.triage.telemetry.TelemetryRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/chaos/admin/clear-history wipes evidence, so it must not be callable by any visitor. */
class ClearHistoryAuthTest {

    private static final String URL = "/api/chaos/admin/clear-history";

    @Nested
    @SpringBootTest(properties = {
            "triage.admin-token=",
            "triage.memory-file=build/test-data/auth-incident-memory.json",
            "triage.history-file=build/test-data/auth-incident-history.json"})
    @AutoConfigureMockMvc
    class WithoutAToken {
        @Autowired
        MockMvc mvc;
        @MockitoBean
        LiveChaosService liveChaos;
        @MockitoBean
        TelemetryRouter telemetry;

        @Test
        void theEndpointDoesNotExist() throws Exception {
            when(telemetry.isLive()).thenReturn(true);
            mvc.perform(post(URL)).andExpect(status().isNotFound());
            mvc.perform(post(URL).header("X-Admin-Token", "anything")).andExpect(status().isNotFound());
            verify(liveChaos, never()).clearHistoryAll();
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "triage.admin-token=s3cret-test-token",
            "triage.memory-file=build/test-data/auth-incident-memory.json",
            "triage.history-file=build/test-data/auth-incident-history.json"})
    @AutoConfigureMockMvc
    class WithAToken {
        @Autowired
        MockMvc mvc;
        @MockitoBean
        LiveChaosService liveChaos;
        @MockitoBean
        TelemetryRouter telemetry;

        @BeforeEach
        void liveEstate() {
            when(telemetry.isLive()).thenReturn(true);
            when(liveChaos.clearHistoryAll()).thenReturn(Map.of());
        }

        @Test
        void missingOrWrongTokenIsRejected() throws Exception {
            mvc.perform(post(URL)).andExpect(status().isUnauthorized());
            mvc.perform(post(URL).header("X-Admin-Token", "wrong")).andExpect(status().isUnauthorized());
            mvc.perform(post(URL).header("X-Admin-Token", "s3cret-test-token-but-longer"))
                    .andExpect(status().isUnauthorized());
            verify(liveChaos, never()).clearHistoryAll();
        }

        @Test
        void theRightTokenClearsHistory() throws Exception {
            mvc.perform(post(URL).header("X-Admin-Token", "s3cret-test-token")).andExpect(status().isOk());
            verify(liveChaos).clearHistoryAll();
        }
    }
}
