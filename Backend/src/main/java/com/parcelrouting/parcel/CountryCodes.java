package com.parcelrouting.parcel;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class CountryCodes {
    private static final Set<String> ISO_ALPHA_2_CODES = Arrays.stream(Locale.getISOCountries())
            .collect(Collectors.toUnmodifiableSet());

    private CountryCodes() {
    }

    public static String normalize(String countryCode) {
        return countryCode == null ? null : countryCode.strip().toUpperCase(Locale.ROOT);
    }

    public static boolean isValid(String countryCode) {
        return countryCode != null && ISO_ALPHA_2_CODES.contains(countryCode);
    }
}
