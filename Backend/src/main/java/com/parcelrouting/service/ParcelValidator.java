package com.parcelrouting.service;

import com.parcelrouting.parcel.CountryCodes;
import com.parcelrouting.parcel.Parcel;
import org.springframework.stereotype.Component;

@Component
public class ParcelValidator {

    public void validate(Parcel parcel) {
        if (parcel == null) {
            throw new IllegalArgumentException("Parcel must not be null");
        }
        if (!(parcel.weightKg() >= 0)) {
            throw new IllegalArgumentException("Parcel weightKg must be greater than or equal to zero");
        }
        if (!(parcel.valueEur() >= 0)) {
            throw new IllegalArgumentException("Parcel valueEur must be greater than or equal to zero");
        }
        if (parcel.destinationCountry() == null || parcel.destinationCountry().isBlank()) {
            throw new IllegalArgumentException("destinationCountry must not be blank");
        }
        if (!CountryCodes.isValid(parcel.destinationCountry())) {
            throw new IllegalArgumentException("destinationCountry must be a valid ISO 3166-1 alpha-2 country code");
        }
        if (parcel.attributes() == null) {
            throw new IllegalArgumentException("Parcel attributes must not be null");
        }
    }
}
