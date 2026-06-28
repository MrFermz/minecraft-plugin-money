package com.mrfermz.mcplugins.money.storage;

import com.mrfermz.mcplugins.core.db.Dialect;
import com.mrfermz.mcplugins.core.log.PluginLog;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.bukkit.plugin.Plugin;

/**
 * Stores balances in the shared central database via the {@link DataSource}
 * owned by {@code minecraft-plugin-core} — money never opens its own pool
 * (CLAUDE.md → Database). Tables are namespaced with the {@code money_} prefix.
 *
 * <p>SQL is {@link Dialect}-aware so the same plugin works on SQLite, PostgreSQL
 * and MySQL/MariaDB. Balances are stored as text
 * ({@link BigDecimal#toPlainString()}) so precision is exact on every engine.
 *
 * <p>Every {@link #put} buffers the row and kicks a debounced async flush, so a
 * change (e.g. {@code /money set}) lands in the database within milliseconds
 * without blocking the main thread; rapid changes coalesce into one batch. A
 * periodic flush in {@link com.mrfermz.mcplugins.money.MoneyPlugin} is a safety
 * net. New accounts are written straight away via {@link #create}.
 */
public final class SqlMoneyStorage implements MoneyStorage {

    private final Plugin plugin;
    private final DataSource dataSource;
    private final Dialect dialect;
    private final PluginLog log;
    private final String table;
    private final ConcurrentHashMap<UUID, BigDecimal> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean flushQueued = new AtomicBoolean(false);

    public SqlMoneyStorage(Plugin plugin, DataSource dataSource, String tablePrefix, Dialect dialect, PluginLog log) {
        this.plugin = plugin;
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.log = log;
        // tablePrefix comes from core (e.g. "money_"), not user input.
        this.table = tablePrefix + "balances";
        createTable();
    }

    private void createTable() {
        // Every table has a generated UUID surrogate id as its PK (ecosystem
        // convention); the player uuid is a UNIQUE natural key used for upserts.
        // UUIDs are fixed-width so VARCHAR(36) works on every engine; balance is
        // text for exact BigDecimal precision.
        String balanceType = dialect.isMySqlFamily() ? "VARCHAR(64)" : "TEXT";
        String ddl = "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "id VARCHAR(36) PRIMARY KEY, "
                + "uuid VARCHAR(36) NOT NULL UNIQUE, "
                + "balance " + balanceType + " NOT NULL)";
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(ddl);
        } catch (SQLException ex) {
            log.error("Failed to create table " + table, ex);
        }
    }

    @Override
    public Map<UUID, BigDecimal> loadAll() {
        Map<UUID, BigDecimal> out = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, balance FROM " + table)) {
            while (rs.next()) {
                try {
                    out.put(UUID.fromString(rs.getString("uuid")), new BigDecimal(rs.getString("balance")));
                } catch (IllegalArgumentException badRow) {
                    log.warn("Skipping malformed row in {}: uuid={}", table, rs.getString("uuid"));
                }
            }
        } catch (SQLException ex) {
            log.error("Failed to load balances from " + table, ex);
        }
        return out;
    }

    @Override
    public void create(UUID player, BigDecimal balance) {
        String sql = dialect.isMySqlFamily()
                ? "INSERT IGNORE INTO " + table + " (id, uuid, balance) VALUES (?, ?, ?)"
                : "INSERT INTO " + table + " (id, uuid, balance) VALUES (?, ?, ?) ON CONFLICT (uuid) DO NOTHING";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, player.toString());
            ps.setString(3, balance.toPlainString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.error("Failed to create account row for " + player + " in " + table, ex);
        }
    }

    @Override
    public void put(UUID player, BigDecimal balance) {
        pending.put(player, balance);
        // Persist promptly without blocking the caller. Coalesce bursts: only one
        // async flush is in flight at a time, and it drains everything pending.
        if (flushQueued.compareAndSet(false, true)) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                flushQueued.set(false);
                flush();
            });
        }
    }

    @Override
    public synchronized void flush() {
        if (pending.isEmpty()) {
            return;
        }
        // Snapshot the dirty rows; remove-if-unchanged afterwards so concurrent
        // puts that arrive mid-flush aren't lost.
        Map<UUID, BigDecimal> batch = new HashMap<>(pending);
        String sql = dialect.isMySqlFamily()
                ? "INSERT INTO " + table + " (id, uuid, balance) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE balance = VALUES(balance)"
                : "INSERT INTO " + table + " (id, uuid, balance) VALUES (?, ?, ?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET balance = excluded.balance";
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (Map.Entry<UUID, BigDecimal> e : batch.entrySet()) {
                    // New row id is only used on insert; on uuid conflict the
                    // existing row's id is kept.
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, e.getKey().toString());
                    ps.setString(3, e.getValue().toPlainString());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
            // Only clear rows whose value is unchanged since the snapshot.
            batch.forEach(pending::remove);
        } catch (SQLException ex) {
            log.error("Failed to flush " + batch.size() + " balances to " + table, ex);
        }
    }

    @Override
    public void close() {
        flush();
    }
}
