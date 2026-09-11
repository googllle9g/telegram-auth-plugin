package net.millyland.auth.listener;

import net.millyland.auth.TgAuthPlugin;
import net.millyland.auth.storage.LinkedAccount;
import net.millyland.auth.util.PlayerDataMigrator;
import net.millyland.auth.util.UuidUtil;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.Optional;
import java.util.UUID;

public class UuidMigrationListener implements Listener {

    private final TgAuthPlugin plugin;

    public UuidMigrationListener(TgAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!plugin.cfg().migrateLinkByUsername()) return;
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;

        UUID newUuid = event.getUniqueId();
        String name = event.getName();

        if (plugin.database().findByUuid(newUuid).isPresent()) {
            return;
        }

        Optional<LinkedAccount> byName = plugin.database().findByUsername(name);
        if (byName.isEmpty() || byName.get().uuid().equals(newUuid)) {
            return;
        }

        UUID oldUuid = byName.get().uuid();

        UUID expectedOfflineUuid = UuidUtil.offlineUuidFor(name);
        boolean oldIsGenuineOffline = oldUuid.equals(expectedOfflineUuid);
        boolean newIsGenuineOffline = newUuid.equals(expectedOfflineUuid);

        if (!oldIsGenuineOffline && !newIsGenuineOffline) {
            plugin.getLogger().warning("Skipped auto-migrating the Telegram link for '" + name + "': neither "
                    + oldUuid + " nor " + newUuid + " matches the expected offline-mode UUID for this exact name ("
                    + expectedOfflineUuid + "). This is unusual and could mean two different people used this name "
                    + "at different times - not auto-migrating to be safe. Use /tgauth forcelink to link manually "
                    + "if this really is the same player.");
            return;
        }

        if (!plugin.database().migrateUuid(oldUuid, newUuid, name)) {
            return;
        }

        plugin.getLogger().info("Re-linked existing Telegram account for '" + name + "' from UUID "
                + oldUuid + " to " + newUuid + " (cracked/premium account switch). Migrating player data...");

        boolean migrated = PlayerDataMigrator.migrate(plugin, oldUuid, newUuid);
        if (migrated) {
            plugin.getLogger().info("Migrated inventory/advancements/statistics for '" + name + "' to the new UUID.");
        }
    }
}
