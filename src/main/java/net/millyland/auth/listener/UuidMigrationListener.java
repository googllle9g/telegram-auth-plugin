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

/**
 * Handles the cracked ⇄ premium account switch (same username, different UUID) as early as
 * possible in the login sequence - before Minecraft loads player data for the connecting UUID -
 * so the player's inventory, advancements, statistics and OP status carry over instead of
 * appearing to reset. See {@link PlayerDataMigrator} for what actually gets migrated.
 * <p>
 * AsyncPlayerPreLoginEvent already runs off the main thread and the server waits for it to
 * finish before continuing the login, so blocking DB/file I/O here is the intended pattern
 * (same as any other auth plugin).
 */
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
            return; // already linked under this exact UUID, nothing to migrate
        }

        Optional<LinkedAccount> byName = plugin.database().findByUsername(name);
        if (byName.isEmpty() || byName.get().uuid().equals(newUuid)) {
            return; // no existing link under a different UUID for this name
        }

        UUID oldUuid = byName.get().uuid();

        // Safety check: the offline ("cracked") UUID for a given name is a deterministic
        // function of that name - it's exactly what vanilla/Bukkit computes when running in
        // offline mode, never anything else. So if either side of this migration is that exact
        // computed value, we can be certain it really is the vanilla cracked account for this
        // name, not just some other UUID that happens to share a stored username (which, unlike
        // the offline UUID, could in theory be stale - e.g. two different real people using the
        // same name at different times). A licensed UUID, by contrast, is assigned by Mojang
        // effectively at random and simply cannot be derived from the name - the only way to
        // learn it is an actual login (which is what's happening right now) or a Mojang API
        // lookup, so there's no equivalent check to run on that side.
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
