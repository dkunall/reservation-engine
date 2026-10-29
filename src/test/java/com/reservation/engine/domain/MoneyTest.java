package com.reservation.engine.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void normalisesScaleToCurrency() {
        assertThat(Money.of("10", "USD").amount()).isEqualByComparingTo("10.00");
        assertThat(Money.of("10", "USD").amount().scale()).isEqualTo(2);
        assertThat(Money.of("1000.4", "JPY").amount().scale()).isZero();
    }

    @Test
    void multipliesAndComputesPercentages() {
        assertThat(Money.of("49.99", "USD").times(3).amount()).isEqualByComparingTo("149.97");
        assertThat(Money.of("100.00", "EUR").percentage(50).amount()).isEqualByComparingTo("50.00");
        assertThat(Money.of("0.00", "EUR").isZero()).isTrue();
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> new Money(new BigDecimal("-1"), "USD")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("1", "XXZ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("1", "USD").times(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
