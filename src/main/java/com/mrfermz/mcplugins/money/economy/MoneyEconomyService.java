package com.mrfermz.mcplugins.money.economy;

import com.mrfermz.mcplugins.core.api.EconomyResponse;
import com.mrfermz.mcplugins.core.api.EconomyService;
import com.mrfermz.mcplugins.money.storage.MoneyStorage;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 */
public final class MoneyEconomyService implements EconomyService {

    private final ConcurrentHashMap<UUID, BigDecimal> balances = new ConcurrentHashMap<>();
    private final MoneyStorage storage;
    private final CurrencySettings settings;
    private final DecimalFormat format;

    public MoneyEconomyService(MoneyStorage storage, CurrencySettings settings) {
        this.storage = storage;
        this.settings = settings;
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
        BigDecimal value = scaled(amount);
        if (value.signum() < 0) {
            return EconomyResponse.fail(EconomyResponse.Error.NEGATIVE_AMOUNT, value, getBalance(player));
        }
        BigDecimal updated = balances.merge(player, value, (cur, add) -> scaled(cur.add(add)));
        storage.put(player, updated);
        return EconomyResponse.ok(value, updated);
    }

    @Override
    public EconomyResponse withdraw(UUID player, BigDecimal amount) {
        BigDecimal value = scaled(amount);
        if (value.signum() < 0) {
            return EconomyResponse.fail(EconomyResponse.Error.NEGATIVE_AMOUNT, value, getBalance(player));
        }
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
            return EconomyResponse.fail(EconomyResponse.Error.INSUFFICIENT_FUNDS, value, getBalance(player));
        }
        storage.put(player, result[0]);
        return EconomyResponse.ok(value, result[0]);
    }

    @Override
    public EconomyResponse setBalance(UUID player, BigDecimal amount) {
        BigDecimal value = scaled(amount);
        if (value.signum() < 0) {
            return EconomyResponse.fail(EconomyResponse.Error.NEGATIVE_AMOUNT, value, getBalance(player));
        }
        balances.put(player, value);
        storage.put(player, value);
        return EconomyResponse.ok(value, value);
    }

    @Override
    public synchronized EconomyResponse transfer(UUID from, UUID to, BigDecimal amount) {
        BigDecimal value = scaled(amount);
        if (value.signum() < 0) {
            return EconomyResponse.fail(EconomyResponse.Error.NEGATIVE_AMOUNT, value, getBalance(from));
        }
        if (!has(from, value)) {
            return EconomyResponse.fail(EconomyResponse.Error.INSUFFICIENT_FUNDS, value, getBalance(from));
        }
        EconomyResponse debit = withdraw(from, value);
        if (!debit.success()) {
            return debit;
        }
        deposit(to, value);
        return debit;
    }
}
