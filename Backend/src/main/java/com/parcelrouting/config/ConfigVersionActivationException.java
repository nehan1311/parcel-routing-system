package com.parcelrouting.config;

public class ConfigVersionActivationException extends IllegalStateException {

    public ConfigVersionActivationException(int version, String reason) {
        super("Routing configuration draft cannot be activated: " + version + ". " + reason);
    }
}
