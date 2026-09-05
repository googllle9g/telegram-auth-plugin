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

/**
 * Minimal blocking SQLite storage. Called from an async context wherever possible
 * (Telegram update thread, or Bukkit async tasks) to avoid holding up the main thread.
 */
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

    /**
     * Adds columns introduced by later plugin versions to an existing table, without touching
     * any data already in it - so upgrading the plugin jar never requires deleting/recreating
     * the database.
     */
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

    /** Case-insensitive lookup by last-known username, used by the FastLogin hook (isRegistered)
     *  where only the player's name is known yet, before a Player object exists. */
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

    /**
     * @param telegramUsername the linking user's Telegram @handle at link time (may be null -
     *                          not every Telegram user has one set), stored purely so admins
     *                          have a way to contact the player (see /tgauth userinfo).
     */
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

    /**
     * Persists whether this player has been confirmed premium (real, Mojang-verified account),
     * so admins can check it later (e.g. via /tgauth userinfo) even after a server restart,
     * without needing to wait for a fresh FastLogin check.
     */
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

    /**
     * Re-points an existing link to a new UUID, keeping the same Telegram account attached.
     * Used when a player who previously linked while playing offline/cracked (name-based UUID)
     * later connects with their real premium (Mojang) account, or vice-versa - same person,
     * same name, different UUID - so they don't have to /link again from scratch.
     */
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
}
