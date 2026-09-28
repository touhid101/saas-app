package com.friendspharma.app.util;

import com.friendspharma.app.util.CustomerPricing.Tier;
import com.friendspharma.app.util.SalesPriceCalculator.UnitPrices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which units a customer may buy, and at what price.
 *
 * <p>These decide what a customer is charged, so every rule is pinned. The one
 * that is easy to get backwards: a retailer is restricted to <em>pieces</em> but
 * pays the <em>wholesaler's</em> piece rate — the restriction is on the unit,
 * not on the price list.
 */
class CustomerPricingTest {

    // A product at 250/box, 12 strips of 4 — 48 pcs per box.
    private static final UnitPrices PRICES = SalesPriceCalculator.unitPrices(
            new BigDecimal("250"), 48, 4,
            new BigDecimal("10"), new BigDecimal("12"), new BigDecimal("14"),   // regular
            new BigDecimal("20"), new BigDecimal("22"), new BigDecimal("24"));  // special

    @Nested
    @DisplayName("tierOf")
    class TierResolution {

        @Test
        void readsTheStoredSpellings() {
            assertThat(CustomerPricing.tierOf("Wholesaler")).isEqualTo(Tier.WHOLESALER);
            assertThat(CustomerPricing.tierOf("Retailer")).isEqualTo(Tier.RETAILER);
            assertThat(CustomerPricing.tierOf("Special")).isEqualTo(Tier.SPECIAL);
        }

        @Test
        void isCaseInsensitiveAndPrefixTolerant() {
            assertThat(CustomerPricing.tierOf("WHOLESALE")).isEqualTo(Tier.WHOLESALER);
            assertThat(CustomerPricing.tierOf("  wholesaler  ")).isEqualTo(Tier.WHOLESALER);
            assertThat(CustomerPricing.tierOf("retail")).isEqualTo(Tier.RETAILER);
        }

        @Test
        @DisplayName("an unknown or missing type falls back to the most restrictive tier")
        void unknownFallsBackToRetailer() {
            // Failing open would hand wholesale box pricing to a customer whose
            // terms nobody recorded — the expensive direction to be wrong in.
            assertThat(CustomerPricing.tierOf(null)).isEqualTo(Tier.RETAILER);
            assertThat(CustomerPricing.tierOf("")).isEqualTo(Tier.RETAILER);
            assertThat(CustomerPricing.tierOf("Distributor")).isEqualTo(Tier.RETAILER);
        }
    }

    @Nested
    @DisplayName("allowedUnits")
    class AllowedUnits {

        @Test
        void wholesalerAndSpecialGetEveryUnit() {
            assertThat(CustomerPricing.allowedUnits(Tier.WHOLESALER))
                    .containsExactly("BOX", "STRIP", "PCS");
            assertThat(CustomerPricing.allowedUnits(Tier.SPECIAL))
                    .containsExactly("BOX", "STRIP", "PCS");
        }

        @Test
        void retailerGetsPiecesOnly() {
            assertThat(CustomerPricing.allowedUnits(Tier.RETAILER)).containsExactly("PCS");
        }

        @Test
        void allowsMatchesAllowedUnits() {
            assertThat(CustomerPricing.allows(Tier.RETAILER, "BOX")).isFalse();
            assertThat(CustomerPricing.allows(Tier.RETAILER, "STRIP")).isFalse();
            assertThat(CustomerPricing.allows(Tier.RETAILER, "pcs")).isTrue();
            assertThat(CustomerPricing.allows(Tier.WHOLESALER, "BOX")).isTrue();
            assertThat(CustomerPricing.allows(Tier.SPECIAL, "STRIP")).isTrue();
        }
    }

    @Nested
    @DisplayName("priceFor")
    class Pricing {

        @Test
        void wholesalerPaysTheRegularList() {
            // 250 box less 10%; strip is 250×4/48 = 20.83 less 12%;
            // piece is 250×1/48 = 5.21 less 14%.
            assertThat(CustomerPricing.priceFor(Tier.WHOLESALER, "BOX", PRICES))
                    .isEqualByComparingTo("225.00");
            assertThat(CustomerPricing.priceFor(Tier.WHOLESALER, "STRIP", PRICES))
                    .isEqualByComparingTo("18.33");
            assertThat(CustomerPricing.priceFor(Tier.WHOLESALER, "PCS", PRICES))
                    .isEqualByComparingTo("4.48");
        }

        @Test
        void specialPaysTheSpecialList() {
            assertThat(CustomerPricing.priceFor(Tier.SPECIAL, "BOX", PRICES))
                    .isEqualByComparingTo("200.00");
            assertThat(CustomerPricing.priceFor(Tier.SPECIAL, "STRIP", PRICES))
                    .isEqualByComparingTo("16.25");
            assertThat(CustomerPricing.priceFor(Tier.SPECIAL, "PCS", PRICES))
                    .isEqualByComparingTo("3.96");
        }

        @Test
        @DisplayName("a retailer pays the wholesaler piece rate, not the special one")
        void retailerPaysWholesalerPieceRate() {
            assertThat(CustomerPricing.priceFor(Tier.RETAILER, "PCS", PRICES))
                    .isEqualByComparingTo(CustomerPricing.priceFor(Tier.WHOLESALER, "PCS", PRICES))
                    .isEqualByComparingTo("4.48");
        }

        @Test
        void retailerCannotBeGivenABoxOrStripPrice() {
            assertThat(CustomerPricing.priceFor(Tier.RETAILER, "BOX", PRICES)).isNull();
            assertThat(CustomerPricing.priceFor(Tier.RETAILER, "STRIP", PRICES)).isNull();
        }

        @Test
        @DisplayName("an unpriced unit is null, never a fallback to MRP")
        void unpricedIsNull() {
            // Quietly charging full list because a percentage was missing is
            // worse than refusing the line.
            UnitPrices partial = SalesPriceCalculator.unitPrices(
                    new BigDecimal("250"), 48, 4,
                    null, null, null, null, null, null);

            assertThat(CustomerPricing.priceFor(Tier.WHOLESALER, "BOX", partial)).isNull();
            assertThat(CustomerPricing.priceFor(Tier.SPECIAL, "PCS", partial)).isNull();
        }

        @Test
        void nullPricesAreHandled() {
            assertThat(CustomerPricing.priceFor(Tier.WHOLESALER, "BOX", null)).isNull();
        }

        @Test
        @DisplayName("a unit with no packing configured has no price")
        void unconfiguredPackingHasNoPrice() {
            UnitPrices noStrip = SalesPriceCalculator.unitPrices(
                    new BigDecimal("250"), 48, null,
                    new BigDecimal("10"), new BigDecimal("12"), new BigDecimal("14"),
                    null, null, null);

            assertThat(noStrip.stripPrice()).isNull();
            assertThat(CustomerPricing.priceFor(Tier.WHOLESALER, "STRIP", noStrip)).isNull();
            assertThat(CustomerPricing.priceFor(Tier.WHOLESALER, "BOX", noStrip))
                    .isEqualByComparingTo("225.00");
        }
    }

    @Nested
    @DisplayName("unavailableReason")
    class Reasons {

        @Test
        void nullWhenTheUnitIsSellable() {
            assertThat(CustomerPricing.unavailableReason(Tier.WHOLESALER, "BOX", PRICES)).isNull();
            assertThat(CustomerPricing.unavailableReason(Tier.RETAILER, "PCS", PRICES)).isNull();
        }

        @Test
        void explainsTheUnitRestrictionBeforeThePrice() {
            assertThat(CustomerPricing.unavailableReason(Tier.RETAILER, "BOX", PRICES))
                    .contains("Retailer")
                    .contains("PCS");
        }

        @Test
        void explainsAMissingPrice() {
            UnitPrices noSpecial = SalesPriceCalculator.unitPrices(
                    new BigDecimal("250"), 48, 4,
                    new BigDecimal("10"), new BigDecimal("12"), new BigDecimal("14"),
                    null, null, null);

            assertThat(CustomerPricing.unavailableReason(Tier.SPECIAL, "BOX", noSpecial))
                    .contains("No special price")
                    .contains("Sales Discount%");
        }

        /**
         * The message people were actually getting. Nearly half the catalogue
         * has no packing, and every one of those refusals told the operator to
         * set a percentage — on a screen where setting one changes nothing.
         */
        @Test
        @DisplayName("no packing points at Product Master, not Sales Discount%")
        void missingPackingNamesProductMaster() {
            UnitPrices noPacking = SalesPriceCalculator.unitPricesForSale(
                    new BigDecimal("250"), null, null, null, null, null, null, null, null);

            assertThat(CustomerPricing.unavailableReason(Tier.RETAILER, "PCS", noPacking))
                    .contains("packing")
                    .contains("Product Master")
                    .doesNotContain("Sales Discount%");
        }

        @Test
        @DisplayName("no MRP says so, rather than blaming the percentage")
        void missingMrpNamesMrp() {
            UnitPrices noMrp = SalesPriceCalculator.unitPricesForSale(
                    null, 100, 10, null, null, null, null, null, null);

            assertThat(CustomerPricing.unavailableReason(Tier.WHOLESALER, "BOX", noMrp))
                    .contains("MRP")
                    .doesNotContain("Sales Discount%");
        }

        @Test
        @DisplayName("a product with packing and MRP sells with no percentage set")
        void noPercentageIsNoLongerARefusal() {
            UnitPrices priced = SalesPriceCalculator.unitPricesForSale(
                    new BigDecimal("250"), 100, 10, null, null, null, null, null, null);

            assertThat(CustomerPricing.unavailableReason(Tier.RETAILER, "PCS", priced))
                    .as("this is the case that used to empty the till catalogue")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("mrpFor")
    class MrpApportionment {

        @Test
        @DisplayName("MRP is apportioned to the unit before any discount")
        void apportionsMrp() {
            // The point the legacy view got wrong: a strip is worth its share of
            // the box, not the whole box.
            assertThat(CustomerPricing.mrpFor("BOX", PRICES)).isEqualByComparingTo("250");
            assertThat(CustomerPricing.mrpFor("STRIP", PRICES)).isEqualByComparingTo("20.83");
            assertThat(CustomerPricing.mrpFor("PCS", PRICES)).isEqualByComparingTo("5.21");
        }
    }
}
