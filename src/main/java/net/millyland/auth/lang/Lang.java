package net.millyland.auth.lang;

import net.millyland.auth.TgAuthPlugin;
import net.millyland.auth.util.YamlMerger;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Loads a lang/&lt;code&gt;.yml file from the plugin data folder (extracting the bundled
 * default first if missing) so server owners can add or edit translations freely by
 * dropping new files into the lang/ folder and setting `language: <code>` in config.yml.
 */
public class Lang {

    private final TgAuthPlugin plugin;
    private FileConfiguration messages;
    private FileConfiguration fallback; // en.yml, used if a key is missing from the chosen language

    public Lang(TgAuthPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        String code = plugin.cfg().language();
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }

        syncBundledLangFile("en.yml");
        syncBundledLangFile("ru.yml");

        File target = new File(langDir, code + ".yml");
        if (!target.exists()) {
            plugin.getLogger().warning("Language file lang/" + code + ".yml not found, falling back to ru.yml");
            target = new File(langDir, "ru.yml");
        }

        this.messages = YamlConfiguration.loadConfiguration(target);

        File fallbackFile = new File(langDir, "en.yml");
        this.fallback = YamlConfiguration.loadConfiguration(fallbackFile);
    }

    /**
     * Extracts the bundled lang/&lt;fileName&gt; on first run, or - if it already exists on
     * disk (e.g. from before a plugin update added new message keys) - merges in any new keys
     * from the bundled version without touching translations the server owner already edited.
     */
    private void syncBundledLangFile(String fileName) {
        File out = new File(new File(plugin.getDataFolder(), "lang"), fileName);
        if (!out.exists()) {
            extractDefault(fileName, out);
        } else {
            YamlMerger.mergeMissingKeys(plugin, "lang/" + fileName, out);
        }
    }

    private void extractDefault(String fileName, File out) {
        try (InputStream in = plugin.getResource("lang/" + fileName)) {
            if (in == null) return;
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration yc = YamlConfiguration.loadConfiguration(reader);
                yc.save(out);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not extract default language file " + fileName + ": " + e.getMessage());
        }
    }

    public String prefix() {
        return color(raw("prefix", ""));
    }

    private String raw(String path, String def) {
        String v = messages.getString(path);
        if (v == null && fallback != null) v = fallback.getString(path);
        return v == null ? def : v;
    }

    public String get(String path) {
        return color(raw(path, path));
    }

    public String get(String path, Object... placeholdersKV) {
        String msg = raw(path, path);
        for (int i = 0; i + 1 < placeholdersKV.length; i += 2) {
            msg = msg.replace(String.valueOf(placeholdersKV[i]), String.valueOf(placeholdersKV[i + 1]));
        }
        return color(msg);
    }

    /** Convenience: prefixed message. */
    public String pget(String path, Object... placeholdersKV) {
        return prefix() + get(path, placeholdersKV);
    }

    /** Raw (uncolored, unprefixed) message - useful for Telegram text. */
    public String rawGet(String path, Object... placeholdersKV) {
        String msg = raw(path, path);
        for (int i = 0; i + 1 < placeholdersKV.length; i += 2) {
            msg = msg.replace(String.valueOf(placeholdersKV[i]), String.valueOf(placeholdersKV[i + 1]));
        }
        return ChatColor.stripColor(color(msg));
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
