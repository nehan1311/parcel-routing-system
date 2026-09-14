package com.parcelrouting.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record ParcelSubmissionRequest(
        @NotNull @DecimalMin(value = "0.0") Double weightKg,
        @NotNull @DecimalMin(value = "0.0") Double valueEur,
        String destinationCountry,
        Map<String, Object> attributes
) {
}
