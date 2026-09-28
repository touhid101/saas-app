package com.friendspharma.app.entity;

import com.friendspharma.app.util.CustomerPricing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The global tier and the pharmacy row's CUSTOMER_TYPE must mean the same
 * thing to the sale path: what the bridge writes onto the shadow row has to
 * come back out of {@link CustomerPricing#tierOf} as the tier the customer
 * was granted. If this drifts, an online wholesaler is priced as a retailer
 * at the till.
 */
class EcomCustomerTierTest {

    @Test
    @DisplayName("every global tier maps to a POS spelling the pricing engine reads back as the same tier")
    void roundTrip() {
        assertThat(CustomerPricing.tierOf(EcomCustomer.posCustomerTypeOf(EcomCustomer.TYPE_RETAILER)))
                .isEqualTo(CustomerPricing.Tier.RETAILER);
        assertThat(CustomerPricing.tierOf(EcomCustomer.posCustomerTypeOf(EcomCustomer.TYPE_WHOLESALER)))
                .isEqualTo(CustomerPricing.Tier.WHOLESALER);
        assertThat(CustomerPricing.tierOf(EcomCustomer.posCustomerTypeOf(EcomCustomer.TYPE_SPECIAL)))
                .isEqualTo(CustomerPricing.Tier.SPECIAL);
    }

    @Test
    @DisplayName("the POS spellings are the ones the V45 check constraint admits")
    void posSpellings() {
        assertThat(EcomCustomer.posCustomerTypeOf("RETAILER")).isEqualTo("Retailer");
        assertThat(EcomCustomer.posCustomerTypeOf("WHOLESALER")).isEqualTo("Wholesaler");
        assertThat(EcomCustomer.posCustomerTypeOf("SPECIAL")).isEqualTo("Special");
        // Unknown or missing falls to the most restrictive tier, never to a guess.
        assertThat(EcomCustomer.posCustomerTypeOf(null)).isEqualTo("Retailer");
        assertThat(EcomCustomer.posCustomerTypeOf("HOSPITAL")).isEqualTo("Retailer");
    }

    @Test
    @DisplayName("only the three tiers are known")
    void knownTypes() {
        assertThat(EcomCustomer.isKnownType("RETAILER")).isTrue();
        assertThat(EcomCustomer.isKnownType("Retailer")).isFalse();
        assertThat(EcomCustomer.isKnownType("HOSPITAL")).isFalse();
        assertThat(EcomCustomer.isKnownType(null)).isFalse();
    }
}
