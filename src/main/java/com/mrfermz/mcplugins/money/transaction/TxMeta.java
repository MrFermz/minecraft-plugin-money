package com.mrfermz.mcplugins.money.transaction;

import java.util.UUID;

/**
 * The attribution metadata for a single balance change: the intent
 * ({@link TransactionType}), who triggered it, and an optional reason.
 *
 * <p>It's a small carrier so {@link com.mrfermz.mcplugins.money.economy.MoneyEconomyService}
 * can record a rich {@link Transaction} without bloating every method signature.
 * Command-initiated mutations build one with the real actor; the plain
 * {@code EconomyService} interface methods fall back to {@link #system}.
 *
 * @param type   the kind of change
 * @param actor  the player who triggered it, or null for console/system
 * @param reason optional free-text context, or null
 */
public record TxMeta(TransactionType type, UUID actor, String reason) {

    /** Attribution for a programmatic change with no human actor. */
    public static TxMeta system(TransactionType type) {
        return new TxMeta(type, null, null);
    }

    /** Attribution for a command-initiated change, no extra reason. */
    public static TxMeta by(TransactionType type, UUID actor) {
        return new TxMeta(type, actor, null);
    }
}
