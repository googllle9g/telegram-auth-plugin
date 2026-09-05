package net.millyland.auth.hook;

import net.millyland.auth.TgAuthPlugin;
import net.millyland.auth.util.MojangApi;
import net.millyland.auth.util.UuidUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers TgAuth as FastLogin's "auth plugin" hook.
 * <p>
 * FastLogin (games647) only performs its premium (Mojang) verification when it is either
 * running behind BungeeCord/Velocity, OR it has a recognised local "auth plugin" hooked in
 * (see {@code com.github.games647.fastlogin.core.hooks.AuthPlugin}). Without either, it logs
 * "No auth plugin were found..." and disables its own login handling entirely.
 * <p>
 * FastLogin ships with hooks for a fixed list of well-known plugins (AuthMe, nLogin, etc.),
 * but its core module also exposes a small public API for third parties:
 * {@code FastLoginCore#setAuthPluginHook(AuthPlugin<P> hook)}. Because compiling directly
 * against FastLogin's classes would tie this plugin to one exact FastLogin build (and this
 * project intentionally has zero hard dependency on it), the hook below is created as a JDK
 * dynamic {@link Proxy} implementing that interface purely via reflection:
 * <ul>
 *   <li>{@code isRegistered(String playerName)} - true if that name is already linked to a
 *       Telegram account in our own database.</li>
 *   <li>{@code forceLogin(Player player)} - called by FastLogin once it has verified (via
 *       Mojang) that the connecting player owns this premium account AND we reported them as
 *       already "registered". We simply mark the session as verified-premium.</li>
 *   <li>{@code forceRegister(Player player, String password)} - called for a verified premium
 *       player we reported as NOT registered yet. We don't use passwords, so this is a no-op
 *       beyond marking the session verified-premium; TgAuth's own /link flow still runs.</li>
 * </ul>
 * If reflection fails for any reason (FastLogin absent, or its API changed in a way our
 * method-name probing can't handle), TgAuth falls back to a UUID-version heuristic
 * (see {@link UuidUtil}) so the plugin keeps working either way.
 */
public class FastLoginHook {

    private final TgAuthPlugin plugin;
    private boolean hookRegistered;
    private boolean fastLoginPresent;
    private final Set<UUID> markedPremiumInFastLogin = ConcurrentHashMap.newKeySet();
    private final Set<String> optedIntoPremiumCheck = ConcurrentHashMap.newKeySet();

    public FastLoginHook(TgAuthPlugin plugin) {
        this.plugin = plugin;
        tryRegister();
    }

    private void tryRegister() {
        if (!plugin.cfg().fastLoginEnabled()) {
            return;
        }

        Plugin fastLogin = Bukkit.getPluginManager().getPlugin("FastLogin");
        if (fastLogin == null || !fastLogin.isEnabled()) {
            plugin.getLogger().info("FastLogin not found - premium detection will use the UUID-version heuristic only.");
            return;
        }
        fastLoginPresent = true;

        // Different FastLogin builds have moved this interface between a couple of packages
        // over time; try each known candidate rather than hard-coding just one.
        String[] ifaceCandidates = {
                "com.github.games647.fastlogin.core.hooks.AuthPlugin",
                "com.github.games647.fastlogin.core.hooking.AuthPlugin",
                "com.github.games647.fastlogin.bukkit.hook.AuthPlugin"
        };

        Class<?> authPluginIface = null;
        for (String fqcn : ifaceCandidates) {
            try {
                authPluginIface = Class.forName(fqcn);
                break;
            } catch (ClassNotFoundException ignored) {
                // try next candidate
            }
        }

        if (authPluginIface == null) {
            plugin.getLogger().warning("Could not find FastLogin's AuthPlugin interface under any known package name "
                    + "(tried: " + String.join(", ", ifaceCandidates) + "). "
                    + "This usually means your FastLogin build renamed/moved it. "
                    + "TgAuth will keep working, but falls back to the UUID-version heuristic for premium detection.");
            return;
        }

        try {
            Object proxyHook = Proxy.newProxyInstance(
                    authPluginIface.getClassLoader(),
                    new Class<?>[]{authPluginIface},
                    new AuthPluginInvocationHandler()
            );

            // Step 1: get the "core" object that exposes setAuthPluginHook(...). On most builds
            // this is FastLoginBukkit#getCore(); fall back to using the plugin instance itself
            // in case a build exposes the setter directly on the plugin.
            Object core = invokeNoArgIfPresent(fastLogin, "getCore");
            if (core == null) core = fastLogin;

            Method setHook = findSingleArgMethod(core.getClass(), "setAuthPluginHook", authPluginIface);
            if (setHook == null && core != fastLogin) {
                // also try directly on the plugin instance as a last resort
                setHook = findSingleArgMethod(fastLogin.getClass(), "setAuthPluginHook", authPluginIface);
                if (setHook != null) core = fastLogin;
            }

            if (setHook == null) {
                plugin.getLogger().warning("Found FastLogin's AuthPlugin interface (" + authPluginIface.getName()
                        + ") but no matching setAuthPluginHook(...) method on " + core.getClass().getName()
                        + ". Your FastLogin build likely uses a different registration API. "
                        + "TgAuth will keep working, but falls back to the UUID-version heuristic for premium detection.");
                return;
            }

            setHook.invoke(core, proxyHook);

            hookRegistered = true;
            plugin.getLogger().info("Registered TgAuth as FastLogin's auth-plugin hook ("
                    + authPluginIface.getName() + " via " + core.getClass().getSimpleName() + "#setAuthPluginHook). "
                    + "FastLogin premium verification is now active.");
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("Could not hook into FastLogin's AuthPlugin API (version mismatch?): " + e);
            plugin.getLogger().warning("TgAuth will keep working, but falls back to the UUID-version heuristic "
                    + "for premium detection instead of a real FastLogin verification. "
                    + "Run /tgauth fastlogin for diagnostics.");
        }
    }

    private Object invokeNoArgIfPresent(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private Method findSingleArgMethod(Class<?> owner, String name, Class<?> paramType) {
        for (Method m : owner.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isAssignableFrom(paramType)) {
                return m;
            }
        }
        return null;
    }

    public boolean isFastLoginPresent() {
        return fastLoginPresent;
    }

    public boolean isHookRegistered() {
        return hookRegistered;
    }

    public int markedPremiumCount() {
        return markedPremiumInFastLogin.size();
    }

    public int optedInCount() {
        return optedIntoPremiumCheck.size();
    }

    /**
     * Best-effort premium check for the initial PlayerJoinEvent decision, before FastLogin's
     * own (slightly delayed) forceLogin/forceRegister callback has necessarily fired yet.
     * The authoritative signal is {@link net.millyland.auth.auth.AuthManager#isFastLoginVerifiedPremium(java.util.UUID)},
     * which this falls back to first.
     */
    public boolean isPremium(Player player) {
        if (plugin.authManager().isFastLoginVerifiedPremium(player.getUniqueId())) {
            return true;
        }
        return UuidUtil.looksPremium(player.getUniqueId());
    }

    /**
     * Runs FastLogin's own "/premium &lt;name&gt;" console command for this player so FastLogin's
     * own premium list also records them, not just TgAuth's internal state. Idempotent (only
     * dispatched once per player per server run) and a complete no-op if FastLogin isn't
     * installed/enabled or the feature is turned off in config.yml.
     */
    public void markPremiumInFastLogin(Player player) {
        if (!plugin.cfg().addToFastLoginPremiumList()) return;

        Plugin fastLogin = Bukkit.getPluginManager().getPlugin("FastLogin");
        if (fastLogin == null || !fastLogin.isEnabled()) return;

        if (!markedPremiumInFastLogin.add(player.getUniqueId())) return; // already done this run

        runPremiumCommand(player.getName(), "confirmed-premium");
    }

    /**
     * Breaks a chicken-and-egg problem: by default FastLogin never automatically attempts its
     * Mojang online-mode verification for a name it hasn't been told about before ("opt-in" -
     * see FastLogin's own issue tracker) - which means a genuinely licensed player's very first
     * connection would never get flagged as premium by {@code forceLogin}/{@code forceRegister}
     * at all, since nothing ever asked FastLogin to check them in the first place, and
     * {@link #markPremiumInFastLogin} only ever fires *after* that check already succeeded once.
     * <p>
     * So instead of waiting for confirmation, this is called the moment a brand-new account
     * finishes its very first {@code /link} - after confirming, via the free/public
     * {@link MojangApi}, that the name is actually owned by some real Mojang account (so we
     * don't pointlessly opt in names we're confident are cracked). This only tells FastLogin
     * "please attempt the online-mode handshake for this name from now on" - it does not itself
     * grant premium status; FastLogin still does its own cryptographic verification on the
     * player's *next* connection before deciding anything. Some FastLogin versions require a
     * reconnect for this to take effect, which is expected/normal for how "/premium" works.
     */
    public void optIntoFastLoginPremiumCheck(String playerName) {
        if (!plugin.cfg().addToFastLoginPremiumList()) return;

        Plugin fastLogin = Bukkit.getPluginManager().getPlugin("FastLogin");
        if (fastLogin == null || !fastLogin.isEnabled()) return;

        String key = playerName.toLowerCase(java.util.Locale.ROOT);
        if (!optedIntoPremiumCheck.add(key)) return; // already opted in this run

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!MojangApi.isPremiumUsername(playerName)) {
                // Not a real Mojang account name at all - definitely cracked, nothing to opt into.
                return;
            }
            runPremiumCommand(playerName, "opted-in-for-verification");
        });
    }

    private void runPremiumCommand(String name, String reason) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "premium " + name);
                if (ok) {
                    plugin.getLogger().info("Ran FastLogin's '/premium " + name + "' command (" + reason + ").");
                } else {
                    plugin.getLogger().warning("FastLogin rejected the '/premium " + name
                            + "' command (unexpected - check FastLogin's own logs).");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Could not run FastLogin's '/premium " + name + "' command: " + e);
            }
        });
    }

    private class AuthPluginInvocationHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxyObj, Method method, Object[] args) {
            try {
                switch (method.getName()) {
                    case "isRegistered": {
                        String playerName = (String) args[0];
                        return plugin.database().findByUsername(playerName).isPresent();
                    }
                    case "forceLogin": {
                        Player player = (Player) args[0];
                        plugin.authManager().markFastLoginVerifiedPremium(player);
                        return true;
                    }
                    case "forceRegister": {
                        Player player = (Player) args[0];
                        plugin.authManager().markFastLoginVerifiedPremium(player);
                        return true;
                    }
                    case "toString":
                        return "TgAuthFastLoginHook";
                    case "hashCode":
                        return System.identityHashCode(proxyObj);
                    case "equals":
                        return proxyObj == (args != null && args.length > 0 ? args[0] : null);
                    default:
                        return null;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error handling FastLogin hook call " + method.getName() + ": " + e);
                return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
            }
        }
    }
}
