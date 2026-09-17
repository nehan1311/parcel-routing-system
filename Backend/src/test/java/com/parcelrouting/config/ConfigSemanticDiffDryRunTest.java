package com.parcelrouting.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parcelrouting.parcel.ParcelRepository;
import com.parcelrouting.routing.ComparisonOperator;
import com.parcelrouting.routing.ConfigSemanticDiffer;
import com.parcelrouting.routing.DryRunSimulator;
import com.parcelrouting.routing.RoutingConfig;
import com.parcelrouting.routing.Rule;
import com.parcelrouting.routing.Condition;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigSemanticDiffDryRunTest {

    private final RoutingConfigVersionRepository configRepository = mock(RoutingConfigVersionRepository.class);
    private final ParcelRepository parcelRepository = mock(ParcelRepository.class);
    private final DryRunSimulator simulator = mock(DryRunSimulator.class);
    private final ConfigService configService = new ConfigService(
            configRepository, new ObjectMapper(), new ConfigValidator(), simulator, parcelRepository, 100
    );

    @Test
    void dryRunIncludesSemanticFieldChangeAgainstActiveConfiguration() {
        when(configRepository.findByVersion(2)).thenReturn(java.util.Optional.of(version(2, ConfigVersionStatus.DRAFT, configJson("value_eur", 20))));
        RoutingConfigVersion active = version(1, ConfigVersionStatus.ACTIVE, configJson("weight_kg", 10));
        setId(active, 1L);
        when(configRepository.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of(active));
        when(parcelRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(Page.empty());
        when(simulator.simulate(any(RoutingConfig.class)))
                .thenReturn(new DryRunSimulator.DryRunResult(8, 8, 0, List.of()));

        DryRunSimulator.DryRunResult result = configService.dryRun(2);

        assertEquals(8, result.totalCases());
        assertEquals(8, result.passedCases());
        assertEquals(0, result.failedCases());
        assertEquals("PASS", result.overallStatus());
        assertEquals(1, result.semanticDiff().size());
        assertEquals(ConfigSemanticDiffer.ChangeType.FIELD_CHANGE,
                result.semanticDiff().getFirst().changeType());
    }

    @Test
    void firstActivationHasEmptySemanticDiffWithoutThrowing() {
        when(configRepository.findByVersion(1)).thenReturn(java.util.Optional.of(version(1, ConfigVersionStatus.DRAFT, configJson("weight_kg", 10))));
        when(configRepository.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of());
        when(parcelRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(Page.empty());
        when(simulator.simulate(any(RoutingConfig.class)))
                .thenReturn(new DryRunSimulator.DryRunResult(8, 8, 0, List.of()));

        DryRunSimulator.DryRunResult result = configService.dryRun(1);

        assertTrue(result.semanticDiff().isEmpty());
        assertEquals(0, result.boundarySimulation().totalSimulated());
        assertEquals(8, result.passedCases());
        assertEquals(0, result.failedCases());
    }

    @Test
    void existingFourArgumentDryRunResultDefaultsSemanticDiffToEmpty() {
        DryRunSimulator.DryRunResult result = new DryRunSimulator.DryRunResult(1, 1, 0, List.of());

        assertTrue(result.semanticDiff().isEmpty());
        assertEquals(1, result.totalCases());
    }

    private RoutingConfigVersion version(int version, ConfigVersionStatus status, String rulesJson) {
        return new RoutingConfigVersion(version, status, rulesJson, "admin", Instant.now(),
                status == ConfigVersionStatus.ACTIVE ? Instant.now() : null, null);
    }

    private String configJson(String field, int threshold) {
        return """
                {
                  "insurance": {"requiredAboveValueEur": 1000},
                  "rules": [{
                    "id": "heavy",
                    "priority": 1,
                    "condition": {"field": "%s", "operator": "GT", "value": %d},
                    "department": "Heavy"
                  }]
                }
                """.formatted(field, threshold);
    }

    private void setId(RoutingConfigVersion version, Long id) {
        try {
            java.lang.reflect.Field field = RoutingConfigVersion.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(version, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
