package net.millyland.auth.hook;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.millyland.auth.TgAuthPlugin;
import org.bukkit.Bukkit;

import java.util.UUID;

public class LuckPermsHook {

    private final TgAuthPlugin plugin;
    private LuckPerms luckPerms;

    public LuckPermsHook(TgAuthPlugin plugin) {
        this.plugin = plugin;
        setup();
    }

    private void setup() {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            return;
        }
        try {
            luckPerms = LuckPermsProvider.get();
            plugin.getLogger().info("LuckPerms detected - offline permission checks enabled.");
        } catch (Throwable t) {
            plugin.getLogger().warning("LuckPerms plugin found but its API isn't available: " + t);
        }
    }

    public boolean isPresent() {
        return luckPerms != null;
    }

    public boolean hasPermission(UUID uuid, String permission) {
        if (luckPerms == null) return false;
        try {
            User user = luckPerms.getUserManager().getUser(uuid);
            if (user == null) {
                user = luckPerms.getUserManager().loadUser(uuid).join();
            }
            if (user == null) return false;
            return user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
        } catch (Throwable t) {
            plugin.getLogger().warning("LuckPerms permission check failed: " + t);
            return false;
        }
    }
}
