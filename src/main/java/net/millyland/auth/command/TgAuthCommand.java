package net.millyland.auth.command;

import net.millyland.auth.TgAuthPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Optional;

public class TgAuthCommand implements CommandExecutor {

    private final TgAuthPlugin plugin;

    public TgAuthCommand(TgAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("tgauth.admin")) {
            sender.sendMessage(plugin.lang().pget("commands.no-permission"));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(plugin.lang().pget("commands.usage"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.cfg().load();
                plugin.lang().load();
                sender.sendMessage(plugin.lang().pget("commands.reload-success"));
            }
            case "unlink" -> {
                if (args.length < 2) {
                    sender.sendMessage(plugin.lang().pget("commands.usage"));
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                if (!target.hasPlayedBefore() && !target.isOnline()) {
                    sender.sendMessage(plugin.lang().pget("commands.player-not-found"));
                    return true;
                }
                boolean removed = plugin.database().unlink(target.getUniqueId());
                sender.sendMessage(removed
                        ? plugin.lang().pget("commands.unlink-success", "%player%", args[1])
                        : plugin.lang().pget("commands.unlink-not-linked"));
            }
            case "forcelink" -> {
                if (args.length < 3) {
                    sender.sendMessage(plugin.lang().pget("commands.usage"));
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                long telegramId;
                try {
                    telegramId = Long.parseLong(args[2]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(plugin.lang().pget("commands.usage"));
                    return true;
                }
                plugin.database().link(target.getUniqueId(), telegramId, args[1], null);
                sender.sendMessage(plugin.lang().pget("commands.forcelink-success",
                        "%player%", args[1], "%telegram%", String.valueOf(telegramId)));
            }
            case "info" -> {
                if (args.length < 2) {
                    sender.sendMessage(plugin.lang().pget("commands.usage"));
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                Optional<net.millyland.auth.storage.LinkedAccount> acc = plugin.database().findByUuid(target.getUniqueId());
                sender.sendMessage(acc.isPresent()
                        ? plugin.lang().pget("commands.info-linked", "%player%", args[1],
                                "%telegram%", String.valueOf(acc.get().telegramId()))
                        : plugin.lang().pget("commands.info-not-linked", "%player%", args[1]));
            }
            case "userinfo" -> {
                if (args.length < 2) {
                    sender.sendMessage(plugin.lang().pget("commands.usage"));
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                Optional<net.millyland.auth.storage.LinkedAccount> acc = plugin.database().findByUuid(target.getUniqueId());
                if (acc.isEmpty()) {
                    sender.sendMessage(plugin.lang().pget("commands.userinfo-not-linked", "%player%", args[1]));
                    return true;
                }
                var a = acc.get();
                String tgUsername = (a.telegramUsername() == null || a.telegramUsername().isBlank())
                        ? plugin.lang().get("commands.userinfo-username-unset")
                        : "@" + a.telegramUsername();
                String premiumText = a.premium()
                        ? plugin.lang().get("commands.userinfo-premium-yes")
                        : plugin.lang().get("commands.userinfo-premium-no");

                sender.sendMessage(plugin.lang().pget("commands.userinfo-header", "%player%", args[1]));
                sender.sendMessage(plugin.lang().pget("commands.userinfo-telegram-id",
                        "%telegram%", String.valueOf(a.telegramId())));
                sender.sendMessage(plugin.lang().pget("commands.userinfo-telegram-username", "%tguser%", tgUsername));
                sender.sendMessage(plugin.lang().pget("commands.userinfo-premium", "%premium%", premiumText));
            }
            case "fastlogin" -> {
                var hook = plugin.fastLoginHook();
                sender.sendMessage("§7[TgAuth] FastLogin installed: " + (hook.isFastLoginPresent() ? "§ayes" : "§cno"));
                sender.sendMessage("§7[TgAuth] Hook registered: " + (hook.isHookRegistered()
                        ? "§ayes" : "§cno (check the console log at startup for the reason)"));
                sender.sendMessage("§7[TgAuth] Premium-verified players this run: §f" + plugin.authManager().fastLoginVerifiedCount());
                sender.sendMessage("§7[TgAuth] Opted into FastLogin's premium check this run: §f" + hook.optedInCount());
                sender.sendMessage("§7[TgAuth] Added to FastLogin's /premium list this run: §f" + hook.markedPremiumCount());
                sender.sendMessage("§7[TgAuth] config: fastlogin.enabled=" + plugin.cfg().fastLoginEnabled()
                        + ", premium-skip-confirmation=" + plugin.cfg().premiumSkipConfirmation()
                        + ", add-to-fastlogin-premium-list=" + plugin.cfg().addToFastLoginPremiumList());
            }
            default -> sender.sendMessage(plugin.lang().pget("commands.usage"));
        }
        return true;
    }
}
