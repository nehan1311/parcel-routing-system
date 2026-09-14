package com.parcelrouting.config;

public class ConfigVersionNotFoundException extends RuntimeException {

    public ConfigVersionNotFoundException(int version) {
        super("Routing configuration version not found: " + version);
    }
}
