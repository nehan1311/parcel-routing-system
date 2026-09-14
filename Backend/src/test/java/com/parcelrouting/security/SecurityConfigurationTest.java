package com.parcelrouting.security;

import com.parcelrouting.config.ConfigService;
import com.parcelrouting.parcel.Parcel;
import com.parcelrouting.parcel.ParcelEntity;
import com.parcelrouting.parcel.ParcelRepository;
import com.parcelrouting.parcel.ParcelStatus;
import com.parcelrouting.service.ParcelService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class SecurityConfigurationTest {

    private static final String OPERATOR_PASSWORD = "operator-test-only";
    private static final String APPROVER_PASSWORD = "approver-test-only";
    private static final String ADMIN_PASSWORD = "admin-test-only";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ParcelService parcelService;

    @MockBean
    private ConfigService configService;

    @MockBean
    private ParcelRepository parcelRepository;

    @Test
    void rejectsUnauthenticatedParcelSubmission() throws Exception {
        mockMvc.perform(post("/api/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isUnauthorized());

        verify(parcelService, never()).submit(any(Parcel.class));
    }

    @Test
    void allowsOperatorParcelSubmission() throws Exception {
        ParcelEntity responseParcel = routedParcel();
        when(parcelService.submit(any(Parcel.class))).thenReturn(responseParcel);

        mockMvc.perform(post("/api/parcels")
                        .with(basicAuthentication("operator", OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isCreated());

        verify(parcelService).submit(any(Parcel.class));
    }

    @Test
    void forbidsApproverParcelSubmission() throws Exception {
        mockMvc.perform(post("/api/parcels")
                        .with(basicAuthentication("approver", APPROVER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isForbidden());

        verify(parcelService, never()).submit(any(Parcel.class));
    }

    @Test
    void forbidsAdminParcelSubmission() throws Exception {
        mockMvc.perform(post("/api/parcels")
                        .with(basicAuthentication("admin", ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isForbidden());

        verify(parcelService, never()).submit(any(Parcel.class));
    }

    @Test
    void exposesHealthWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    private ParcelEntity routedParcel() {
        ParcelEntity parcelEntity = mock(ParcelEntity.class);
        when(parcelEntity.getId()).thenReturn(1L);
        when(parcelEntity.getStatus()).thenReturn(ParcelStatus.ROUTED);
        when(parcelEntity.getDepartment()).thenReturn("Regular");
        when(parcelEntity.getPredictedDepartment()).thenReturn("Regular");
        when(parcelEntity.getMatchedRuleId()).thenReturn("regular-department");
        when(parcelEntity.getRoutingConfigVersionId()).thenReturn(1L);
        return parcelEntity;
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

    private String validRequestJson() {
        return """
                {
                  "weightKg": 15.0,
                  "valueEur": 2000.0,
                  "destinationCountry": "NL",
                  "attributes": {
                    "fragile": true
                  }
                }
                """;
    }
}
