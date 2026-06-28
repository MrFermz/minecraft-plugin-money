package com.mrfermz.mcplugins.money.transaction;

/**
 * The kind of balance change recorded in the audit log. The type captures the
 * <em>intent</em> behind a mutation, which the raw {@code deposit}/{@code
 * withdraw} on {@link com.mrfermz.mcplugins.core.api.EconomyService} can't
 * express on its own (an admin {@code give} and a shop refund are both deposits).
 */
public enum TransactionType {

    /** Player-initiated {@code /money pay} from one account to another. */
    PAY,
    /** A programmatic balance move between two accounts (non-command). */
    TRANSFER,
    /** Admin {@code /money give} — credited an account out of thin air. */
    GIVE,
    /** Admin {@code /money take} — debited an account. */
    TAKE,
    /** Admin {@code /money set} — overwrote a balance with an exact value. */
    SET,
    /** Admin {@code /money reset} — set a balance back to the starting amount. */
    RESET,
    /** Programmatic credit via the {@code EconomyService} API (non-command). */
    DEPOSIT,
    /** Programmatic debit via the {@code EconomyService} API (non-command). */
    WITHDRAW
}
