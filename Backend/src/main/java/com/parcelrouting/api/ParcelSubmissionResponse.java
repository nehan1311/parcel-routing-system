package com.parcelrouting.api;

import com.parcelrouting.parcel.ParcelEntity;
import com.parcelrouting.parcel.ParcelStatus;

public record ParcelSubmissionResponse(
        Long id,
        ParcelStatus status,
        String department,
        String predictedDepartment,
        String matchedRuleId,
        Long routingConfigVersionId,
        boolean insuranceRequired
) {

    public static ParcelSubmissionResponse from(ParcelEntity parcel) {
        return new ParcelSubmissionResponse(
                parcel.getId(),
                parcel.getStatus(),
                parcel.getDepartment(),
                parcel.getPredictedDepartment(),
                parcel.getMatchedRuleId(),
                parcel.getRoutingConfigVersionId(),
                parcel.getStatus() == ParcelStatus.PENDING_APPROVAL
        );
    }
}
