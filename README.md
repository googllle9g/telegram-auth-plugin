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
- Cracked ⇄ premium account migration (inventory, advancements, stats, OP) — off by default,
  see [Security](#security) before enabling.
- Rate-limited link codes, parameterized SQL, no plaintext secrets in logs.
- Fully translatable (`lang/*.yml`), config and language files auto-update on plugin updates.

## Requirements

- Paper or a Paper fork (Purpur, etc.), Minecraft 1.21.11
- Java 21+
- A Telegram bot token from [@BotFather](https://t.me/BotFather)
- [FastLogin](https://www.spigotmc.org/resources/fastlogin.14153/) (optional, for hybrid
  premium+cracked servers)

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
language: "ru"

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
  migrate-link-by-username: false      # see Security
  migration-overwrite-existing-data: false

fastlogin:
  enabled: true
  premium-skip-confirmation: true
  premium-check-wait-seconds: 4
  add-to-fastlogin-premium-list: true

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

## FastLogin integration

FastLogin doesn't run any Mojang verification at all unless it's behind a proxy or has a
registered "auth plugin" hook — otherwise you'll see `No auth plugin were found by this plugin`
in its logs and it does nothing. TgAuth registers itself as that hook automatically on startup
(via reflection, so it doesn't need FastLogin as a build dependency and tolerates version
differences). Run `/tgauth fastlogin` to check whether it worked.

**Requires `autoRegister: true` in FastLogin's own `config.yml`** (a FastLogin setting, separate
from TgAuth's). Without it, FastLogin only checks premium status for names already registered
with the hooked auth plugin — a brand-new player's very first connection never gets checked at
all, since there's nothing registered for that name yet. If you've avoided `autoRegister`
before because of password issues with LoginSecurity/AuthMe (it force-generates a real login
password there), that doesn't apply to TgAuth: our `forceRegister` implementation ignores the
password argument entirely — TgAuth has no concept of passwords, everything goes through
Telegram, so there's nothing for a random generated password to break.

As a secondary safety net, TgAuth also checks Mojang's public username API on a brand-new
account's first `/link` and runs FastLogin's `/premium <name>` command if the name is owned by
a real account — this doesn't grant trust by itself, it just nudges FastLogin to check, and only
helps for accounts FastLogin already considers registered by that point (i.e. it complements
`autoRegister`, it isn't a substitute for it).

Requires `online-mode: false` in `server.properties` — FastLogin performs its own per-player
Mojang check.

## Security

- **Link codes are rate-limited**, per Telegram account and globally, against brute-forcing the
  6-digit code.
- **Confirm/Reject buttons use a random 128-bit token** — not guessable, not replayable.
- **All SQL is parameterized.**
- **`auth.migrate-link-by-username` is off by default.** It re-links an account across a cracked
  ⇄ premium UUID switch by matching username, which can't be cryptographically verified — a
  cracked client can squat any free username. Enabling it means a player who registers first
  under a squatted name could take over the real owner's account when they later connect with
  their licensed account. Only enable this on a small/trusted/whitelisted server.
- Player data migration files/inventory are never overwritten unless
  `migration-overwrite-existing-data: true` is also set.

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
