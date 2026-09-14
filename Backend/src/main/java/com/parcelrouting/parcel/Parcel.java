package com.parcelrouting.parcel;

import java.util.Map;

public record Parcel(
        double weightKg,
        double valueEur,
        String destinationCountry,
        Map<String, Object> attributes
) {

    public Parcel {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
