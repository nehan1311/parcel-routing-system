package com.parcelrouting.parcel;

import java.util.Map;

public record Parcel(
        double weightKg,
        double valueEur,
        String destinationCountry,
        Map<String, Object> attributes
) {

    public Parcel {
        destinationCountry = CountryCodes.normalize(destinationCountry);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
