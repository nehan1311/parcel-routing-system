package com.parcelrouting.config;

import com.parcelrouting.routing.RoutingConfig;

public record ActiveRoutingConfig(Long versionId, int version, RoutingConfig routingConfig) {

    public ActiveRoutingConfig(Long versionId, RoutingConfig routingConfig) {
        this(versionId, Math.toIntExact(versionId), routingConfig);
    }
}
