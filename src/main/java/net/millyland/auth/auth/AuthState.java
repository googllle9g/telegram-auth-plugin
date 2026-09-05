package net.millyland.auth.auth;

public enum AuthState {
    /** Player is not linked yet, waiting for them to /link a code in the Telegram bot. */
    AWAITING_LINK,
    /** Player is linked, waiting for Confirm/Reject in Telegram. */
    AWAITING_CONFIRM,
    /** Player is free to play. */
    AUTHENTICATED
}
