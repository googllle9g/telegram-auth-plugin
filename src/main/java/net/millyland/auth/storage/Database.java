package net.millyland.auth.storage;

import net.millyland.auth.TgAuthPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class Database {

    private final TgAuthPlugin plugin;
    private Connection connection;

    public Database(TgAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void connect() throws SQLException {
        File dbFile = new File(plugin.getDataFolder(), plugin.cfg().storageFile());
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS linked_accounts (
                    uuid TEXT PRIMARY KEY,
                    telegram_id INTEGER UNIQUE NOT NULL,
                    username TEXT,
                    linked_at INTEGER NOT NULL
                )
            """);
        }
        migrateSchema();
    }

    private void migrateSchema() throws SQLException {
        Set<String> existing = new HashSet<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(linked_accounts)")) {
            while (rs.next()) {
                existing.add(rs.getString("name").toLowerCase(Locale.ROOT));
            }
        }

        if (!existing.contains("telegram_username")) {
            try (Statement st = connection.createStatement()) {
                st.execute("ALTER TABLE linked_accounts ADD COLUMN telegram_username TEXT");
            }
            plugin.getLogger().info("Database schema updated: added telegram_username column.");
        }

        if (!existing.contains("premium")) {
            try (Statement st = connection.createStatement()) {
                st.execute("ALTER TABLE linked_accounts ADD COLUMN premium INTEGER NOT NULL DEFAULT 0");
            }
            plugin.getLogger().info("Database schema updated: added premium column.");
        }

        if (!existing.contains("last_confirmed_ip")) {
            try (Statement st = connection.createStatement()) {
                st.execute("ALTER TABLE linked_accounts ADD COLUMN last_confirmed_ip TEXT");
                st.execute("ALTER TABLE linked_accounts ADD COLUMN last_confirmed_at INTEGER NOT NULL DEFAULT 0");
            }
            plugin.getLogger().info("Database schema updated: added last_confirmed_ip/last_confirmed_at columns.");
        }
    }

    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    private static final String COLUMNS = "uuid, telegram_id, username, linked_at, telegram_username, premium";

    public synchronized Optional<LinkedAccount> findByUuid(UUID uuid) {
        String sql = "SELECT " + COLUMNS + " FROM linked_accounts WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (findByUuid): " + e.getMessage());
        }
        return Optional.empty();
    }

    public synchronized Optional<LinkedAccount> findByTelegramId(long telegramId) {
        String sql = "SELECT " + COLUMNS + " FROM linked_accounts WHERE telegram_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, telegramId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (findByTelegramId): " + e.getMessage());
        }
        return Optional.empty();
    }

    public synchronized Optional<LinkedAccount> findByUsername(String username) {
        String sql = "SELECT " + COLUMNS + " FROM linked_accounts WHERE LOWER(username) = LOWER(?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (findByUsername): " + e.getMessage());
        }
        return Optional.empty();
    }

    public synchronized boolean link(UUID uuid, long telegramId, String username, String telegramUsername) {
        String sql = "INSERT INTO linked_accounts (uuid, telegram_id, username, linked_at, telegram_username, premium) "
                + "VALUES (?, ?, ?, ?, ?, 0)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, telegramId);
            ps.setString(3, username);
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, telegramUsername);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (link): " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean unlink(UUID uuid) {
        String sql = "DELETE FROM linked_accounts WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (unlink): " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean setPremium(UUID uuid, boolean premium) {
        String sql = "UPDATE linked_accounts SET premium = ? WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, premium ? 1 : 0);
            ps.setString(2, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (setPremium): " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean migrateUuid(UUID oldUuid, UUID newUuid, String newUsername) {
        String sql = "UPDATE linked_accounts SET uuid = ?, username = ? WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, newUuid.toString());
            ps.setString(2, newUsername);
            ps.setString(3, oldUuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (migrateUuid): " + e.getMessage());
            return false;
        }
    }

    private LinkedAccount map(ResultSet rs) throws SQLException {
        return new LinkedAccount(
                UUID.fromString(rs.getString("uuid")),
                rs.getLong("telegram_id"),
                rs.getString("username"),
                rs.getLong("linked_at"),
                rs.getString("telegram_username"),
                rs.getInt("premium") != 0
        );
    }

    public synchronized java.util.List<LinkedAccount> findPage(int offset, int limit) {
        java.util.List<LinkedAccount> results = new java.util.ArrayList<>();
        String sql = "SELECT " + COLUMNS + " FROM linked_accounts ORDER BY linked_at DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(map(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (findPage): " + e.getMessage());
        }
        return results;
    }

    public synchronized int countAll() {
        String sql = "SELECT COUNT(*) FROM linked_accounts";
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (countAll): " + e.getMessage());
        }
        return 0;
    }

    public synchronized boolean matchesRecentIp(UUID uuid, String ip, int cooldownSeconds) {
        String sql = "SELECT last_confirmed_ip, last_confirmed_at FROM linked_accounts WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                String storedIp = rs.getString("last_confirmed_ip");
                long storedAt = rs.getLong("last_confirmed_at");
                if (storedIp == null || !storedIp.equals(ip)) return false;
                return System.currentTimeMillis() - storedAt <= cooldownSeconds * 1000L;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (matchesRecentIp): " + e.getMessage());
            return false;
        }
    }

    public synchronized void recordConfirmedIp(UUID uuid, String ip) {
        String sql = "UPDATE linked_accounts SET last_confirmed_ip = ?, last_confirmed_at = ? WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (recordConfirmedIp): " + e.getMessage());
        }
    }

    public synchronized void revokeTrustedIpIfUsedByOtherAccount(UUID excludeUuid, String ip) {
        String sql = "UPDATE linked_accounts SET last_confirmed_ip = NULL, last_confirmed_at = 0 "
                + "WHERE last_confirmed_ip = ? AND uuid != ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.setString(2, excludeUuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (revokeTrustedIpIfUsedByOtherAccount): " + e.getMessage());
        }
    }

    public synchronized void revokeTrustedIpIfMismatched(UUID uuid, String currentIp) {
        String sql = "UPDATE linked_accounts SET last_confirmed_ip = NULL, last_confirmed_at = 0 "
                + "WHERE uuid = ? AND last_confirmed_ip IS NOT NULL AND last_confirmed_ip != ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, currentIp);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (revokeTrustedIpIfMismatched): " + e.getMessage());
        }
    }
}
