package com.mrfermz.mcplugins.money.storage;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence seam for player balances.
 *
 * <p>The economy service keeps balances in memory and mirrors every change here
 * via {@link #put}. Writes are buffered; {@link #flush()} is what actually hits
 * the backing store, so it can be called off the main thread on a timer.
 *
 * <p>The implementation is {@link SqlMoneyStorage}, which writes {@code money_*}
 * tables in the shared central database exposed by {@code minecraft-plugin-core}.
 * Money never opens its own connection pool.
 */
public interface MoneyStorage {

    /** Loads every known balance to seed the in-memory cache at startup. */
    Map<UUID, BigDecimal> loadAll();

    /**
     * Persists a brand-new account immediately (insert-if-absent), rather than
     * waiting for the next {@link #flush()}. Used when a new player joins so the
     * row exists in the database right away. Safe to call off the main thread.
     */
    void create(UUID player, BigDecimal balance);

    /** Records a balance change in the write buffer (fast, no I/O). */
    void put(UUID player, BigDecimal balance);

    /** Persists buffered changes to the backing store if anything is dirty. */
    void flush();

    /** Flushes and releases any resources. */
    void close();
}
