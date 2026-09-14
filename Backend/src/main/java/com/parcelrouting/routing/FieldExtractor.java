package com.parcelrouting.routing;

import com.parcelrouting.parcel.Parcel;

public final class FieldExtractor {

    private static final String ATTRIBUTE_PREFIX = "attribute:";

    private FieldExtractor() {
    }

    public static Object extract(Parcel parcel, String fieldName) {
        if (fieldName == null) {
            throw new IllegalArgumentException("Parcel field name must not be null");
        }
        if (fieldName.isBlank()) {
            throw new IllegalArgumentException("Parcel field name must not be blank");
        }

        return switch (fieldName) {
            case "weight_kg" -> parcel.weightKg();
            case "value_eur" -> parcel.valueEur();
            case "destination_country" -> parcel.destinationCountry();
            default -> extractAttributeOrThrow(parcel, fieldName);
        };
    }

    private static Object extractAttributeOrThrow(Parcel parcel, String fieldName) {
        if (fieldName != null && fieldName.startsWith(ATTRIBUTE_PREFIX)) {
            String attributeName = fieldName.substring(ATTRIBUTE_PREFIX.length());
            return parcel.attributes().get(attributeName);
        }

        throw new IllegalArgumentException("Unknown parcel field: " + fieldName);
    }
}
