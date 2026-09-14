package com.parcelrouting.config;

import com.parcelrouting.routing.RoutingConfig;

public record ActiveRoutingConfig(Long versionId, RoutingConfig routingConfig) {
}
