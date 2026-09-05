package net.millyland.auth.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Talks to Mojang's public (no auth required) username-lookup endpoint to answer a single,
 * narrow question: "is this username currently owned by some real Mojang/Microsoft account?"
 * <p>
 * This does NOT verify that the player currently connecting under that name actually owns the
 * account - that cryptographic proof is what FastLogin's own online-mode handshake does. This
 * is only used to decide whether it's worth asking FastLogin to attempt that handshake at all
 * (see {@link net.millyland.auth.hook.FastLoginHook#optIntoFastLoginPremiumCheck}), since
 * FastLogin's own automatic premium check is "opt-in" per name and never runs on its own for a
 * name it hasn't been told about yet - a chicken-and-egg problem this breaks.
 */
public final class MojangApi {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private MojangApi() {
    }

    /**
     * @return true if the name currently belongs to a real (premium) Mojang account, false if
     * it doesn't or the lookup couldn't be completed (network issue, rate limit, etc. - errs on
     * the side of "don't know", since callers should treat that the same as "not premium").
     */
    public static boolean isPremiumUsername(String name) {
        try {
            String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + encoded))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<Void> response = CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
