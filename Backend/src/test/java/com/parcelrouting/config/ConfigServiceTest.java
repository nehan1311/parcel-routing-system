package com.parcelrouting.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parcelrouting.routing.ComparisonOperator;
import com.parcelrouting.routing.RoutingConfig;
import com.parcelrouting.routing.DryRunSimulator;
import com.parcelrouting.parcel.ParcelEntity;
import com.parcelrouting.parcel.ParcelStatus;
import com.parcelrouting.parcel.ParcelRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;
import org.springframework.data.domain.Page;

class ConfigServiceTest {

    private final RoutingConfigVersionRepository repository = mock(RoutingConfigVersionRepository.class);
    private final DryRunSimulator dryRunSimulator = mock(DryRunSimulator.class);
    private final ParcelRepository parcelRepository = mock(ParcelRepository.class);
    private final ConfigService configService = new ConfigService(
            repository, new ObjectMapper(), new ConfigValidator(), dryRunSimulator, parcelRepository, 100
    );

    @Test
    void parsesActiveConfigurationIntoRoutingConfig() {
        when(repository.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of(activeVersion(validConfigJson())));

        RoutingConfig config = configService.getActiveConfig();

        assertEquals(1000, config.insuranceThresholdEur());
        assertEquals(3, config.rules().size());
        assertEquals(10, config.rules().get(0).priority());
        assertEquals(20, config.rules().get(1).priority());
        assertEquals(30, config.rules().get(2).priority());
        assertEquals(ComparisonOperator.GT, config.rules().get(0).condition().operator());
        assertEquals(ComparisonOperator.GT, config.rules().get(1).condition().operator());
        assertEquals(ComparisonOperator.LTE, config.rules().get(2).condition().operator());
    }

    @Test
    void throwsWhenNoActiveConfigurationExists() {
        when(repository.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                configService::getActiveConfig
        );

        assertTrue(exception.getMessage().contains("No active routing configuration exists"));
    }

    @Test
    void throwsClearlyForMalformedJson() {
        when(repository.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of(activeVersion("{invalid-json")));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                configService::getActiveConfig
        );

        assertTrue(exception.getMessage().contains("Malformed routing configuration JSON"));
    }

    @Test
    void throwsClearlyForUnknownOperator() {
        String invalidOperatorJson = validConfigJson().replace("\"GT\"", "\"BETWEEN\"");
        when(repository.findByStatus(ConfigVersionStatus.ACTIVE))
                .thenReturn(List.of(activeVersion(invalidOperatorJson)));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                configService::getActiveConfig
        );

        assertTrue(exception.getMessage().contains("Unknown routing comparison operator: BETWEEN"));
    }

    @Test
    void createsValidDraftWithoutChangingActiveConfiguration() {
        RoutingConfigVersion active = activeVersion(validConfigJson());
        RoutingConfigVersion latest = new RoutingConfigVersion(
                4, ConfigVersionStatus.ARCHIVED, "{}", "admin", Instant.now(), null, null
        );
        when(repository.findTopByOrderByVersionDesc()).thenReturn(java.util.Optional.of(latest));
        when(repository.save(any(RoutingConfigVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoutingConfigVersion draft = configService.createDraft(validConfig(), "admin");

        assertEquals(ConfigVersionStatus.DRAFT, draft.getStatus());
        assertEquals(5, draft.getVersion());
        assertEquals("admin", draft.getCreatedBy());
        assertTrue(draft.getCreatedAt().isBefore(Instant.now().plusSeconds(1)));
        assertEquals(latest.getId(), draft.getBasedOnVersionId());
        assertTrue(draft.getRulesJson().contains("heavy-department"));
        assertEquals(null, draft.getReason());
        assertEquals(ConfigVersionStatus.ACTIVE, active.getStatus());
        assertEquals(validConfigJson(), active.getRulesJson());
        verify(repository).save(draft);
        verify(repository, never()).findByStatus(ConfigVersionStatus.ACTIVE);
    }

    @Test
    void rejectsInvalidDraftBeforeAccessingOrPersistingDatabaseState() {
        RoutingConfig invalidConfig = new RoutingConfig(-1, List.of());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> configService.createDraft(invalidConfig, "admin")
        );

        assertTrue(exception.getMessage().contains("Insurance threshold"));
        verify(repository, never()).findTopByOrderByVersionDesc();
        verify(repository, never()).save(any(RoutingConfigVersion.class));
    }

    @Test
    void createsDraftWithReasonAndPersistsIt() {
        when(repository.findTopByOrderByVersionDesc()).thenReturn(java.util.Optional.empty());
        when(repository.save(any(RoutingConfigVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RoutingConfigVersion draft = configService.createDraft(validConfig(), "admin", "Increase heavy parcel coverage");

        assertEquals("Increase heavy parcel coverage", draft.getReason());
        verify(repository).save(draft);
    }

    @Test
    void oldVersionWithoutReasonLoadsWithNullReason() {
        RoutingConfigVersion oldVersion = draftVersion(2, validConfigJson());
        when(repository.findAllByOrderByVersionDesc()).thenReturn(List.of(oldVersion));

        List<ConfigService.ConfigHistoryEntry> history = configService.history();

        assertEquals(1, history.size());
        assertEquals(null, history.getFirst().reason());
    }

    @Test
    void historyIncludesPersistedReason() {
        RoutingConfigVersion version = new RoutingConfigVersion(
                2, ConfigVersionStatus.DRAFT, validConfigJson(), "admin", Instant.now(), null, 1L,
                "Document threshold adjustment"
        );
        when(repository.findAllByOrderByVersionDesc()).thenReturn(List.of(version));

        List<ConfigService.ConfigHistoryEntry> history = configService.history();

        assertEquals("Document threshold adjustment", history.getFirst().reason());
    }

    @Test
    void validDraftReturnsAnalyzerWarningsWithoutChangingValidationResult() {
        RoutingConfigVersion draft = draftVersion(2, """
                {
                  "insurance": {"requiredAboveValueEur": 1000},
                  "rules": [
                    {"id": "light", "priority": 1, "condition": {"field": "weight_kg", "operator": "LT", "value": 10}, "department": "Light"},
                    {"id": "heavy", "priority": 2, "condition": {"field": "weight_kg", "operator": "GT", "value": 20}, "department": "Heavy"}
                  ]
                }
                """);
        when(repository.findByVersion(2)).thenReturn(java.util.Optional.of(draft));

        ConfigService.DraftValidationResult result = configService.validateDraft(2);

        assertEquals(2, result.version());
        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.type() == com.parcelrouting.routing.RuleSetAnalyzer.WarningType.GAP));
    }

    @Test
    void validDraftWithNoAnalyzerWarningsReturnsEmptyWarnings() {
        when(repository.findByVersion(2)).thenReturn(java.util.Optional.of(draftVersion(2, validConfigJson())));

        ConfigService.DraftValidationResult result = configService.validateDraft(2);

        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void structurallyInvalidDraftStillPropagatesTheSameValidationException() {
        String duplicatePriorityJson = validConfigJson().replace("\"priority\": 20", "\"priority\": 10");
        when(repository.findByVersion(2)).thenReturn(java.util.Optional.of(draftVersion(2, duplicatePriorityJson)));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> configService.validateDraft(2));

        assertEquals("Duplicate routing rule priority: 10", exception.getMessage());
    }

    @Test
    void existingThreeArgumentDraftValidationResultDefaultsWarningsToEmpty() {
        ConfigService.DraftValidationResult result = new ConfigService.DraftValidationResult(2, true, List.of());

        assertEquals(2, result.version());
        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void dryRunRecordsTheCurrentDraftConfigurationWithoutChangingActiveConfiguration() {
        RoutingConfigVersion active = activeVersion(validConfigJson());
        setId(active, 1L);
        RoutingConfigVersion draft = new RoutingConfigVersion(
                2, ConfigVersionStatus.DRAFT, validConfigJson(), "admin", Instant.now(), null, 1L
        );
        DryRunSimulator.DryRunResult result = new DryRunSimulator.DryRunResult(8, 8, 0, List.of());
        when(repository.findByVersion(2)).thenReturn(java.util.Optional.of(draft));
        when(dryRunSimulator.simulate(any(RoutingConfig.class))).thenReturn(result);

        DryRunSimulator.DryRunResult dryRunResult = configService.dryRun(2);

        assertEquals(ConfigVersionStatus.ACTIVE, active.getStatus());
        assertEquals(validConfigJson(), active.getRulesJson());
        assertTrue(draft.isDryRunPassed());
        assertEquals(validConfigJson(), draft.getDryRunRulesJson());
        assertEquals(result.totalCases(), dryRunResult.totalCases());
        assertEquals(result.passedCases(), dryRunResult.passedCases());
        assertEquals(result.failedCases(), dryRunResult.failedCases());
        assertEquals(result.failures(), dryRunResult.failures());
        assertEquals(5.0, dryRunResult.materialityThresholdPercent());
        verify(repository).save(draft);
        verify(dryRunSimulator).simulate(any(RoutingConfig.class));
    }

    @Test
    void dryRunRejectsMissingAndNonDraftVersions() {
        when(repository.findByVersion(99)).thenReturn(java.util.Optional.empty());
        assertThrows(ConfigVersionNotFoundException.class, () -> configService.dryRun(99));

        when(repository.findByVersion(1)).thenReturn(java.util.Optional.of(activeVersion(validConfigJson())));
        assertThrows(ConfigVersionStateException.class, () -> configService.dryRun(1));
        verify(dryRunSimulator, never()).simulate(any(RoutingConfig.class));
    }

    @Test
    void dryRunUsesConfiguredHistoricalAnalysisLimit() {
        ConfigService limitedService = new ConfigService(
                repository, new ObjectMapper(), new ConfigValidator(), dryRunSimulator, parcelRepository, 7
        );
        RoutingConfigVersion draft = draftVersion(2, validConfigJson());
        when(repository.findByVersion(2)).thenReturn(java.util.Optional.of(draft));
        when(dryRunSimulator.simulate(any(RoutingConfig.class)))
                .thenReturn(new DryRunSimulator.DryRunResult(8, 8, 0, List.of()));
        when(parcelRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(Page.empty());
        when(dryRunSimulator.analyzeHistorical(any(), any()))
                .thenReturn(new DryRunSimulator.HistoricalImpact(0, 0, 0, 0, List.of(), List.of()));

        limitedService.dryRun(2);

        verify(parcelRepository).findAllByOrderByCreatedAtDesc(argThat(pageable -> pageable.getPageSize() == 7));
    }

    @Test
    void successfulDryRunActivatesDraftArchivesPreviousActiveAndKeepsParcelSnapshot() {
        RoutingConfigVersion active = activeVersion(validConfigJson());
        setId(active, 1L);
        RoutingConfigVersion draft = draftVersion(2, alternateConfigJson());
        ParcelEntity existingParcel = new ParcelEntity(
                1, 100, "DE", "{}", ParcelStatus.ROUTED, "Mail", "Mail", "mail-department",
                1L, Instant.now(), null, null
        );
        DryRunSimulator.DryRunResult passed = new DryRunSimulator.DryRunResult(8, 8, 0, List.of());
        when(repository.findByVersion(2)).thenReturn(java.util.Optional.of(draft));
        when(dryRunSimulator.simulate(any(RoutingConfig.class))).thenReturn(passed);
        when(repository.findByStatus(ConfigVersionStatus.ACTIVE)).thenAnswer(invocation -> List.of(active));
        when(repository.save(draft)).thenReturn(draft);

        configService.dryRun(2);
        configService.approveMaterialChange(2L, "reviewer");
        RoutingConfigVersion activated = configService.activate(2L, "admin");

        assertEquals(ConfigVersionStatus.ARCHIVED, active.getStatus());
        assertEquals(ConfigVersionStatus.ACTIVE, activated.getStatus());
        assertEquals("admin", activated.getActivatedBy());
        assertTrue(activated.getActivatedAt().isBefore(Instant.now().plusSeconds(1)));
        assertEquals(alternateConfigJson(), activated.getRulesJson());
        assertEquals(1L, existingParcel.getRoutingConfigVersionId());
        assertEquals(1, List.of(active, draft).stream()
                .filter(version -> version.getStatus() == ConfigVersionStatus.ACTIVE).count());
        verify(repository).saveAll(List.of(active));
        verify(repository).flush();
    }

    @Test
    void fieldChangeWithoutAcknowledgmentIsBlockedAndNamesTheRule() {
        RoutingConfigVersion active = activeVersion(validConfigJson());
        setId(active, 1L);
        RoutingConfigVersion draft = draftVersion(2, fieldChangedConfigJson());
        draft.recordDryRun(Instant.now(), true);
        when(repository.findByVersion(2)).thenReturn(java.util.Optional.of(draft));
        when(repository.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of(active));

        UnacknowledgedRuleChangeException exception = assertThrows(
                UnacknowledgedRuleChangeException.class,
                () -> configService.activate(2L, "admin")
        );

        assertTrue(exception.getMessage().contains("heavy-department"));
        assertEquals(ConfigVersionStatus.ACTIVE, active.getStatus());
        assertEquals(ConfigVersionStatus.DRAFT, draft.getStatus());
        verify(repository, never()).saveAll(any());
    }

    @Test
    void acknowledgedFieldChangeActivatesSuccessfully() {
        RoutingConfigVersion active = activeVersion(validConfigJson());
        setId(active, 1L);
        RoutingConfigVersion draft = draftVersion(2, fieldChangedConfigJson());
        draft.recordDryRun(Instant.now(), true);
        when(repository.findByVersion(2)).thenReturn(java.util.Optional.of(draft));
        when(repository.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of(active));
        when(repository.save(draft)).thenReturn(draft);

        configService.approveMaterialChange(2L, "reviewer");
        RoutingConfigVersion activated = configService.activate(
                2L, "admin", List.of("heavy-department")
        );

        assertEquals(ConfigVersionStatus.ACTIVE, activated.getStatus());
        assertEquals(ConfigVersionStatus.ARCHIVED, active.getStatus());
        verify(repository).saveAll(List.of(active));
        verify(repository).flush();
    }

    @Test
    void acknowledgmentForDifferentRuleDoesNotAllowFieldChange() {
        RoutingConfigVersion active = activeVersion(validConfigJson());
        setId(active, 1L);
        RoutingConfigVersion draft = draftVersion(2, fieldChangedConfigJson());
        draft.recordDryRun(Instant.now(), true);
        when(repository.findByVersion(2)).thenReturn(java.util.Optional.of(draft));
        when(repository.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of(active));

        UnacknowledgedRuleChangeException exception = assertThrows(
                UnacknowledgedRuleChangeException.class,
                () -> configService.activate(2L, "admin", List.of("regular-department"))
        );

        assertTrue(exception.getMessage().contains("heavy-department"));
        verify(repository, never()).saveAll(any());
    }

    @Test
    void firstActivationSkipsSemanticAcknowledgmentCheck() {
        RoutingConfigVersion draft = draftVersion(1, fieldChangedConfigJson());
        draft.recordDryRun(Instant.now(), true);
        when(repository.findByVersion(1)).thenReturn(java.util.Optional.of(draft));
        when(repository.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of());
        when(repository.save(draft)).thenReturn(draft);

        RoutingConfigVersion activated = configService.activate(1L, "admin", List.of());

        assertEquals(ConfigVersionStatus.ACTIVE, activated.getStatus());
        verify(repository).saveAll(List.of());
        verify(repository).flush();
    }

    @Test
    void thresholdChangeDoesNotRequireAcknowledgment() {
        RoutingConfigVersion active = activeVersion(validConfigJson());
        setId(active, 1L);
        RoutingConfigVersion draft = draftVersion(2, thresholdChangedConfigJson());
        draft.recordDryRun(Instant.now(), true);
        when(repository.findByVersion(2)).thenReturn(java.util.Optional.of(draft));
        when(repository.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of(active));
        when(repository.save(draft)).thenReturn(draft);

        configService.approveMaterialChange(2L, "reviewer");
        RoutingConfigVersion activated = configService.activate(2L, "admin");

        assertEquals(ConfigVersionStatus.ACTIVE, activated.getStatus());
        assertEquals(ConfigVersionStatus.ARCHIVED, active.getStatus());
    }

    @Test
    void lowMaterialityChangeActivatesWithoutApproval() {
        RoutingConfigVersion active = activeVersion(validConfigJson());
        setId(active, 1L);
        RoutingConfigVersion draft = draftVersion(2, validConfigJson().replace(
                "\"requiredAboveValueEur\": 1000", "\"requiredAboveValueEur\": 1010"));
        draft.recordDryRun(Instant.now(), true);
        when(repository.findByVersion(2)).thenReturn(java.util.Optional.of(draft));
        when(repository.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of(active));
        when(repository.save(draft)).thenReturn(draft);

        RoutingConfigVersion activated = configService.activate(2L, "admin");

        assertEquals(ConfigVersionStatus.ACTIVE, activated.getStatus());
        assertEquals(ConfigVersionStatus.ARCHIVED, active.getStatus());
    }

    @Test
    void highMaterialityChangeWithoutApprovalIsBlockedWithPercentage() {
        RoutingConfigVersion active = activeVersion(validConfigJson());
        setId(active, 1L);
        RoutingConfigVersion draft = draftVersion(2, alternateConfigJson());
        draft.recordDryRun(Instant.now(), true);
        when(repository.findByVersion(2)).thenReturn(java.util.Optional.of(draft));
        when(repository.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of(active));

        MaterialChangeApprovalException exception = assertThrows(
                MaterialChangeApprovalException.class,
                () -> configService.activate(2L, "admin")
        );

        assertTrue(exception.getMessage().contains("materiality is"));
        assertTrue(exception.getMessage().contains("threshold"));
    }

    @Test
    void materialChangeApprovalByCreatorIsRejected() {
        RoutingConfigVersion draft = draftVersion(2, alternateConfigJson());
        when(repository.findByVersion(2)).thenReturn(java.util.Optional.of(draft));

        MaterialChangeApprovalException exception = assertThrows(
                MaterialChangeApprovalException.class,
                () -> configService.approveMaterialChange(2L, "admin")
        );

        assertTrue(exception.getMessage().contains("different admin"));
    }

    @Test
    void staleMaterialChangeApprovalDoesNotSatisfyGate() {
        RoutingConfigVersion active = activeVersion(validConfigJson());
        setId(active, 1L);
        RoutingConfigVersion draft = draftVersion(2, validConfigJson());
        draft.recordDryRun(Instant.now(), true);
        when(repository.findByVersion(2)).thenReturn(java.util.Optional.of(draft));
        when(repository.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of(active));
        when(repository.save(draft)).thenReturn(draft);

        configService.approveMaterialChange(2L, "reviewer");
        setRulesJson(draft, alternateConfigJson());
        draft.recordDryRun(Instant.now(), true);

        MaterialChangeApprovalException exception = assertThrows(
                MaterialChangeApprovalException.class,
                () -> configService.activate(2L, "admin")
        );

        assertTrue(exception.getMessage().contains("materiality is"));
    }

    @Test
    void activationWithoutSuccessfulDryRunOrWithFailedDryRunLeavesActiveUnchanged() {
        RoutingConfigVersion active = activeVersion(validConfigJson());
        RoutingConfigVersion draft = draftVersion(2, validConfigJson());
        when(repository.findByVersion(2)).thenReturn(java.util.Optional.of(draft));

        assertThrows(ConfigVersionActivationException.class, () -> configService.activate(2L, "admin"));
        assertEquals(ConfigVersionStatus.ACTIVE, active.getStatus());

        when(dryRunSimulator.simulate(any(RoutingConfig.class)))
                .thenReturn(new DryRunSimulator.DryRunResult(8, 7, 1, List.of()));
        configService.dryRun(2);

        assertThrows(ConfigVersionActivationException.class, () -> configService.activate(2L, "admin"));
        assertEquals(ConfigVersionStatus.ACTIVE, active.getStatus());
        assertEquals(ConfigVersionStatus.DRAFT, draft.getStatus());
    }

    @Test
    void activationRejectsMissingAndNonDraftVersions() {
        when(repository.findByVersion(99)).thenReturn(java.util.Optional.empty());
        assertThrows(ConfigVersionNotFoundException.class, () -> configService.activate(99L, "admin"));

        when(repository.findByVersion(1)).thenReturn(java.util.Optional.of(activeVersion(validConfigJson())));
        assertThrows(ConfigVersionStateException.class, () -> configService.activate(1L, "admin"));
        verify(repository, never()).findByStatus(ConfigVersionStatus.ACTIVE);
    }

    @Test
    void historyReturnsVersionsNewestFirstWithPredecessorRelationship() {
        RoutingConfigVersion newest = draftVersion(3, validConfigJson());
        RoutingConfigVersion active = activeVersion(validConfigJson());
        when(repository.findAllByOrderByVersionDesc()).thenReturn(List.of(newest, active));

        List<ConfigService.ConfigHistoryEntry> history = configService.history();

        assertEquals(List.of(3, 1), history.stream().map(ConfigService.ConfigHistoryEntry::version).toList());
        assertEquals(ConfigVersionStatus.DRAFT, history.getFirst().status());
        assertEquals("admin", history.getFirst().createdBy());
        assertEquals(newest.getBasedOnVersionId(), history.getFirst().predecessorVersionId());
    }

    @Test
    void rollbackCreatesAndActivatesNewVersionWithoutChangingArchivedTargetOrParcelSnapshot() {
        RoutingConfigVersion target = archivedVersion(2, alternateConfigJson());
        setId(target, 22L);
        RoutingConfigVersion currentActive = activeVersion(validConfigJson());
        ParcelEntity existingParcel = new ParcelEntity(
                2, 200, "DE", "{}", ParcelStatus.ROUTED, "Regular", "Regular", "regular-department",
                1L, Instant.now(), null, null
        );
        DryRunSimulator.DryRunResult passed = new DryRunSimulator.DryRunResult(8, 8, 0, List.of());
        when(repository.findByVersion(2)).thenReturn(java.util.Optional.of(target));
        when(repository.findTopByOrderByVersionDesc()).thenReturn(java.util.Optional.of(target));
        when(repository.findByStatus(ConfigVersionStatus.ACTIVE)).thenReturn(List.of(currentActive));
        when(repository.save(any(RoutingConfigVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(dryRunSimulator.simulate(any(RoutingConfig.class))).thenReturn(passed);

        RoutingConfigVersion rollback = configService.rollback(2L, "admin");

        assertEquals(3, rollback.getVersion());
        assertEquals(ConfigVersionStatus.ACTIVE, rollback.getStatus());
        assertEquals(alternateConfigJson(), rollback.getRulesJson());
        assertEquals(22L, rollback.getBasedOnVersionId());
        assertEquals("admin", rollback.getActivatedBy());
        assertEquals(ConfigVersionStatus.ARCHIVED, target.getStatus());
        assertEquals(alternateConfigJson(), target.getRulesJson());
        assertEquals(ConfigVersionStatus.ARCHIVED, currentActive.getStatus());
        assertEquals(1L, existingParcel.getRoutingConfigVersionId());
        assertEquals(1, List.of(target, currentActive, rollback).stream()
                .filter(configuration -> configuration.getStatus() == ConfigVersionStatus.ACTIVE).count());
        verify(repository).saveAll(List.of(currentActive));
        verify(repository).flush();
    }

    @Test
    void failedRollbackLeavesCurrentActiveConfigurationUnchanged() {
        RoutingConfigVersion target = archivedVersion(2, validConfigJson());
        RoutingConfigVersion currentActive = activeVersion(alternateConfigJson());
        when(repository.findByVersion(2)).thenReturn(java.util.Optional.of(target));
        when(repository.findTopByOrderByVersionDesc()).thenReturn(java.util.Optional.of(currentActive));
        when(dryRunSimulator.simulate(any(RoutingConfig.class)))
                .thenReturn(new DryRunSimulator.DryRunResult(8, 7, 1, List.of()));

        assertThrows(ConfigVersionActivationException.class, () -> configService.rollback(2L, "admin"));

        assertEquals(ConfigVersionStatus.ACTIVE, currentActive.getStatus());
        assertEquals(ConfigVersionStatus.ARCHIVED, target.getStatus());
        verify(repository, never()).findByStatus(ConfigVersionStatus.ACTIVE);
    }

    @Test
    void rollbackRejectsMissingAndNonArchivedTargets() {
        when(repository.findByVersion(99)).thenReturn(java.util.Optional.empty());
        assertThrows(ConfigVersionNotFoundException.class, () -> configService.rollback(99L, "admin"));

        when(repository.findByVersion(1)).thenReturn(java.util.Optional.of(activeVersion(validConfigJson())));
        assertThrows(ConfigVersionRollbackException.class, () -> configService.rollback(1L, "admin"));
    }

    private RoutingConfigVersion activeVersion(String rulesJson) {
        return new RoutingConfigVersion(
                1,
                ConfigVersionStatus.ACTIVE,
                rulesJson,
                "SYSTEM",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                null
        );
    }

    private String validConfigJson() {
        return """
                {
                  "insurance": {
                    "requiredAboveValueEur": 1000
                  },
                  "rules": [
                    {
                      "id": "heavy-department",
                      "priority": 10,
                      "condition": {
                        "field": "weight_kg",
                        "operator": "GT",
                        "value": 10
                      },
                      "department": "Heavy"
                    },
                    {
                      "id": "regular-department",
                      "priority": 20,
                      "condition": {
                        "field": "weight_kg",
                        "operator": "GT",
                        "value": 1
                      },
                      "department": "Regular"
                    },
                    {
                      "id": "mail-department",
                      "priority": 30,
                      "condition": {
                        "field": "weight_kg",
                        "operator": "LTE",
                        "value": 1
                      },
                      "department": "Mail"
                    }
                  ]
                }
                """;
    }

    private RoutingConfig validConfig() {
        return new RoutingConfig(1000, List.of(
                new com.parcelrouting.routing.Rule(
                        "heavy-department", 10,
                        new com.parcelrouting.routing.Condition("weight_kg", ComparisonOperator.GT, 10),
                        "Heavy"
                )
        ));
    }

    private RoutingConfigVersion draftVersion(int version, String rulesJson) {
        return new RoutingConfigVersion(
                version, ConfigVersionStatus.DRAFT, rulesJson, "admin", Instant.now(), null, 1L
        );
    }

    private RoutingConfigVersion archivedVersion(int version, String rulesJson) {
        return new RoutingConfigVersion(
                version, ConfigVersionStatus.ARCHIVED, rulesJson, "admin", Instant.now(), Instant.now(), 1L
        );
    }

    private void setId(RoutingConfigVersion version, Long id) {
        try {
            java.lang.reflect.Field field = RoutingConfigVersion.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(version, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private void setRulesJson(RoutingConfigVersion version, String rulesJson) {
        try {
            java.lang.reflect.Field field = RoutingConfigVersion.class.getDeclaredField("rulesJson");
            field.setAccessible(true);
            field.set(version, rulesJson);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private String alternateConfigJson() {
        return validConfigJson().replace("\"requiredAboveValueEur\": 1000", "\"requiredAboveValueEur\": 1500");
    }

    private String fieldChangedConfigJson() {
        return validConfigJson().replaceFirst("\"field\": \"weight_kg\"", "\"field\": \"destination_country\"")
                .replaceFirst("\"operator\": \"GT\"", "\"operator\": \"EQ\"")
                .replaceFirst("\"value\": 10", "\"value\": \"DE\"");
    }

    private String thresholdChangedConfigJson() {
        return validConfigJson().replaceFirst("\"value\": 10", "\"value\": 20");
    }
}
