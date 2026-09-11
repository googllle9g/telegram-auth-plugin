package net.millyland.auth.util;

import net.millyland.auth.TgAuthPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

public final class PlayerDataMigrator {

    private PlayerDataMigrator() {
    }

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
