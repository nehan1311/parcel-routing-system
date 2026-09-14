package com.parcelrouting.config;

import com.parcelrouting.routing.ComparisonOperator;
import com.parcelrouting.routing.Condition;
import com.parcelrouting.routing.RoutingConfig;
import com.parcelrouting.routing.Rule;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Component
public class ConfigValidator {

    private static final Set<String> NUMERIC_FIELDS = Set.of("weight_kg", "value_eur");
    private static final Set<String> TEXT_FIELDS = Set.of("destination_country");

    public void validate(RoutingConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Routing configuration must not be null");
        }
        if (config.insuranceThresholdEur() < 0) {
            throw new IllegalArgumentException("Insurance threshold must be greater than or equal to zero");
        }
        if (config.rules() == null || config.rules().isEmpty()) {
            throw new IllegalArgumentException("Routing rules must not be empty");
        }

        Set<String> ruleIds = new HashSet<>();
        Set<Integer> priorities = new HashSet<>();
        for (Rule rule : config.rules()) {
            validateRule(rule, ruleIds, priorities);
        }
    }

    private void validateRule(Rule rule, Set<String> ruleIds, Set<Integer> priorities) {
        if (rule == null) {
            throw new IllegalArgumentException("Routing rules must not contain null entries");
        }
        if (rule.id() == null || rule.id().isBlank()) {
            throw new IllegalArgumentException("Routing rule ID must not be blank");
        }
        if (!ruleIds.add(rule.id())) {
            throw new IllegalArgumentException("Duplicate routing rule ID: " + rule.id());
        }
        if (rule.priority() < 1) {
            throw new IllegalArgumentException("Routing rule priority must be at least 1");
        }
        if (!priorities.add(rule.priority())) {
            throw new IllegalArgumentException("Duplicate routing rule priority: " + rule.priority());
        }
        if (rule.department() == null || rule.department().isBlank()) {
            throw new IllegalArgumentException("Routing rule department must not be blank");
        }
        validateCondition(rule.condition());
    }

    private void validateCondition(Condition condition) {
        if (condition == null) {
            throw new IllegalArgumentException("Routing rule condition must not be null");
        }
        if (condition.field() == null || condition.field().isBlank()) {
            throw new IllegalArgumentException("Routing condition field must not be blank");
        }
        if (condition.operator() == null) {
            throw new IllegalArgumentException("Routing condition operator must not be null");
        }
        if (condition.value() == null) {
            throw new IllegalArgumentException("Routing condition value must not be null");
        }

        String field = condition.field();
        if (NUMERIC_FIELDS.contains(field)) {
            validateNumericCondition(condition);
        } else if (TEXT_FIELDS.contains(field) || isAttributeField(field)) {
            validateTextOrAttributeCondition(condition);
        } else {
            throw new IllegalArgumentException("Unknown routing condition field: " + field);
        }
    }

    private void validateNumericCondition(Condition condition) {
        if (condition.operator() == ComparisonOperator.IN) {
            if (!(condition.value() instanceof Collection<?>)) {
                throw new IllegalArgumentException("IN routing condition value must be a collection");
            }
            if (((Collection<?>) condition.value()).stream().anyMatch(value -> !(value instanceof Number))) {
                throw new IllegalArgumentException("Numeric IN routing condition values must be numbers");
            }
            return;
        }
        if (!(condition.value() instanceof Number)) {
            throw new IllegalArgumentException("Numeric routing condition value must be a number");
        }
    }

    private void validateTextOrAttributeCondition(Condition condition) {
        if (condition.operator() != ComparisonOperator.EQ
                && condition.operator() != ComparisonOperator.NEQ
                && condition.operator() != ComparisonOperator.IN) {
            throw new IllegalArgumentException("Routing condition operator is not valid for field: " + condition.field());
        }
        if (condition.operator() == ComparisonOperator.IN && !(condition.value() instanceof Collection<?>)) {
            throw new IllegalArgumentException("IN routing condition value must be a collection");
        }
    }

    private boolean isAttributeField(String field) {
        return field.startsWith("attribute:") && field.substring("attribute:".length()).isBlank() == false;
    }
}
