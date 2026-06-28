package com.mrfermz.mcplugins.money.transaction;

import java.util.List;
import java.util.UUID;

/**
 * Append-only audit log of every balance change. The economy service hands a
 * {@link Transaction} here on each successful mutation; the implementation
 * buffers it and persists asynchronously so the main thread is never blocked.
 *
 * <p>The implementation is {@link SqlTransactionLog}, which writes the
 * {@code money_transactions} table in the shared central database. Use
 * {@link #NOOP} when the audit log is disabled in config.
 */
public interface TransactionLog {

    /** Records a change (fast, buffered — no blocking I/O on the caller). */
    void record(Transaction transaction);

    /**
     * Reads the most recent transactions, newest first.
     *
     * @param player if non-null, only rows where this player is the actor,
     *               sender or recipient
     * @param limit  maximum rows to return
     */
    List<Transaction> recent(UUID player, int limit);

    /** Persists any buffered rows to the backing store. */
    void flush();

    /** Flushes and releases any resources. */
    void close();

    /** No-op log used when the audit feature is disabled. */
    TransactionLog NOOP = new TransactionLog() {
        @Override public void record(Transaction transaction) { }
        @Override public List<Transaction> recent(UUID player, int limit) { return List.of(); }
        @Override public void flush() { }
        @Override public void close() { }
    };
}
