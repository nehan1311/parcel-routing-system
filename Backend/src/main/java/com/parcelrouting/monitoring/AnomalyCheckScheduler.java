package com.parcelrouting.monitoring;

import com.parcelrouting.parcel.ParcelEntity;
import com.parcelrouting.parcel.ParcelRepository;
import com.parcelrouting.parcel.ParcelStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@ConditionalOnBean(ParcelRepository.class)
public class AnomalyCheckScheduler {

    private static final List<ParcelStatus> DECISION_STATUSES = List.of(
            ParcelStatus.ROUTED, ParcelStatus.PENDING_APPROVAL
    );

    private final ParcelRepository parcelRepository;
    private final Logger logger;
    private final Clock clock;
    private final Duration recentWindow;
    private final Duration baselineWindow;
    private final double thresholdMultiplier;
    private final int minimumObservedCount;

    @Autowired
    public AnomalyCheckScheduler(
            ParcelRepository parcelRepository,
            @Value("${parcel.monitoring.anomaly.threshold-multiplier:2.0}") double thresholdMultiplier,
            @Value("${parcel.monitoring.anomaly.minimum-observed-count:3}") int minimumObservedCount,
            @Value("${parcel.monitoring.anomaly.recent-window-minutes:5}") long recentWindowMinutes,
            @Value("${parcel.monitoring.anomaly.baseline-window-minutes:55}") long baselineWindowMinutes
    ) {
        this(
                parcelRepository,
                LoggerFactory.getLogger(AnomalyCheckScheduler.class),
                Clock.systemUTC(),
                Duration.ofMinutes(recentWindowMinutes),
                Duration.ofMinutes(baselineWindowMinutes),
                thresholdMultiplier,
                minimumObservedCount
        );
    }

    AnomalyCheckScheduler(
            ParcelRepository parcelRepository,
            Logger logger,
            Clock clock,
            Duration recentWindow,
            Duration baselineWindow,
            double thresholdMultiplier,
            int minimumObservedCount
    ) {
        this.parcelRepository = parcelRepository;
        this.logger = logger;
        this.clock = clock;
        this.recentWindow = recentWindow;
        this.baselineWindow = baselineWindow;
        this.thresholdMultiplier = thresholdMultiplier;
        this.minimumObservedCount = minimumObservedCount;
    }

    @Scheduled(fixedDelayString = "${parcel.monitoring.anomaly.interval-ms:300000}")
    public void checkForUnusualRoutingPatterns() {
        try {
            inspectRecentDecisions();
        } catch (RuntimeException exception) {
            logger.error("routing_pattern_check_failed", exception);
        }
    }

    void inspectRecentDecisions() {
        validateConfiguration();
        Instant now = clock.instant();
        Instant recentStart = now.minus(recentWindow);
        Instant baselineStart = recentStart.minus(baselineWindow);
        List<ParcelEntity> decisions = parcelRepository.findByStatusInAndCreatedAtAfter(DECISION_STATUSES, baselineStart);

        Map<PatternKey, Long> recentCounts = count(decisions, recentStart, now);
        Map<PatternKey, Long> baselineCounts = count(decisions, baselineStart, recentStart);
        recentCounts.forEach((pattern, observedCount) -> {
            long baselineCount = baselineCounts.getOrDefault(pattern, 0L);
            double expectedCount = baselineCount * ((double) recentWindow.toMillis() / baselineWindow.toMillis());
            double alertThreshold = Math.max(1.0, expectedCount * thresholdMultiplier);
            if (observedCount >= minimumObservedCount && observedCount > alertThreshold) {
                logger.atWarn()
                        .addKeyValue("department", pattern.department())
                        .addKeyValue("routingConfigVersion", pattern.routingConfigVersionId())
                        .addKeyValue("observedCount", observedCount)
                        .addKeyValue("baselineCount", baselineCount)
                        .addKeyValue("expectedCount", expectedCount)
                        .addKeyValue("thresholdMultiplier", thresholdMultiplier)
                        .addKeyValue("recentWindowMinutes", recentWindow.toMinutes())
                        .addKeyValue("baselineWindowMinutes", baselineWindow.toMinutes())
                        .log("unusual_routing_pattern_detected");
            }
        });
    }

    private Map<PatternKey, Long> count(List<ParcelEntity> decisions, Instant fromInclusive, Instant toExclusive) {
        return decisions.stream()
                .filter(parcel -> !parcel.getCreatedAt().isBefore(fromInclusive))
                .filter(parcel -> parcel.getCreatedAt().isBefore(toExclusive))
                .filter(parcel -> parcel.getPredictedDepartment() != null)
                .collect(Collectors.groupingBy(
                        parcel -> new PatternKey(parcel.getPredictedDepartment(), parcel.getRoutingConfigVersionId()),
                        Collectors.counting()
                ));
    }

    private void validateConfiguration() {
        if (recentWindow.isZero() || recentWindow.isNegative()
                || baselineWindow.isZero() || baselineWindow.isNegative()
                || thresholdMultiplier <= 0
                || minimumObservedCount < 1) {
            throw new IllegalArgumentException("Routing anomaly monitoring configuration must use positive windows and thresholds");
        }
    }

    private record PatternKey(String department, Long routingConfigVersionId) {
    }
}
