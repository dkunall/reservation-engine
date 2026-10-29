package com.reservation.engine.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/** Immutable monetary amount; scale normalised to the currency's minor units. */
public record Money(BigDecimal amount, String currency) {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Currency c;
        try {
            c = Currency.getInstance(currency);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown currency: " + currency);
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must be >= 0");
        }
        int digits = Math.max(c.getDefaultFractionDigits(), 0);
        amount = amount.setScale(digits, RoundingMode.HALF_EVEN);
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public Money times(long multiplier) {
        if (multiplier < 0) {
            throw new IllegalArgumentException("multiplier must be >= 0");
        }
        return new Money(amount.multiply(BigDecimal.valueOf(multiplier)), currency);
    }

    public Money percentage(int percent) {
        return new Money(amount.multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_EVEN), currency);
    }

    @JsonIgnore
    public boolean isZero() {
        return amount.signum() == 0;
    }
}
