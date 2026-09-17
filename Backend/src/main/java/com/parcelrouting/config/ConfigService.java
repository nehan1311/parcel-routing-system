package com.parcelrouting.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parcelrouting.routing.ComparisonOperator;
import com.parcelrouting.routing.Condition;
import com.parcelrouting.routing.DryRunSimulator;
import com.parcelrouting.routing.RoutingConfig;
import com.parcelrouting.routing.Rule;
import com.parcelrouting.routing.ConfigSemanticDiffer;
import com.parcelrouting.routing.BoundarySimulator;
import com.parcelrouting.routing.RuleSetAnalyzer;
import com.parcelrouting.parcel.ParcelRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;

@Service
public class ConfigService {

    private final RoutingConfigVersionRepository routingConfigVersionRepository;
    private final ObjectMapper objectMapper;
    private final ConfigValidator configValidator;
    private final DryRunSimulator dryRunSimulator;
    private final ParcelRepository parcelRepository;
    private final int historicalImpactLimit;
    private final ConfigSemanticDiffer semanticDiffer = new ConfigSemanticDiffer();
    private final RuleSetAnalyzer ruleSetAnalyzer = new RuleSetAnalyzer();
    private final BoundarySimulator boundarySimulator = new BoundarySimulator(
            0, 50, 0.5,
            0, 5_000, 25
    );

    public ConfigService(
            RoutingConfigVersionRepository routingConfigVersionRepository,
            ObjectMapper objectMapper,
            ConfigValidator configValidator,
            DryRunSimulator dryRunSimulator,
            ParcelRepository parcelRepository,
            @Value("${parcel.config.historical-impact-limit:100}") int historicalImpactLimit
    ) {
        this.routingConfigVersionRepository = routingConfigVersionRepository;
        this.objectMapper = objectMapper;
        this.configValidator = configValidator;
        this.dryRunSimulator = dryRunSimulator;
        this.parcelRepository = parcelRepository;
        if (historicalImpactLimit < 1) {
            throw new IllegalArgumentException("Historical impact limit must be at least 1");
        }
        this.historicalImpactLimit = historicalImpactLimit;
    }

    @Transactional
    public RoutingConfigVersion createDraft(RoutingConfig routingConfig, String createdBy) {
        return createDraft(routingConfig, createdBy, null);
    }

    @Transactional
    public RoutingConfigVersion createDraft(RoutingConfig routingConfig, String createdBy, String reason) {
        configValidator.validate(routingConfig);
        if (createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("Draft creator must not be blank");
        }
        if (reason != null && reason.length() > 500) {
            throw new IllegalArgumentException("Draft reason must not exceed 500 characters");
        }

        Optional<RoutingConfigVersion> latest = routingConfigVersionRepository.findTopByOrderByVersionDesc();
        int nextVersion = latest.map(version -> version.getVersion() + 1).orElse(1);
        Long basedOnVersionId = latest.map(RoutingConfigVersion::getId).orElse(null);

        RoutingConfigVersion draft = new RoutingConfigVersion(
                nextVersion,
                ConfigVersionStatus.DRAFT,
                serializeRoutingConfig(routingConfig),
                createdBy,
                Instant.now(),
                null,
                basedOnVersionId,
                reason
        );
        return routingConfigVersionRepository.save(draft);
    }

    @Transactional(readOnly = true)
    public DraftValidationResult validateDraft(int version) {
        RoutingConfigVersion draft = routingConfigVersionRepository.findByVersion(version)
                .orElseThrow(() -> new ConfigVersionNotFoundException(version));
        if (draft.getStatus() != ConfigVersionStatus.DRAFT) {
            throw new ConfigVersionStateException(version);
        }

        try {
            RoutingConfig routingConfig = parseRoutingConfig(draft.getRulesJson());
            configValidator.validate(routingConfig);
            List<RuleSetAnalyzer.Warning> warnings = ruleSetAnalyzer.analyze(routingConfig);
            return new DraftValidationResult(version, true, List.of(), warnings);
        } catch (IllegalStateException exception) {
            throw new IllegalArgumentException("Draft configuration is invalid: " + exception.getMessage(), exception);
        }
    }

    @Transactional
    public DryRunSimulator.DryRunResult dryRun(int version) {
        RoutingConfigVersion draft = routingConfigVersionRepository.findByVersion(version)
                .orElseThrow(() -> new ConfigVersionNotFoundException(version));
        if (draft.getStatus() != ConfigVersionStatus.DRAFT) {
            throw new ConfigVersionStateException(version);
        }

        RoutingConfig configuration = parseRoutingConfig(draft.getRulesJson());
        configValidator.validate(configuration);
        DryRunSimulator.DryRunResult regressionResult = dryRunSimulator.simulate(configuration);
        Page<com.parcelrouting.parcel.ParcelEntity> historicalPage =
                parcelRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, historicalImpactLimit));
        DryRunSimulator.HistoricalImpact historicalImpact = dryRunSimulator.analyzeHistorical(
                configuration, historicalPage == null ? List.of() : historicalPage.getContent()
        );
        if (historicalImpact == null) {
            historicalImpact = new DryRunSimulator.HistoricalImpact(0, 0, 0, 0, List.of(), List.of());
        }
        List<ConfigSemanticDiffer.RuleDiff> semanticDiff = List.of();
        BoundarySimulator.BoundarySimulationResult boundarySimulation = BoundarySimulator.empty();
        try {
            RoutingConfig activeConfiguration = getActiveConfigWithVersion().routingConfig();
            semanticDiff = semanticDiffer.diff(activeConfiguration, configuration);
            boundarySimulation = boundarySimulator.simulate(activeConfiguration, configuration);
        } catch (IllegalStateException exception) {
            if (!"No active routing configuration exists".equals(exception.getMessage())) {
                throw exception;
            }
        }
        DryRunSimulator.DryRunResult result = new DryRunSimulator.DryRunResult(
                regressionResult.totalCases(), regressionResult.passedCases(), regressionResult.failedCases(),
                regressionResult.failures(), historicalImpact, semanticDiff, boundarySimulation
        );
        draft.recordDryRun(Instant.now(), result.failedCases() == 0);
        routingConfigVersionRepository.save(draft);
        return result;
    }

    @Transactional
    public RoutingConfigVersion activate(Long version, String activatedBy) {
        return activate(version, activatedBy, List.of());
    }

    @Transactional
    public RoutingConfigVersion activate(Long version, String activatedBy, List<String> acknowledgedRuleChanges) {
        if (version == null || version > Integer.MAX_VALUE || version < Integer.MIN_VALUE) {
            throw new IllegalArgumentException("Routing configuration version must be a valid integer");
        }
        if (activatedBy == null || activatedBy.isBlank()) {
            throw new IllegalArgumentException("Configuration activator must not be blank");
        }

        RoutingConfigVersion draft = routingConfigVersionRepository.findByVersion(version.intValue())
                .orElseThrow(() -> new ConfigVersionNotFoundException(version.intValue()));
        return activateDraftWithAcknowledgment(draft, activatedBy, acknowledgedRuleChanges);
    }

    @Transactional(readOnly = true)
    public List<ConfigHistoryEntry> history() {
        return routingConfigVersionRepository.findAllByOrderByVersionDesc().stream()
                .map(version -> new ConfigHistoryEntry(
                        version.getVersion(),
                        version.getStatus(),
                        version.getCreatedBy(),
                        version.getCreatedAt(),
                        version.getActivatedBy(),
                        version.getActivatedAt(),
                        version.getBasedOnVersionId(),
                        version.getReason()
                ))
                .toList();
    }

    @Transactional
    public RoutingConfigVersion rollback(Long version, String activatedBy) {
        if (version == null || version > Integer.MAX_VALUE || version < Integer.MIN_VALUE) {
            throw new IllegalArgumentException("Routing configuration version must be a valid integer");
        }
        if (activatedBy == null || activatedBy.isBlank()) {
            throw new IllegalArgumentException("Configuration activator must not be blank");
        }

        int targetVersionNumber = version.intValue();
        RoutingConfigVersion target = routingConfigVersionRepository.findByVersion(targetVersionNumber)
                .orElseThrow(() -> new ConfigVersionNotFoundException(targetVersionNumber));
        if (target.getStatus() != ConfigVersionStatus.ARCHIVED) {
            throw new ConfigVersionRollbackException(targetVersionNumber, "only archived versions may be rollback targets");
        }

        RoutingConfig targetConfiguration;
        try {
            targetConfiguration = parseRoutingConfig(target.getRulesJson());
            configValidator.validate(targetConfiguration);
        } catch (IllegalStateException | IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Rollback target configuration is invalid: " + exception.getMessage(), exception
            );
        }

        Optional<RoutingConfigVersion> latest = routingConfigVersionRepository.findTopByOrderByVersionDesc();
        RoutingConfigVersion rollbackDraft = new RoutingConfigVersion(
                latest.map(existing -> existing.getVersion() + 1).orElse(1),
                ConfigVersionStatus.DRAFT,
                target.getRulesJson(),
                activatedBy,
                Instant.now(),
                null,
                target.getId(),
                target.getReason()
        );
        DryRunSimulator.DryRunResult dryRunResult = dryRunSimulator.simulate(targetConfiguration);
        rollbackDraft.recordDryRun(Instant.now(), dryRunResult.failedCases() == 0);

        return activateDraft(rollbackDraft, activatedBy);
    }

    private RoutingConfigVersion activateDraftWithAcknowledgment(
            RoutingConfigVersion draft, String activatedBy, List<String> acknowledgedRuleChanges
    ) {
        if (draft.getStatus() != ConfigVersionStatus.DRAFT) {
            throw new ConfigVersionStateException(draft.getVersion());
        }
        if (!draft.hasSuccessfulDryRunForCurrentConfiguration()) {
            throw new ConfigVersionActivationException(
                    draft.getVersion(), "a successful dry-run for the current configuration is required"
            );
        }

        Set<String> acknowledged = acknowledgedRuleChanges == null
                ? Set.of()
                : new HashSet<>(acknowledgedRuleChanges);
        List<String> requiredAcknowledgements = findUnacknowledgedRuleChanges(draft, acknowledged);
        if (!requiredAcknowledgements.isEmpty()) {
            throw new UnacknowledgedRuleChangeException(draft.getVersion(), requiredAcknowledgements);
        }

        return activateDraftAfterChecks(draft, activatedBy);
    }

    private RoutingConfigVersion activateDraft(RoutingConfigVersion draft, String activatedBy) {
        if (draft.getStatus() != ConfigVersionStatus.DRAFT) {
            throw new ConfigVersionStateException(draft.getVersion());
        }
        if (!draft.hasSuccessfulDryRunForCurrentConfiguration()) {
            throw new ConfigVersionActivationException(
                    draft.getVersion(), "a successful dry-run for the current configuration is required"
            );
        }

        return activateDraftAfterChecks(draft, activatedBy);
    }

    private RoutingConfigVersion activateDraftAfterChecks(RoutingConfigVersion draft, String activatedBy) {
        List<RoutingConfigVersion> activeVersions = routingConfigVersionRepository.findByStatus(ConfigVersionStatus.ACTIVE);
        for (RoutingConfigVersion activeVersion : activeVersions) {
            activeVersion.archive();
        }
        routingConfigVersionRepository.saveAll(activeVersions);
        routingConfigVersionRepository.flush();

        draft.activate(activatedBy, Instant.now());
        return routingConfigVersionRepository.save(draft);
    }

    private List<String> findUnacknowledgedRuleChanges(
            RoutingConfigVersion draft, Set<String> acknowledgedRuleChanges
    ) {
        RoutingConfig activeConfiguration;
        try {
            activeConfiguration = getActiveConfigWithVersion().routingConfig();
        } catch (IllegalStateException exception) {
            if ("No active routing configuration exists".equals(exception.getMessage())) {
                return List.of();
            }
            throw exception;
        }

        RoutingConfig draftConfiguration = parseRoutingConfig(draft.getRulesJson());
        return semanticDiffer.diff(activeConfiguration, draftConfiguration).stream()
                .filter(diff -> diff.changeType() == ConfigSemanticDiffer.ChangeType.FIELD_CHANGE
                        || diff.changeType() == ConfigSemanticDiffer.ChangeType.OPERATOR_CHANGE)
                .map(ConfigSemanticDiffer.RuleDiff::ruleId)
                .filter(ruleId -> !acknowledgedRuleChanges.contains(ruleId))
                .sorted()
                .distinct()
                .collect(Collectors.toList());
    }

    public RoutingConfig getActiveConfig() {
        return parseRoutingConfig(loadActiveVersion().getRulesJson());
    }

    public ActiveRoutingConfig getActiveConfigWithVersion() {
        RoutingConfigVersion activeVersion = loadActiveVersion();
        if (activeVersion.getId() == null) {
            throw new IllegalStateException("Active routing configuration has no database ID");
        }

        return new ActiveRoutingConfig(
                activeVersion.getId(),
                activeVersion.getVersion(),
                parseRoutingConfig(activeVersion.getRulesJson()),
                activeVersion.getReason()
        );
    }

    private RoutingConfigVersion loadActiveVersion() {
        List<RoutingConfigVersion> activeVersions =
                routingConfigVersionRepository.findByStatus(ConfigVersionStatus.ACTIVE);

        if (activeVersions.isEmpty()) {
            throw new IllegalStateException("No active routing configuration exists");
        }

        return activeVersions.get(0);
    }

    private RoutingConfig parseRoutingConfig(String rulesJson) {
        if (rulesJson == null) {
            throw new IllegalStateException("Active routing configuration has no rules JSON");
        }

        try {
            JsonNode root = objectMapper.readTree(rulesJson);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("Routing configuration JSON must be an object");
            }

            JsonNode insurance = requiredObject(root, "insurance");
            int insuranceThresholdEur = requiredInt(insurance, "requiredAboveValueEur");
            JsonNode rules = requiredArray(root, "rules");

            List<Rule> parsedRules = new ArrayList<>();
            for (JsonNode ruleNode : rules) {
                parsedRules.add(parseRule(ruleNode));
            }

            return new RoutingConfig(insuranceThresholdEur, parsedRules);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Malformed routing configuration JSON", exception);
        }
    }

    private String serializeRoutingConfig(RoutingConfig routingConfig) {
        try {
            return objectMapper.writeValueAsString(new RoutingConfigSnapshot(
                    new InsuranceSnapshot(routingConfig.insuranceThresholdEur()),
                    routingConfig.rules()
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Routing configuration could not be serialized", exception);
        }
    }

    private record RoutingConfigSnapshot(InsuranceSnapshot insurance, List<Rule> rules) {
    }

    private record InsuranceSnapshot(int requiredAboveValueEur) {
    }

    public record DraftValidationResult(
            int version, boolean valid, List<String> errors, List<RuleSetAnalyzer.Warning> warnings
    ) {
        public DraftValidationResult(int version, boolean valid, List<String> errors) {
            this(version, valid, errors, List.of());
        }
    }

    public record ConfigHistoryEntry(
            int version,
            ConfigVersionStatus status,
            String createdBy,
            Instant createdAt,
            String activatedBy,
            Instant activatedAt,
            Long predecessorVersionId,
            String reason
    ) {
        public ConfigHistoryEntry(
                int version,
                ConfigVersionStatus status,
                String createdBy,
                Instant createdAt,
                String activatedBy,
                Instant activatedAt,
                Long predecessorVersionId
        ) {
            this(version, status, createdBy, createdAt, activatedBy, activatedAt, predecessorVersionId, null);
        }
    }

    private Rule parseRule(JsonNode ruleNode) throws JsonProcessingException {
        if (!ruleNode.isObject()) {
            throw new IllegalStateException("Routing rule must be an object");
        }

        JsonNode condition = requiredObject(ruleNode, "condition");
        return new Rule(
                requiredText(ruleNode, "id"),
                requiredInt(ruleNode, "priority"),
                new Condition(
                        requiredText(condition, "field"),
                        parseOperator(condition),
                        requiredValue(condition, "value")
                ),
                requiredText(ruleNode, "department")
        );
    }

    private ComparisonOperator parseOperator(JsonNode condition) {
        String operator = requiredText(condition, "operator");
        try {
            return ComparisonOperator.valueOf(operator);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unknown routing comparison operator: " + operator, exception);
        }
    }

    private Object requiredValue(JsonNode node, String fieldName) throws JsonProcessingException {
        JsonNode value = node.get(fieldName);
        if (value == null) {
            throw new IllegalStateException("Missing routing configuration field: " + fieldName);
        }
        return objectMapper.treeToValue(value, Object.class);
    }

    private JsonNode requiredObject(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isObject()) {
            throw new IllegalStateException("Missing or invalid routing configuration object: " + fieldName);
        }
        return value;
    }

    private JsonNode requiredArray(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isArray()) {
            throw new IllegalStateException("Missing or invalid routing configuration array: " + fieldName);
        }
        return value;
    }

    private String requiredText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("Missing or invalid routing configuration text field: " + fieldName);
        }
        return value.asText();
    }

    private int requiredInt(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalStateException("Missing or invalid routing configuration integer field: " + fieldName);
        }
        return value.intValue();
    }
}
