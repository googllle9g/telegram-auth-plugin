package net.millyland.auth.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class UuidUtil {

    public static boolean looksPremium(UUID uuid) {
        return uuid.version() == 4;
    }

    public static UUID offlineUuidFor(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
