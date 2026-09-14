package com.parcelrouting.routing;

import com.parcelrouting.parcel.Parcel;

public final class ConditionEvaluator {

    private ConditionEvaluator() {
    }

    public static boolean matches(Parcel parcel, Condition condition) {
        if (parcel == null) {
            throw new IllegalArgumentException("Parcel must not be null");
        }
        if (condition == null) {
            throw new IllegalArgumentException("Condition must not be null");
        }

        Object actual = FieldExtractor.extract(parcel, condition.field());
        return OperatorEvaluator.compare(actual, condition.operator(), condition.value());
    }
}
