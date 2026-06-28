package com.mrfermz.mcplugins.money;

import com.mrfermz.mcplugins.core.CoreApi;
import com.mrfermz.mcplugins.core.EcosystemData;
import com.mrfermz.mcplugins.core.api.EconomyService;
import com.mrfermz.mcplugins.core.db.DatabaseService;
import com.mrfermz.mcplugins.core.log.PluginLog;
import com.mrfermz.mcplugins.money.command.MoneyCommand;
import com.mrfermz.mcplugins.money.economy.CurrencySettings;
import com.mrfermz.mcplugins.money.economy.MoneyEconomyService;
import com.mrfermz.mcplugins.money.listener.AccountListener;
import com.mrfermz.mcplugins.money.storage.MoneyStorage;
import com.mrfermz.mcplugins.money.storage.SqlMoneyStorage;
import java.math.BigDecimal;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Economy/currency plugin. Provides the in-game money used by the main system
 * that arrives later, exposes it through the shared {@link EconomyService}
 * (registered on Bukkit's {@code ServicesManager}), and ships the player/admin
 * commands.
 */
public final class MoneyPlugin extends JavaPlugin {

    private static final long FLUSH_INTERVAL_TICKS = 20L * 60; // 1 minute

    /** Subfolder under the shared {@code plugins/mrfermz/} ecosystem dir. */
    private static final String MODULE = "money";

    private PluginLog log;
    private MoneyStorage storage;
    private MoneyEconomyService economy;

    @Override
    public void onEnable() {
        this.log = PluginLog.of(this);

        // All on-server files live together under plugins/mrfermz/money/ via the
        // core helper — not the per-plugin getDataFolder() (see CLAUDE.md).
        FileConfiguration config = EcosystemData.config(this, MODULE);
        CurrencySettings settings = readSettings(config);

        // Persist via the shared central database owned by core — money never
        // opens its own pool (CLAUDE.md → Database).
        String storageType = config.getString("storage.type", "sqlite");
        DatabaseService db = CoreApi.database(getServer()).orElse(null);
        if (db == null) {
            log.error("Central DatabaseService not available from minecraft-plugin-core — "
                    + "disabling MoneyPlugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!"sqlite".equalsIgnoreCase(storageType)) {
            log.warn("storage.type '{}' is not recognised; using the central DB.", storageType);
        }
        this.storage = new SqlMoneyStorage(this, db.dataSource(), db.tablePrefix(MODULE), db.dialect(), log);
        this.economy = new MoneyEconomyService(storage, settings);

        // Expose the economy to the rest of the ecosystem.
        getServer().getServicesManager().register(
                EconomyService.class, economy, this, ServicePriority.Normal);

        // New players get a DB row the moment they join.
        getServer().getPluginManager().registerEvents(
                new AccountListener(this, economy, storage), this);

        registerCommands(settings.startingBalance());

        // Buffered writes are flushed off the main thread on a timer.
        getServer().getAsyncScheduler().runAtFixedRate(this,
                task -> storage.flush(),
                FLUSH_INTERVAL_TICKS * 50, FLUSH_INTERVAL_TICKS * 50,
                java.util.concurrent.TimeUnit.MILLISECONDS);

        log.info("Economy ready: currency '{}' (storage: {} via central DB).",
                settings.namePlural(), storageType.toLowerCase());
    }

    @Override
    public void onDisable() {
        if (economy != null) {
            getServer().getServicesManager().unregister(EconomyService.class, economy);
        }
        if (storage != null) {
            storage.close();
        }
        if (log != null) {
            log.info("Economy disabled, balances flushed.");
        }
    }

    private CurrencySettings readSettings(FileConfiguration config) {
        return new CurrencySettings(
                config.getString("currency.name-singular", "Coin"),
                config.getString("currency.name-plural", "Coins"),
                config.getString("currency.symbol", "$"),
                config.getInt("currency.decimals", 2),
                BigDecimal.valueOf(config.getDouble("currency.starting-balance", 100.0)));
    }

    private void registerCommands(BigDecimal startingBalance) {
        MoneyCommand handler = new MoneyCommand(economy, startingBalance);
        var money = getCommand("money");
        money.setExecutor(handler);
        money.setTabCompleter(handler);
    }
}
