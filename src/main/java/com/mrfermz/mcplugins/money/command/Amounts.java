package com.mrfermz.mcplugins.money.command;

import java.math.BigDecimal;
import java.util.Optional;

/** Small parsing helpers shared by the money commands. */
final class Amounts {

    private Amounts() {
    }

    /** Parses a strictly-positive monetary amount, rejecting NaN/negatives/zero. */
    static Optional<BigDecimal> parsePositive(String raw) {
        try {
            BigDecimal value = new BigDecimal(raw);
            if (value.signum() <= 0) {
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /** Parses a non-negative amount (zero allowed) — used by admin {@code set}. */
    static Optional<BigDecimal> parseNonNegative(String raw) {
        try {
            BigDecimal value = new BigDecimal(raw);
            if (value.signum() < 0) {
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }
}
