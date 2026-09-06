package net.millyland.auth.auth;

import net.millyland.auth.TgAuthPlugin;
import net.millyland.auth.storage.LinkedAccount;
import net.millyland.auth.util.UuidUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {

    private final TgAuthPlugin plugin;
    private final SecureRandom random = new SecureRandom();

    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, UUID> pendingCodes = new ConcurrentHashMap<>();     // link code -> uuid
    private final Map<String, UUID> pendingConfirms = new ConcurrentHashMap<>();  // confirm token -> uuid
    private final java.util.Set<UUID> fastLoginPremiumVerified = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<Long, LinkAttemptThrottle> linkAttemptThrottles = new ConcurrentHashMap<>();

    /** Defense in depth against distributed brute-forcing (an attacker spreading guesses across
     *  many different Telegram accounts to dodge the per-account throttle above): a simple
     *  global fixed-window counter of failed /link attempts, regardless of who made them. */
    private final java.util.concurrent.atomic.AtomicInteger globalFailedLinkAttempts = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long globalFailedWindowStart = System.currentTimeMillis();
    private volatile long globalLockoutUntil;

    /** Tracks failed /link code guesses per Telegram user, to make brute-forcing the 6-digit
     *  code impractical - without this, nothing stops a script from trying all ~900,000
     *  possible codes within the code's validity window. */
    private static final class LinkAttemptThrottle {
        int failedAttempts;
        long windowStart;
        long blockedUntil;
    }

    public AuthManager(TgAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public PlayerSession session(UUID uuid) {
        return sessions.get(uuid);
    }

    public boolean isAuthenticated(UUID uuid) {
        PlayerSession s = sessions.get(uuid);
        return s == null || s.state == AuthState.AUTHENTICATED;
    }

    // ---------------------------------------------------------------------
    // Join handling
    // ---------------------------------------------------------------------

    /** Called (async-safe: does its own DB read) right after a player joins. */
    public void handleJoin(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        PlayerSession session = new PlayerSession(uuid, name);
        sessions.put(uuid, session);

        if (player.hasPermission("tgauth.bypass")) {
            session.state = AuthState.AUTHENTICATED;
            return;
        }

        boolean hookActive = plugin.fastLoginHook().isHookRegistered();
        if (!hookActive && UuidUtil.looksPremium(uuid)) {
            // No FastLogin hook active: the UUID-version heuristic is the only signal we'll
            // ever get for this join, so seed it as our final answer right away.
            fastLoginPremiumVerified.add(uuid);
        }
        session.premium = fastLoginPremiumVerified.contains(uuid);

        applyFreezeEffects(player);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Note: cracked<->premium UUID migration (DB link + inventory/advancements/stats/OP
            // files) already happened earlier, in UuidMigrationListener's AsyncPlayerPreLoginEvent
            // handler - before Minecraft even loaded this player's data - so a plain lookup by
            // the player's current UUID is enough here.
            Optional<LinkedAccount> linked = plugin.database().findByUuid(uuid);

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) return;

                if (linked.isEmpty()) {
                    beginLinkFlow(p, session);
                    scheduleAuthTimeout(uuid);
                } else {
                    decideLinkedAccountFlow(p, session, linked.get(), hookActive);
                }
            });
        });
    }

    /**
     * Decides, for an already-linked account, whether to authenticate immediately (premium,
     * verified), wait briefly for FastLogin's async Mojang check to answer, or go straight to
     * a Telegram Confirm/Reject request (cracked account, or FastLogin unavailable/disabled).
     */
    private void decideLinkedAccountFlow(Player player, PlayerSession session, LinkedAccount account, boolean hookActive) {
        UUID uuid = player.getUniqueId();
        boolean skipEnabled = plugin.cfg().premiumSkipConfirmation();

        if (skipEnabled && isFastLoginVerifiedPremium(uuid)) {
            authenticate(player, session, "join.premium-auto-login");
            return;
        }

        if (skipEnabled && hookActive) {
            // FastLogin is installed and actively checking with Mojang; its answer is a network
            // round-trip and just hasn't arrived yet. Give it a short grace period instead of
            // immediately sending a Telegram confirmation the player might not even need -
            // markFastLoginVerifiedPremium() will short-circuit this wait the moment FastLogin
            // reports back (see below).
            int waitSeconds = plugin.cfg().premiumCheckWaitSeconds();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) return;
                if (session.state == AuthState.AUTHENTICATED) return;

                if (isFastLoginVerifiedPremium(uuid)) {
                    authenticate(p, session, "join.premium-auto-login");
                } else {
                    beginConfirmFlow(p, session, account);
                    scheduleAuthTimeout(uuid);
                }
            }, waitSeconds * 20L);
            return;
        }

        beginConfirmFlow(player, session, account);
        scheduleAuthTimeout(uuid);
    }

    public void handleQuit(UUID uuid) {
        PlayerSession s = sessions.remove(uuid);
        if (s != null) {
            if (s.linkCode != null) pendingCodes.remove(s.linkCode);
            if (s.confirmToken != null) pendingConfirms.remove(s.confirmToken);
        }
    }

    // ---------------------------------------------------------------------
    // Link flow (unlinked accounts)
    // ---------------------------------------------------------------------

    private void beginLinkFlow(Player player, PlayerSession session) {
        session.state = AuthState.AWAITING_LINK;
        String code = generateCode();
        session.linkCode = code;
        session.linkCodeExpireAt = System.currentTimeMillis() + plugin.cfg().codeExpireSeconds() * 1000L;
        pendingCodes.put(code, player.getUniqueId());

        String botUsername = plugin.cfg().botUsername();
        player.sendMessage(plugin.lang().pget("join.need-link-code",
                "%code%", code, "%bot%", "@" + botUsername));

        scheduleReminder(player.getUniqueId());
    }

    private void scheduleReminder(UUID uuid) {
        int interval = plugin.cfg().reminderIntervalSeconds();
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            PlayerSession s = sessions.get(uuid);
            Player p = Bukkit.getPlayer(uuid);
            if (s == null || p == null || !p.isOnline() || s.state != AuthState.AWAITING_LINK) {
                task.cancel();
                return;
            }
            long remaining = (s.linkCodeExpireAt - System.currentTimeMillis()) / 1000L;
            if (remaining <= 0) {
                task.cancel();
                return;
            }
            p.sendMessage(plugin.lang().pget("join.need-link-reminder",
                    "%code%", s.linkCode, "%seconds%", String.valueOf(remaining)));
        }, interval * 20L, interval * 20L);
    }

    /** Called from the Telegram thread when someone sends /link <code>. */
    public void handleLinkAttempt(String code, long telegramId, String tgUsername, long chatId) {
        long now = System.currentTimeMillis();

        if (globalLockoutUntil > now) {
            long secondsLeft = (globalLockoutUntil - now) / 1000L + 1;
            plugin.telegram().send(chatId, plugin.lang().rawGet("link.too-many-attempts", "%seconds%", String.valueOf(secondsLeft)));
            return;
        }

        LinkAttemptThrottle throttle = linkAttemptThrottles.computeIfAbsent(telegramId, k -> new LinkAttemptThrottle());

        synchronized (throttle) {
            if (throttle.blockedUntil > now) {
                long secondsLeft = (throttle.blockedUntil - now) / 1000L + 1;
                plugin.telegram().send(chatId, plugin.lang().rawGet("link.too-many-attempts", "%seconds%", String.valueOf(secondsLeft)));
                return;
            }

            UUID uuid = pendingCodes.get(code);
            PlayerSession session = uuid == null ? null : sessions.get(uuid);

            boolean codeValid = uuid != null && session != null && session.state == AuthState.AWAITING_LINK
                    && now <= session.linkCodeExpireAt;

            if (!codeValid) {
                registerFailedAttempt(throttle, now);
                plugin.telegram().send(chatId, plugin.lang().rawGet("link.invalid-code"));
                return;
            }

            if (plugin.database().findByTelegramId(telegramId).isPresent()) {
                plugin.telegram().send(chatId, plugin.lang().rawGet("link.already-linked-telegram"));
                return;
            }
            if (plugin.database().findByUuid(uuid).isPresent()) {
                plugin.telegram().send(chatId, plugin.lang().rawGet("link.already-linked-player"));
                return;
            }

            boolean ok = plugin.database().link(uuid, telegramId, session.name, tgUsername);
            pendingCodes.remove(code);

            if (!ok) {
                plugin.telegram().send(chatId, plugin.lang().rawGet("link.invalid-code"));
                return;
            }

            // Successful, legitimate link - clear this Telegram user's failed-attempt history.
            linkAttemptThrottles.remove(telegramId);

            plugin.telegram().send(chatId, plugin.lang().rawGet("link.success-telegram", "%player%", session.name));

            // Breaks FastLogin's chicken-and-egg problem for a brand-new registration: without this,
            // a genuinely licensed player's very first link would never trigger FastLogin's own
            // (opt-in per name) Mojang verification, so they'd never get flagged premium in the
            // first place. See FastLoginHook#optIntoFastLoginPremiumCheck for the full explanation.
            plugin.fastLoginHook().optIntoFastLoginPremiumCheck(session.name);

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) return;
                authenticate(p, session, "link.success-ingame");
            });
        }
    }

    private void registerFailedAttempt(LinkAttemptThrottle throttle, long now) {
        int maxAttempts = plugin.cfg().linkMaxAttempts();
        int windowSeconds = plugin.cfg().linkAttemptWindowSeconds();
        int lockoutSeconds = plugin.cfg().linkLockoutSeconds();

        if (now - throttle.windowStart > windowSeconds * 1000L) {
            // Previous window expired - start a fresh one.
            throttle.windowStart = now;
            throttle.failedAttempts = 0;
        }

        throttle.failedAttempts++;
        if (throttle.failedAttempts >= maxAttempts) {
            throttle.blockedUntil = now + lockoutSeconds * 1000L;
            throttle.failedAttempts = 0;
            throttle.windowStart = now;
        }

        registerGlobalFailedAttempt(now);
    }

    /** Distributed-brute-force defense: if wrong /link guesses are piling up across MANY
     *  different Telegram accounts at once (not just one), lock out all /link attempts briefly
     *  regardless of who's making them, since that pattern only really happens during an attack,
     *  not from ordinary player typos. */
    private synchronized void registerGlobalFailedAttempt(long now) {
        int maxAttempts = plugin.cfg().globalLinkMaxAttempts();
        int windowSeconds = plugin.cfg().globalLinkAttemptWindowSeconds();
        int lockoutSeconds = plugin.cfg().globalLinkLockoutSeconds();

        if (now - globalFailedWindowStart > windowSeconds * 1000L) {
            globalFailedWindowStart = now;
            globalFailedLinkAttempts.set(0);
        }

        int attempts = globalFailedLinkAttempts.incrementAndGet();
        if (attempts >= maxAttempts) {
            globalLockoutUntil = now + lockoutSeconds * 1000L;
            globalFailedLinkAttempts.set(0);
            globalFailedWindowStart = now;
            plugin.getLogger().warning("Too many failed /link attempts across " + attempts
                    + " requests in a short window - temporarily locking out ALL /link attempts "
                    + "for " + lockoutSeconds + "s. This usually means someone is brute-forcing "
                    + "link codes using multiple Telegram accounts.");
        }
    }
    // ---------------------------------------------------------------------
    // Confirm flow (already-linked accounts, non-premium or premium w/o skip)
    // ---------------------------------------------------------------------

    private void beginConfirmFlow(Player player, PlayerSession session, LinkedAccount account) {
        session.state = AuthState.AWAITING_CONFIRM;
        String token = generateToken();
        session.confirmToken = token;
        session.confirmExpireAt = System.currentTimeMillis() + plugin.cfg().confirmTimeoutSeconds() * 1000L;
        pendingConfirms.put(token, player.getUniqueId());

        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "?";
        String text = plugin.lang().rawGet("confirm.message", "%player%", session.name, "%ip%", ip);

        plugin.telegram().sendConfirmRequest(account.telegramId(), text, token);
        player.sendMessage(plugin.lang().pget("join.need-confirm"));
    }

    public void registerTelegramMessage(String token, long chatId, int messageId) {
        UUID uuid = pendingConfirms.get(token);
        if (uuid == null) return;
        PlayerSession session = sessions.get(uuid);
        if (session == null) return;
        session.telegramChatId = chatId;
        session.telegramMessageId = messageId;
    }

    /** Called from the Telegram thread when Confirm/Reject is tapped. */
    public void handleConfirmCallback(String token, boolean approve, long chatId, int messageId) {
        UUID uuid = pendingConfirms.remove(token);
        if (uuid == null) return;
        PlayerSession session = sessions.get(uuid);
        if (session == null || session.state != AuthState.AWAITING_CONFIRM) return;

        String resultText = approve
                ? plugin.lang().rawGet("confirm.confirmed-telegram")
                : plugin.lang().rawGet("confirm.rejected-telegram");
        plugin.telegram().editConfirmResult(chatId, messageId, resultText);

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) return;

            if (approve) {
                authenticate(p, session, "confirm.confirmed-ingame");
            } else {
                kick(p, plugin.lang().get("confirm.rejected-kick"));
            }
        });
    }

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

    private void authenticate(Player player, PlayerSession session, String messageKeyOrNull) {
        session.state = AuthState.AUTHENTICATED;
        removeFreezeEffects(player);

        if (session.premium) {
            plugin.fastLoginHook().markPremiumInFastLogin(player);
            UUID uuid = player.getUniqueId();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> plugin.database().setPremium(uuid, true));
        }

        if (messageKeyOrNull != null) {
            player.sendMessage(plugin.lang().pget(messageKeyOrNull));
        }
    }

    private void scheduleAuthTimeout(UUID uuid) {
        int timeout = plugin.cfg().authTimeoutSeconds();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PlayerSession s = sessions.get(uuid);
            Player p = Bukkit.getPlayer(uuid);
            if (s == null || p == null || !p.isOnline()) return;
            if (s.state == AuthState.AUTHENTICATED) return;

            String key = s.state == AuthState.AWAITING_LINK ? "link.code-expired-kick" : "confirm.timeout-kick";
            kick(p, plugin.lang().get(key));
        }, timeout * 20L);
    }

    private void kick(Player player, String legacyColoredText) {
        Component component = LegacyComponentSerializer.legacySection().deserialize(legacyColoredText);
        player.kick(component);
    }

    private void applyFreezeEffects(Player player) {
        // Reset fall distance immediately on join: if this player disconnected mid-fall last
        // time (e.g. kicked by the auth timeout while falling, or just closed the client),
        // Minecraft can persist that in-progress fall distance across the reconnect. Since our
        // freeze pins the player's Y position (see onMove in PlayerProtectListener), they'd
        // never "land" to reset it naturally while frozen, so it could sit there, or even stack
        // further, across repeated disconnect/reconnect cycles - both of which are directly
        // exploitable via any fall-distance-scaled damage source (e.g. the Mace's Density
        // enchantment) once the fake distance eventually gets discharged. Reset unconditionally.
        player.setFallDistance(0f);

        if (plugin.cfg().applyBlindness()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 1, false, false, false));
        }
        if (plugin.cfg().applySlowness()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 5, false, false, false));
        }
    }

    private void removeFreezeEffects(Player player) {
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        // Reset again right before unfreezing, in case anything nudged it during the freeze
        // window - cheap extra safety net for the same fall-distance exploit described above.
        player.setFallDistance(0f);
    }

    private String generateCode() {
        int code = 100000 + random.nextInt(900000);
        return String.valueOf(code);
    }

    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Admin: resend a code to a player currently awaiting link, or report they don't need one. */
    public Optional<String> currentLinkCode(UUID uuid) {
        PlayerSession s = sessions.get(uuid);
        if (s == null || s.state != AuthState.AWAITING_LINK) return Optional.empty();
        return Optional.ofNullable(s.linkCode);
    }

    // ---------------------------------------------------------------------
    // FastLogin hook callbacks
    // ---------------------------------------------------------------------

    public boolean isFastLoginVerifiedPremium(UUID uuid) {
        return fastLoginPremiumVerified.contains(uuid);
    }

    public int fastLoginVerifiedCount() {
        return fastLoginPremiumVerified.size();
    }

    /**
     * Called (possibly off the main thread) by {@link net.millyland.auth.hook.FastLoginHook} once
     * FastLogin has cryptographically verified, via Mojang, that the connecting player owns
     * this premium account. If we already started a Telegram Confirm/Reject request for this
     * join (because our earlier, less certain guess said "not premium"), upgrade it to an
     * instant authentication now instead of waiting on Telegram.
     */
    public void markFastLoginVerifiedPremium(Player player) {
        UUID uuid = player.getUniqueId();
        fastLoginPremiumVerified.add(uuid);

        PlayerSession session = sessions.get(uuid);
        if (session == null) {
            // handleJoin() hasn't created the session yet; it will pick this flag up itself.
            return;
        }
        session.premium = true;

        if (!plugin.cfg().premiumSkipConfirmation()) return;
        if (session.state != AuthState.AWAITING_CONFIRM) return;

        // Cancel the pending Telegram confirmation and log the player in immediately.
        if (session.confirmToken != null) pendingConfirms.remove(session.confirmToken);

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) return;
            if (session.state == AuthState.AUTHENTICATED) return;
            authenticate(p, session, "join.premium-auto-login");
        });
    }
}
