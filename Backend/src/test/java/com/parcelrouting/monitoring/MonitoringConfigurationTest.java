package com.parcelrouting.monitoring;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.parcelrouting.batch.BatchProcessor;
import com.parcelrouting.config.ConfigService;
import com.parcelrouting.parcel.ParcelEntity;
import com.parcelrouting.parcel.ParcelStatus;
import com.parcelrouting.service.ApprovalService;
import com.parcelrouting.service.ParcelService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
class MonitoringConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ParcelService parcelService;

    @MockBean
    private ConfigService configService;

    @MockBean
    private ApprovalService approvalService;

    @MockBean
    private BatchProcessor batchProcessor;

    @Test
    void preservesSuppliedCorrelationIdAndClearsMdcAfterRequest() throws Exception {
        ParcelEntity response = routedParcel();
        when(parcelService.submit(any())).thenReturn(response);
        String correlationId = "trace-123";

        mockMvc.perform(post("/api/parcels")
                        .with(operatorAuthentication())
                        .header(CorrelationIdFilter.HEADER_NAME, correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validParcelJson()))
                .andExpect(status().isCreated())
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, correlationId));

        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void generatesCorrelationIdForApiResponses() throws Exception {
        ParcelEntity response = routedParcel();
        when(parcelService.submit(any())).thenReturn(response);

        String correlationId = mockMvc.perform(post("/api/parcels")
                        .with(operatorAuthentication())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validParcelJson()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader(CorrelationIdFilter.HEADER_NAME);

        assertTrue(correlationId != null && UUID.fromString(correlationId) != null);
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void unauthenticatedApiResponsesAlsoReceiveCorrelationId() throws Exception {
        mockMvc.perform(post("/api/parcels")
                        .header(CorrelationIdFilter.HEADER_NAME, "trace-unauthenticated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validParcelJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, "trace-unauthenticated"));
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void unexpectedFailuresRemainSafeAndRetainCorrelationId() throws Exception {
        when(parcelService.submit(any())).thenThrow(new IllegalStateException("sensitive-request-value"));

        mockMvc.perform(post("/api/parcels")
                        .with(operatorAuthentication())
                        .header(CorrelationIdFilter.HEADER_NAME, "trace-error")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validParcelJson()))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, "trace-error"))
                .andExpect(jsonPath("$.message").value("Parcel submission could not be completed"));
    }

    @Test
    void routingDecisionLogContainsOnlyRequiredDecisionFields() {
        Logger logger = (Logger) LoggerFactory.getLogger(RoutingDecisionLogger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.put(CorrelationIdFilter.MDC_KEY, "trace-log");
        try {
            ParcelEntity parcel = mock(ParcelEntity.class);
            when(parcel.getId()).thenReturn(42L);
            when(parcel.getStatus()).thenReturn(ParcelStatus.PENDING_APPROVAL);
            when(parcel.getPredictedDepartment()).thenReturn("Heavy");
            when(parcel.getMatchedRuleId()).thenReturn("heavy-department");
            when(parcel.getRoutingConfigVersionId()).thenReturn(7L);

            new RoutingDecisionLogger().logCompletedDecision(parcel);

            ILoggingEvent event = appender.list.getFirst();
            assertTrue(hasKeyValue(event, "correlationId", "trace-log"));
            assertTrue(hasKeyValue(event, "parcelId", "42"));
            assertTrue(hasKeyValue(event, "routingStatus", "PENDING_APPROVAL"));
            assertTrue(hasKeyValue(event, "predictedDepartment", "Heavy"));
            assertTrue(hasKeyValue(event, "matchedRuleId", "heavy-department"));
            assertTrue(hasKeyValue(event, "routingConfigVersion", "7"));
            assertTrue(hasKeyValue(event, "insuranceRequired", "true"));
            assertFalse(event.getKeyValuePairs().toString().contains("sensitive-request-value"));
        } finally {
            MDC.clear();
            logger.detachAppender(appender);
        }
    }

    private ParcelEntity routedParcel() {
        ParcelEntity parcel = mock(ParcelEntity.class);
        when(parcel.getId()).thenReturn(1L);
        when(parcel.getStatus()).thenReturn(ParcelStatus.ROUTED);
        when(parcel.getDepartment()).thenReturn("Regular");
        when(parcel.getPredictedDepartment()).thenReturn("Regular");
        when(parcel.getMatchedRuleId()).thenReturn("regular-department");
        when(parcel.getRoutingConfigVersionId()).thenReturn(1L);
        return parcel;
    }

    private boolean hasKeyValue(ILoggingEvent event, String key, String value) {
        return event.getKeyValuePairs().stream()
                .anyMatch(pair -> pair.key.equals(key) && String.valueOf(pair.value).equals(value));
    }

    private RequestPostProcessor operatorAuthentication() {
        return request -> {
            String encoded = Base64.getEncoder().encodeToString(
                    "operator:operator-test-only".getBytes(StandardCharsets.UTF_8)
            );
            request.addHeader(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
            return request;
        };
    }

    private String validParcelJson() {
        return """
                {"weightKg": 15, "valueEur": 2000, "destinationCountry": "DE", "attributes": {}}
                """;
    }
}
