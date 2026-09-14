package com.parcelrouting.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parcelrouting.routing.ComparisonOperator;
import com.parcelrouting.routing.Condition;
import com.parcelrouting.routing.RoutingConfig;
import com.parcelrouting.routing.Rule;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ConfigService {

    private final RoutingConfigVersionRepository routingConfigVersionRepository;
    private final ObjectMapper objectMapper;

    public ConfigService(
            RoutingConfigVersionRepository routingConfigVersionRepository,
            ObjectMapper objectMapper
    ) {
        this.routingConfigVersionRepository = routingConfigVersionRepository;
        this.objectMapper = objectMapper;
    }

    public RoutingConfig getActiveConfig() {
        return parseRoutingConfig(loadActiveVersion().getRulesJson());
    }

    public ActiveRoutingConfig getActiveConfigWithVersion() {
        RoutingConfigVersion activeVersion = loadActiveVersion();
        if (activeVersion.getId() == null) {
            throw new IllegalStateException("Active routing configuration has no database ID");
        }

        return new ActiveRoutingConfig(
                activeVersion.getId(),
                parseRoutingConfig(activeVersion.getRulesJson())
        );
    }

    private RoutingConfigVersion loadActiveVersion() {
        List<RoutingConfigVersion> activeVersions =
                routingConfigVersionRepository.findByStatus(ConfigVersionStatus.ACTIVE);

        if (activeVersions.isEmpty()) {
            throw new IllegalStateException("No active routing configuration exists");
        }

        return activeVersions.get(0);
    }

    private RoutingConfig parseRoutingConfig(String rulesJson) {
        if (rulesJson == null) {
            throw new IllegalStateException("Active routing configuration has no rules JSON");
        }

        try {
            JsonNode root = objectMapper.readTree(rulesJson);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("Routing configuration JSON must be an object");
            }

            JsonNode insurance = requiredObject(root, "insurance");
            int insuranceThresholdEur = requiredInt(insurance, "requiredAboveValueEur");
            JsonNode rules = requiredArray(root, "rules");

            List<Rule> parsedRules = new ArrayList<>();
            for (JsonNode ruleNode : rules) {
                parsedRules.add(parseRule(ruleNode));
            }

            return new RoutingConfig(insuranceThresholdEur, parsedRules);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Malformed routing configuration JSON", exception);
        }
    }

    private Rule parseRule(JsonNode ruleNode) throws JsonProcessingException {
        if (!ruleNode.isObject()) {
            throw new IllegalStateException("Routing rule must be an object");
        }

        JsonNode condition = requiredObject(ruleNode, "condition");
        return new Rule(
                requiredText(ruleNode, "id"),
                requiredInt(ruleNode, "priority"),
                new Condition(
                        requiredText(condition, "field"),
                        parseOperator(condition),
                        requiredValue(condition, "value")
                ),
                requiredText(ruleNode, "department")
        );
    }

    private ComparisonOperator parseOperator(JsonNode condition) {
        String operator = requiredText(condition, "operator");
        try {
            return ComparisonOperator.valueOf(operator);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unknown routing comparison operator: " + operator, exception);
        }
    }

    private Object requiredValue(JsonNode node, String fieldName) throws JsonProcessingException {
        JsonNode value = node.get(fieldName);
        if (value == null) {
            throw new IllegalStateException("Missing routing configuration field: " + fieldName);
        }
        return objectMapper.treeToValue(value, Object.class);
    }

    private JsonNode requiredObject(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isObject()) {
            throw new IllegalStateException("Missing or invalid routing configuration object: " + fieldName);
        }
        return value;
    }

    private JsonNode requiredArray(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isArray()) {
            throw new IllegalStateException("Missing or invalid routing configuration array: " + fieldName);
        }
        return value;
    }

    private String requiredText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("Missing or invalid routing configuration text field: " + fieldName);
        }
        return value.asText();
    }

    private int requiredInt(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalStateException("Missing or invalid routing configuration integer field: " + fieldName);
        }
        return value.intValue();
    }
}
