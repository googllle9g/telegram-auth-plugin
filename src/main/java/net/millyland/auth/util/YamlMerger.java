package net.millyland.auth.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class YamlMerger {

    private YamlMerger() {
    }

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
                continue;
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
