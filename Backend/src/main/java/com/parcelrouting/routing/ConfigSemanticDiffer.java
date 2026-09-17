package com.parcelrouting.routing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure comparison of two routing configurations; it performs no validation or persistence. */
public final class ConfigSemanticDiffer {

    private static final Set<String> NUMERIC_FIELDS = Set.of("weight_kg", "value_eur");

    public List<RuleDiff> diff(RoutingConfig active, RoutingConfig draft) {
        Objects.requireNonNull(active, "Active routing configuration must not be null");
        Objects.requireNonNull(draft, "Draft routing configuration must not be null");

        Map<String, Rule> activeRules = indexRules(active.rules(), "active");
        Map<String, Rule> draftRules = indexRules(draft.rules(), "draft");
        Set<String> ids = new HashSet<>();
        ids.addAll(activeRules.keySet());
        ids.addAll(draftRules.keySet());

        return ids.stream()
                .sorted()
                .map(id -> classify(id, activeRules.get(id), draftRules.get(id)))
                .toList();
    }

    public List<RuleDiff> compare(RoutingConfig active, RoutingConfig draft) {
        return diff(active, draft);
    }

    private RuleDiff classify(String id, Rule active, Rule draft) {
        if (active == null) {
            return new RuleDiff(id, ChangeType.NEW_RULE, Severity.NORMAL, false, null, draft);
        }
        if (draft == null) {
            return new RuleDiff(id, ChangeType.REMOVED_RULE, Severity.NORMAL, false, active, null);
        }

        String activeField = active.condition() == null ? null : active.condition().field();
        String draftField = draft.condition() == null ? null : draft.condition().field();
        ComparisonOperator activeOperator = active.condition() == null ? null : active.condition().operator();
        ComparisonOperator draftOperator = draft.condition() == null ? null : draft.condition().operator();
        Object activeValue = active.condition() == null ? null : active.condition().value();
        Object draftValue = draft.condition() == null ? null : draft.condition().value();
        boolean crossesBoundary = !Objects.equals(activeField, draftField)
                && isNumeric(activeField) != isNumeric(draftField);

        ChangeType type;
        if (!Objects.equals(activeField, draftField)) type = ChangeType.FIELD_CHANGE;
        else if (!Objects.equals(activeOperator, draftOperator)) type = ChangeType.OPERATOR_CHANGE;
        else if (!Objects.equals(activeValue, draftValue)) type = ChangeType.THRESHOLD_CHANGE;
        else if (active.priority() != draft.priority()) type = ChangeType.PRIORITY_CHANGE;
        else if (!Objects.equals(active.department(), draft.department())) type = ChangeType.DEPARTMENT_CHANGE;
        else type = ChangeType.NO_CHANGE;

        Severity severity = type == ChangeType.FIELD_CHANGE && crossesBoundary
                ? Severity.HIGH_ATTENTION : Severity.NORMAL;
        return new RuleDiff(id, type, severity, crossesBoundary, active, draft);
    }

    private Map<String, Rule> indexRules(List<Rule> rules, String side) {
        Map<String, Rule> indexed = new HashMap<>();
        if (rules == null) return indexed;
        for (Rule rule : rules) {
            if (rule == null || rule.id() == null) {
                throw new IllegalArgumentException("Cannot compare " + side + " configuration with a null rule or rule ID");
            }
            if (indexed.put(rule.id(), rule) != null) {
                throw new IllegalArgumentException("Cannot compare " + side + " configuration with duplicate rule ID: " + rule.id());
            }
        }
        return indexed;
    }

    private boolean isNumeric(String field) {
        return NUMERIC_FIELDS.contains(field);
    }

    public enum ChangeType {
        NO_CHANGE,
        THRESHOLD_CHANGE,
        OPERATOR_CHANGE,
        FIELD_CHANGE,
        PRIORITY_CHANGE,
        DEPARTMENT_CHANGE,
        NEW_RULE,
        REMOVED_RULE
    }

    public enum Severity {
        NORMAL,
        HIGH_ATTENTION
    }

    public record RuleDiff(
            String ruleId,
            ChangeType changeType,
            Severity severity,
            boolean crossesNumericTextBoundary,
            Rule activeRule,
            Rule draftRule
    ) {
    }
}
