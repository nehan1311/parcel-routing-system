package com.parcelrouting.routing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigSemanticDifferTest {

    private final ConfigSemanticDiffer differ = new ConfigSemanticDiffer();

    @Test
    void classifiesEveryRuleChangeType() {
        RoutingConfig active = new RoutingConfig(1000, List.of(
                rule("same", 1, "weight_kg", ComparisonOperator.GT, 10, "Mail"),
                rule("threshold", 2, "weight_kg", ComparisonOperator.GT, 10, "Mail"),
                rule("operator", 3, "weight_kg", ComparisonOperator.GT, 10, "Mail"),
                rule("field", 4, "weight_kg", ComparisonOperator.GT, 10, "Mail"),
                rule("priority", 5, "weight_kg", ComparisonOperator.GT, 10, "Mail"),
                rule("department", 6, "weight_kg", ComparisonOperator.GT, 10, "Mail"),
                rule("removed", 7, "weight_kg", ComparisonOperator.GT, 10, "Mail")
        ));
        RoutingConfig draft = new RoutingConfig(1000, List.of(
                rule("same", 1, "weight_kg", ComparisonOperator.GT, 10, "Mail"),
                rule("threshold", 2, "weight_kg", ComparisonOperator.GT, 20, "Mail"),
                rule("operator", 3, "weight_kg", ComparisonOperator.GTE, 10, "Mail"),
                rule("field", 4, "value_eur", ComparisonOperator.GT, 10, "Mail"),
                rule("priority", 8, "weight_kg", ComparisonOperator.GT, 10, "Mail"),
                rule("department", 6, "weight_kg", ComparisonOperator.GT, 10, "Heavy"),
                rule("new", 9, "weight_kg", ComparisonOperator.GT, 10, "Mail")
        ));

        List<ConfigSemanticDiffer.RuleDiff> diffs = differ.diff(active, draft);

        assertType(diffs, "same", ConfigSemanticDiffer.ChangeType.NO_CHANGE);
        assertType(diffs, "threshold", ConfigSemanticDiffer.ChangeType.THRESHOLD_CHANGE);
        assertType(diffs, "operator", ConfigSemanticDiffer.ChangeType.OPERATOR_CHANGE);
        assertType(diffs, "field", ConfigSemanticDiffer.ChangeType.FIELD_CHANGE);
        assertType(diffs, "priority", ConfigSemanticDiffer.ChangeType.PRIORITY_CHANGE);
        assertType(diffs, "department", ConfigSemanticDiffer.ChangeType.DEPARTMENT_CHANGE);
        assertType(diffs, "new", ConfigSemanticDiffer.ChangeType.NEW_RULE);
        assertType(diffs, "removed", ConfigSemanticDiffer.ChangeType.REMOVED_RULE);
    }

    @Test
    void marksNumericToTextFieldChangeAsHighAttention() {
        ConfigSemanticDiffer.RuleDiff diff = differ.diff(
                config(rule("r", 1, "weight_kg", ComparisonOperator.GT, 10, "Heavy")),
                config(rule("r", 1, "destination_country", ComparisonOperator.EQ, "DE", "Heavy"))
        ).getFirst();

        assertEquals(ConfigSemanticDiffer.ChangeType.FIELD_CHANGE, diff.changeType());
        assertEquals(ConfigSemanticDiffer.Severity.HIGH_ATTENTION, diff.severity());
        assertTrue(diff.crossesNumericTextBoundary());
    }

    @Test
    void doesNotMarkNumericToNumericFieldChangeAsBoundaryCrossing() {
        ConfigSemanticDiffer.RuleDiff diff = differ.diff(
                config(rule("r", 1, "weight_kg", ComparisonOperator.GT, 10, "Heavy")),
                config(rule("r", 1, "value_eur", ComparisonOperator.GT, 10, "Heavy"))
        ).getFirst();

        assertEquals(ConfigSemanticDiffer.ChangeType.FIELD_CHANGE, diff.changeType());
        assertEquals(ConfigSemanticDiffer.Severity.NORMAL, diff.severity());
        assertFalse(diff.crossesNumericTextBoundary());
    }

    private void assertType(List<ConfigSemanticDiffer.RuleDiff> diffs, String id, ConfigSemanticDiffer.ChangeType expected) {
        assertEquals(expected, diffs.stream().filter(diff -> diff.ruleId().equals(id)).findFirst().orElseThrow().changeType());
    }

    private RoutingConfig config(Rule rule) {
        return new RoutingConfig(1000, List.of(rule));
    }

    private Rule rule(String id, int priority, String field, ComparisonOperator operator, Object value, String department) {
        return new Rule(id, priority, new Condition(field, operator, value), department);
    }
}
