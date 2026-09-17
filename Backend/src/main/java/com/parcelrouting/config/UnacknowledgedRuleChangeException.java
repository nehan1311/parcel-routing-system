package com.parcelrouting.config;

import java.util.List;

public class UnacknowledgedRuleChangeException extends ConfigVersionActivationException {

    public UnacknowledgedRuleChangeException(int version, List<String> ruleIds) {
        super(version, "unacknowledged field/operator changes for rule IDs: " + String.join(", ", ruleIds));
    }
}
