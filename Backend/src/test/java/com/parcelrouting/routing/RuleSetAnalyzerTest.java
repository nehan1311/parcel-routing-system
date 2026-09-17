package com.parcelrouting.routing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleSetAnalyzerTest {

    private final RuleSetAnalyzer analyzer = new RuleSetAnalyzer();

    @Test
    void detectsNumericGap() {
        List<RuleSetAnalyzer.Warning> warnings = analyzer.analyze(config(
                rule("light", 1, ComparisonOperator.LT, 10),
                rule("heavy", 2, ComparisonOperator.GT, 20)
        ));

        assertEquals(1, warnings.stream().filter(warning -> warning.type() == RuleSetAnalyzer.WarningType.GAP).count());
    }

    @Test
    void detectsUnreachableRuleWhenHigherPriorityConditionIsStrictSuperset() {
        List<RuleSetAnalyzer.Warning> warnings = analyzer.analyze(config(
                rule("broad", 1, ComparisonOperator.GT, 1),
                rule("narrow", 2, ComparisonOperator.GT, 10)
        ));

        assertTrue(warnings.stream().anyMatch(warning -> warning.type() == RuleSetAnalyzer.WarningType.UNREACHABLE_RULE
                && warning.ruleId().equals("narrow")));
    }

    @Test
    void detectsNumericOverlapAcrossIndependentFields() {
        List<RuleSetAnalyzer.Warning> warnings = analyzer.analyze(config(
                rule("weight-rule", 1, "weight_kg", ComparisonOperator.GT, 1),
                rule("value-rule", 2, "value_eur", ComparisonOperator.GT, 100)
        ));

        assertTrue(warnings.stream().anyMatch(warning -> warning.type() == RuleSetAnalyzer.WarningType.OVERLAP));
    }

    @Test
    void cleanRuleSetProducesNoWarnings() {
        assertTrue(analyzer.analyze(config(
                rule("mail", 1, ComparisonOperator.LTE, 1),
                rule("regular", 2, ComparisonOperator.GT, 1)
        )).isEmpty());
    }

    @Test
    void defaultProjectRuleSetProducesNoWarnings() {
        assertTrue(analyzer.analyze(config(
                rule("heavy-department", 10, ComparisonOperator.GT, 10),
                rule("regular-department", 20, ComparisonOperator.GT, 1),
                rule("mail-department", 30, ComparisonOperator.LTE, 1)
        )).isEmpty());
    }

    @Test
    void exactBoundariesAreCoveredWithoutFalseWarnings() {
        assertTrue(analyzer.analyze(config(
                rule("up-to-ten", 1, ComparisonOperator.LTE, 10),
                rule("over-ten", 2, ComparisonOperator.GT, 10)
        )).isEmpty());
        assertTrue(analyzer.analyze(config(
                rule("up-to-value", 1, "value_eur", ComparisonOperator.LTE, 1000),
                rule("over-value", 2, "value_eur", ComparisonOperator.GT, 1000)
        )).isEmpty());
    }

    private RoutingConfig config(com.parcelrouting.routing.Rule... rules) {
        return new RoutingConfig(1000, List.of(rules));
    }

    private Rule rule(String id, int priority, ComparisonOperator operator, Object value) {
        return rule(id, priority, "weight_kg", operator, value);
    }

    private Rule rule(String id, int priority, String field, ComparisonOperator operator, Object value) {
        return new Rule(id, priority, new Condition(field, operator, value), "Department");
    }
}
