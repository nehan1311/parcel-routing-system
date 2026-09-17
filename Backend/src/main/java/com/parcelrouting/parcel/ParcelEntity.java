package com.parcelrouting.parcel;

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
@Table(name = "parcel")
public class ParcelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "weight_kg", nullable = false)
    private double weightKg;

    @Column(name = "value_eur", nullable = false)
    private double valueEur;

    @Column(name = "destination_country")
    private String destinationCountry;

    @Column(name = "attributes_json", columnDefinition = "text")
    private String attributesJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ParcelStatus status;

    @Column(name = "department")
    private String department;

    @Column(name = "predicted_department")
    private String predictedDepartment;

    @Column(name = "matched_rule_id")
    private String matchedRuleId;

    @Column(name = "insurance_required")
    private Boolean insuranceRequired;

    @Column(name = "routing_config_version_id")
    private Long routingConfigVersionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    protected ParcelEntity() {
    }

    public ParcelEntity(
            double weightKg,
            double valueEur,
            String destinationCountry,
            String attributesJson,
            ParcelStatus status,
            String department,
            String predictedDepartment,
            String matchedRuleId,
            Long routingConfigVersionId,
            Instant createdAt,
            String approvedBy,
            Instant approvedAt
    ) {
        this(weightKg, valueEur, destinationCountry, attributesJson, status, department, predictedDepartment,
                matchedRuleId, null, routingConfigVersionId, createdAt, approvedBy, approvedAt);
    }

    public ParcelEntity(
            double weightKg,
            double valueEur,
            String destinationCountry,
            String attributesJson,
            ParcelStatus status,
            String department,
            String predictedDepartment,
            String matchedRuleId,
            Boolean insuranceRequired,
            Long routingConfigVersionId,
            Instant createdAt,
            String approvedBy,
            Instant approvedAt
    ) {
        this.weightKg = weightKg;
        this.valueEur = valueEur;
        this.destinationCountry = destinationCountry;
        this.attributesJson = attributesJson;
        this.status = status;
        this.department = department;
        this.predictedDepartment = predictedDepartment;
        this.matchedRuleId = matchedRuleId;
        this.insuranceRequired = insuranceRequired;
        this.routingConfigVersionId = routingConfigVersionId;
        this.createdAt = createdAt;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
    }

    public Long getId() {
        return id;
    }

    public double getWeightKg() {
        return weightKg;
    }

    public double getValueEur() {
        return valueEur;
    }

    public String getDestinationCountry() {
        return destinationCountry;
    }

    public String getAttributesJson() {
        return attributesJson;
    }

    public ParcelStatus getStatus() {
        return status;
    }

    public String getDepartment() {
        return department;
    }

    public String getPredictedDepartment() {
        return predictedDepartment;
    }

    public String getMatchedRuleId() {
        return matchedRuleId;
    }

    public Boolean getInsuranceRequired() {
        return insuranceRequired;
    }

    public Long getRoutingConfigVersionId() {
        return routingConfigVersionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setStatus(ParcelStatus status) {
        this.status = status;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }
}
