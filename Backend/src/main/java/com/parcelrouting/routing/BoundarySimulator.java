package com.parcelrouting.routing;

import com.parcelrouting.parcel.Parcel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pure comparison of routing decisions over a configurable numeric boundary grid. */
public final class BoundarySimulator {

    private static final int MAX_EVALUATIONS = 50_000;
    private static final double DEFAULT_MIN_WEIGHT = 0;
    private static final double DEFAULT_MAX_WEIGHT = 1000;
    private static final double DEFAULT_WEIGHT_STEP = 10;
    private static final double DEFAULT_MIN_VALUE = 0;
    private static final double DEFAULT_MAX_VALUE = 100_000;
    private static final double DEFAULT_VALUE_STEP = 1000;

    private final double minWeightKg;
    private final double maxWeightKg;
    private final double weightStep;
    private final double minValueEur;
    private final double maxValueEur;
    private final double valueStep;

    public BoundarySimulator() {
        this(DEFAULT_MIN_WEIGHT, DEFAULT_MAX_WEIGHT, DEFAULT_WEIGHT_STEP,
                DEFAULT_MIN_VALUE, DEFAULT_MAX_VALUE, DEFAULT_VALUE_STEP);
    }

    public BoundarySimulator(
            double minWeightKg, double maxWeightKg, double weightStep,
            double minValueEur, double maxValueEur, double valueStep
    ) {
        validateBounds(minWeightKg, maxWeightKg, weightStep, "weight");
        validateBounds(minValueEur, maxValueEur, valueStep, "value");
        long weightPoints = points(minWeightKg, maxWeightKg, weightStep);
        long valuePoints = points(minValueEur, maxValueEur, valueStep);
        if (weightPoints * valuePoints > MAX_EVALUATIONS) {
            throw new IllegalArgumentException("Boundary simulation grid must not exceed 50000 evaluations");
        }
        this.minWeightKg = minWeightKg;
        this.maxWeightKg = maxWeightKg;
        this.weightStep = weightStep;
        this.minValueEur = minValueEur;
        this.maxValueEur = maxValueEur;
        this.valueStep = valueStep;
    }

    public BoundarySimulationResult simulate(RoutingConfig active, RoutingConfig draft) {
        if (active == null || draft == null) return empty();

        int weightCount = (int) points(minWeightKg, maxWeightKg, weightStep);
        int valueCount = (int) points(minValueEur, maxValueEur, valueStep);
        boolean[][] changed = new boolean[weightCount][valueCount];
        int departmentChanges = 0;
        int insuranceChanges = 0;

        RoutingEngine engine = new RoutingEngine();
        for (int weightIndex = 0; weightIndex < weightCount; weightIndex++) {
            double weight = point(minWeightKg, weightStep, weightIndex);
            for (int valueIndex = 0; valueIndex < valueCount; valueIndex++) {
                double value = point(minValueEur, valueStep, valueIndex);
                Parcel parcel = new Parcel(weight, value, "DE", Map.of());
                RoutingDecision activeDecision = evaluate(engine, parcel, active);
                RoutingDecision draftDecision = evaluate(engine, parcel, draft);
                boolean departmentChanged = !java.util.Objects.equals(
                        department(activeDecision), department(draftDecision));
                boolean insuranceChanged = insuranceRequired(activeDecision) != insuranceRequired(draftDecision);
                changed[weightIndex][valueIndex] = departmentChanged || insuranceChanged;
                if (departmentChanged) departmentChanges++;
                if (insuranceChanged) insuranceChanges++;
            }
        }

        return new BoundarySimulationResult(
                weightCount * valueCount, departmentChanges, insuranceChanges,
                compactRanges(changed, weightCount, valueCount)
        );
    }

    public static BoundarySimulationResult empty() {
        return new BoundarySimulationResult(0, 0, 0, List.of());
    }

    private RoutingDecision evaluate(RoutingEngine engine, Parcel parcel, RoutingConfig configuration) {
        try {
            return engine.evaluate(parcel, configuration);
        } catch (IllegalArgumentException exception) {
            if ("No matching routing rule found".equals(exception.getMessage())) return null;
            throw exception;
        }
    }

    private String department(RoutingDecision decision) {
        return decision == null ? null : decision.predictedDepartment();
    }

    private boolean insuranceRequired(RoutingDecision decision) {
        return decision != null && decision.insuranceRequired();
    }

    private List<ChangedRange> compactRanges(boolean[][] changed, int weightCount, int valueCount) {
        List<RangeCell> cells = new ArrayList<>();
        for (int weightIndex = 0; weightIndex < weightCount; weightIndex++) {
            int valueIndex = 0;
            while (valueIndex < valueCount) {
                if (!changed[weightIndex][valueIndex]) {
                    valueIndex++;
                    continue;
                }
                int start = valueIndex;
                while (valueIndex + 1 < valueCount && changed[weightIndex][valueIndex + 1]) valueIndex++;
                cells.add(new RangeCell(weightIndex, weightIndex, start, valueIndex));
                valueIndex++;
            }
        }

        for (int index = 0; index < cells.size(); index++) {
            RangeCell current = cells.get(index);
            while (index + 1 < cells.size()) {
                RangeCell next = cells.get(index + 1);
                if (current.valueStart != next.valueStart || current.valueEnd != next.valueEnd
                        || current.weightEnd + 1 != next.weightStart) break;
                current = new RangeCell(current.weightStart, next.weightEnd, current.valueStart, current.valueEnd);
                cells.set(index, current);
                cells.remove(index + 1);
            }
        }

        return cells.stream().map(cell -> new ChangedRange(
                point(minWeightKg, weightStep, cell.weightStart),
                point(minWeightKg, weightStep, cell.weightEnd),
                point(minValueEur, valueStep, cell.valueStart),
                point(minValueEur, valueStep, cell.valueEnd),
                (cell.weightEnd - cell.weightStart + 1) * (cell.valueEnd - cell.valueStart + 1)
        )).toList();
    }

    private static void validateBounds(double min, double max, double step, String name) {
        if (!Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(step)
                || min > max || step <= 0) {
            throw new IllegalArgumentException(name + " bounds must be finite, ordered, and use a positive step");
        }
    }

    private static long points(double min, double max, double step) {
        return (long) Math.floor((max - min) / step + 1e-9) + 1;
    }

    private static double point(double min, double step, int index) {
        return min + step * index;
    }

    private record RangeCell(int weightStart, int weightEnd, int valueStart, int valueEnd) { }

    public record ChangedRange(
            double minWeightKg, double maxWeightKg, double minValueEur, double maxValueEur, int gridPoints
    ) { }

    public record BoundarySimulationResult(
            int totalSimulated, int changedDepartments, int changedInsuranceStatuses, List<ChangedRange> changedRanges
    ) { }
}
