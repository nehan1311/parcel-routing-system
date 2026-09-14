package com.parcelrouting.routing;

public record Rule(String id, int priority, Condition condition, String department) {
}
