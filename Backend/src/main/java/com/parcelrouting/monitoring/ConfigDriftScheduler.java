package com.parcelrouting.monitoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parcelrouting.config.ConfigVersionStatus;
import com.parcelrouting.config.RoutingConfigVersion;
import com.parcelrouting.config.RoutingConfigVersionRepository;
import com.parcelrouting.parcel.ParcelEntity;
import com.parcelrouting.parcel.ParcelRepository;
import com.parcelrouting.routing.BoundarySimulator;
import com.parcelrouting.routing.DryRunSimulator;
import com.parcelrouting.routing.RoutingConfig;
import com.parcelrouting.routing.RoutingDecision;
import com.parcelrouting.routing.RoutingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
@ConditionalOnBean({ParcelRepository.class, RoutingConfigVersionRepository.class})
public class ConfigDriftScheduler {

    private final ParcelRepository parcelRepository;
    private final RoutingConfigVersionRepository configVersionRepository;
    private final DryRunSimulator dryRunSimulator;
    private final BoundarySimulator boundarySimulator;
    private final RoutingEngine routingEngine;
    private final ObjectMapper objectMapper;
    private final Logger logger;
    private final Clock clock;
    private final Duration monitoringWindow;
    private final int minimumSampleCount;
    private final double driftMultiplier;

    @Autowired
    public ConfigDriftScheduler(
            ParcelRepository parcelRepository,
            RoutingConfigVersionRepository configVersionRepository,
            DryRunSimulator dryRunSimulator,
            ObjectMapper objectMapper,
            @Value("${parcel.monitoring.config-drift.window-days:7}") long monitoringWindowDays,
            @Value("${parcel.monitoring.config-drift.minimum-sample-count:5}") int minimumSampleCount,
            @Value("${parcel.monitoring.config-drift.drift-multiplier:2.0}") double driftMultiplier
    ) {
        this(
                parcelRepository,
                configVersionRepository,
                dryRunSimulator,
                new BoundarySimulator(0, 50, 0.5, 0, 5_000, 25),
                new RoutingEngine(),
                objectMapper,
                LoggerFactory.getLogger(ConfigDriftScheduler.class),
                Clock.systemUTC(),
                Duration.ofDays(monitoringWindowDays),
                minimumSampleCount,
                driftMultiplier
        );
    }

    ConfigDriftScheduler(
            ParcelRepository parcelRepository,
            RoutingConfigVersionRepository configVersionRepository,
            DryRunSimulator dryRunSimulator,
            BoundarySimulator boundarySimulator,
            RoutingEngine routingEngine,
            ObjectMapper objectMapper,
            Logger logger,
            Clock clock,
            Duration monitoringWindow,
            int minimumSampleCount,
            double driftMultiplier
    ) {
        this.parcelRepository = parcelRepository;
        this.configVersionRepository = configVersionRepository;
        this.dryRunSimulator = dryRunSimulator;
        this.boundarySimulator = boundarySimulator;
        this.routingEngine = routingEngine;
        this.objectMapper = objectMapper;
        this.logger = logger;
        this.clock = clock;
        this.monitoringWindow = monitoringWindow;
        this.minimumSampleCount = minimumSampleCount;
        this.driftMultiplier = driftMultiplier;
    }

    @Scheduled(fixedDelayString = "${parcel.monitoring.config-drift.interval-ms:300000}")
    public void checkForConfigDrift() {
        try {
            inspectConfigDrift();
        } catch (RuntimeException exception) {
            logger.error("config_drift_check_failed", exception);
        }
    }

    void inspectConfigDrift() {
        validateConfiguration();
        RoutingConfigVersion active = configVersionRepository.findByStatus(ConfigVersionStatus.ACTIVE)
                .stream()
                .findFirst()
                .orElse(null);
        if (active == null || active.getBasedOnVersionId() == null || active.getActivatedAt() == null) {
            return;
        }

        Instant now = clock.instant();
        if (now.isAfter(active.getActivatedAt().plus(monitoringWindow))) {
            return;
        }

        RoutingConfigVersion previous = configVersionRepository.findById(active.getBasedOnVersionId()).orElse(null);
        if (previous == null) {
            return;
        }

        RoutingConfig previousConfiguration = parseRoutingConfig(previous.getRulesJson());
        RoutingConfig activeConfiguration = parseRoutingConfig(active.getRulesJson());
        BoundarySimulator.BoundarySimulationResult prediction =
                boundarySimulator.simulate(previousConfiguration, activeConfiguration);
        if (prediction.totalSimulated() == 0) {
            return;
        }

        List<ParcelEntity> parcels = parcelRepository.findByRoutingConfigVersionIdAndCreatedAtGreaterThanEqual(
                active.getId(), active.getActivatedAt());
        if (parcels.size() < minimumSampleCount) {
            return;
        }

        int departmentChanges = 0;
        int insuranceChanges = 0;
        for (ParcelEntity entity : parcels) {
            try {
                RoutingDecision previousDecision = routingEngine.evaluate(
                        dryRunSimulator.toParcel(entity), previousConfiguration);
                if (!java.util.Objects.equals(entity.getDepartment(), previousDecision.predictedDepartment())) {
                    departmentChanges++;
                }
                if (!java.util.Objects.equals(entity.getInsuranceRequired(), previousDecision.insuranceRequired())) {
                    insuranceChanges++;
                }
            } catch (RuntimeException | java.io.IOException exception) {
                // A malformed or unroutable persisted parcel is not evidence of configuration drift.
            }
        }

        double predictedPercent = materiality(
                prediction.changedDepartments(), prediction.changedInsuranceStatuses(), prediction.totalSimulated());
        double observedPercent = materiality(departmentChanges, insuranceChanges, parcels.size());
        if (observedPercent > predictedPercent * driftMultiplier) {
            logger.atWarn()
                    .addKeyValue("routingConfigVersionId", active.getId())
                    .addKeyValue("routingConfigVersion", active.getVersion())
                    .addKeyValue("predictedPercent", predictedPercent)
                    .addKeyValue("observedPercent", observedPercent)
                    .addKeyValue("sampleCount", parcels.size())
                    .addKeyValue("basedOnVersion", previous.getVersion())
                    .addKeyValue("driftMultiplier", driftMultiplier)
                    .log("post_activation_drift_detected");
        }
    }

    private double materiality(int departmentChanges, int insuranceChanges, int total) {
        return Math.max(departmentChanges, insuranceChanges) * 100.0 / total;
    }

    private RoutingConfig parseRoutingConfig(String rulesJson) {
        try {
            JsonNode root = objectMapper.readTree(rulesJson);
            int threshold = root.has("insuranceThresholdEur")
                    ? root.get("insuranceThresholdEur").asInt()
                    : root.path("insurance").path("requiredAboveValueEur").asInt();
            List<com.parcelrouting.routing.Rule> rules = objectMapper.treeToValue(
                    root.get("rules"), new TypeReference<>() { }
            );
            return new RoutingConfig(threshold, rules);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Malformed routing configuration JSON", exception);
        }
    }

    private void validateConfiguration() {
        if (monitoringWindow.isZero() || monitoringWindow.isNegative()
                || minimumSampleCount < 1 || driftMultiplier <= 0) {
            throw new IllegalArgumentException("Config drift monitoring configuration must use positive window, sample, and multiplier");
        }
    }
}
