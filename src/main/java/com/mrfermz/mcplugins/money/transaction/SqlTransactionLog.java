package com.mrfermz.mcplugins.money.transaction;

import com.mrfermz.mcplugins.core.db.Dialect;
import com.mrfermz.mcplugins.core.log.PluginLog;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.bukkit.plugin.Plugin;

/**
 * Writes the money audit log to the shared central database via core's
 * {@link DataSource} — money never opens its own pool (CLAUDE.md → Database).
 * The table is namespaced {@code money_transactions}.
 *
 * <p>The log is append-only and write-heavy, so rows are buffered in a queue and
 * flushed off the main thread in batches: {@link #record} enqueues and kicks a
 * debounced async flush, and {@link com.mrfermz.mcplugins.money.MoneyPlugin}
 * runs a periodic flush as a safety net. SQL is {@link Dialect}-aware so the
 * same code runs on SQLite, PostgreSQL and MySQL/MariaDB; amounts are stored as
 * text ({@link BigDecimal#toPlainString()}) so precision is exact everywhere.
 */
public final class SqlTransactionLog implements TransactionLog {

    private static final String COLUMNS =
            "ts, type, actor, from_uuid, to_uuid, amount, from_balance, to_balance, reason";

    private final Plugin plugin;
    private final DataSource dataSource;
    private final Dialect dialect;
    private final PluginLog log;
    private final String table;
    private final ConcurrentLinkedQueue<Transaction> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushQueued = new AtomicBoolean(false);

    public SqlTransactionLog(Plugin plugin, DataSource dataSource, String tablePrefix,
                             Dialect dialect, PluginLog log) {
        this.plugin = plugin;
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.log = log;
        // tablePrefix comes from core (e.g. "money_"), not user input.
        this.table = tablePrefix + "transactions";
        createTable();
    }

    private void createTable() {
        // Auto-increment PK syntax differs per engine.
        String idCol = switch (dialect) {
            case SQLITE -> "id INTEGER PRIMARY KEY AUTOINCREMENT";
            case POSTGRESQL -> "id BIGSERIAL PRIMARY KEY";
            case MYSQL, MARIADB -> "id BIGINT AUTO_INCREMENT PRIMARY KEY";
        };
        String ddl = "CREATE TABLE IF NOT EXISTS " + table + " ("
                + idCol + ", "
                + "ts BIGINT NOT NULL, "
                + "type VARCHAR(16) NOT NULL, "
                + "actor VARCHAR(36), "
                + "from_uuid VARCHAR(36), "
                + "to_uuid VARCHAR(36), "
                + "amount VARCHAR(64) NOT NULL, "
                + "from_balance VARCHAR(64), "
                + "to_balance VARCHAR(64), "
                + "reason VARCHAR(255))";
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(ddl);
        } catch (SQLException ex) {
            log.error("Failed to create table " + table, ex);
            return;
        }
        // Indexes speed up /money log filtering/ordering; harmless if they exist.
        safeIndex("idx_" + table + "_ts", "ts");
        safeIndex("idx_" + table + "_from", "from_uuid");
        safeIndex("idx_" + table + "_to", "to_uuid");
        safeIndex("idx_" + table + "_actor", "actor");
    }

    /**
     * Creates an index, tolerating "already exists" since MySQL lacks
     * {@code CREATE INDEX IF NOT EXISTS}.
     */
    private void safeIndex(String name, String column) {
        String ifNotExists = dialect.isMySqlFamily() ? "" : "IF NOT EXISTS ";
        String sql = "CREATE INDEX " + ifNotExists + name + " ON " + table + " (" + column + ")";
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException ex) {
            // MySQL throws if the index is already there — that's expected on reboot.
            if (!dialect.isMySqlFamily()) {
                log.warn("Could not create index {} on {}: {}", name, table, ex.getMessage());
            }
        }
    }

    @Override
    public void record(Transaction transaction) {
        pending.add(transaction);
        // Persist promptly without blocking the caller; coalesce bursts so only
        // one async flush is in flight at a time and it drains the whole queue.
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
        List<Transaction> batch = new ArrayList<>();
        for (Transaction t; (t = pending.poll()) != null; ) {
            batch.add(t);
        }
        String sql = "INSERT INTO " + table + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (Transaction t : batch) {
                    ps.setLong(1, t.timestamp());
                    ps.setString(2, t.type().name());
                    ps.setString(3, str(t.actor()));
                    ps.setString(4, str(t.from()));
                    ps.setString(5, str(t.to()));
                    ps.setString(6, t.amount().toPlainString());
                    ps.setString(7, plain(t.fromBalance()));
                    ps.setString(8, plain(t.toBalance()));
                    ps.setString(9, t.reason());
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
        } catch (SQLException ex) {
            // Re-queue so a transient DB hiccup doesn't drop the audit trail.
            pending.addAll(batch);
            log.error("Failed to flush " + batch.size() + " transactions to " + table, ex);
        }
    }

    @Override
    public List<Transaction> recent(UUID player, int limit) {
        String where = player != null ? " WHERE actor = ? OR from_uuid = ? OR to_uuid = ?" : "";
        String sql = "SELECT " + COLUMNS + " FROM " + table + where + " ORDER BY ts DESC, id DESC LIMIT ?";
        List<Transaction> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            if (player != null) {
                String id = player.toString();
                ps.setString(idx++, id);
                ps.setString(idx++, id);
                ps.setString(idx++, id);
            }
            ps.setInt(idx, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        } catch (SQLException ex) {
            log.error("Failed to read transactions from " + table, ex);
        }
        return out;
    }

    private Transaction read(ResultSet rs) throws SQLException {
        TransactionType type;
        try {
            type = TransactionType.valueOf(rs.getString("type"));
        } catch (IllegalArgumentException unknown) {
            type = TransactionType.TRANSFER; // forward-compat: unknown stored type
        }
        return new Transaction(
                rs.getLong("ts"),
                type,
                uuid(rs.getString("actor")),
                uuid(rs.getString("from_uuid")),
                uuid(rs.getString("to_uuid")),
                new BigDecimal(rs.getString("amount")),
                decimal(rs.getString("from_balance")),
                decimal(rs.getString("to_balance")),
                rs.getString("reason"));
    }

    @Override
    public void close() {
        flush();
    }

    private static String str(UUID id) {
        return id == null ? null : id.toString();
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
