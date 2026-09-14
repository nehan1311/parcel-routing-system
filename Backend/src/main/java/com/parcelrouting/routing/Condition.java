package com.parcelrouting.routing;

public record Condition(String field, ComparisonOperator operator, Object value) {
}
