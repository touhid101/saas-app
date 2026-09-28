package com.friendspharma.app.service.impl.ecom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The distance the marketplace sorts and filters by. Pinned against known Dhaka landmarks. */
class EcomPharmacyDistanceTest {

    @Test
    @DisplayName("Uttara to Dhanmondi is about 17 km, and zero to itself")
    void dhaka() {
        double uttaraLat = 23.8759, uttaraLng = 90.3795;
        double dhanmondiLat = 23.7461, dhanmondiLng = 90.3742;
        assertThat(EcomPharmacyService.haversineKm(uttaraLat, uttaraLng, dhanmondiLat, dhanmondiLng)).isCloseTo(14.5, within(0.5));
        assertThat(EcomPharmacyService.haversineKm(uttaraLat, uttaraLng, uttaraLat, uttaraLng)).isCloseTo(0, within(1e-9));
    }

    @Test
    @DisplayName("symmetric, and Dhaka to Chattogram about 215 km")
    void chattogram() {
        double a = EcomPharmacyService.haversineKm(23.8103, 90.4125, 22.3569, 91.7832);
        double b = EcomPharmacyService.haversineKm(22.3569, 91.7832, 23.8103, 90.4125);
        assertThat(a).isCloseTo(b, within(1e-9));
        assertThat(a).isCloseTo(214.0, within(5.0));
    }
}
