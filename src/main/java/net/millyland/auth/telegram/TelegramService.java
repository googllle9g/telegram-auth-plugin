package net.millyland.auth.telegram;

import net.millyland.auth.TgAuthPlugin;
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

import java.util.List;

public class TelegramService extends TelegramLongPollingBot {

    private final TgAuthPlugin plugin;
    private final String username;

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

    private void handleMessage(Message message) {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();
        String text = message.getText().trim();

        if (text.startsWith("/start")) {
            send(chatId, plugin.lang().rawGet("telegram.start"));
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

        if (data == null) return;

        if (data.startsWith("confirm:") || data.startsWith("reject:")) {
            boolean approve = data.startsWith("confirm:");
            String token = data.substring(data.indexOf(':') + 1);
            plugin.authManager().handleConfirmCallback(token, approve, chatId, messageId);
            answerCallback(callback.getId(), null);
        }
    }

    public void sendConfirmRequest(long chatId, String text, String token) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText(text);

        InlineKeyboardButton confirmBtn = new InlineKeyboardButton();
        confirmBtn.setText(plugin.lang().rawGet("confirm.button-confirm"));
        confirmBtn.setCallbackData("confirm:" + token);

        InlineKeyboardButton rejectBtn = new InlineKeyboardButton();
        rejectBtn.setText(plugin.lang().rawGet("confirm.button-reject"));
        rejectBtn.setCallbackData("reject:" + token);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(List.of(List.of(confirmBtn, rejectBtn)));
        msg.setReplyMarkup(markup);

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
                // Harmless: happens if the same result was already applied (e.g. a duplicate
                // Confirm/Reject tap sending two callback queries for the same button click).
                // Nothing to do, no need to alarm the console with a warning for this.
                return;
            }
            plugin.getLogger().warning("Failed to edit Telegram message: " + msg);
        }
    }

    public void send(long chatId, String text) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText(text);
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            plugin.getLogger().warning("Failed to send Telegram message: " + e.getMessage());
        }
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
