package com.mrfermz.mcplugins.money.economy;

import com.mrfermz.mcplugins.core.api.EconomyResponse;
import com.mrfermz.mcplugins.core.api.EconomyService;
import com.mrfermz.mcplugins.money.storage.MoneyStorage;
import com.mrfermz.mcplugins.money.transaction.Transaction;
import com.mrfermz.mcplugins.money.transaction.TransactionLog;
import com.mrfermz.mcplugins.money.transaction.TransactionType;
import com.mrfermz.mcplugins.money.transaction.TxMeta;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link EconomyService} implementation. Holds balances in a thread-safe
 * in-memory cache and mirrors every mutation to {@link MoneyStorage}.
 *
 * <p>All math runs through {@link BigDecimal} rounded to the configured scale;
 * balances never go negative (no overdraft).
 *
 * <p>Every successful mutation is recorded to the {@link TransactionLog} audit
 * trail. The plain {@link EconomyService} interface methods log with no human
 * actor (system); the {@link TxMeta}-aware overloads let the command layer
 * attribute the change to whoever ran the command.
 */
public final class MoneyEconomyService implements EconomyService {

    private final ConcurrentHashMap<UUID, BigDecimal> balances = new ConcurrentHashMap<>();
    private final MoneyStorage storage;
    private final CurrencySettings settings;
    private final TransactionLog txLog;
    private final DecimalFormat format;

    public MoneyEconomyService(MoneyStorage storage, CurrencySettings settings, TransactionLog txLog) {
        this.storage = storage;
        this.settings = settings;
        this.txLog = txLog;
        this.balances.putAll(storage.loadAll());

        StringBuilder pattern = new StringBuilder("#,##0");
        if (settings.decimals() > 0) {
            pattern.append('.');
            pattern.append("0".repeat(settings.decimals()));
        }
        this.format = new DecimalFormat(pattern.toString(), DecimalFormatSymbols.getInstance(Locale.US));
    }

    private BigDecimal scaled(BigDecimal value) {
        return value.setScale(settings.decimals(), RoundingMode.HALF_UP);
    }

    /** Read-only view of every cached balance (used by {@code /baltop}). */
    public Map<UUID, BigDecimal> snapshot() {
        return Collections.unmodifiableMap(balances);
    }

    /**
     * Seeds a new account with the starting balance if one doesn't exist yet.
     *
     * @return the starting balance if a new account was just created, otherwise
     *         empty (the player was already known)
     */
    public Optional<BigDecimal> seedIfAbsent(UUID player) {
        BigDecimal start = scaled(settings.startingBalance());
        return balances.putIfAbsent(player, start) == null ? Optional.of(start) : Optional.empty();
    }

    @Override
    public String currencyNameSingular() {
        return settings.nameSingular();
    }

    @Override
    public String currencyNamePlural() {
        return settings.namePlural();
    }

    @Override
    public String format(BigDecimal amount) {
        return settings.symbol() + format.format(scaled(amount));
    }

    @Override
    public boolean hasAccount(UUID player) {
        return balances.containsKey(player);
    }

    @Override
    public BigDecimal getBalance(UUID player) {
        return balances.computeIfAbsent(player, id -> scaled(settings.startingBalance()));
    }

    @Override
    public boolean has(UUID player, BigDecimal amount) {
        return getBalance(player).compareTo(scaled(amount)) >= 0;
    }

    @Override
    public EconomyResponse deposit(UUID player, BigDecimal amount) {
        return deposit(player, amount, TxMeta.system(TransactionType.DEPOSIT));
    }

    /** Credits an account and records the change under the given attribution. */
    public EconomyResponse deposit(UUID player, BigDecimal amount, TxMeta meta) {
        BigDecimal value = scaled(amount);
        if (value.signum() < 0) {
            return EconomyResponse.fail(EconomyResponse.Error.NEGATIVE_AMOUNT, value, getBalance(player));
        }
        BigDecimal updated = applyDeposit(player, value);
        txLog.record(new Transaction(now(), meta.type(), meta.actor(),
                null, player, value, null, updated, meta.reason()));
        return EconomyResponse.ok(value, updated);
    }

    @Override
    public EconomyResponse withdraw(UUID player, BigDecimal amount) {
        return withdraw(player, amount, TxMeta.system(TransactionType.WITHDRAW));
    }

    /** Debits an account if affordable and records the change. */
    public EconomyResponse withdraw(UUID player, BigDecimal amount, TxMeta meta) {
        BigDecimal value = scaled(amount);
        if (value.signum() < 0) {
            return EconomyResponse.fail(EconomyResponse.Error.NEGATIVE_AMOUNT, value, getBalance(player));
        }
        BigDecimal updated = applyWithdraw(player, value);
        if (updated == null) {
            return EconomyResponse.fail(EconomyResponse.Error.INSUFFICIENT_FUNDS, value, getBalance(player));
        }
        txLog.record(new Transaction(now(), meta.type(), meta.actor(),
                player, null, value, updated, null, meta.reason()));
        return EconomyResponse.ok(value, updated);
    }

    @Override
    public EconomyResponse setBalance(UUID player, BigDecimal amount) {
        return setBalance(player, amount, TxMeta.system(TransactionType.SET));
    }

    /** Overwrites a balance with an exact value and records the change. */
    public EconomyResponse setBalance(UUID player, BigDecimal amount, TxMeta meta) {
        BigDecimal value = scaled(amount);
        if (value.signum() < 0) {
            return EconomyResponse.fail(EconomyResponse.Error.NEGATIVE_AMOUNT, value, getBalance(player));
        }
        balances.put(player, value);
        storage.put(player, value);
        txLog.record(new Transaction(now(), meta.type(), meta.actor(),
                null, player, value, null, value, meta.reason()));
        return EconomyResponse.ok(value, value);
    }

    @Override
    public EconomyResponse transfer(UUID from, UUID to, BigDecimal amount) {
        return transfer(from, to, amount, new TxMeta(TransactionType.TRANSFER, from, null));
    }

    /**
     * Moves money between two accounts atomically and records a single audit row
     * for the whole transfer (not separate debit/credit entries).
     */
    public synchronized EconomyResponse transfer(UUID from, UUID to, BigDecimal amount, TxMeta meta) {
        BigDecimal value = scaled(amount);
        if (value.signum() < 0) {
            return EconomyResponse.fail(EconomyResponse.Error.NEGATIVE_AMOUNT, value, getBalance(from));
        }
        BigDecimal fromBalance = applyWithdraw(from, value);
        if (fromBalance == null) {
            return EconomyResponse.fail(EconomyResponse.Error.INSUFFICIENT_FUNDS, value, getBalance(from));
        }
        BigDecimal toBalance = applyDeposit(to, value);
        txLog.record(new Transaction(now(), meta.type(), meta.actor(),
                from, to, value, fromBalance, toBalance, meta.reason()));
        return EconomyResponse.ok(value, fromBalance);
    }

    // ----- internal mutations (no logging; callers above record one row) -----

    /** Credits {@code value} (already scaled & non-negative); returns new balance. */
    private BigDecimal applyDeposit(UUID player, BigDecimal value) {
        BigDecimal updated = balances.merge(player, value, (cur, add) -> scaled(cur.add(add)));
        storage.put(player, updated);
        return updated;
    }

    /**
     * Debits {@code value} (already scaled & non-negative) if affordable.
     *
     * @return the new balance, or {@code null} if the player can't afford it
     */
    private BigDecimal applyWithdraw(UUID player, BigDecimal value) {
        getBalance(player); // ensure account is seeded before compute
        BigDecimal[] result = new BigDecimal[1];
        balances.compute(player, (id, cur) -> {
            BigDecimal current = cur == null ? scaled(settings.startingBalance()) : cur;
            if (current.compareTo(value) < 0) {
                result[0] = null;
                return current;
            }
            BigDecimal updated = scaled(current.subtract(value));
            result[0] = updated;
            return updated;
        });
        if (result[0] == null) {
            return null;
        }
        storage.put(player, result[0]);
        return result[0];
    }

    private static Instant now() {
        return Instant.now();
    }
}
