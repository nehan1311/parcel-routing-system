package com.parcelrouting.api;

import com.parcelrouting.batch.BatchProcessor;
import com.parcelrouting.config.ConfigService;
import com.parcelrouting.config.ConfigVersionNotFoundException;
import com.parcelrouting.config.ConfigVersionStateException;
import com.parcelrouting.config.ConfigVersionStatus;
import com.parcelrouting.config.RoutingConfigVersion;
import com.parcelrouting.routing.DryRunSimulator;
import com.parcelrouting.service.ApprovalService;
import com.parcelrouting.service.ParcelService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "parcel.security.operator-password=operator-test-only",
        "parcel.security.approver-password=approver-test-only",
        "parcel.security.admin-password=admin-test-only"
})
@AutoConfigureMockMvc
class ConfigControllerTest {

    private static final String ADMIN_PASSWORD = "admin-test-only";
    private static final String OPERATOR_PASSWORD = "operator-test-only";
    private static final String APPROVER_PASSWORD = "approver-test-only";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ConfigService configService;

    @MockBean
    private ParcelService parcelService;

    @MockBean
    private ApprovalService approvalService;

    @MockBean
    private BatchProcessor batchProcessor;

    @Test
    void adminCreatesDraftSuccessfully() throws Exception {
        when(configService.createDraft(any(), eq("admin"))).thenReturn(draftVersion(2));

        mockMvc.perform(post("/api/config/drafts")
                        .with(basicAuthentication("admin", ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validConfigurationJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.configuration.insuranceThresholdEur").value(1000));

        verify(configService).createDraft(any(), eq("admin"));
    }

    @Test
    void operatorIsForbidden() throws Exception {
        mockMvc.perform(post("/api/config/drafts")
                        .with(basicAuthentication("operator", OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validConfigurationJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    void insuranceApproverIsForbidden() throws Exception {
        mockMvc.perform(post("/api/config/drafts")
                        .with(basicAuthentication("approver", APPROVER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validConfigurationJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminValidatesValidDraft() throws Exception {
        when(configService.validateDraft(2)).thenReturn(new ConfigService.DraftValidationResult(2, true, List.of()));

        mockMvc.perform(post("/api/config/drafts/2/validate")
                        .with(basicAuthentication("admin", ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void adminRunsDryRunByDelegatingToConfigService() throws Exception {
        when(configService.dryRun(2)).thenReturn(passingDryRun());

        mockMvc.perform(post("/api/config/drafts/2/dry-run")
                        .with(basicAuthentication("admin", ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCases").value(3))
                .andExpect(jsonPath("$.passedCases").value(3))
                .andExpect(jsonPath("$.failedCases").value(0));

        verify(configService).dryRun(2);
    }

    @Test
    void adminActivatesDraftAndPassesAuthenticatedUsernameToService() throws Exception {
        RoutingConfigVersion activated = new RoutingConfigVersion(
                2, ConfigVersionStatus.ACTIVE, "{}", "admin", Instant.now(), Instant.now(), 1L
        );
        when(configService.activate(2L, "admin")).thenReturn(activated);

        mockMvc.perform(post("/api/config/drafts/2/activate")
                        .with(basicAuthentication("admin", ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(configService).activate(2L, "admin");
    }

    @Test
    void dryRunAndActivationReturnNotFoundForMissingDraft() throws Exception {
        when(configService.dryRun(99)).thenThrow(new ConfigVersionNotFoundException(99));
        when(configService.activate(99L, "admin")).thenThrow(new ConfigVersionNotFoundException(99));

        mockMvc.perform(post("/api/config/drafts/99/dry-run")
                        .with(basicAuthentication("admin", ADMIN_PASSWORD)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/config/drafts/99/activate")
                        .with(basicAuthentication("admin", ADMIN_PASSWORD)))
                .andExpect(status().isNotFound());
    }

    @Test
    void dryRunAndActivationReturnConflictForNonDraftVersion() throws Exception {
        when(configService.dryRun(1)).thenThrow(new ConfigVersionStateException(1));
        when(configService.activate(1L, "admin")).thenThrow(new ConfigVersionStateException(1));

        mockMvc.perform(post("/api/config/drafts/1/dry-run")
                        .with(basicAuthentication("admin", ADMIN_PASSWORD)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/config/drafts/1/activate")
                        .with(basicAuthentication("admin", ADMIN_PASSWORD)))
                .andExpect(status().isConflict());
    }

    @Test
    void activationWithoutSuccessfulDryRunReturnsConflict() throws Exception {
        when(configService.activate(2L, "admin"))
                .thenThrow(new com.parcelrouting.config.ConfigVersionActivationException(
                        2, "a successful dry-run for the current configuration is required"
                ));

        mockMvc.perform(post("/api/config/drafts/2/activate")
                        .with(basicAuthentication("admin", ADMIN_PASSWORD)))
                .andExpect(status().isConflict());

        verify(configService).activate(2L, "admin");
    }

    @Test
    void nonAdminsAndUnauthenticatedUsersCannotDryRunOrActivate() throws Exception {
        mockMvc.perform(post("/api/config/drafts/2/dry-run")
                        .with(basicAuthentication("operator", OPERATOR_PASSWORD)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/config/drafts/2/activate")
                        .with(basicAuthentication("approver", APPROVER_PASSWORD)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/config/drafts/2/dry-run"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/config/drafts/2/activate"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(configService);
    }

    @Test
    void invalidDraftReturnsValidationErrors() throws Exception {
        when(configService.createDraft(any(), eq("admin")))
                .thenThrow(new IllegalArgumentException("Duplicate routing rule priority: 1"));

        mockMvc.perform(post("/api/config/drafts")
                        .with(basicAuthentication("admin", ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validConfigurationJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Duplicate routing rule priority: 1"));
    }

    @Test
    void missingDraftReturnsNotFound() throws Exception {
        when(configService.validateDraft(99)).thenThrow(new ConfigVersionNotFoundException(99));

        mockMvc.perform(post("/api/config/drafts/99/validate")
                        .with(basicAuthentication("admin", ADMIN_PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Routing configuration version not found: 99"));
    }

    @Test
    void nonDraftVersionReturnsConflict() throws Exception {
        when(configService.validateDraft(1)).thenThrow(new ConfigVersionStateException(1));

        mockMvc.perform(post("/api/config/drafts/1/validate")
                        .with(basicAuthentication("admin", ADMIN_PASSWORD)))
                .andExpect(status().isConflict());
    }

    @Test
    void activeConfigurationRemainsUnchanged() throws Exception {
        RoutingConfigVersion active = new RoutingConfigVersion(
                1, ConfigVersionStatus.ACTIVE, "{}", "system", Instant.now(), Instant.now(), null
        );
        when(configService.createDraft(any(), eq("admin"))).thenReturn(draftVersion(2));

        mockMvc.perform(post("/api/config/drafts")
                        .with(basicAuthentication("admin", ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validConfigurationJson()))
                .andExpect(status().isOk());

        org.junit.jupiter.api.Assertions.assertEquals(ConfigVersionStatus.ACTIVE, active.getStatus());
    }

    @Test
    void adminGetsConfigurationHistoryNewestFirst() throws Exception {
        when(configService.history()).thenReturn(List.of(
                new ConfigService.ConfigHistoryEntry(
                        3, ConfigVersionStatus.ACTIVE, "admin", Instant.now(), "admin", Instant.now(), 2L
                ),
                new ConfigService.ConfigHistoryEntry(
                        2, ConfigVersionStatus.ARCHIVED, "admin", Instant.now(), "admin", Instant.now(), 1L
                )
        ));

        mockMvc.perform(get("/api/config/history")
                        .with(basicAuthentication("admin", ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(3))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].predecessorVersionId").value(2))
                .andExpect(jsonPath("$[1].version").value(2));
    }

    @Test
    void nonAdminCannotAccessHistoryOrRollback() throws Exception {
        mockMvc.perform(get("/api/config/history")
                        .with(basicAuthentication("operator", OPERATOR_PASSWORD)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/config/2/rollback")
                        .with(basicAuthentication("approver", APPROVER_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminRollsBackThroughConfigServiceAndMissingTargetReturnsNotFound() throws Exception {
        RoutingConfigVersion activated = new RoutingConfigVersion(
                3, ConfigVersionStatus.ACTIVE, "{}", "admin", Instant.now(), Instant.now(), 2L
        );
        when(configService.rollback(2L, "admin")).thenReturn(activated);

        mockMvc.perform(post("/api/config/2/rollback")
                        .with(basicAuthentication("admin", ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        verify(configService).rollback(2L, "admin");

        when(configService.rollback(99L, "admin")).thenThrow(new ConfigVersionNotFoundException(99));
        mockMvc.perform(post("/api/config/99/rollback")
                        .with(basicAuthentication("admin", ADMIN_PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Routing configuration version not found: 99"));
    }

    private RoutingConfigVersion draftVersion(int version) {
        return new RoutingConfigVersion(version, ConfigVersionStatus.DRAFT, "{}", "admin", Instant.now(), null, 1L);
    }

    private DryRunSimulator.DryRunResult passingDryRun() {
        return new DryRunSimulator.DryRunResult(3, 3, 0, List.of());
    }

    private RequestPostProcessor basicAuthentication(String username, String password) {
        return request -> {
            String credentials = username + ":" + password;
            String encodedCredentials = Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            request.addHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedCredentials);
            return request;
        };
    }

    private String validConfigurationJson() {
        return """
                {
                  "insuranceThresholdEur": 1000,
                  "rules": [
                    {
                      "id": "mail",
                      "priority": 1,
                      "condition": {"field": "weight_kg", "operator": "LTE", "value": 1},
                      "department": "Mail"
                    }
                  ]
                }
                """;
    }
}
