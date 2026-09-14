package com.parcelrouting.routing;

import java.util.Collection;
import java.util.Objects;

public final class OperatorEvaluator {

    private OperatorEvaluator() {
    }

    public static boolean compare(
            Object actual,
            ComparisonOperator operator,
            Object expected
    ) {
        if (operator == null) {
            throw new IllegalArgumentException("Comparison operator must not be null");
        }

        return switch (operator) {
            case GT -> compareNumbers(actual, expected) > 0;
            case GTE -> compareNumbers(actual, expected) >= 0;
            case LT -> compareNumbers(actual, expected) < 0;
            case LTE -> compareNumbers(actual, expected) <= 0;
            case EQ -> Objects.equals(actual, expected);
            case NEQ -> !Objects.equals(actual, expected);
            case IN -> contains(expected, actual);
        };
    }

    private static int compareNumbers(Object actual, Object expected) {
        if (actual == null || expected == null) {
            throw new IllegalArgumentException("Numeric comparison requires non-null actual and expected values");
        }
        if (!(actual instanceof Number actualNumber) || !(expected instanceof Number expectedNumber)) {
            throw new IllegalArgumentException("Numeric comparison requires Number actual and expected values");
        }

        return Double.compare(actualNumber.doubleValue(), expectedNumber.doubleValue());
    }

    private static boolean contains(Object expected, Object actual) {
        if (!(expected instanceof Collection<?> expectedCollection)) {
            throw new IllegalArgumentException("IN comparison requires expected value to be a Collection");
        }
        if (actual == null) {
            return false;
        }

        return expectedCollection.contains(actual);
    }
}
