package net.millyland.auth;

import net.millyland.auth.auth.AuthManager;
import net.millyland.auth.command.TgAuthCommand;
import net.millyland.auth.command.TgCodeCommand;
import net.millyland.auth.config.Config;
import net.millyland.auth.hook.FastLoginHook;
import net.millyland.auth.hook.LuckPermsHook;
import net.millyland.auth.lang.Lang;
import net.millyland.auth.listener.PlayerJoinQuitListener;
import net.millyland.auth.listener.PlayerProtectListener;
import net.millyland.auth.listener.UuidMigrationListener;
import net.millyland.auth.storage.Database;
import net.millyland.auth.telegram.TelegramService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class TgAuthPlugin extends JavaPlugin {

    private Config config;
    private Lang lang;
    private Database database;
    private AuthManager authManager;
    private FastLoginHook fastLoginHook;
    private LuckPermsHook luckPermsHook;
    private TelegramService telegramService;
    private File primaryWorldContainer;

    @Override
    public void onEnable() {
        this.config = new Config(this);
        this.lang = new Lang(this);

        this.database = new Database(this);
        try {
            database.connect();
        } catch (Exception e) {
            getLogger().severe("Could not connect to the database, disabling plugin: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.authManager = new AuthManager(this);
        this.fastLoginHook = new FastLoginHook(this);
        this.luckPermsHook = new LuckPermsHook(this);

        if (config.botToken() == null || config.botToken().isBlank()
                || config.botToken().equals("PUT_YOUR_BOT_TOKEN_HERE")) {
            getLogger().severe("Telegram bot token is not configured in config.yml! The plugin will not work until you set telegram.bot-token.");
        } else {
            this.telegramService = new TelegramService(this);
            try {
                telegramService.start();
                getLogger().info("Telegram bot started.");
            } catch (Exception e) {
                getLogger().severe("Failed to start Telegram bot: " + e.getMessage());
            }
        }

        getServer().getPluginManager().registerEvents(new PlayerJoinQuitListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerProtectListener(this), this);
        getServer().getPluginManager().registerEvents(new UuidMigrationListener(this), this);

        this.primaryWorldContainer = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0).getWorldFolder();
        if (primaryWorldContainer == null) {
            getLogger().warning("No world was loaded at plugin startup - cracked/premium player data migration "
                    + "will not work until the server is restarted with a world present.");
        }

        TgAuthCommand tgAuthCommand = new TgAuthCommand(this);
        getCommand("tgauth").setExecutor(tgAuthCommand);
        getCommand("tgauth").setTabCompleter(tgAuthCommand);
        getCommand("tgcode").setExecutor(new TgCodeCommand(this));

        if (config.migrateLinkByUsername()) {
            getLogger().info("auth.migrate-link-by-username is enabled - cracked/premium UUID switches for the "
                    + "same username will re-link automatically. This requires FastLogin's own premiumUuid: true "
                    + "AND secondAttemptCracked: true settings to be enabled to work correctly and safely - run "
                    + "/tgauth fastlogin to check them. See config.yml for details.");

            if (config.migrationOverwriteExistingData()) {
                getLogger().info("auth.migration-overwrite-existing-data is enabled (default) - migrations will "
                        + "overwrite any playerdata/advancements/stats already present for the destination UUID. "
                        + "Safe on a fresh server or one where TgAuth/FastLogin were set up from the start. If "
                        + "you added this setup to an ALREADY-RUNNING server with real players who had progress "
                        + "under their own premium UUID before this existed, turn this off in config.yml.");
            }
        }

        getLogger().info("TgAuth enabled.");
    }

    @Override
    public void onDisable() {
        if (telegramService != null) {
            try {
                telegramService.onClosing();
            } catch (Throwable ignored) {
            }
        }
        if (database != null) {
            database.close();
        }
    }

    public Config cfg() {
        return config;
    }

    public Lang lang() {
        return lang;
    }

    public Database database() {
        return database;
    }

    public AuthManager authManager() {
        return authManager;
    }

    public FastLoginHook fastLoginHook() {
        return fastLoginHook;
    }

    public LuckPermsHook luckPermsHook() {
        return luckPermsHook;
    }

    public TelegramService telegram() {
        return telegramService;
    }

    public File primaryWorldContainer() {
        return primaryWorldContainer;
    }
}
