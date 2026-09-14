package com.parcelrouting.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DryRunSimulatorTest {

    @Test
    void allRegressionFixturesPassAgainstCurrentRules() {
        DryRunSimulator simulator = new DryRunSimulator(new RoutingEngine(), new ObjectMapper());

        DryRunSimulator.DryRunResult result = simulator.simulate(currentRules());

        assertEquals(8, result.totalCases());
        assertEquals(8, result.passedCases());
        assertEquals(0, result.failedCases());
        assertTrue(result.failures().isEmpty());
    }


    @Test
    void brokenDraftReportsExpectedAndActualDetailsForEveryFailure() {
        DryRunSimulator simulator = new DryRunSimulator(new RoutingEngine(), new ObjectMapper());
        RoutingConfig brokenDraft = new RoutingConfig(500, currentRules().rules());

        DryRunSimulator.DryRunResult result = simulator.simulate(brokenDraft);

        assertEquals(8, result.totalCases());
        assertEquals(7, result.passedCases());
        assertEquals(1, result.failedCases());
        assertFalse(result.failures().isEmpty());
        DryRunSimulator.DryRunFailure failure = result.failures().getFirst();
        assertEquals("1000 EUR does not require insurance", failure.caseName());
        assertFalse(failure.expected().insuranceRequired());
        assertTrue(failure.actual().insuranceRequired());
        assertEquals("Heavy", failure.actual().predictedDepartment());
        assertEquals("heavy-department", failure.actual().matchedRuleId());
    }

    @Test
    void delegatesEveryFixtureToTheExistingRoutingEngine() {
        RoutingEngine routingEngine = mock(RoutingEngine.class);
        when(routingEngine.evaluate(any(), eq(currentRules())))
                .thenReturn(new RoutingDecision(false, "Mail", "mail-department"));
        DryRunSimulator simulator = new DryRunSimulator(routingEngine, new ObjectMapper());

        simulator.simulate(currentRules());

        verify(routingEngine, times(8)).evaluate(any(), eq(currentRules()));
    }

    private RoutingConfig currentRules() {
        return new RoutingConfig(1000, List.of(
                new Rule("heavy-department", 10, new Condition("weight_kg", ComparisonOperator.GT, 10), "Heavy"),
                new Rule("regular-department", 20, new Condition("weight_kg", ComparisonOperator.GT, 1), "Regular"),
                new Rule("mail-department", 30, new Condition("weight_kg", ComparisonOperator.LTE, 1), "Mail")
        ));
    }
}
