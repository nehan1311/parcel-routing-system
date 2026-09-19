package com.parcelrouting.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parcelrouting.parcel.Parcel;
import com.parcelrouting.parcel.ParcelEntity;
import com.parcelrouting.parcel.ParcelStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoricalImpactTest {

    private final DryRunSimulator simulator = new DryRunSimulator(new RoutingEngine(), new ObjectMapper());

    @Test
    void convertsHistoricalParcelWithAttributes() throws Exception {
        Parcel parcel = simulator.toParcel(parcel("Mail", "mail", false, "{\"service\":\"priority\"}"));

        assertEquals(1, parcel.weightKg());
        assertEquals(100, parcel.valueEur());
        assertEquals("DE", parcel.destinationCountry());
        assertEquals("priority", parcel.attributes().get("service"));
    }

    @Test
    void convertsHistoricalParcelWithNullAttributesJsonUsingEmptyObjectFallback() throws Exception {
        ParcelEntity entity = new ParcelEntity(
                2, 200, "DE", null, ParcelStatus.ROUTED, "Mail", "Mail", "mail",
                false, 1L, Instant.now(), null, null
        );

        Parcel parcel = simulator.toParcel(entity);

        assertEquals(2, parcel.weightKg());
        assertEquals(200, parcel.valueEur());
        assertEquals("DE", parcel.destinationCountry());
        assertTrue(parcel.attributes().isEmpty());
    }

    @Test
    void historicalParcelWithNoChangeIsReportedWithoutImpact() {
        DryRunSimulator.HistoricalImpact impact = simulator.analyzeHistorical(config(), List.of(parcel("Mail", "mail", false, "{}")));

        assertEquals(1, impact.parcelsAnalyzed());
        assertEquals(0, impact.departmentChanges());
        assertEquals(0, impact.insuranceChanges());
        assertEquals(0, impact.matchedRuleChanges());
        assertTrue(impact.changes().isEmpty());
        assertTrue(impact.failures().isEmpty());
    }

    @Test
    void historicalParcelWhoseDepartmentChangesIsCounted() {
        DryRunSimulator.HistoricalImpact impact = simulator.analyzeHistorical(config(), List.of(parcel("Legacy", "mail", false, "{}")));

        assertEquals(1, impact.departmentChanges());
        assertEquals(0, impact.insuranceChanges());
        assertEquals(0, impact.matchedRuleChanges());
        assertEquals(1, impact.changes().size());
        assertEquals("Legacy", impact.changes().getFirst().currentDepartment());
        assertEquals("Mail", impact.changes().getFirst().proposedDepartment());
    }

    @Test
    void historicalImpactDoesNotModifyParcels() {
        ParcelEntity parcel = parcel("Legacy", "legacy-rule", true, "{\"service\":\"priority\"}");
        ParcelStatus status = parcel.getStatus();
        String department = parcel.getDepartment();
        String predictedDepartment = parcel.getPredictedDepartment();
        String matchedRuleId = parcel.getMatchedRuleId();

        simulator.analyzeHistorical(config(), List.of(parcel));

        assertEquals(status, parcel.getStatus());
        assertEquals(department, parcel.getDepartment());
        assertEquals(predictedDepartment, parcel.getPredictedDepartment());
        assertEquals(matchedRuleId, parcel.getMatchedRuleId());
        assertEquals(true, parcel.getInsuranceRequired());
    }

    @Test
    void noHistoricalParcelsProducesAnEmptyImpact() {
        DryRunSimulator.HistoricalImpact impact = simulator.analyzeHistorical(config(), List.of());

        assertEquals(0, impact.parcelsAnalyzed());
        assertTrue(impact.failures().isEmpty());
    }

    @Test
    void malformedHistoricalAttributesAreReportedClearly() {
        ParcelEntity malformed = parcel("Mail", "mail", false, "{not-json");

        DryRunSimulator.HistoricalImpact impact = simulator.analyzeHistorical(config(), List.of(malformed));

        assertEquals(1, impact.parcelsAnalyzed());
        assertEquals(1, impact.failures().size());
        assertEquals(malformed.getId(), impact.failures().getFirst().parcelId());
        assertTrue(impact.failures().getFirst().error().length() > 0);
    }

    private RoutingConfig config() {
        return new RoutingConfig(1000, List.of(
                new Rule("mail", 1, new Condition("weight_kg", ComparisonOperator.LTE, 1), "Mail")
        ));
    }

    private ParcelEntity parcel(String predictedDepartment, String matchedRule, boolean insuranceRequired, String attributes) {
        return new ParcelEntity(1, 100, "DE", attributes, ParcelStatus.ROUTED, predictedDepartment,
                predictedDepartment, matchedRule, insuranceRequired, 1L, Instant.now(), null, null);
    }
}
