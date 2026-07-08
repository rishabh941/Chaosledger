package com.chaosledger.ledger.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the Money value object.
 *
 * Money is the fundamental building block of financial calculations.
 * Every bug here propagates to every balance in the system.
 * These tests verify:
 * - Scale enforcement (exactly 2 decimal places)
 * - Currency mismatch prevention
 * - Arithmetic correctness with BigDecimal
 * - Comparison operators
 */
class MoneyTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("Valid 2-decimal amount creates Money correctly")
        void validAmount_createsCorrectly() {
            Money m = Money.of(new BigDecimal("100.50"), "INR");
            assertThat(m.amount()).isEqualByComparingTo(new BigDecimal("100.50"));
            assertThat(m.currency()).isEqualTo("INR");
        }

        @Test
        @DisplayName("zero() creates Money with amount 0.00")
        void zero_createsZeroAmount() {
            Money m = Money.zero("INR");
            assertThat(m.amount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(m.isZero()).isTrue();
        }

        @Test
        @DisplayName("3+ decimal places → ArithmeticException (scale violation)")
        void tooManyDecimals_throws() {
            assertThatThrownBy(() -> Money.of(new BigDecimal("10.123"), "INR"))
                    .isInstanceOf(ArithmeticException.class);
        }

        @Test
        @DisplayName("Null amount → NullPointerException")
        void nullAmount_throws() {
            assertThatThrownBy(() -> Money.of((BigDecimal) null, "INR"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Null currency → NullPointerException")
        void nullCurrency_throws() {
            assertThatThrownBy(() -> Money.of(BigDecimal.TEN, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Blank currency → IllegalArgumentException")
        void blankCurrency_throws() {
            assertThatThrownBy(() -> Money.of(BigDecimal.TEN, "   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Arithmetic")
    class ArithmeticTests {

        @Test
        @DisplayName("100 + 50 = 150")
        void add_sameAmount_correct() {
            Money a = Money.of("100.00", "INR");
            Money b = Money.of("50.00", "INR");
            Money result = a.add(b);
            assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("150.00"));
        }

        @Test
        @DisplayName("100 - 30 = 70")
        void subtract_correct() {
            Money a = Money.of("100.00", "INR");
            Money b = Money.of("30.00", "INR");
            Money result = a.subtract(b);
            assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("70.00"));
        }

        @Test
        @DisplayName("Add different currencies → IllegalArgumentException")
        void add_differentCurrencies_throws() {
            Money inr = Money.of("100.00", "INR");
            Money usd = Money.of("50.00", "USD");
            assertThatThrownBy(() -> inr.add(usd))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("different currencies");
        }

        @Test
        @DisplayName("Subtract different currencies → IllegalArgumentException")
        void subtract_differentCurrencies_throws() {
            Money inr = Money.of("100.00", "INR");
            Money usd = Money.of("50.00", "USD");
            assertThatThrownBy(() -> inr.subtract(usd))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Comparisons")
    class ComparisonTests {

        @Test
        @DisplayName("Positive amount → isPositive() true")
        void positive_isPositive() {
            assertThat(Money.of("100.00", "INR").isPositive()).isTrue();
        }

        @Test
        @DisplayName("Zero → isZero() true, isPositive() false")
        void zero_isZero() {
            Money m = Money.zero("INR");
            assertThat(m.isZero()).isTrue();
            assertThat(m.isPositive()).isFalse();
        }

        @Test
        @DisplayName("Negative amount → isNegative() true")
        void negative_isNegative() {
            Money m = Money.of("-50.00", "INR");
            assertThat(m.isNegative()).isTrue();
        }

        @Test
        @DisplayName("100 >= 50 → true")
        void greaterThanOrEqual_greater() {
            Money hundred = Money.of("100.00", "INR");
            Money fifty = Money.of("50.00", "INR");
            assertThat(hundred.isGreaterThanOrEqual(fifty)).isTrue();
        }

        @Test
        @DisplayName("100 >= 100 → true (equal case)")
        void greaterThanOrEqual_equal() {
            Money a = Money.of("100.00", "INR");
            Money b = Money.of("100.00", "INR");
            assertThat(a.isGreaterThanOrEqual(b)).isTrue();
        }

        @Test
        @DisplayName("50 >= 100 → false")
        void greaterThanOrEqual_less() {
            Money fifty = Money.of("50.00", "INR");
            Money hundred = Money.of("100.00", "INR");
            assertThat(fifty.isGreaterThanOrEqual(hundred)).isFalse();
        }

        @Test
        @DisplayName("Compare different currencies → IllegalArgumentException")
        void compare_differentCurrencies_throws() {
            Money inr = Money.of("100.00", "INR");
            Money usd = Money.of("100.00", "USD");
            assertThatThrownBy(() -> inr.isGreaterThanOrEqual(usd))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Equality")
    class EqualityTests {

        @Test
        @DisplayName("Same amount and currency → equal")
        void sameValues_areEqual() {
            Money a = Money.of("100.00", "INR");
            Money b = Money.of("100.00", "INR");
            assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("Different amount → not equal")
        void differentAmount_notEqual() {
            Money a = Money.of("100.00", "INR");
            Money b = Money.of("200.00", "INR");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Different currency → not equal")
        void differentCurrency_notEqual() {
            Money a = Money.of("100.00", "INR");
            Money b = Money.of("100.00", "USD");
            assertThat(a).isNotEqualTo(b);
        }
    }
}
