package com.parcelrouting.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "routing_config_version")
public class RoutingConfigVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "version", nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ConfigVersionStatus status;

    @Column(name = "rules_json", nullable = false, columnDefinition = "text")
    private String rulesJson;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "based_on_version_id")
    private Long basedOnVersionId;

    protected RoutingConfigVersion() {
    }

    public RoutingConfigVersion(
            int version,
            ConfigVersionStatus status,
            String rulesJson,
            String createdBy,
            Instant createdAt,
            Instant activatedAt,
            Long basedOnVersionId
    ) {
        this.version = version;
        this.status = status;
        this.rulesJson = rulesJson;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.activatedAt = activatedAt;
        this.basedOnVersionId = basedOnVersionId;
    }

    public Long getId() {
        return id;
    }

    public int getVersion() {
        return version;
    }

    public ConfigVersionStatus getStatus() {
        return status;
    }

    public String getRulesJson() {
        return rulesJson;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public Long getBasedOnVersionId() {
        return basedOnVersionId;
    }
}
