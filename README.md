# TgAuth

Telegram-based login for Paper/Purpur servers. Players authenticate through a Telegram bot
instead of a password — a code in chat for first-time linking, and inline Confirm/Reject
buttons for every login after that. Optional FastLogin integration adds real Mojang-verified
premium detection, with a safe fallback when it's not installed.

## Features

- No password login — players link their Minecraft account to Telegram once, then confirm
  logins with a button tap.
- Unauthenticated players can't move, chat, break/place blocks, open inventory, or attack/be
  attacked. Every relevant Bukkit event is covered, including edge cases like melee damage,
  buckets, and vehicles.
- Optional FastLogin integration: licensed players skip the confirm step entirely once linked;
  cracked/offline accounts always confirm through Telegram. Works standalone without FastLogin.
- Cracked ⇄ premium account migration (inventory, advancements, stats, OP) — on by default.
- Rate-limited link codes, parameterized SQL, no plaintext secrets in logs.
- Fully translatable (`lang/*.yml`), config and language files auto-update on plugin updates.

## Requirements

- Paper or a Paper fork (Purpur, etc.), Minecraft 1.21.11
- Java 21+
- A Telegram bot token from [@BotFather](https://t.me/BotFather)
- [FastLogin](https://ci.codemc.io/job/Games647/job/FastLogin/) (optional, for hybrid
  premium+cracked servers)
- [LuckPerms](https://luckperms.net/) (optional, for offline admin-panel permission checks)

## Building

```bash
mvn clean package
```

Output: `target/TgAuth.jar`

## Setup

1. Drop `TgAuth.jar` into `plugins/`, start the server once to generate `config.yml`.
2. Set `telegram.bot-token` and `telegram.bot-username` in `plugins/TgAuth/config.yml`.
3. `/tgauth reload` or restart.

Player flow: join → get a code in chat → send `/link <code>` to the bot → done. On future
logins, either instant (licensed + FastLogin confirmed) or a Confirm/Reject button in Telegram.

## Commands

| Command | Description | Permission |
|---|---|---|
| `/tgcode` | Show your current link code | — |
| `/tgauth reload` | Reload config and language files | `tgauth.admin` |
| `/tgauth unlink <player>` | Unlink a player from Telegram | `tgauth.admin` |
| `/tgauth forcelink <player> <telegramId>` | Force-link an account | `tgauth.admin` |
| `/tgauth userinfo <player>` | Link status, Telegram ID, @username, premium status | `tgauth.admin` |
| `/tgauth fastlogin` | FastLogin integration diagnostics | `tgauth.admin` |

## Permissions

| Node | Default | Effect |
|---|---|---|
| `tgauth.admin` | op | Admin commands |
| `tgauth.bypass` | false | Skips authorization entirely |

## Configuration reference

`config.yml` (defaults shown):

```yaml
language: "en"

telegram:
  bot-token: ""
  bot-username: ""

auth:
  code-expire-seconds: 300
  confirm-timeout-seconds: 90
  auth-timeout-seconds: 120
  reminder-interval-seconds: 20
  apply-blindness: true
  apply-slowness: true
  migrate-link-by-username: true
  migration-overwrite-existing-data: true
  cracked-ip-cooldown-seconds: 0

fastlogin:
  enabled: true
  premium-skip-confirmation: true
  premium-check-wait-seconds: 4

storage:
  file: "database.db"

security:
  link-max-attempts: 5
  link-attempt-window-seconds: 60
  link-lockout-seconds: 300
  global-link-max-attempts: 20
  global-link-attempt-window-seconds: 60
  global-link-lockout-seconds: 120
```

Language files live in `plugins/TgAuth/lang/`. Add a new `xx.yml` with the same keys and set
`language: xx` to add a translation.

## Trusted-IP cooldown for cracked accounts

Set `auth.cracked-ip-cooldown-seconds` above 0 to let a cracked/offline account skip the
Confirm/Reject request on reconnect, as long as it's from the same IP that confirmed recently.
Off (`0`) by default — every login always requires confirming.

Trust is scoped tightly to avoid it turning into a standing whitelist:
- Reconnecting from a **different** IP still requires confirming as normal, and immediately
  revokes the old trusted IP — it won't silently come back into play later.
- If that same IP is later used to log into a **different** account, the original account's
  trust is revoked too — a shared IP (NAT, VPN exit, family members) isn't uniquely tied to
  either player anymore.

A first-time `/link` also counts as a confirmed action and starts the trust immediately.

## Bot admin panel

Send `/admin` to the bot to open an inline-button panel (set `telegram.admin-panel-enabled:
false` in `config.yml` to disable this entirely). Two ways to get access:
- Your Telegram ID is listed in `telegram.admin-ids` in `config.yml`, **or**
- Your Telegram is linked (`/link`) to a Minecraft account that has the `tgauth.admin`
  permission. Checked live if you're online; if offline, checked via LuckPerms if it's
  installed, otherwise falls back to OP status.

Once in, the panel offers:
- **Search player** — reply with a Minecraft name to open its account view.
- **List accounts** — paginated browse of every linked account, 5 per page, tap a name to open
  its account view.

The account view shows Telegram ID, @username, and premium status, with action buttons:

| Button | Effect |
|---|---|
| 🔗 Unlink | Remove the Telegram link (asks to confirm first) |
| ⭐ Un-premium | Clears TgAuth's own premium flag and runs FastLogin's `/unpremium <name>` (if FastLogin is installed) so it drops them from its premium list too |
| 👢 Kick | Prompts for a reason, then kicks if online |
| 🔨 Ban | Prompts for a reason, then adds a name-ban and kicks if online |
| ♻ Unban | Removes a name-ban |
| ⚠ Warn | Prompts for a reason, then messages the player in-game if online |

Kick/Warn ask for a reason as your next message before acting. Ban asks for a reason, then a
duration (`1s`, `5m`, `2h`, `7d`, or `p` for permanent).

Each of Kick/Ban/Unban/Warn can run a configured command instead of TgAuth's built-in Bukkit
behavior — set `admin-commands.kick-command` / `ban-command` / `unban-command` / `warn-command`
in `config.yml` if you use a dedicated punishment plugin (LiteBans, AdvancedBan, etc.). Warn
in particular defaults to just messaging the player in-game; set `warn-command` if you want it
to run your punishment plugin's own `/warn` instead. Placeholders: `%player%`, `%reason%`,
`%duration%` (ban only). Leave blank to keep the built-in behavior.

Get your numeric Telegram ID from a bot like `@userinfobot`.

## FastLogin integration

FastLogin doesn't run any Mojang verification at all unless it's behind a proxy or has a
registered "auth plugin" hook — otherwise you'll see `No auth plugin were found by this plugin`
in its logs and it does nothing. TgAuth registers itself as that hook automatically on startup
(via reflection, so it doesn't need FastLogin as a build dependency and tolerates version
differences). Run `/tgauth fastlogin` to check the status of everything below.

Three settings in **FastLogin's own `config.yml`** (not TgAuth's) matter for a correctly
working hybrid (premium + cracked) server — verified against
[FastLogin's actual config.yml comments](https://github.com/games647/FastLogin/blob/main/core/src/main/resources/config.yml):

- **`autoRegister: true`** — without it, FastLogin never checks a brand-new
  (never-before-registered) name's premium status at all. If you've avoided this before because
  of password issues with LoginSecurity/AuthMe (it force-generates a real login password there),
  that doesn't apply to TgAuth: our `forceRegister` implementation ignores the password argument
  entirely — TgAuth has no concept of passwords, everything goes through Telegram.
- **`secondAttemptCracked: true`** — with `autoRegister` on but this off, a genuinely cracked
  player using a name FastLogin decides to check gets disconnected ("invalid session") and keeps
  getting disconnected on every reconnect, since FastLogin re-attempts the premium handshake
  every single time instead of remembering the name already failed once. Without this, cracked
  players effectively can't use a hybrid server at all.
- **`premiumUuid: true`** — without it, FastLogin does **not** switch a verified-premium
  player's effective UUID to their real Mojang UUID; they keep the same offline/cracked UUID
  regardless of verification. TgAuth's `auth.migrate-link-by-username` only has any effect when
  a UUID actually changes between a cracked and a premium login for the same name — without
  this setting, that never happens, so the feature is a no-op either way.

Requires `online-mode: false` in `server.properties` — FastLogin performs its own per-player
Mojang check.

## Security

- **Link codes are rate-limited**, per Telegram account and globally, against brute-forcing the
  6-digit code.
- **Confirm/Reject buttons use a random 128-bit token** — not guessable, not replayable.
- **All SQL is parameterized.**
- ([`JoinManagement.onLogin`](https://github.com/games647/FastLogin/blob/master/core/src/main/java/com/github/games647/fastlogin/core/shared/JoinManagement.java),
  [`config.yml`](https://github.com/games647/FastLogin/blob/main/core/src/main/resources/config.yml)):
  with `secondAttemptCracked: true` set (see FastLogin integration above), FastLogin keeps a
  permanent per-username record — the first time a name fails Mojang verification, it's marked
  cracked for good, and every later connection under that name skips straight to a cracked
  session with no further Mojang attempt, regardless of who's actually connecting. So if someone
  squats a free username while cracked, the real owner connecting later with their licensed
  account gets the *same* UUID as the squatter (with `premiumUuid: true` also set, as required
  for this feature to do anything at all — see above), not a different one — there's nothing to
  migrate. Enabled by default on that basis, **provided both FastLogin settings above are set as
  documented** — run `/tgauth fastlogin` to check.
  **Narrow exception:** if an admin (or a player with `fastlogin.bukkit.command.premium`)
  manually runs FastLogin's `/premium <name>` for a name that already has a squatted TgAuth
  link, and the real owner happens to be the one connecting at that moment, a genuinely
  different UUID *can* appear — and this setting would then migrate the link, squatted one
  included. Check `/tgauth userinfo <name>` before manually running `/premium` for a name you
  didn't link yourself.
- Player data migration overwrites any playerdata/advancements/stats already present for the
  destination UUID by default (`migration-overwrite-existing-data: true`) — safe on a fresh
  server, or one where TgAuth/FastLogin were set up from day one, since a migration only ever
  targets a UUID being recognised as premium for the first time. Turn it off if you added this
  setup to an already-running server where players already had real progress under their own
  premium UUIDs.

Report anything else you find.

## Third-party components

Bundled (shaded) into the release jar:

- [TelegramBots](https://github.com/rubenlagus/TelegramBots) — MIT License — © Ruben Bermudez
- [SQLite JDBC](https://github.com/xerial/sqlite-jdbc) — Apache License 2.0 — © Xerial Project
- [Gson](https://github.com/google/gson) — Apache License 2.0 — © Google
- [OkHttp](https://github.com/square/okhttp) — Apache License 2.0 — © Square, Inc.

Compiled against only (not bundled): [Paper](https://papermc.io/) (`paper-api`).

## License

See [`LICENSE`](LICENSE).
