package net.millyland.auth.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Keeps a YAML file on disk (e.g. config.yml, lang/en.yml) in sync with the version bundled
 * inside the plugin jar: any leaf key present in the bundled resource but missing from the
 * file on disk gets added (with the bundled default value) and the file is re-saved. Existing
 * values the server owner already set are never touched or overwritten.
 * <p>
 * This is what makes updating the plugin jar safe: new settings/messages introduced by an
 * update simply appear in the existing config.yml / lang files on the next startup instead of
 * silently not existing (the old behaviour of {@code saveDefaultConfig()}, which only ever
 * creates the file once and never revisits it again).
 * <p>
 * Caveat: because this uses Bukkit's standard YamlConfiguration to re-save the file, any
 * comments in it are lost on a merge (a well-known limitation of that YAML implementation).
 * This only happens the one time new keys actually need to be added, not on every normal
 * startup - files that are already fully up to date are left completely untouched.
 */
public final class YamlMerger {

    private YamlMerger() {
    }

    /**
     * @return true if new keys were merged in (the file was rewritten), false if it was
     * already up to date or the merge could not be performed.
     */
    public static boolean mergeMissingKeys(JavaPlugin plugin, String resourcePath, File targetFile) {
        if (!targetFile.exists()) {
            return false;
        }

        FileConfiguration defaultConfig = loadBundledResource(plugin, resourcePath);
        if (defaultConfig == null) {
            return false;
        }

        FileConfiguration userConfig = YamlConfiguration.loadConfiguration(targetFile);

        boolean changed = false;
        for (String key : defaultConfig.getKeys(true)) {
            if (defaultConfig.isConfigurationSection(key)) {
                continue; // only interested in leaf values, section nodes get created implicitly
            }
            if (!userConfig.contains(key)) {
                userConfig.set(key, defaultConfig.get(key));
                changed = true;
            }
        }

        if (changed) {
            try {
                userConfig.save(targetFile);
                plugin.getLogger().info(targetFile.getName()
                        + " was missing settings added by a plugin update - merged the new defaults in automatically.");
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save merged " + targetFile.getName() + ": " + e.getMessage());
                return false;
            }
        }

        return changed;
    }

    private static FileConfiguration loadBundledResource(JavaPlugin plugin, String resourcePath) {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) return null;
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read bundled resource " + resourcePath + ": " + e.getMessage());
            return null;
        }
    }
}
