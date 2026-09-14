package com.parcelrouting.api;

import com.parcelrouting.config.ConfigService;
import com.parcelrouting.parcel.ParcelEntity;
import com.parcelrouting.parcel.ParcelStatus;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class ApprovalControllerTest {

    private static final String OPERATOR_PASSWORD = "operator-test-only";
    private static final String APPROVER_PASSWORD = "approver-test-only";
    private static final String ADMIN_PASSWORD = "admin-test-only";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ApprovalService approvalService;

    @MockBean
    private ParcelService parcelService;

    @MockBean
    private ConfigService configService;

    @Test
    void approverCanAccessPendingApprovalParcels() throws Exception {
        ParcelEntity pendingParcel = parcel(ParcelStatus.PENDING_APPROVAL);
        when(approvalService.findPendingApproval()).thenReturn(List.of(pendingParcel));

        mockMvc.perform(get("/api/parcels/pending-approval")
                        .with(basicAuthentication("approver", APPROVER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING_APPROVAL"));

        verify(approvalService).findPendingApproval();
    }

    @Test
    void operatorIsForbiddenFromApprovalEndpoints() throws Exception {
        mockMvc.perform(get("/api/parcels/pending-approval")
                        .with(basicAuthentication("operator", OPERATOR_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminIsForbiddenFromApprovalEndpoints() throws Exception {
        mockMvc.perform(get("/api/parcels/pending-approval")
                        .with(basicAuthentication("admin", ADMIN_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void approverCanApprovePendingParcel() throws Exception {
        ParcelEntity approvedParcel = parcel(ParcelStatus.ROUTED);
        when(approvalService.approve(12L, "approver")).thenReturn(approvedParcel);

        mockMvc.perform(post("/api/parcels/12/approve")
                        .with(basicAuthentication("approver", APPROVER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROUTED"));
    }

    @Test
    void passesAuthenticatedUsernameToApprovalService() throws Exception {
        ParcelEntity approvedParcel = parcel(ParcelStatus.ROUTED);
        when(approvalService.approve(13L, "approver")).thenReturn(approvedParcel);

        mockMvc.perform(post("/api/parcels/13/approve")
                        .with(basicAuthentication("approver", APPROVER_PASSWORD)))
                .andExpect(status().isOk());

        verify(approvalService).approve(13L, "approver");
    }

    @Test
    void returnsNotFoundForMissingParcel() throws Exception {
        when(approvalService.approve(14L, "approver"))
                .thenThrow(new NoSuchElementException("Parcel not found: 14"));

        mockMvc.perform(post("/api/parcels/14/approve")
                        .with(basicAuthentication("approver", APPROVER_PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Parcel not found: 14"));
    }

    @Test
    void returnsConflictForNonPendingParcel() throws Exception {
        when(approvalService.approve(15L, "approver"))
                .thenThrow(new ApprovalService.ApprovalNotPendingException());

        mockMvc.perform(post("/api/parcels/15/approve")
                        .with(basicAuthentication("approver", APPROVER_PASSWORD)))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsUnauthenticatedApprovalRequests() throws Exception {
        mockMvc.perform(get("/api/parcels/pending-approval"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/parcels/16/approve"))
                .andExpect(status().isUnauthorized());
    }

    private ParcelEntity parcel(ParcelStatus status) {
        ParcelEntity parcel = mock(ParcelEntity.class);
        when(parcel.getStatus()).thenReturn(status);
        when(parcel.getCreatedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        return parcel;
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
}
