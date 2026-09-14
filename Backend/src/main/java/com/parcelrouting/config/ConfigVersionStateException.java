package com.parcelrouting.config;

public class ConfigVersionStateException extends IllegalStateException {

    public ConfigVersionStateException(int version) {
        super("Routing configuration version is not a draft: " + version);
    }
}
