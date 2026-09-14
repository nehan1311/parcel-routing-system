package com.parcelrouting.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.parcelrouting.parcel.Parcel;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class DryRunSimulator {

    private static final String FIXTURE_RESOURCE = "fixtures/regression-fixtures.json";

    private final RoutingEngine routingEngine;
    private final ObjectMapper objectMapper;

    public DryRunSimulator(RoutingEngine routingEngine, ObjectMapper objectMapper) {
        this.routingEngine = routingEngine;
        this.objectMapper = objectMapper;
    }

    public DryRunResult simulate(RoutingConfig draftConfiguration) {
        RegressionFixtures fixtures = loadFixtures();
        List<DryRunFailure> failures = new ArrayList<>();

        for (RegressionCase fixtureCase : fixtures.cases()) {
            ExpectedDecision expected = fixtureCase.expected();
            try {
                RoutingDecision actual = routingEngine.evaluate(fixtureCase.parcel(), draftConfiguration);
                if (!matches(expected, actual)) {
                    failures.add(new DryRunFailure(fixtureCase.name(), expected, ActualDecision.from(actual), null));
                }
            } catch (RuntimeException exception) {
                failures.add(new DryRunFailure(fixtureCase.name(), expected, null, exception.getMessage()));
            }
        }

        int totalCases = fixtures.cases().size();
        return new DryRunResult(totalCases, totalCases - failures.size(), failures.size(), List.copyOf(failures));
    }

    private RegressionFixtures loadFixtures() {
        ClassPathResource resource = new ClassPathResource(FIXTURE_RESOURCE);
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(inputStream, RegressionFixtures.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Regression fixtures could not be loaded", exception);
        }
    }

    private boolean matches(ExpectedDecision expected, RoutingDecision actual) {
        return expected.insuranceRequired() == actual.insuranceRequired()
                && java.util.Objects.equals(expected.predictedDepartment(), actual.predictedDepartment())
                && java.util.Objects.equals(expected.matchedRuleId(), actual.matchedRuleId());
    }

    public record DryRunResult(int totalCases, int passedCases, int failedCases, List<DryRunFailure> failures) {
    }

    public record DryRunFailure(String caseName, ExpectedDecision expected, ActualDecision actual, String error) {
    }

    public record ExpectedDecision(boolean insuranceRequired, String predictedDepartment, String matchedRuleId) {
    }

    public record ActualDecision(boolean insuranceRequired, String predictedDepartment, String matchedRuleId) {
        private static ActualDecision from(RoutingDecision decision) {
            return new ActualDecision(
                    decision.insuranceRequired(), decision.predictedDepartment(), decision.matchedRuleId()
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RegressionFixtures(List<RegressionCase> cases) {
    }

    private record RegressionCase(String name, Parcel parcel, ExpectedDecision expected) {
    }
}
