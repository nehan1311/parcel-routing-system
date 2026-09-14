package com.parcelrouting.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record RoutingConfig(int insuranceThresholdEur, List<Rule> rules) {

    public RoutingConfig {
        rules = rules == null ? null : Collections.unmodifiableList(new ArrayList<>(rules));
    }
}
