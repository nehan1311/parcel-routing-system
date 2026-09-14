package com.parcelrouting.routing;

public record RoutingDecision(
        boolean insuranceRequired,
        String predictedDepartment,
        String matchedRuleId
) {
}
