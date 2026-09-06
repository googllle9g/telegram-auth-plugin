package net.millyland.auth.config;

import net.millyland.auth.TgAuthPlugin;
import net.millyland.auth.util.YamlMerger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

public class Config {

    private static final String FILE_NAME = "config.yml";

    private final TgAuthPlugin plugin;
    private FileConfiguration cfg;

    public Config(TgAuthPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    /**
     * Loads config.yml, extracting the bundled default on first run, and merging in any new
     * keys a plugin update introduced without touching values already set by the server owner.
     * See {@link YamlMerger} for details/caveats.
     */
    public void load() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        File configFile = new File(plugin.getDataFolder(), FILE_NAME);
        if (!configFile.exists()) {
            plugin.saveResource(FILE_NAME, false);
        } else {
            YamlMerger.mergeMissingKeys(plugin, FILE_NAME, configFile);
        }

        this.cfg = YamlConfiguration.loadConfiguration(configFile);
    }

    public String language() {
        return cfg.getString("language", "ru");
    }

    public String botToken() {
        return cfg.getString("telegram.bot-token", "");
    }

    public String botUsername() {
        return cfg.getString("telegram.bot-username", "");
    }

    public int codeExpireSeconds() {
        return cfg.getInt("auth.code-expire-seconds", 300);
    }

    public int confirmTimeoutSeconds() {
        return cfg.getInt("auth.confirm-timeout-seconds", 90);
    }

    public int authTimeoutSeconds() {
        return cfg.getInt("auth.auth-timeout-seconds", 120);
    }

    public int reminderIntervalSeconds() {
        return cfg.getInt("auth.reminder-interval-seconds", 20);
    }

    public boolean applyBlindness() {
        return cfg.getBoolean("auth.apply-blindness", true);
    }

    public boolean applySlowness() {
        return cfg.getBoolean("auth.apply-slowness", true);
    }

    public boolean migrateLinkByUsername() {
        return cfg.getBoolean("auth.migrate-link-by-username", false);
    }

    public boolean migrationOverwriteExistingData() {
        return cfg.getBoolean("auth.migration-overwrite-existing-data", false);
    }

    public boolean fastLoginEnabled() {
        return cfg.getBoolean("fastlogin.enabled", true);
    }

    public boolean premiumSkipConfirmation() {
        return cfg.getBoolean("fastlogin.premium-skip-confirmation", true);
    }

    public int premiumCheckWaitSeconds() {
        return cfg.getInt("fastlogin.premium-check-wait-seconds", 4);
    }

    public boolean addToFastLoginPremiumList() {
        return cfg.getBoolean("fastlogin.add-to-fastlogin-premium-list", true);
    }

    public String storageFile() {
        return cfg.getString("storage.file", "database.db");
    }

    public int linkMaxAttempts() {
        return cfg.getInt("security.link-max-attempts", 5);
    }

    public int linkAttemptWindowSeconds() {
        return cfg.getInt("security.link-attempt-window-seconds", 60);
    }

    public int linkLockoutSeconds() {
        return cfg.getInt("security.link-lockout-seconds", 300);
    }

    public int globalLinkMaxAttempts() {
        return cfg.getInt("security.global-link-max-attempts", 20);
    }

    public int globalLinkAttemptWindowSeconds() {
        return cfg.getInt("security.global-link-attempt-window-seconds", 60);
    }

    public int globalLinkLockoutSeconds() {
        return cfg.getInt("security.global-link-lockout-seconds", 120);
    }
}
