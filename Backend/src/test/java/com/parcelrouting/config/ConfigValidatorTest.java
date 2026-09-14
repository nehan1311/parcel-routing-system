package com.parcelrouting.config;

import com.parcelrouting.routing.ComparisonOperator;
import com.parcelrouting.routing.Condition;
import com.parcelrouting.routing.RoutingConfig;
import com.parcelrouting.routing.Rule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigValidatorTest {

    private final ConfigValidator validator = new ConfigValidator();

    @Test
    void acceptsValidConfiguration() {
        assertDoesNotThrow(() -> validator.validate(validConfig()));
    }

    @Test
    void rejectsDuplicateRuleIds() {
        RoutingConfig config = new RoutingConfig(1000, List.of(
                rule("duplicate", 1, "Mail", numericCondition()),
                rule("duplicate", 2, "Regular", numericCondition())
        ));

        assertValidationFailure(config, "Duplicate routing rule ID");
    }

    @Test
    void rejectsDuplicatePriorities() {
        RoutingConfig config = new RoutingConfig(1000, List.of(
                rule("mail", 1, "Mail", numericCondition()),
                rule("regular", 1, "Regular", numericCondition())
        ));

        assertValidationFailure(config, "Duplicate routing rule priority");
    }

    @Test
    void rejectsBlankDepartment() {
        assertValidationFailure(new RoutingConfig(1000, List.of(rule("mail", 1, " ", numericCondition()))), "department");
    }

    @Test
    void rejectsInvalidConditions() {
        assertValidationFailure(
                new RoutingConfig(1000, List.of(rule("mail", 1, "Mail", new Condition("unknown", ComparisonOperator.EQ, "x")))),
                "Unknown routing condition field"
        );
    }

    @Test
    void rejectsNegativeInsuranceThreshold() {
        assertValidationFailure(new RoutingConfig(-1, List.of(rule("mail", 1, "Mail", numericCondition()))), "Insurance threshold");
    }

    @Test
    void rejectsEmptyRules() {
        assertValidationFailure(new RoutingConfig(1000, List.of()), "must not be empty");
    }

    private void assertValidationFailure(RoutingConfig config, String message) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> validator.validate(config));
        assertTrue(exception.getMessage().contains(message));
    }

    private RoutingConfig validConfig() {
        return new RoutingConfig(1000, List.of(rule("mail", 1, "Mail", numericCondition())));
    }

    private Rule rule(String id, int priority, String department, Condition condition) {
        return new Rule(id, priority, condition, department);
    }

    private Condition numericCondition() {
        return new Condition("weight_kg", ComparisonOperator.LTE, 1);
    }
}
