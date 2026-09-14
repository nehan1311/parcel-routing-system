package com.parcelrouting.monitoring;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.parcelrouting.parcel.ParcelEntity;
import com.parcelrouting.parcel.ParcelRepository;
import com.parcelrouting.parcel.ParcelStatus;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnomalyCheckSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");

    @Test
    void normalDistributionProducesNoAnomaly() {
        ParcelRepository repository = mock(ParcelRepository.class);
        List<ParcelEntity> distribution = decisions("Heavy", 1L, 3, 33);
        when(repository.findByStatusInAndCreatedAtAfter(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(distribution);

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            scheduler(repository, 2.0).inspectRecentDecisions();
            assertFalse(hasMessage(appender, "unusual_routing_pattern_detected"));
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void unusualDistributionProducesActionableAnomalyWarning() {
        ParcelRepository repository = mock(ParcelRepository.class);
        List<ParcelEntity> distribution = decisions("Heavy", 7L, 3, 0);
        when(repository.findByStatusInAndCreatedAtAfter(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(distribution);

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            scheduler(repository, 2.0).inspectRecentDecisions();
            ILoggingEvent event = eventFor(appender, "unusual_routing_pattern_detected");
            assertTrue(hasKeyValue(event, "department", "Heavy"));
            assertTrue(hasKeyValue(event, "routingConfigVersion", "7"));
            assertTrue(hasKeyValue(event, "observedCount", "3"));
            assertTrue(hasKeyValue(event, "baselineCount", "0"));
            assertFalse(event.getFormattedMessage().contains("weight"));
            assertFalse(event.getFormattedMessage().contains("value"));
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void thresholdConfigurationIsRespected() {
        ParcelRepository repository = mock(ParcelRepository.class);
        List<ParcelEntity> distribution = decisions("Heavy", 1L, 3, 20);
        when(repository.findByStatusInAndCreatedAtAfter(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(distribution);

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            scheduler(repository, 3.0).inspectRecentDecisions();
            assertFalse(hasMessage(appender, "unusual_routing_pattern_detected"));
            scheduler(repository, 1.0).inspectRecentDecisions();
            assertTrue(hasMessage(appender, "unusual_routing_pattern_detected"));
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void schedulerFailureIsContained() {
        ParcelRepository repository = mock(ParcelRepository.class);
        when(repository.findByStatusInAndCreatedAtAfter(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("database temporarily unavailable"));

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            assertDoesNotThrow(() -> scheduler(repository, 2.0).checkForUnusualRoutingPatterns());
            assertTrue(hasMessage(appender, "routing_pattern_check_failed"));
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void monitorDoesNotModifyRoutingDecisions() {
        ParcelRepository repository = mock(ParcelRepository.class);
        ParcelEntity parcel = parcel("Heavy", 4L, NOW.minusSeconds(60));
        List<ParcelEntity> decisions = List.of(
                parcel,
                parcel("Heavy", 4L, NOW.minusSeconds(120)),
                parcel("Heavy", 4L, NOW.minusSeconds(180))
        );
        when(repository.findByStatusInAndCreatedAtAfter(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(decisions);

        scheduler(repository, 2.0).inspectRecentDecisions();

        verify(parcel, never()).setStatus(org.mockito.ArgumentMatchers.any());
        verify(parcel, never()).setDepartment(org.mockito.ArgumentMatchers.any());
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private AnomalyCheckScheduler scheduler(ParcelRepository repository, double multiplier) {
        return new AnomalyCheckScheduler(
                repository,
                (Logger) LoggerFactory.getLogger(AnomalyCheckScheduler.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5),
                Duration.ofMinutes(55),
                multiplier,
                3
        );
    }

    private List<ParcelEntity> decisions(String department, Long configVersion, int recent, int baseline) {
        List<ParcelEntity> decisions = new ArrayList<>();
        for (int index = 0; index < recent; index++) decisions.add(parcel(department, configVersion, NOW.minusSeconds(60L * (index + 1))));
        for (int index = 0; index < baseline; index++) decisions.add(parcel(department, configVersion, NOW.minus(Duration.ofMinutes(6 + index))));
        return decisions;
    }

    private ParcelEntity parcel(String department, Long configVersion, Instant createdAt) {
        ParcelEntity parcel = mock(ParcelEntity.class);
        when(parcel.getStatus()).thenReturn(ParcelStatus.ROUTED);
        when(parcel.getPredictedDepartment()).thenReturn(department);
        when(parcel.getRoutingConfigVersionId()).thenReturn(configVersion);
        when(parcel.getCreatedAt()).thenReturn(createdAt);
        return parcel;
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(AnomalyCheckScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(AnomalyCheckScheduler.class)).detachAppender(appender);
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
