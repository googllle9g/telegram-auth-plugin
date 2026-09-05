package net.millyland.auth.storage;

import java.util.UUID;

public record LinkedAccount(UUID uuid, long telegramId, String username, long linkedAt,
                             String telegramUsername, boolean premium) {
}
