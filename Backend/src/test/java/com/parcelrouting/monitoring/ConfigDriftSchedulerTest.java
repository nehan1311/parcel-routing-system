package com.parcelrouting.monitoring;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parcelrouting.config.ConfigVersionStatus;
import com.parcelrouting.config.RoutingConfigVersion;
import com.parcelrouting.config.RoutingConfigVersionRepository;
import com.parcelrouting.parcel.ParcelEntity;
import com.parcelrouting.parcel.ParcelRepository;
import com.parcelrouting.routing.BoundarySimulator;
import com.parcelrouting.routing.DryRunSimulator;
import com.parcelrouting.routing.RoutingEngine;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigDriftSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");

    @Test
    void firstActivationIsSkipped() {
        ParcelRepository parcels = mock(ParcelRepository.class);
        RoutingConfigVersionRepository versions = mock(RoutingConfigVersionRepository.class);
        RoutingConfigVersion active = version(2, 10L, null, NOW.minus(Duration.ofDays(1)), rules("Mail", "LTE", 1000));
        when(versions.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of(active));

        assertDoesNotThrow(() -> scheduler(parcels, versions).inspectConfigDrift());
        verify(parcels, never()).findByRoutingConfigVersionIdAndCreatedAtGreaterThanEqual(any(), any());
    }

    @Test
    void versionBeyondMonitoringWindowIsSkipped() {
        ParcelRepository parcels = mock(ParcelRepository.class);
        RoutingConfigVersionRepository versions = mock(RoutingConfigVersionRepository.class);
        RoutingConfigVersion active = version(2, 10L, 1L, NOW.minus(Duration.ofDays(8)), rules("Mail", "LTE", 1000));
        when(versions.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of(active));

        scheduler(parcels, versions).inspectConfigDrift();

        verify(versions, never()).findById(1L);
        verify(parcels, never()).findByRoutingConfigVersionIdAndCreatedAtGreaterThanEqual(any(), any());
    }

    @Test
    void insufficientSamplesAreSkipped() {
        ParcelRepository parcels = mock(ParcelRepository.class);
        RoutingConfigVersionRepository versions = mock(RoutingConfigVersionRepository.class);
        Instant activatedAt = NOW.minus(Duration.ofDays(1));
        RoutingConfigVersion active = version(2, 10L, 1L, activatedAt, rules("Mail", "LTE", 1000));
        RoutingConfigVersion previous = version(1, 1L, null, NOW.minus(Duration.ofDays(2)), rules("Mail", "LTE", 1000));
        when(versions.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of(active));
        when(versions.findById(1L)).thenReturn(Optional.of(previous));
        List<ParcelEntity> samples = List.of(parcel("Heavy", false));
        when(parcels.findByRoutingConfigVersionIdAndCreatedAtGreaterThanEqual(10L, activatedAt))
                .thenReturn(samples);

        scheduler(parcels, versions).inspectConfigDrift();

        verify(parcels).findByRoutingConfigVersionIdAndCreatedAtGreaterThanEqual(10L, activatedAt);
    }

    @Test
    void observedDriftBelowThresholdDoesNotAlert() {
        ParcelRepository parcels = mock(ParcelRepository.class);
        RoutingConfigVersionRepository versions = mock(RoutingConfigVersionRepository.class);
        Instant activatedAt = NOW.minus(Duration.ofDays(1));
        RoutingConfigVersion active = version(2, 10L, 1L, activatedAt, rules("Heavy", "GT", 0));
        RoutingConfigVersion previous = version(1, 1L, null, NOW.minus(Duration.ofDays(2)), rules("Mail", "LTE", 1000));
        when(versions.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of(active));
        when(versions.findById(1L)).thenReturn(Optional.of(previous));
        List<ParcelEntity> samples = List.of(parcel("Mail", false), parcel("Mail", false));
        when(parcels.findByRoutingConfigVersionIdAndCreatedAtGreaterThanEqual(10L, activatedAt))
                .thenReturn(samples);

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            scheduler(parcels, versions).inspectConfigDrift();
            assertFalse(hasMessage(appender, "post_activation_drift_detected"));
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void observedDriftAboveThresholdLogsStructuredAlert() {
        ParcelRepository parcels = mock(ParcelRepository.class);
        RoutingConfigVersionRepository versions = mock(RoutingConfigVersionRepository.class);
        Instant activatedAt = NOW.minus(Duration.ofDays(1));
        RoutingConfigVersion active = version(2, 10L, 1L, activatedAt, rules("Mail", "LTE", 1000));
        RoutingConfigVersion previous = version(1, 1L, null, NOW.minus(Duration.ofDays(2)), rules("Mail", "LTE", 1000));
        when(versions.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of(active));
        when(versions.findById(1L)).thenReturn(Optional.of(previous));
        List<ParcelEntity> samples = List.of(
                        parcel("Heavy", false), parcel("Heavy", false), parcel("Heavy", false),
                        parcel("Heavy", false), parcel("Heavy", false)
                );
        when(parcels.findByRoutingConfigVersionIdAndCreatedAtGreaterThanEqual(10L, activatedAt))
                .thenReturn(samples);

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            scheduler(parcels, versions).inspectConfigDrift();
            ILoggingEvent event = eventFor(appender, "post_activation_drift_detected");
            assertTrue(hasKeyValue(event, "routingConfigVersionId", "10"));
            assertTrue(hasKeyValue(event, "routingConfigVersion", "2"));
            assertTrue(hasKeyValue(event, "predictedPercent", "0.0"));
            assertTrue(hasKeyValue(event, "observedPercent", "100.0"));
            assertTrue(hasKeyValue(event, "sampleCount", "5"));
            assertTrue(hasKeyValue(event, "basedOnVersion", "1"));
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void schedulerPerformsNoWrites() {
        ParcelRepository parcels = mock(ParcelRepository.class);
        RoutingConfigVersionRepository versions = mock(RoutingConfigVersionRepository.class);
        RoutingConfigVersion active = version(2, 10L, null, NOW.minus(Duration.ofDays(1)), rules("Mail", "LTE", 1000));
        when(versions.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of(active));

        scheduler(parcels, versions).inspectConfigDrift();

        verify(parcels, never()).save(any());
        verify(parcels, never()).saveAll(any());
        verify(versions, never()).save(any());
        verify(versions, never()).saveAll(any());
        verify(versions, never()).flush();
    }

    private ConfigDriftScheduler scheduler(ParcelRepository parcels, RoutingConfigVersionRepository versions) {
        return new ConfigDriftScheduler(
                parcels,
                versions,
                new DryRunSimulator(new RoutingEngine(), new ObjectMapper()),
                new BoundarySimulator(0, 2, 1, 0, 2, 1),
                new RoutingEngine(),
                new ObjectMapper(),
                (Logger) LoggerFactory.getLogger(ConfigDriftScheduler.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofDays(7),
                2,
                2.0
        );
    }

    private RoutingConfigVersion version(int number, Long id, Long basedOn, Instant activatedAt, String rulesJson) {
        RoutingConfigVersion version = mock(RoutingConfigVersion.class);
        when(version.getVersion()).thenReturn(number);
        when(version.getId()).thenReturn(id);
        when(version.getBasedOnVersionId()).thenReturn(basedOn);
        when(version.getActivatedAt()).thenReturn(activatedAt);
        when(version.getRulesJson()).thenReturn(rulesJson);
        return version;
    }

    private ParcelEntity parcel(String department, boolean insurance) {
        ParcelEntity parcel = mock(ParcelEntity.class);
        when(parcel.getWeightKg()).thenReturn(1.0);
        when(parcel.getValueEur()).thenReturn(100.0);
        when(parcel.getDestinationCountry()).thenReturn("DE");
        when(parcel.getAttributesJson()).thenReturn("{}");
        when(parcel.getDepartment()).thenReturn(department);
        when(parcel.getInsuranceRequired()).thenReturn(insurance);
        return parcel;
    }

    private String rules(String department, String operator, int value) {
        return "{\"insuranceThresholdEur\":1000,\"rules\":["
                + "{\"id\":\"rule\",\"priority\":1,\"condition\":{\"field\":\"weight_kg\",\"operator\":\""
                + operator + "\",\"value\":" + value + "},\"department\":\"" + department + "\"}]}";
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(ConfigDriftScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(ConfigDriftScheduler.class)).detachAppender(appender);
    }

    private boolean hasMessage(ListAppender<ILoggingEvent> appender, String message) {
        return appender.list.stream().anyMatch(event -> event.getMessage().equals(message));
    }

    private ILoggingEvent eventFor(ListAppender<ILoggingEvent> appender, String message) {
        return appender.list.stream().filter(event -> event.getMessage().equals(message)).findFirst().orElseThrow();
    }

    private boolean hasKeyValue(ILoggingEvent event, String key, String value) {
        return event.getKeyValuePairs().stream()
                .anyMatch(pair -> pair.key.equals(key) && String.valueOf(pair.value).equals(value));
    }
}
