package com.mrfermz.mcplugins.money.economy;

import java.math.BigDecimal;

/**
 * Display and behaviour settings for the currency, loaded from {@code config.yml}.
 *
 * @param nameSingular    e.g. {@code "Coin"}
 * @param namePlural      e.g. {@code "Coins"}
 * @param symbol          prefix shown when formatting, e.g. {@code "$"}
 * @param decimals        number of fractional digits balances are rounded to
 * @param startingBalance balance a brand-new account begins with
 */
public record CurrencySettings(
        String nameSingular,
        String namePlural,
        String symbol,
        int decimals,
        BigDecimal startingBalance) {
}
