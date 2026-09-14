package com.parcelrouting.config;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoutingConfigVersionRepository extends JpaRepository<RoutingConfigVersion, Long> {

    List<RoutingConfigVersion> findByStatus(ConfigVersionStatus status);

    Optional<RoutingConfigVersion> findTopByOrderByVersionDesc();
}
