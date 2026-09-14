package com.parcelrouting.config;

public class ConfigVersionRollbackException extends IllegalStateException {

    public ConfigVersionRollbackException(int version, String reason) {
        super("Routing configuration version cannot be rolled back: " + version + ". " + reason);
    }
}
