# TgAuth — Telegram-based authorization for Paper 1.21.11

## What the plugin does
- Unauthenticated / unlinked players can't move, chat, break/place blocks, open inventories,
  attack, interact — nothing at all except `/tgcode`.
- **First join (account not linked yet):** the player gets a code in Minecraft chat and has to
  send it to the Telegram bot as `/link <code>`. This applies to both licensed and pirated
  (offline) accounts — linking is mandatory for everyone.
- **Return visits (account already linked):**
  - If the account is **premium** (verified via FastLogin, or by the UUID-version fallback if
    FastLogin isn't available/hasn't answered yet) and `premium-skip-confirmation` is enabled —
    the player logs straight in, no confirmation needed.
  - If the account is **not premium** (cracked) — Telegram gets a "confirm login, IP: ..."
    message with inline **Confirm/Reject** buttons. The player stays frozen until it's answered.
    Reject → kicked from the server.
- Fully translatable: `lang/ru.yml` and `lang/en.yml` ship in the plugin folder, the active
  language is picked with `language` in `config.yml`. Add your own `lang/xx.yml` with the same
  keys and point the config at it.

## Building
You need Java 21 and Maven. This sandbox has no network access, so the jar can't be built here —
download the source archive and build it yourself:

```bash
mvn clean package
```

The finished jar shows up at `target/TgAuth.jar`.

## Setup
1. Create a bot via `@BotFather`, get its token and username.
2. Drop `TgAuth.jar` into `plugins/`, start the server once so `config.yml` gets generated.
3. Fill in `telegram.bot-token` and `telegram.bot-username` in `plugins/TgAuth/config.yml`.
4. Optionally install `FastLogin` alongside it (softdepend) — the integration wires itself up
   automatically. Without FastLogin, the plugin falls back to detecting premium/offline by
   UUID version.
5. Restart, or run `/tgauth reload`.

## Commands
- `/tgcode` — player: show your current link code (if you need one right now).
- `/tgauth reload` — admin: reload the config and language files.
- `/tgauth unlink <name>` — admin: unlink an account from Telegram.
- `/tgauth forcelink <name> <telegramId>` — admin: force-link an account.
- `/tgauth info <name>` — admin: show the link status for an account.
- `/tgauth userinfo <name>` — admin: full profile — linked yes/no, Telegram ID, Telegram
  @username (to contact the player directly), and whether they're in FastLogin's premium list.
- `/tgauth fastlogin` — admin: diagnostics for the FastLogin integration (is it installed? did
  the hook register? how many players were verified premium this run?).

## Permissions
- `tgauth.admin` (default: op) — admin commands.
- `tgauth.bypass` (default: false) — skips authorization entirely for that player.

## Works completely fine without FastLogin
FastLogin is an entirely optional add-on, never a hard dependency:
- It's declared as `softdepend` in `plugin.yml`, not `depend` — the server will happily start
  TgAuth even if FastLogin isn't installed at all.
- The integration is implemented via `java.lang.reflect.Proxy` at runtime (see
  `FastLoginHook.java`) — there isn't a single FastLogin class in the compile-time dependencies.
  The plugin builds and behaves identically whether or not FastLogin will ever sit next to it.
- If FastLogin isn't found (or is turned off via `fastlogin.enabled: false`), TgAuth simply logs
  one info line and falls back to the UUID-version heuristic for premium/cracked detection —
  every other part of the flow (code in chat/bot, Confirm/Reject buttons, blocking actions)
  works exactly the same.

This is deliberate so the plugin can be published on Modrinth as a standalone product: servers
that don't need the hybrid premium+cracked mode don't have to install FastLogin at all — plain
Telegram login works out of the box.

## Auto-adding to FastLogin's premium list
FastLogin's own Mojang verification is "opt-in" per name by default - it never automatically
checks a name it hasn't been told about before. That creates a chicken-and-egg problem: without
telling FastLogin about a name first, a genuinely licensed player's very first connection would
never get flagged premium at all, since nothing ever asked FastLogin to check them. TgAuth
handles both sides of this:
- **At first registration** (a brand-new account's first successful `/link`), TgAuth checks the
  free/public Mojang username API to see if the name is actually owned by a real account, and if
  so runs FastLogin's own `/premium <name>` console command right then - this doesn't grant
  premium status by itself, it just tells FastLogin "please attempt the online-mode handshake
  for this name from now on". FastLogin still does its own cryptographic verification on the
  next connection before deciding anything. (Some FastLogin versions require a reconnect for
  this to take effect - that's expected, normal `/premium` behaviour, not a bug.)
- **Once FastLogin actually confirms** a session as premium (via `forceLogin`/`forceRegister` on
  our hook), TgAuth also runs `/premium <name>` again as reinforcement, so FastLogin's own
  persistent list stays in sync with what TgAuth already knows.

Both paths run at most once per name/player per server session, and are a complete no-op if
FastLogin isn't installed. Disable with `fastlogin.add-to-fastlogin-premium-list: false`. Check
`/tgauth fastlogin` for live counts of both.

## Auto-migrating a link when the UUID changes (cracked ⇄ premium) — off by default, read this first
The same username gets **different** UUIDs on an offline (cracked) account versus a licensed
one — that's just how Minecraft works. Left alone, this doesn't just mean re-linking Telegram:
Minecraft itself stores inventory, advancements, statistics and OP status in files/lists keyed
by UUID, so a player switching from cracked to premium (or back) would appear to lose
*everything* — inventory, ender chest, achievements, OP — on top of having to `/link` again.

**Security warning:** this feature is powerful but only partially verifiable, so it's **disabled
by default** (`auth.migrate-link-by-username: false`). The check TgAuth runs before migrating
(see below) can only prove *"this old UUID really is what a normal cracked login for this exact
name produces"* — it can **not** prove that the same real person is behind both logins. On a
server that allows cracked connections, anyone can connect under any free username. That opens a
name-squatting attack: someone connects cracked as e.g. `PopularStreamer` (a name they don't
actually own on Mojang), completes `/link` with *their own* Telegram, and when the real
`PopularStreamer` later connects with their real licensed account for the first time, this
feature would silently hand that account over to the attacker's Telegram — meaning the
attacker's Telegram would receive (and could simply reject) every future login-confirmation
request for the real owner's own account. Only turn this on for a trusted/small/whitelisted
playerbase, or if you've mitigated name-squatting some other way (e.g. a whitelist that only
admits names an admin has manually verified). On a public server, leave it off - players will
just need to run `/link` once more when switching between cracked and licensed, which is
completely safe (worst case for an attacker who squats a name: they get their own harmless,
separate cracked account, nothing merges with anyone else's).

If you do enable it, TgAuth handles both parts, as early as possible in the login sequence
(`AsyncPlayerPreLoginEvent`, before Minecraft even loads player data for the new UUID — doing
this any later would be too late to have any effect):
- **Telegram link** — re-points the existing link to the new UUID instead of asking for a fresh
  `/link`.
- **Player data files** — moves `playerdata/<uuid>.dat` (inventory, ender chest, position, etc.),
  `advancements/<uuid>.json` and `stats/<uuid>.json` from the old UUID to the new one, so the
  player's world state carries over exactly as it was.
- **OP status** — if the old UUID was OP, the new UUID is made OP and the old one de-opped, via
  the standard Bukkit API (works the same regardless of server implementation).

Both are matched by exact username (case-insensitive) via `net.millyland.auth.listener.UuidMigrationListener`
and `PlayerDataMigrator`. The world folder used for the file moves is captured once at plugin
startup (main thread) rather than looked up during the async login event, and every step logs a
clear line (source missing / target already exists / moved successfully / failed) - if a
migration doesn't seem to be doing what you expect, check the console around
`Migrating player data for ...` for the exact reason.

By default, if the destination UUID already has a playerdata/advancements/stats file on disk
(e.g. it connected to the server before, even just briefly during testing or during FastLogin's
own verification), TgAuth refuses to overwrite it, to avoid destroying real data. If your
server's actual flow always looks like *join cracked → `/link` → run FastLogin's `/premium`
yourself → reconnect with the licensed account*, that destination UUID realistically never has
data of its own yet at that point, so it's safe to set
`auth.migration-overwrite-existing-data: true` in `config.yml` to let migrations win
unconditionally. Leave it off if you're not sure the destination UUID never holds data you'd
mind losing.

As an extra (but not sufficient on its own, see the warning above) safety check, before
migrating, TgAuth verifies that at
least one side of the switch is exactly the *deterministic* offline-mode UUID for that name
(`UUID.nameUUIDFromBytes(("OfflinePlayer:" + name)...)`, the same formula vanilla/Bukkit use) -
this is a pure function of the username, so if it matches, we can be certain that UUID really is
*some* vanilla cracked login for this exact name - it just can't tell us it was the *same person*
as the one connecting now. A licensed UUID, by contrast, is assigned by Mojang effectively at
random and can't be derived from the name at all - the only way to learn it is an actual login or
a Mojang API lookup - so there's no equivalent check on that side; if neither UUID matches the
expected offline value, the migration is skipped and a warning is logged instead of guessing.

**Not covered:** permission plugins (LuckPerms, PermissionsEx, etc.) each store their own data
in their own format/storage, so TgAuth can't migrate them generically. If a permission plugin
you use tracks by UUID rather than by name, check whether it has its own "clone/merge user"
command for this same cracked/premium switch scenario. Similarly, whitelist/ban lists
(`whitelist.json`, `banned-players.json`) aren't touched — add the new UUID manually if needed.

## Config & language files auto-update across plugin updates
Updating the plugin jar never silently drops new settings or messages: on every startup (and on
`/tgauth reload`), TgAuth compares your `config.yml` and `lang/*.yml` against the versions
bundled in the jar and appends any keys that are missing, keeping every value you've already
set untouched. See `YamlMerger.java`. One caveat: because this uses Bukkit's standard YAML
writer to save the merged file, comments in it are lost the one time new keys actually get
added (files that are already fully up to date are left completely untouched, so this doesn't
happen on every boot).

## FastLogin integration details
By default, FastLogin doesn't perform any premium/license check at all unless it's either
running behind BungeeCord/Velocity, or has a recognised "auth plugin" hooked in — that's simply
how it's built (you'll see this in its logs as
`No auth plugin were found by this plugin ... Either one or both of the checks have to pass`).
TgAuth handles this itself: on startup it registers with FastLogin as a real `AuthPlugin` hook
through the public `FastLoginCore#setAuthPluginHook(...)` method (via reflection/`Proxy`, with
no hard compile-time dependency on a specific FastLogin build — this makes it more resilient to
FastLogin updates). Once registered:
- FastLogin checks with Mojang whether the account is licensed, and as soon as it confirms this
  it calls `forceLogin`/`forceRegister` on our hook, and TgAuth immediately marks the player as
  verified premium. Since that Mojang round-trip isn't instant, TgAuth waits up to
  `fastlogin.premium-check-wait-seconds` (default 4s) before deciding whether to send a Telegram
  confirmation request — if FastLogin confirms the license within that window, the premium
  player never even sees the Confirm/Reject buttons and logs straight in.
- If registration fails for any reason (incompatible FastLogin build, FastLogin not installed,
  etc.) a warning is logged and TgAuth falls back to the UUID-version heuristic (premium = v4,
  cracked = v3). The plugin keeps working either way, just slightly less precisely about
  premium/cracked without a real FastLogin check. Run `/tgauth fastlogin` at any time to check
  whether the hook actually registered and why not, if it didn't.

**Note:** for the hybrid mode (both licensed and cracked players) the server must run with
`online-mode: false` in `server.properties` — that's a requirement of FastLogin itself, since it
performs the Mojang check for each connecting player on its own.

## Storage layout
SQLite file at `plugins/TgAuth/database.db`, table `linked_accounts`:
`uuid, telegram_id, username, linked_at, telegram_username, premium`.
`telegram_username` and `premium` were added in a later version; existing databases get these
columns added automatically on startup (`Database.migrateSchema()`), no manual steps needed.
`premium` is a persisted flag (survives restarts) set the first time TgAuth authenticates that
account as a confirmed-premium player - it's what `/tgauth userinfo` reports.
