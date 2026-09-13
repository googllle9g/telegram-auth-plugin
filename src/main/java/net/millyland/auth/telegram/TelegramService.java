package net.millyland.auth.telegram;

import net.millyland.auth.TgAuthPlugin;
import net.millyland.auth.storage.LinkedAccount;
import net.kyori.adventure.text.Component;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TelegramService extends TelegramLongPollingBot {

    private static final int ADMIN_PAGE_SIZE = 5;

    private final TgAuthPlugin plugin;
    private final String username;

    private final Set<Long> awaitingSearchInput = ConcurrentHashMap.newKeySet();
    private final Map<Long, PendingAdminAction> awaitingReasonInput = new ConcurrentHashMap<>();

    private record PendingAdminAction(String type, UUID uuid, String playerName, String reason) {
        PendingAdminAction(String type, UUID uuid, String playerName) {
            this(type, uuid, playerName, null);
        }
    }

    public TelegramService(TgAuthPlugin plugin) {
        super(plugin.cfg().botToken());
        this.plugin = plugin;
        this.username = plugin.cfg().botUsername();
    }

    public void start() throws TelegramApiException {
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(this);
    }

    @Override
    public String getBotUsername() {
        return username;
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
            } else if (update.hasMessage() && update.getMessage().hasText()) {
                handleMessage(update.getMessage());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling Telegram update: " + e.getMessage());
        }
    }

    private boolean isAdmin(long telegramId) {
        if (!plugin.cfg().adminPanelEnabled()) return false;
        if (plugin.cfg().adminTelegramIds().contains(telegramId)) return true;

        var linked = plugin.database().findByTelegramId(telegramId);
        if (linked.isEmpty()) return false;
        UUID uuid = linked.get().uuid();

        try {
            Boolean onlineResult = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                Player online = Bukkit.getPlayer(uuid);
                return online != null ? online.hasPermission("tgauth.admin") : null;
            }).get();
            if (onlineResult != null) return onlineResult;
        } catch (Exception e) {
            return false;
        }

        if (plugin.luckPermsHook().isPresent()) {
            return plugin.luckPermsHook().hasPermission(uuid, "tgauth.admin");
        }

        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, () -> Bukkit.getOfflinePlayer(uuid).isOp()).get();
        } catch (Exception e) {
            return false;
        }
    }

    private void handleMessage(Message message) {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();
        String text = message.getText().trim();

        PendingAdminAction pending = awaitingReasonInput.remove(telegramId);
        if (pending != null) {
            if (isAdmin(telegramId)) {
                if (pending.type().equals("ban_reason")) {
                    awaitingReasonInput.put(telegramId, new PendingAdminAction("ban_duration", pending.uuid(), pending.playerName(), text));
                    send(chatId, "Send the ban duration (e.g. 1s, 5m, 2h, 7d) or 'p' for permanent.",
                            keyboard(inlineRow(button("Cancel", "admin:cancelreason:" + pending.uuid()))));
                } else if (pending.type().equals("ban_duration")) {
                    executeBan(telegramId, chatId, pending, text);
                } else {
                    executeAdminAction(chatId, pending, text);
                }
            }
            return;
        }

        if (awaitingSearchInput.remove(telegramId)) {
            if (isAdmin(telegramId)) {
                adminSearch(chatId, text);
            }
            return;
        }

        if (text.startsWith("/start")) {
            send(chatId, plugin.lang().rawGet("telegram.start"));
            return;
        }

        if (text.startsWith("/admin")) {
            if (isAdmin(telegramId)) {
                sendAdminMenu(chatId);
            }
            return;
        }

        if (text.startsWith("/link")) {
            String[] parts = text.split("\\s+");
            if (parts.length < 2) {
                send(chatId, plugin.lang().rawGet("telegram.link-usage"));
                return;
            }
            String code = parts[1].trim();
            plugin.authManager().handleLinkAttempt(code, telegramId, message.getFrom().getUserName(), chatId);
            return;
        }

        send(chatId, plugin.lang().rawGet("telegram.unknown-command"));
    }

    private void handleCallback(CallbackQuery callback) {
        String data = callback.getData();
        long chatId = callback.getMessage().getChatId();
        int messageId = callback.getMessage().getMessageId();
        long telegramId = callback.getFrom().getId();

        if (data == null) return;

        if (data.startsWith("confirm:") || data.startsWith("reject:")) {
            boolean approve = data.startsWith("confirm:");
            String token = data.substring(data.indexOf(':') + 1);
            plugin.authManager().handleConfirmCallback(token, approve, chatId, messageId);
            answerCallback(callback.getId(), null);
            return;
        }

        if (data.startsWith("admin:")) {
            if (!isAdmin(telegramId)) {
                answerCallback(callback.getId(), "Not authorized.");
                return;
            }
            handleAdminCallback(telegramId, data.substring("admin:".length()), chatId, messageId);
            answerCallback(callback.getId(), null);
        }
    }

    private void handleAdminCallback(long telegramId, String action, long chatId, int messageId) {
        awaitingReasonInput.remove(telegramId);
        if (!action.equals("search")) {
            awaitingSearchInput.remove(chatId);
        }

        if (action.equals("menu")) {
            editToAdminMenu(chatId, messageId);
        } else if (action.equals("search")) {
            awaitingSearchInput.add(chatId);
            editText(chatId, messageId, "Send the player's Minecraft name as your next message.");
        } else if (action.startsWith("list:")) {
            int page = parseIntOr(action.substring("list:".length()), 0);
            editToAdminList(chatId, messageId, page);
        } else if (action.startsWith("view:")) {
            editToAccountView(chatId, messageId, action.substring("view:".length()));
        } else if (action.startsWith("unlink:")) {
            String uuid = action.substring("unlink:".length());
            editText(chatId, messageId, "Unlink this account? This cannot be undone.",
                    keyboard(inlineRow(button("✅ Confirm unlink", "admin:unlinkconfirm:" + uuid),
                            button("Cancel", "admin:view:" + uuid))));
        } else if (action.startsWith("unlinkconfirm:")) {
            String uuidStr = action.substring("unlinkconfirm:".length());
            boolean ok;
            try {
                ok = plugin.database().unlink(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException e) {
                ok = false;
            }
            editText(chatId, messageId, ok ? "✅ Unlinked." : "❌ Could not unlink (already removed?).",
                    keyboard(inlineRow(button("« Back", "admin:menu"))));
        } else if (action.startsWith("unpremium:")) {
            String uuidStr = action.substring("unpremium:".length());
            withAccount(uuidStr, a -> {
                plugin.database().setPremium(a.uuid(), false);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (plugin.fastLoginHook().isFastLoginPresent()) {
                        try {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "unpremium " + a.username());
                        } catch (Exception e) {
                            plugin.getLogger().warning("Could not run FastLogin's '/unpremium " + a.username() + "': " + e);
                        }
                    }
                });
                editText(chatId, messageId, "✅ Premium flag cleared" + (plugin.fastLoginHook().isFastLoginPresent()
                        ? " (also ran FastLogin's /unpremium)." : "."),
                        keyboard(inlineRow(button("« Back", "admin:view:" + uuidStr))));
            }, () -> editText(chatId, messageId, "❌ Account not found."));
        } else if (action.startsWith("unban:")) {
            String uuidStr = action.substring("unban:".length());
            withAccount(uuidStr, a -> {
                String custom = plugin.cfg().unbanCommand();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!custom.isBlank()) {
                        dispatchConfiguredCommand(custom, a.username(), null, null);
                    } else {
                        Bukkit.getBanList(BanList.Type.NAME).pardon(a.username());
                    }
                });
                editText(chatId, messageId, "✅ Unbanned " + a.username() + ".",
                        keyboard(inlineRow(button("« Back", "admin:view:" + uuidStr))));
            }, () -> editText(chatId, messageId, "❌ Account not found."));
        } else if (action.startsWith("kick:")) {
            promptForReason(telegramId, chatId, messageId, "kick", action.substring("kick:".length()));
        } else if (action.startsWith("ban:")) {
            promptForReason(telegramId, chatId, messageId, "ban_reason", action.substring("ban:".length()));
        } else if (action.startsWith("warn:")) {
            promptForReason(telegramId, chatId, messageId, "warn", action.substring("warn:".length()));
        } else if (action.startsWith("cancelreason:")) {
            editToAccountView(chatId, messageId, action.substring("cancelreason:".length()));
        }
    }

    private void promptForReason(long telegramId, long chatId, int messageId, String type, String uuidStr) {
        withAccount(uuidStr, a -> {
            awaitingReasonInput.put(telegramId, new PendingAdminAction(type, a.uuid(), a.username()));
            String verb = type.equals("ban_reason") ? "banning" : type + "ing";
            editText(chatId, messageId, "Send the reason for " + verb + " " + a.username() + " as your next message.",
                    keyboard(inlineRow(button("Cancel", "admin:cancelreason:" + uuidStr))));
        }, () -> editText(chatId, messageId, "❌ Account not found."));
    }

    private void executeAdminAction(long chatId, PendingAdminAction action, String reason) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            switch (action.type()) {
                case "kick" -> {
                    String custom = plugin.cfg().kickCommand();
                    if (!custom.isBlank()) {
                        boolean ok = dispatchConfiguredCommand(custom, action.playerName(), reason, null);
                        send(chatId, ok ? "✅ Ran kick command for " + action.playerName() + ": " + reason
                                : "❌ Kick command failed for " + action.playerName() + " (check console).");
                        return;
                    }
                    Player p = Bukkit.getPlayer(action.uuid());
                    if (p != null && p.isOnline()) {
                        p.kick(Component.text(reason));
                        send(chatId, "✅ Kicked " + action.playerName() + ": " + reason);
                    } else {
                        send(chatId, "⚠ " + action.playerName() + " is not online - could not kick.");
                    }
                }
                case "warn" -> {
                    String custom = plugin.cfg().warnCommand();
                    if (!custom.isBlank()) {
                        boolean ok = dispatchConfiguredCommand(custom, action.playerName(), reason, null);
                        send(chatId, ok ? "✅ Ran warn command for " + action.playerName() + ": " + reason
                                : "❌ Warn command failed for " + action.playerName() + " (check console).");
                        return;
                    }
                    Player p = Bukkit.getPlayer(action.uuid());
                    if (p != null && p.isOnline()) {
                        p.sendMessage(Component.text("Warning: " + reason));
                        send(chatId, "✅ Warned " + action.playerName() + ": " + reason);
                    } else {
                        send(chatId, "⚠ " + action.playerName() + " is not online - could not deliver warning.");
                    }
                }
                default -> {
                }
            }
        });
    }

    private boolean dispatchConfiguredCommand(String template, String player, String reason, String duration) {
        String cmd = template
                .replace("%player%", player)
                .replace("%reason%", reason == null ? "" : reason)
                .replace("%duration%", duration == null ? "" : duration);
        try {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } catch (Exception e) {
            plugin.getLogger().warning("Configured admin command '" + cmd + "' threw an error: " + e);
            return false;
        }
    }

    private static final long DURATION_PERMANENT = 0L;
    private static final long DURATION_INVALID = -1L;

    private long parseDuration(String input) {
        String s = input.trim().toLowerCase();
        if (s.equals("p") || s.equals("perm") || s.equals("permanent")) return DURATION_PERMANENT;
        var m = java.util.regex.Pattern.compile("^(\\d+)([smhdw])$").matcher(s);
        if (!m.matches()) return DURATION_INVALID;
        long amount = Long.parseLong(m.group(1));
        long unit = switch (m.group(2)) {
            case "s" -> 1000L;
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            case "d" -> 86_400_000L;
            case "w" -> 604_800_000L;
            default -> -1L;
        };
        long total = amount * unit;
        return total <= 0 ? DURATION_INVALID : total;
    }

    private void executeBan(long telegramId, long chatId, PendingAdminAction action, String durationInput) {
        long parsed = parseDuration(durationInput);
        if (parsed == DURATION_INVALID) {
            awaitingReasonInput.put(telegramId, action);
            send(chatId, "❌ Invalid duration '" + durationInput + "'. Use e.g. 1s, 5m, 2h, 7d, or 'p' for permanent.",
                    keyboard(inlineRow(button("Cancel", "admin:cancelreason:" + action.uuid()))));
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            String custom = plugin.cfg().banCommand();
            if (!custom.isBlank()) {
                boolean ok = dispatchConfiguredCommand(custom, action.playerName(), action.reason(), durationInput);
                send(chatId, ok ? "✅ Ran ban command for " + action.playerName() + ": " + action.reason()
                        : "❌ Ban command failed for " + action.playerName() + " (check console).");
                return;
            }

            java.util.Date expiry = parsed == DURATION_PERMANENT ? null : new java.util.Date(System.currentTimeMillis() + parsed);
            Bukkit.getBanList(BanList.Type.NAME).addBan(action.playerName(), action.reason(), expiry, null);
            Player p = Bukkit.getPlayer(action.uuid());
            if (p != null && p.isOnline()) {
                p.kick(Component.text("Banned: " + action.reason()));
            }
            String durationText = parsed == DURATION_PERMANENT ? "permanently" : "until " + expiry;
            send(chatId, "✅ Banned " + action.playerName() + " " + durationText + ": " + action.reason());
        });
    }

    private void withAccount(String uuidStr, java.util.function.Consumer<LinkedAccount> onFound, Runnable onMissing) {
        Optional<LinkedAccount> acc;
        try {
            acc = plugin.database().findByUuid(UUID.fromString(uuidStr));
        } catch (IllegalArgumentException e) {
            acc = Optional.empty();
        }
        if (acc.isPresent()) {
            onFound.accept(acc.get());
        } else {
            onMissing.run();
        }
    }

    private void adminSearch(long chatId, String name) {
        var acc = plugin.database().findByUsername(name);
        if (acc.isEmpty()) {
            send(chatId, "No linked account found for '" + name + "'.");
            sendAdminMenu(chatId);
            return;
        }
        sendAccountView(chatId, acc.get());
    }

    private void sendAdminMenu(long chatId) {
        int total = plugin.database().countAll();
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText("TgAuth admin panel — " + total + " linked account(s).");
        msg.setReplyMarkup(adminMenuMarkup());
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            plugin.getLogger().warning("Failed to send admin menu: " + e.getMessage());
        }
    }

    private void editToAdminMenu(long chatId, int messageId) {
        int total = plugin.database().countAll();
        editText(chatId, messageId, "TgAuth admin panel — " + total + " linked account(s).", adminMenuMarkup());
    }

    private InlineKeyboardMarkup adminMenuMarkup() {
        return keyboard(
                inlineRow(button("🔍 Search player", "admin:search")),
                inlineRow(button("📋 List accounts", "admin:list:0")));
    }

    private void editToAdminList(long chatId, int messageId, int page) {
        int total = plugin.database().countAll();
        int maxPage = Math.max(0, (total - 1) / ADMIN_PAGE_SIZE);
        page = Math.max(0, Math.min(page, maxPage));

        List<LinkedAccount> pageItems = plugin.database().findPage(page * ADMIN_PAGE_SIZE, ADMIN_PAGE_SIZE);
        StringBuilder sb = new StringBuilder("Linked accounts (page " + (page + 1) + "/" + (maxPage + 1) + "):");
        if (pageItems.isEmpty()) {
            sb.append("\n\n(none)");
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (LinkedAccount a : pageItems) {
            rows.add(inlineRow(button("👤 " + a.username(), "admin:view:" + a.uuid())));
        }

        List<InlineKeyboardButton> navRow = new ArrayList<>();
        if (page > 0) navRow.add(button("« Prev", "admin:list:" + (page - 1)));
        navRow.add(button("Menu", "admin:menu"));
        if (page < maxPage) navRow.add(button("Next »", "admin:list:" + (page + 1)));
        rows.add(navRow);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        editText(chatId, messageId, sb.toString(), markup);
    }

    private void sendAccountView(long chatId, LinkedAccount a) {
        send(chatId, formatAccount(a), accountViewMarkup(a));
    }

    private void editToAccountView(long chatId, int messageId, String uuidStr) {
        withAccount(uuidStr, a -> editText(chatId, messageId, formatAccount(a), accountViewMarkup(a)),
                () -> editText(chatId, messageId, "❌ Account not found.", keyboard(inlineRow(button("« Menu", "admin:menu")))));
    }

    private InlineKeyboardMarkup accountViewMarkup(LinkedAccount a) {
        String uuid = a.uuid().toString();
        return keyboard(
                inlineRow(button("🔗 Unlink", "admin:unlink:" + uuid), button("⭐ Un-premium", "admin:unpremium:" + uuid)),
                inlineRow(button("👢 Kick", "admin:kick:" + uuid), button("🔨 Ban", "admin:ban:" + uuid)),
                inlineRow(button("♻ Unban", "admin:unban:" + uuid), button("⚠ Warn", "admin:warn:" + uuid)),
                inlineRow(button("« Menu", "admin:menu")));
    }

    private String formatAccount(LinkedAccount a) {
        String tgUser = (a.telegramUsername() == null || a.telegramUsername().isBlank())
                ? "(no username)" : "@" + a.telegramUsername();
        return a.username() + "\n"
                + "  Telegram ID: " + a.telegramId() + "\n"
                + "  Telegram: " + tgUser + "\n"
                + "  Premium: " + (a.premium() ? "yes" : "no");
    }

    private int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void sendConfirmRequest(long chatId, String text, String token) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText(text);
        msg.setReplyMarkup(keyboard(inlineRow(
                button(plugin.lang().rawGet("confirm.button-confirm"), "confirm:" + token),
                button(plugin.lang().rawGet("confirm.button-reject"), "reject:" + token))));

        try {
            var sent = execute(msg);
            plugin.authManager().registerTelegramMessage(token, chatId, sent.getMessageId());
        } catch (TelegramApiException e) {
            plugin.getLogger().warning("Failed to send Telegram confirm request: " + e.getMessage());
        }
    }

    public void editConfirmResult(long chatId, int messageId, String newText) {
        try {
            EditMessageText edit = new EditMessageText();
            edit.setChatId(chatId);
            edit.setMessageId(messageId);
            edit.setText(newText);
            execute(edit);

            EditMessageReplyMarkup clearMarkup = new EditMessageReplyMarkup();
            clearMarkup.setChatId(chatId);
            clearMarkup.setMessageId(messageId);
            clearMarkup.setReplyMarkup(null);
            execute(clearMarkup);
        } catch (TelegramApiException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("message is not modified")) {

                return;
            }
            plugin.getLogger().warning("Failed to edit Telegram message: " + msg);
        }
    }

    public void send(long chatId, String text) {
        send(chatId, text, null);
    }

    private void send(long chatId, String text, InlineKeyboardMarkup markup) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText(text);
        if (markup != null) msg.setReplyMarkup(markup);
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            plugin.getLogger().warning("Failed to send Telegram message: " + e.getMessage());
        }
    }

    private void editText(long chatId, int messageId, String text) {
        editText(chatId, messageId, text, null);
    }

    private void editText(long chatId, int messageId, String text, InlineKeyboardMarkup markup) {
        try {
            EditMessageText edit = new EditMessageText();
            edit.setChatId(chatId);
            edit.setMessageId(messageId);
            edit.setText(text);
            if (markup != null) edit.setReplyMarkup(markup);
            execute(edit);
        } catch (TelegramApiException e) {
            String msg = e.getMessage();
            if (msg == null || !msg.contains("message is not modified")) {
                plugin.getLogger().warning("Failed to edit Telegram message: " + msg);
            }
        }
    }

    private InlineKeyboardButton button(String text, String callbackData) {
        InlineKeyboardButton btn = new InlineKeyboardButton();
        btn.setText(text);
        btn.setCallbackData(callbackData);
        return btn;
    }

    private List<InlineKeyboardButton> inlineRow(InlineKeyboardButton... buttons) {
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (InlineKeyboardButton b : buttons) row.add(b);
        return row;
    }

    @SafeVarargs
    private InlineKeyboardMarkup keyboard(List<InlineKeyboardButton>... rows) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        for (List<InlineKeyboardButton> row : rows) keyboard.add(row);
        markup.setKeyboard(keyboard);
        return markup;
    }

    private void answerCallback(String callbackId, String text) {
        try {
            AnswerCallbackQuery answer = new AnswerCallbackQuery();
            answer.setCallbackQueryId(callbackId);
            if (text != null) answer.setText(text);
            execute(answer);
        } catch (TelegramApiException ignored) {
        }
    }
}
