package com.parcelrouting.routing;

import com.parcelrouting.parcel.Parcel;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RoutingEngine {

    public RoutingDecision evaluate(Parcel parcel, RoutingConfig config) {
        if (parcel == null) {
            throw new IllegalArgumentException("Parcel must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("RoutingConfig must not be null");
        }

        List<Rule> rules = validateRules(config.rules());
        boolean insuranceRequired = parcel.valueEur() > config.insuranceThresholdEur();

        return rules.stream()
                .sorted(Comparator.comparingInt(Rule::priority))
                .filter(rule -> ConditionEvaluator.matches(parcel, rule.condition()))
                .findFirst()
                .map(rule -> new RoutingDecision(insuranceRequired, rule.department(), rule.id()))
                .orElseThrow(() -> new IllegalArgumentException("No matching routing rule found"));
    }

    private static List<Rule> validateRules(List<Rule> rules) {
        if (rules == null) {
            throw new IllegalArgumentException("Routing rules list must not be null");
        }

        Set<Integer> priorities = new HashSet<>();
        for (Rule rule : rules) {
            if (rule == null) {
                throw new IllegalArgumentException("Routing rules list must not contain null rules");
            }
            if (rule.condition() == null) {
                throw new IllegalArgumentException("Routing rule condition must not be null for rule: " + rule.id());
            }
            if (!priorities.add(rule.priority())) {
                throw new IllegalArgumentException("Duplicate routing rule priority: " + rule.priority());
            }
        }

        return rules;
    }
}
