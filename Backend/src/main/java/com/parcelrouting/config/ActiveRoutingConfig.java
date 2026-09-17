package com.parcelrouting.config;

import com.parcelrouting.routing.RoutingConfig;

public record ActiveRoutingConfig(Long versionId, int version, RoutingConfig routingConfig, String reason) {

    public ActiveRoutingConfig(Long versionId, int version, RoutingConfig routingConfig) {
        this(versionId, version, routingConfig, null);
    }

    public ActiveRoutingConfig(Long versionId, RoutingConfig routingConfig) {
        this(versionId, Math.toIntExact(versionId), routingConfig, null);
    }
}
