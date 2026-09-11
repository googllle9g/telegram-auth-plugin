package net.millyland.auth.auth;

import java.util.UUID;

public class PlayerSession {

    public final UUID uuid;
    public final String name;
    public volatile AuthState state;

    public volatile String linkCode;
    public volatile long linkCodeExpireAt;

    public volatile String confirmToken;
    public volatile long confirmExpireAt;
    public volatile Integer telegramMessageId;
    public volatile Long telegramChatId;

    public volatile long joinedAt;
    public volatile boolean premium;

    public volatile String pendingIp;

    public PlayerSession(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        this.joinedAt = System.currentTimeMillis();
    }
}
