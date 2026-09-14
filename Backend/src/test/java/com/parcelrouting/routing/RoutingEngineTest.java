package com.parcelrouting.routing;

import com.parcelrouting.parcel.Parcel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingEngineTest {

    private final RoutingEngine routingEngine = new RoutingEngine();

    @Test
    void routesBoundaryWeightsToExpectedDepartments() {
        assertDecision(parcel(0.5, 500), standardConfig(), false, "Mail", "mail-department");
        assertDecision(parcel(1.0, 500), standardConfig(), false, "Mail", "mail-department");
        assertDecision(parcel(1.01, 500), standardConfig(), false, "Regular", "regular-department");
        assertDecision(parcel(10.0, 500), standardConfig(), false, "Regular", "regular-department");
        assertDecision(parcel(10.01, 500), standardConfig(), false, "Heavy", "heavy-department");
    }

    @Test
    void appliesInsuranceThresholdWithoutChangingDepartmentRouting() {
        assertDecision(parcel(15, 1000), standardConfig(), false, "Heavy", "heavy-department");
        assertDecision(parcel(15, 1000.01), standardConfig(), true, "Heavy", "heavy-department");
        assertDecision(parcel(15, 2000), standardConfig(), true, "Heavy", "heavy-department");
    }

    @Test
    void usesRulePriorityInsteadOfListOrder() {
        RoutingConfig config = new RoutingConfig(1000, List.of(
                rule("regular-department", 20, "weight_kg", ComparisonOperator.GT, 1, "Regular"),
                rule("heavy-department", 10, "weight_kg", ComparisonOperator.GT, 10, "Heavy")
        ));

        RoutingDecision decision = routingEngine.evaluate(parcel(15, 500), config);

        assertEquals("Heavy", decision.predictedDepartment());
        assertEquals("heavy-department", decision.matchedRuleId());
    }

    @Test
    void rejectsDuplicateRulePriorities() {
        RoutingConfig config = new RoutingConfig(1000, List.of(
                rule("first-rule", 10, "weight_kg", ComparisonOperator.GT, 1, "Regular"),
                rule("second-rule", 10, "weight_kg", ComparisonOperator.GT, 10, "Heavy")
        ));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> routingEngine.evaluate(parcel(15, 500), config)
        );
        assertTrue(exception.getMessage().contains("Duplicate routing rule priority"));
    }

    @Test
    void throwsWhenNoRuleMatchesParcel() {
        RoutingConfig config = new RoutingConfig(1000, List.of(
                rule("too-heavy", 10, "weight_kg", ComparisonOperator.GT, 100, "Oversized")
        ));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> routingEngine.evaluate(parcel(15, 500), config)
        );
        assertTrue(exception.getMessage().contains("No matching routing rule found"));
    }

    @Test
    void throwsWhenRuleUsesUnknownField() {
        RoutingConfig config = new RoutingConfig(1000, List.of(
                rule("unknown-field-rule", 10, "unknown_field", ComparisonOperator.EQ, "anything", "Unknown")
        ));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> routingEngine.evaluate(parcel(15, 500), config)
        );
        assertTrue(exception.getMessage().contains("Unknown parcel field: unknown_field"));
    }

    @Test
    void rejectsNullAndBlankRuleFields() {
        RoutingConfig nullFieldConfig = new RoutingConfig(1000, List.of(
                rule("null-field-rule", 10, null, ComparisonOperator.EQ, "anything", "Unknown")
        ));
        RoutingConfig blankFieldConfig = new RoutingConfig(1000, List.of(
                rule("blank-field-rule", 10, "  ", ComparisonOperator.EQ, "anything", "Unknown")
        ));

        IllegalArgumentException nullFieldException = assertThrows(
                IllegalArgumentException.class,
                () -> routingEngine.evaluate(parcel(15, 500), nullFieldConfig)
        );
        IllegalArgumentException blankFieldException = assertThrows(
                IllegalArgumentException.class,
                () -> routingEngine.evaluate(parcel(15, 500), blankFieldConfig)
        );

        assertTrue(nullFieldException.getMessage().contains("Parcel field name must not be null"));
        assertTrue(blankFieldException.getMessage().contains("Parcel field name must not be blank"));
    }

    @Test
    void copiesRulesIntoAnUnmodifiableList() {
        List<Rule> sourceRules = new ArrayList<>(List.of(
                rule("heavy-department", 10, "weight_kg", ComparisonOperator.GT, 10, "Heavy")
        ));
        RoutingConfig config = new RoutingConfig(1000, sourceRules);

        sourceRules.clear();

        assertEquals(1, config.rules().size());
        assertThrows(UnsupportedOperationException.class, () -> config.rules().clear());
    }

    @Test
    void rejectsNullParcel() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> routingEngine.evaluate(null, standardConfig())
        );
        assertTrue(exception.getMessage().contains("Parcel must not be null"));
    }

    @Test
    void rejectsNullRoutingConfig() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> routingEngine.evaluate(parcel(15, 500), null)
        );
        assertTrue(exception.getMessage().contains("RoutingConfig must not be null"));
    }

    @Test
    void rejectsNullRulesList() {
        RoutingConfig config = new RoutingConfig(1000, null);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> routingEngine.evaluate(parcel(15, 500), config)
        );
        assertTrue(exception.getMessage().contains("Routing rules list must not be null"));
    }

    @Test
    void rejectsRulesListContainingNull() {
        RoutingConfig config = new RoutingConfig(1000, Arrays.asList(
                rule("heavy-department", 10, "weight_kg", ComparisonOperator.GT, 10, "Heavy"),
                null
        ));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> routingEngine.evaluate(parcel(15, 500), config)
        );
        assertTrue(exception.getMessage().contains("Routing rules list must not contain null rules"));
    }

    @Test
    void rejectsRuleContainingNullCondition() {
        RoutingConfig config = new RoutingConfig(1000, List.of(
                new Rule("missing-condition", 10, null, "Heavy")
        ));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> routingEngine.evaluate(parcel(15, 500), config)
        );
        assertTrue(exception.getMessage().contains("Routing rule condition must not be null"));
    }

    private void assertDecision(
            Parcel parcel,
            RoutingConfig config,
            boolean insuranceRequired,
            String predictedDepartment,
            String matchedRuleId
    ) {
        RoutingDecision decision = routingEngine.evaluate(parcel, config);

        if (insuranceRequired) {
            assertTrue(decision.insuranceRequired());
        } else {
            assertFalse(decision.insuranceRequired());
        }
        assertEquals(predictedDepartment, decision.predictedDepartment());
        assertEquals(matchedRuleId, decision.matchedRuleId());
    }

    private RoutingConfig standardConfig() {
        return new RoutingConfig(1000, List.of(
                rule("heavy-department", 10, "weight_kg", ComparisonOperator.GT, 10, "Heavy"),
                rule("regular-department", 20, "weight_kg", ComparisonOperator.GT, 1, "Regular"),
                rule("mail-department", 30, "weight_kg", ComparisonOperator.LTE, 1, "Mail")
        ));
    }

    private Rule rule(
            String id,
            int priority,
            String field,
            ComparisonOperator operator,
            Object value,
            String department
    ) {
        return new Rule(id, priority, new Condition(field, operator, value), department);
    }

    private Parcel parcel(double weightKg, double valueEur) {
        return new Parcel(weightKg, valueEur, "DE", Map.of());
    }
}
