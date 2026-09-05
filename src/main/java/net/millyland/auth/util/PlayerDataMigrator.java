package net.millyland.auth.util;

import net.millyland.auth.TgAuthPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * When TgAuth re-points a Telegram link from an old UUID to a new one (see
 * {@link net.millyland.auth.storage.Database#migrateUuid}, used when the same player switches
 * between playing cracked/offline and playing on their real licensed account), Minecraft itself
 * still treats those as two completely unrelated players: separate inventory/playerdata files,
 * separate advancements/statistics, and a separate (missing) op entry. Left alone, the player
 * would appear to have lost everything - inventory, enderchest, advancements, and OP status -
 * the moment they first connect with the "other" UUID.
 * <p>
 * This class copies those files and the op flag over to the new UUID. It must run BEFORE
 * Minecraft loads the new player's (currently empty) data from disk, which is why it's called
 * from an {@code AsyncPlayerPreLoginEvent} listener rather than {@code PlayerJoinEvent} - by
 * the time PlayerJoinEvent fires, the (empty) player data for the new UUID has already been
 * loaded into memory and it's too late for a file-level migration to have any visible effect
 * for that session.
 */
public final class PlayerDataMigrator {

    private PlayerDataMigrator() {
    }

    /**
     * @return true if any file was actually migrated (useful for logging).
     */
    public static boolean migrate(TgAuthPlugin plugin, UUID oldUuid, UUID newUuid) {
        File worldContainer = plugin.primaryWorldContainer();
        if (worldContainer == null) {
            plugin.getLogger().warning("Could not migrate player data for " + oldUuid + " -> " + newUuid
                    + ": no world was loaded at plugin startup.");
            return false;
        }
        plugin.getLogger().info("Migrating player data for " + oldUuid + " -> " + newUuid
                + " under world folder " + worldContainer.getAbsolutePath());

        boolean overwrite = plugin.cfg().migrationOverwriteExistingData();

        boolean migratedAny = false;
        migratedAny |= migrateFile(plugin, "playerdata (inventory/position)",
                new File(worldContainer, "playerdata/" + oldUuid + ".dat"),
                new File(worldContainer, "playerdata/" + newUuid + ".dat"), overwrite);
        migratedAny |= migrateFile(plugin, "advancements",
                new File(worldContainer, "advancements/" + oldUuid + ".json"),
                new File(worldContainer, "advancements/" + newUuid + ".json"), overwrite);
        migratedAny |= migrateFile(plugin, "stats",
                new File(worldContainer, "stats/" + oldUuid + ".json"),
                new File(worldContainer, "stats/" + newUuid + ".json"), overwrite);

        // OP status (ops.json) - done through the public Bukkit API rather than editing the
        // file directly, so it works the same regardless of server implementation details.
        // setOp() touches disk/CraftServer state, so it's dispatched to the main thread; this
        // is fine even though it's not synchronous with the file copies above, since OP status
        // isn't needed until the player is already fully in the world a few ticks later.
        Bukkit.getScheduler().runTask(plugin, () -> {
            OfflinePlayer oldPlayer = Bukkit.getOfflinePlayer(oldUuid);
            if (oldPlayer.isOp()) {
                Bukkit.getOfflinePlayer(newUuid).setOp(true);
                oldPlayer.setOp(false);
                plugin.getLogger().info("Migrated OP status for " + newUuid + " (was " + oldUuid + ").");
            } else {
                plugin.getLogger().info("OP migration: old UUID " + oldUuid + " was not OP, nothing to migrate.");
            }
        });

        return migratedAny;
    }

    private static boolean migrateFile(TgAuthPlugin plugin, String label, File source, File target, boolean overwrite) {
        if (!source.exists()) {
            plugin.getLogger().info("Player data migration (" + label + "): no source file at "
                    + source.getAbsolutePath() + " - nothing to migrate.");
            return false;
        }
        if (target.exists() && !overwrite) {
            plugin.getLogger().warning("Player data migration (" + label + "): target file already exists at "
                    + target.getAbsolutePath() + " - refusing to overwrite it, skipping. "
                    + "(This is expected/safe if that UUID has connected to the server before - e.g. during "
                    + "earlier testing. Delete that file first, or set "
                    + "auth.migration-overwrite-existing-data: true in config.yml, if you want migrations to "
                    + "always win over whatever is already there.)");
            return false;
        }
        if (target.exists()) {
            plugin.getLogger().warning("Player data migration (" + label + "): target file at "
                    + target.getAbsolutePath() + " already existed and is being OVERWRITTEN "
                    + "(auth.migration-overwrite-existing-data is enabled).");
        }
        try {
            if (!target.getParentFile().exists()) {
                target.getParentFile().mkdirs();
            }
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("Player data migration (" + label + "): moved "
                    + source.getAbsolutePath() + " -> " + target.getAbsolutePath());
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Player data migration (" + label + ") FAILED: " + source.getPath()
                    + " -> " + target.getPath() + ": " + e);
            return false;
        }
    }
}
