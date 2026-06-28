package com.mrfermz.mcplugins.money.transaction;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One immutable row of the money audit log: a single balance change, with
 * enough detail to answer "who moved how much, from whom, to whom, and when".
 *
 * <p>Player references are stored as {@link UUID}s only — names are resolved at
 * display time so the log never holds stale usernames and the economy service
 * stays free of Bukkit lookups. Fields that don't apply to a given
 * {@link TransactionType} are {@code null}:
 *
 * <ul>
 *   <li>{@link #from}/{@link #fromBalance} — the debited account (null for a
 *       pure credit like {@code GIVE}/{@code SET}).</li>
 *   <li>{@link #to}/{@link #toBalance} — the credited account (null for a pure
 *       debit like {@code TAKE}).</li>
 *   <li>{@link #actor} — who initiated it; null means console/system.</li>
 * </ul>
 *
 * @param timestamp  epoch millis when the change was applied
 * @param type       the intent behind the change
 * @param actor      who triggered it (command sender); null = console/system
 * @param from       the debited account, or null
 * @param to         the credited account, or null
 * @param amount     the absolute amount moved (for SET/RESET, the new value)
 * @param fromBalance resulting balance of {@code from}, or null
 * @param toBalance   resulting balance of {@code to}, or null
 * @param reason     optional free-text context, or null
 */
public record Transaction(
        long timestamp,
        TransactionType type,
        UUID actor,
        UUID from,
        UUID to,
        BigDecimal amount,
        BigDecimal fromBalance,
        BigDecimal toBalance,
        String reason) {
}
