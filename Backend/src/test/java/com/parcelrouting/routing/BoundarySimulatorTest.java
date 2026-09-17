package com.parcelrouting.routing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundarySimulatorTest {

    @Test
    void reportsChangedWeightBoundaryAsACompactRange() {
        BoundarySimulator simulator = new BoundarySimulator(0, 30, 1, 0, 30_000, 10_000);
        RoutingConfig active = config(
                rule("weight", 1, ComparisonOperator.GT, 10, "Heavy"),
                rule("fallback", 2, ComparisonOperator.LTE, 30, "Regular")
        );
        RoutingConfig draft = config(
                rule("weight", 1, ComparisonOperator.GT, 20, "Heavy"),
                rule("fallback", 2, ComparisonOperator.LTE, 30, "Regular")
        );

        BoundarySimulator.BoundarySimulationResult result = simulator.simulate(active, draft);

        assertEquals(124, result.totalSimulated());
        assertEquals(40, result.changedDepartments());
        assertEquals(0, result.changedInsuranceStatuses());
        assertEquals(1, result.changedRanges().size());
        BoundarySimulator.ChangedRange range = result.changedRanges().getFirst();
        assertEquals(11, range.minWeightKg());
        assertEquals(20, range.maxWeightKg());
        assertEquals(40, range.gridPoints());
    }

    @Test
    void reportsNoChangesForIdenticalConfigurations() {
        RoutingConfig config = config(rule("weight", 1, ComparisonOperator.LTE, 1000, "Department"));

        BoundarySimulator.BoundarySimulationResult result = new BoundarySimulator().simulate(config, config);

        assertEquals(10_201, result.totalSimulated());
        assertEquals(0, result.changedDepartments());
        assertEquals(0, result.changedInsuranceStatuses());
        assertTrue(result.changedRanges().isEmpty());
    }

    @Test
    void oldDryRunResultConstructorsRemainCompatible() {
        DryRunSimulator.DryRunResult fourArgument = new DryRunSimulator.DryRunResult(1, 1, 0, List.of());
        DryRunSimulator.DryRunResult fiveArgument = new DryRunSimulator.DryRunResult(
                1, 1, 0, List.of(), DryRunSimulator.HistoricalImpact.empty()
        );
        DryRunSimulator.DryRunResult sixArgument = new DryRunSimulator.DryRunResult(
                1, 1, 0, List.of(), DryRunSimulator.HistoricalImpact.empty(), List.of()
        );

        assertTrue(fourArgument.boundarySimulation().changedRanges().isEmpty());
        assertTrue(fiveArgument.boundarySimulation().changedRanges().isEmpty());
        assertTrue(sixArgument.boundarySimulation().changedRanges().isEmpty());
    }

    private RoutingConfig config(Rule... rules) {
        return new RoutingConfig(1000, List.of(rules));
    }

    private Rule rule(String id, ComparisonOperator operator, Object value) {
        return rule(id, 1, operator, value, "Department");
    }

    private Rule rule(String id, int priority, ComparisonOperator operator, Object value, String department) {
        return new Rule(id, priority, new Condition("weight_kg", operator, value), department);
    }
}
