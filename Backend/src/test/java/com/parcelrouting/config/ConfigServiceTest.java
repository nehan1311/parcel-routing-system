package com.parcelrouting.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parcelrouting.routing.ComparisonOperator;
import com.parcelrouting.routing.RoutingConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigServiceTest {

    private final RoutingConfigVersionRepository repository = mock(RoutingConfigVersionRepository.class);
    private final ConfigService configService = new ConfigService(repository, new ObjectMapper());

    @Test
    void parsesActiveConfigurationIntoRoutingConfig() {
        when(repository.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of(activeVersion(validConfigJson())));

        RoutingConfig config = configService.getActiveConfig();

        assertEquals(1000, config.insuranceThresholdEur());
        assertEquals(3, config.rules().size());
        assertEquals(10, config.rules().get(0).priority());
        assertEquals(20, config.rules().get(1).priority());
        assertEquals(30, config.rules().get(2).priority());
        assertEquals(ComparisonOperator.GT, config.rules().get(0).condition().operator());
        assertEquals(ComparisonOperator.GT, config.rules().get(1).condition().operator());
        assertEquals(ComparisonOperator.LTE, config.rules().get(2).condition().operator());
    }

    @Test
    void throwsWhenNoActiveConfigurationExists() {
        when(repository.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                configService::getActiveConfig
        );

        assertTrue(exception.getMessage().contains("No active routing configuration exists"));
    }

    @Test
    void throwsClearlyForMalformedJson() {
        when(repository.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of(activeVersion("{invalid-json")));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                configService::getActiveConfig
        );

        assertTrue(exception.getMessage().contains("Malformed routing configuration JSON"));
    }

    @Test
    void throwsClearlyForUnknownOperator() {
        String invalidOperatorJson = validConfigJson().replace("\"GT\"", "\"BETWEEN\"");
        when(repository.findByStatus(ConfigVersionStatus.ACTIVE))
                .thenReturn(List.of(activeVersion(invalidOperatorJson)));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                configService::getActiveConfig
        );

        assertTrue(exception.getMessage().contains("Unknown routing comparison operator: BETWEEN"));
    }

    private RoutingConfigVersion activeVersion(String rulesJson) {
        return new RoutingConfigVersion(
                1,
                ConfigVersionStatus.ACTIVE,
                rulesJson,
                "SYSTEM",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                null
        );
    }

    private String validConfigJson() {
        return """
                {
                  "insurance": {
                    "requiredAboveValueEur": 1000
                  },
                  "rules": [
                    {
                      "id": "heavy-department",
                      "priority": 10,
                      "condition": {
                        "field": "weight_kg",
                        "operator": "GT",
                        "value": 10
                      },
                      "department": "Heavy"
                    },
                    {
                      "id": "regular-department",
                      "priority": 20,
                      "condition": {
                        "field": "weight_kg",
                        "operator": "GT",
                        "value": 1
                      },
                      "department": "Regular"
                    },
                    {
                      "id": "mail-department",
                      "priority": 30,
                      "condition": {
                        "field": "weight_kg",
                        "operator": "LTE",
                        "value": 1
                      },
                      "department": "Mail"
                    }
                  ]
                }
                """;
    }
}
