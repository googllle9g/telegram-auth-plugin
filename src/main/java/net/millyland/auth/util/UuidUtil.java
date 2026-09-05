package net.millyland.auth.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class UuidUtil {

    /**
     * Offline-mode (cracked) UUIDs are generated deterministically from the player name
     * as UUID version 3 (name-based). Real Mojang/Microsoft accounts use UUID version 4
     * (random). This is a reasonable heuristic to tell premium and cracked sessions apart
     * without depending on FastLogin's internal API being available/compatible.
     */
    public static boolean looksPremium(UUID uuid) {
        return uuid.version() == 4;
    }

    /**
     * Computes the exact offline-mode ("cracked") UUID Minecraft/Bukkit would assign to this
     * username - this is the same {@code UUID.nameUUIDFromBytes(("OfflinePlayer:" + name)...)}
     * formula vanilla/Bukkit use internally when the server runs with online-mode disabled.
     * Unlike a premium UUID (which is assigned by Mojang essentially at random and can't be
     * derived from the name at all - it can only be learned by an actual login or a Mojang API
     * lookup), the offline UUID for a given name is always exactly this value. That makes it a
     * reliable way to confirm "this UUID really is the vanilla cracked account for this exact
     * name" before trusting a name-based match for something as consequential as migrating
     * inventory/OP status - see {@link net.millyland.auth.listener.UuidMigrationListener}.
     */
    public static UUID offlineUuidFor(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}

