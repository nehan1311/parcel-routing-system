package com.parcelrouting.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.parcelrouting.parcel.Parcel;
import com.parcelrouting.parcel.ParcelEntity;
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

    /** Evaluates supplied persisted parcels only; it deliberately does not save or mutate them. */
    public HistoricalImpact analyzeHistorical(RoutingConfig draftConfiguration, List<ParcelEntity> historicalParcels) {
        List<HistoricalParcelFailure> failures = new ArrayList<>();
        List<HistoricalParcelChange> changes = new ArrayList<>();
        int departmentChanges = 0;
        int insuranceChanges = 0;
        int matchedRuleChanges = 0;

        for (ParcelEntity entity : historicalParcels) {
            try {
                Map<String, Object> attributes = objectMapper.readValue(
                        entity.getAttributesJson() == null ? "{}" : entity.getAttributesJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<>() { }
                );
                Parcel parcel = new Parcel(entity.getWeightKg(), entity.getValueEur(), entity.getDestinationCountry(), attributes);
                RoutingDecision proposed = routingEngine.evaluate(parcel, draftConfiguration);

                if (entity.getInsuranceRequired() == null) {
                    throw new IllegalStateException("Persisted insurance routing snapshot is unavailable");
                }
                boolean departmentChanged = !java.util.Objects.equals(
                        entity.getPredictedDepartment(), proposed.predictedDepartment()
                );
                boolean matchedRuleChanged = !java.util.Objects.equals(entity.getMatchedRuleId(), proposed.matchedRuleId());
                if (departmentChanged) {
                    departmentChanges++;
                }
                if (matchedRuleChanged) {
                    matchedRuleChanges++;
                }
                if (entity.getInsuranceRequired() != proposed.insuranceRequired()) {
                    insuranceChanges++;
                }
                if (departmentChanged || matchedRuleChanged || entity.getInsuranceRequired() != proposed.insuranceRequired()) {
                    changes.add(new HistoricalParcelChange(
                            entity.getId(), entity.getPredictedDepartment(), proposed.predictedDepartment(),
                            entity.getMatchedRuleId(), proposed.matchedRuleId()
                    ));
                }
            } catch (Exception exception) {
                failures.add(new HistoricalParcelFailure(entity.getId(), exception.getMessage()));
            }
        }
        return new HistoricalImpact(historicalParcels.size(), departmentChanges, insuranceChanges,
                matchedRuleChanges, List.copyOf(changes), List.copyOf(failures));
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

    public record DryRunResult(
            int totalCases, int passedCases, int failedCases, List<DryRunFailure> failures,
            HistoricalImpact historicalImpact,
            List<ConfigSemanticDiffer.RuleDiff> semanticDiff,
            BoundarySimulator.BoundarySimulationResult boundarySimulation
    ) {
        public DryRunResult(int totalCases, int passedCases, int failedCases, List<DryRunFailure> failures) {
            this(totalCases, passedCases, failedCases, failures, HistoricalImpact.empty(), List.of(), BoundarySimulator.empty());
        }

        public DryRunResult(
                int totalCases,
                int passedCases,
                int failedCases,
                List<DryRunFailure> failures,
                HistoricalImpact historicalImpact
        ) {
            this(totalCases, passedCases, failedCases, failures, historicalImpact, List.of(), BoundarySimulator.empty());
        }

        public DryRunResult(
                int totalCases,
                int passedCases,
                int failedCases,
                List<DryRunFailure> failures,
                HistoricalImpact historicalImpact,
                List<ConfigSemanticDiffer.RuleDiff> semanticDiff
        ) {
            this(totalCases, passedCases, failedCases, failures, historicalImpact, semanticDiff, BoundarySimulator.empty());
        }

        @JsonProperty("overallStatus")
        public String overallStatus() {
            return failedCases == 0 ? "PASS" : "FAIL";
        }
    }

    public record DryRunFailure(String caseName, ExpectedDecision expected, ActualDecision actual, String error) {
    }

    public record ExpectedDecision(boolean insuranceRequired, String predictedDepartment, String matchedRuleId) {
        @JsonProperty("department")
        public String department() {
            return predictedDepartment;
        }
    }

    public record ActualDecision(boolean insuranceRequired, String predictedDepartment, String matchedRuleId) {
        @JsonProperty("department")
        public String department() {
            return predictedDepartment;
        }
        private static ActualDecision from(RoutingDecision decision) {
            return new ActualDecision(
                    decision.insuranceRequired(), decision.predictedDepartment(), decision.matchedRuleId()
            );
        }
    }

    public record HistoricalImpact(
            int parcelsAnalyzed,
            int departmentChanges,
            int insuranceChanges,
            int matchedRuleChanges,
            List<HistoricalParcelChange> changes,
            List<HistoricalParcelFailure> failures
    ) {
        static HistoricalImpact empty() {
            return new HistoricalImpact(0, 0, 0, 0, List.of(), List.of());
        }
    }

    public record HistoricalParcelChange(
            Long parcelId,
            String currentDepartment,
            String proposedDepartment,
            String currentRule,
            String proposedRule
    ) {
    }

    public record HistoricalParcelFailure(Long parcelId, String error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RegressionFixtures(List<RegressionCase> cases) {
    }

    private record RegressionCase(String name, Parcel parcel, ExpectedDecision expected) {
    }
}
