package com.parcelrouting.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.parcelrouting.parcel.CountryCodes;

import java.util.Map;

public record ParcelSubmissionRequest(
        @NotNull @DecimalMin(value = "0.0") Double weightKg,
        @NotNull @DecimalMin(value = "0.0") Double valueEur,
        @NotBlank(message = "must not be blank")
        @ValidCountryCode
        String destinationCountry,
        Map<String, Object> attributes
) {
    public ParcelSubmissionRequest {
        destinationCountry = CountryCodes.normalize(destinationCountry);
    }
}
