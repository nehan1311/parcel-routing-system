package com.parcelrouting.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Pure, non-blocking analysis of numeric rule reachability, overlap, and coverage. */
public final class RuleSetAnalyzer {

    private final double minWeightKg;
    private final double maxWeightKg;
    private final double minValueEur;
    private final double maxValueEur;

    public RuleSetAnalyzer() {
        this(0, 1000, 0, 100000);
    }

    public RuleSetAnalyzer(double minWeightKg, double maxWeightKg, double minValueEur, double maxValueEur) {
        if (!Double.isFinite(minWeightKg) || !Double.isFinite(maxWeightKg) || minWeightKg >= maxWeightKg
                || !Double.isFinite(minValueEur) || !Double.isFinite(maxValueEur) || minValueEur >= maxValueEur) {
            throw new IllegalArgumentException("Numeric analysis bounds must be finite and increasing");
        }
        this.minWeightKg = minWeightKg;
        this.maxWeightKg = maxWeightKg;
        this.minValueEur = minValueEur;
        this.maxValueEur = maxValueEur;
    }

    public List<Warning> analyze(com.parcelrouting.routing.RoutingConfig configuration) {
        Objects.requireNonNull(configuration, "Routing configuration must not be null");
        List<com.parcelrouting.routing.Rule> rules = configuration.rules() == null ? List.of() : configuration.rules();
        List<Warning> warnings = new ArrayList<>();

        for (com.parcelrouting.routing.Rule lowerPriority : rules) {
            for (com.parcelrouting.routing.Rule higherPriority : rules) {
                if (higherPriority.priority() >= lowerPriority.priority()) continue;
                if (isStrictSuperset(higherPriority, lowerPriority)) {
                    warnings.add(new Warning(WarningType.UNREACHABLE_RULE, lowerPriority.id(), higherPriority.id(),
                            numericField(lowerPriority), "Rule is unreachable because a higher-priority rule covers it"));
                }
            }
        }

        for (int first = 0; first < rules.size(); first++) {
            for (int second = first + 1; second < rules.size(); second++) {
                com.parcelrouting.routing.Rule left = rules.get(first);
                com.parcelrouting.routing.Rule right = rules.get(second);
                if (left.priority() == right.priority() || !hasPartialNumericOverlap(left, right)) continue;
                Rule higher = left.priority() < right.priority() ? left : right;
                Rule lower = higher == left ? right : left;
                warnings.add(new Warning(WarningType.OVERLAP, higher.id(), lower.id(), numericField(higher),
                        "Numeric conditions overlap; the higher-priority rule applies first"));
            }
        }

        for (String field : List.of("weight_kg", "value_eur")) {
            List<Interval> coverage = rules.stream()
                    .filter(rule -> field.equals(numericField(rule)))
                    .flatMap(rule -> intervals(rule, field).stream())
                    .toList();
            if (!coverage.isEmpty() && !coversDomain(coverage, min(field), max(field))) {
                warnings.add(new Warning(WarningType.GAP, null, null, field,
                        "No numeric rule matches part of the configured analysis range"));
            }
        }
        return List.copyOf(warnings);
    }

    private boolean isStrictSuperset(com.parcelrouting.routing.Rule higher, com.parcelrouting.routing.Rule lower) {
        String field = numericField(higher);
        if (!Objects.equals(field, numericField(lower)) || field == null) return false;
        List<Interval> high = intervals(higher, field);
        List<Interval> low = intervals(lower, field);
        return !low.isEmpty()
                && low.stream().allMatch(interval -> high.stream().anyMatch(container -> contains(container, interval)))
                && !sameCoverage(high, low);
    }

    private boolean hasPartialNumericOverlap(com.parcelrouting.routing.Rule left, com.parcelrouting.routing.Rule right) {
        String leftField = numericField(left);
        String rightField = numericField(right);
        if (leftField == null || rightField == null) return false;
        List<Interval> leftIntervals = intervals(left, leftField);
        List<Interval> rightIntervals = intervals(right, rightField);
        if (leftField.equals(rightField)) {
            boolean intersects = leftIntervals.stream().anyMatch(a -> rightIntervals.stream().anyMatch(b -> overlaps(a, b)));
            return intersects && !containsAll(leftIntervals, rightIntervals) && !containsAll(rightIntervals, leftIntervals);
        }
        return !leftIntervals.isEmpty() && !rightIntervals.isEmpty();
    }

    private List<Interval> intervals(com.parcelrouting.routing.Rule rule, String field) {
        Condition condition = rule.condition();
        if (condition == null || !field.equals(condition.field())) return List.of();
        Object value = condition.value();
        if (condition.operator() == ComparisonOperator.IN && value instanceof Collection<?> values) {
            return values.stream().filter(Number.class::isInstance)
                    .map(Number.class::cast).map(number -> number.doubleValue())
                    .map(point -> new Interval(point, point, true, true)).toList();
        }
        if (!(value instanceof Number number)) return List.of();
        double point = number.doubleValue();
        return switch (condition.operator()) {
            case GT -> List.of(new Interval(point, max(field), false, true));
            case GTE -> List.of(new Interval(point, max(field), true, true));
            case LT -> List.of(new Interval(min(field), point, true, false));
            case LTE -> List.of(new Interval(min(field), point, true, true));
            case EQ -> List.of(new Interval(point, point, true, true));
            case NEQ -> List.of(new Interval(min(field), point, true, false), new Interval(point, max(field), false, true));
            case IN -> List.of();
        };
    }

    private boolean coversDomain(List<Interval> source, double domainMin, double domainMax) {
        List<Interval> sorted = source.stream().map(interval -> clip(interval, domainMin, domainMax))
                .filter(Objects::nonNull).sorted(Comparator.comparingDouble(Interval::min)).toList();
        double cursor = domainMin;
        boolean cursorClosed = true;
        for (Interval interval : sorted) {
            if (interval.min() > cursor || (interval.min() == cursor && !cursorClosed && !interval.minClosed())) return false;
            if (interval.max() > cursor || (interval.max() == cursor && interval.maxClosed())) {
                cursor = interval.max();
                cursorClosed = interval.maxClosed();
            }
            if (cursor == domainMax && cursorClosed) return true;
        }
        return cursor == domainMax && cursorClosed;
    }

    private Interval clip(Interval interval, double domainMin, double domainMax) {
        double min = Math.max(interval.min(), domainMin);
        double max = Math.min(interval.max(), domainMax);
        if (min > max || (min == max && !(interval.minClosed() && interval.maxClosed()))) return null;
        return new Interval(min, max, min == interval.min() ? interval.minClosed() : true,
                max == interval.max() ? interval.maxClosed() : true);
    }

    private boolean sameCoverage(List<Interval> first, List<Interval> second) {
        return containsAll(first, second) && containsAll(second, first);
    }

    private boolean containsAll(List<Interval> containers, List<Interval> candidates) {
        return candidates.stream().allMatch(candidate -> containers.stream().anyMatch(container -> contains(container, candidate)));
    }

    private boolean contains(Interval outer, Interval inner) {
        return (outer.min() < inner.min() || outer.min() == inner.min() && (outer.minClosed() || !inner.minClosed()))
                && (outer.max() > inner.max() || outer.max() == inner.max() && (outer.maxClosed() || !inner.maxClosed()));
    }

    private boolean overlaps(Interval first, Interval second) {
        if (first.max() < second.min() || second.max() < first.min()) return false;
        if (first.max() == second.min()) return first.maxClosed() && second.minClosed();
        if (second.max() == first.min()) return second.maxClosed() && first.minClosed();
        return true;
    }

    private String numericField(com.parcelrouting.routing.Rule rule) {
        if (rule == null || rule.condition() == null) return null;
        String field = rule.condition().field();
        return "weight_kg".equals(field) || "value_eur".equals(field) ? field : null;
    }

    private double min(String field) { return "weight_kg".equals(field) ? minWeightKg : minValueEur; }
    private double max(String field) { return "weight_kg".equals(field) ? maxWeightKg : maxValueEur; }

    private record Interval(double min, double max, boolean minClosed, boolean maxClosed) { }

    public enum WarningType { UNREACHABLE_RULE, OVERLAP, GAP }

    public record Warning(WarningType type, String ruleId, String relatedRuleId, String field, String message) { }
}
