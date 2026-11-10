# KnightLauncherPlus — Agent Instructions & Architecture Guide

## Overview
KnightLauncherPlus is an independent hard fork of `lucasluqui/KnightLauncher`. It does not send changes upstream on its own; genuine upstream bugs found here are reported to the maintainer of this fork, who decides per issue whether to raise a PR. It is a dedicated fork with extended features including custom server joining, built-in game server hosting, and enhanced modding capabilities.

## Upstream Synchronization Policy
- Upstream remote: `https://github.com/lucasluqui/KnightLauncher.git`
- Upstream commits are periodically merged into `main` via `git merge upstream/main --no-ff`.
- When resolving merge conflicts, preserve KnightLauncherPlus features (custom servers, built-in server hosting, dark theme, rebranded URLs) while adopting upstream engine modernizations (Java 25 target, HiDPI scaling, modernized threading, ordered properties).

## Build & Runtime Environment
- **Java**: Java 25 LTS (64-bit HotSpot VM).
- **Build Tool**: Apache Maven (`pom.xml`) via repository wrapper (`mvnw` / `mvnw.cmd`).
  - Copy `deploy.properties.template` to `deploy.properties` first (once); the build bakes it into the jar. CI and the deploy scripts do this for you.
  - Standard development build: `./mvnw clean package` (env `dev`: local Flamingo)
  - Production build: `./mvnw -Denv=prod clean package` (what CI releases)
  - Automated tests: `./mvnw test`.
  - 1-click build & deploy to local game dir: `scripts\build_and_deploy.bat` (or `./scripts/build_and_deploy.sh`). It defaults to the `prod` env, `-Dev` / `--dev` for `dev`; `-Run` / `--run` launches afterwards.
- **Dependencies**:
  - `lib/discord-rpc-20220830184500.jar`
  - `lib/image4j-0.7.2.jar`

## Game Server Architecture & Upstream Integration Policy
- **Server Engine**: KnightLauncherPlus integrates **Tortodrone** (`https://github.com/N0rule/Tortodrone`) as its standalone clean-room game server engine.
- **Installation**: the engine jar is bundled (`rsrc/modules/tortodrone/tortodrone.jar`, unmodified, with Tortodrone's `LICENSE`, `NOTICE`, `THIRD-PARTY.md` and `licenses/`, which are copied next to it). `TortodroneInstaller.ensureInstalled()` (every start; quick when current) first puts the bundled jar in `KnightLauncher/server/` (replacing any jar whose SHA-256 is not `tortodrone_jar_sha256`; works offline), then, if the content pack is missing or from another version, downloads the release pinned in `build.properties` (`tortodrone_version/url/sha256`), verifies the SHA-256 and unpacks only `content/` and the default `config/` (comments stripped; an existing `config/` is never overwritten; the jar and web UI pages are skipped). To update the engine: replace the bundled jar and its notices from the new release, then change `tortodrone_version`, `tortodrone_url`, `tortodrone_sha256` and `tortodrone_jar_sha256` (digests: the release page and `shasum -a 256` of the jar); `TortodroneInstallerTest` fails if the bundled jar and `tortodrone_jar_sha256` disagree.
- **Build Isolation**: The launcher build (`pom.xml`) is completely decoupled from `projectx-pcode.jar`. Clean-room server execution runs in its own JVM process without game jar dependencies.
- **Locking**: `ServerHostManager` serializes start/stop/restart/reset on `_lifecycle`; nothing the UI calls directly may take that lock (a start waits up to 60 s). While the server runs it is the only writer of `data/tortodrone.db`: account changes go through its console (verified against v0.40.8: `account create` asks for the password twice, `gm <name> true|false`, `account delete` asks to repeat the name, `stop` ends the process while the web UI is off), and the launcher edits the database only when the server is stopped (reads are read-only).
- **License**: Tortodrone is PolyForm Noncommercial 1.0.0: the jar may be redistributed with its license text and `Required Notice:` (bundled, and copied beside the jar on install), and it only runs as a separate process, so it is an aggregate with the GPL launcher, but everyone who gets a copy may use it for noncommercial purposes only. Its content pack holds game-derived data that its `NOTICE` says is not licensed for redistribution: never commit or bundle `content/` or a `content.zip`.
- **Upstream Sync**: Keep server logic upstream in Tortodrone. Sync by updating `tortodrone.jar`, content packs, and the version stamp in `build.properties`.

## Package & Component Architecture
- `com.lucasluqui.bootstrap`: Native launcher entry point and runtime loader (`Bootstrap.java`, `Main.java`).
- `com.lucasluqui.launcher`: Core application lifecycle (`LauncherApp`, `LauncherGlobals`, `CustomColors`, `Fonts`, `Stylesheet`, `Updater`).
- `com.lucasluqui.launcher.server`: **Built-in Spiral Knights Game Server Management & UI**.
  - **Process model**: `ServerHostManager` manages the server lifecycle, running `tortodrone.jar --config config/server.yml` with the launcher's console Java, cwd = `<gameDir>/KnightLauncher/server/`. It captures listening notifications and streams stdout to the console panel, relays stdin commands to Tortodrone, and reads `data/keys/public.txt` to register the Local Server entry.
  - `ServerHostManager`: lifecycle and registration of the **Local Server** entry.
  - `ServerSettingsDialog`: Multi-tab dialog for Server Configuration (port, LAN, max clients, log mode, welcome message, startup map, dodge cooldown, safe server data reset with automatic backup) and Accounts & Knights administration.
  - `ui/`: Server Hosting tab (`ServerHostUI`, `ServerConsolePanel`, `ui/handler/ServerEventHandler`).
- `src/main/java8/.../runtime/Updater.java` hardcodes the self-update repository; `UpdaterRepoTest` keeps it equal to `LauncherGlobals`.
- `com.lucasluqui.launcher.flamingo`: Server list and metadata client (`FlamingoManager`). Supports official and manually added custom servers.
- `com.lucasluqui.launcher.mod`: Mod loader and file management (`ModManager`, `ZipMod`, `JarMod`, `Modpack`). Class mods mount into `code/config.jar`.
- `com.lucasluqui.launcher.editor`: Scene and loadout editors (`Silverweave`, `EditorsUI`).
- `com.lucasluqui.launcher.setting`: User preferences, JVM options, per-server connection settings (`SettingsManager`, `SettingsUI`).
- `com.lucasluqui.launcher.ui`: Swing UI panels based on `BaseUI`, `Scale` HiDPI support, and FlatLaf dark theme.
- Shortcuts (`LauncherApp.checkShortcut`): the Windows link, Linux `.desktop` file and macOS `~/Applications/Knight Launcher.app` are rebuilt whenever the launcher's Java path or install folder changes (`launcher.shortcutJava` holds a hash of both). When testing the launcher, run it with `-Duser.home=<scratch folder>` so your real shortcut is not overwritten.
- Official vs private launches: a private (stock client) launch patches `code/config.jar` (`SettingsManager.applyConnectionSettings`) and records `launcher.configJarPrivate`; the next Official launch re-applies the Official values first. Connection settings are validated (port 1-65535, http(s) URLs) before a private launch.

## Design & Color Conventions
- All UI colors are defined in `com.lucasluqui.launcher.CustomColors`.
- Theme (same as the SKapsule app): neutral black surfaces (`#000000` titlebar to `#1E1E1E`/`#333333` components, see `CustomColors`) and one accent, `#AB4A81` (`CustomColors.ACCENT`, also FlatLaf's `@accentColor` in `Stylesheet`). Launch, Silverweave and the changelog button use it; status colors (update green, warning amber, danger red, Discord, Buy Me a Coffee yellow) and the mod-type badge colors stay. The app icons (`rsrc/img/icon-*.png`, `icon-512.icns`) are two colors only: `#AB4A81` ring and shield on black.
- Support & Donations: Buy Me a Coffee (`https://buymeacoffee.com/sirdankk`, brand yellow `#FFDD00`).
- Community Discord: `https://dankware.alwaysdata.net/discord`.
- Home screen layout: the five side buttons start at y=245 (35px, 4px apart) between the "Learn more" pill and the discord/bug/donate row at y=444; adding a button means re-spacing, not overlapping.
- Safeguard files and logic (`extractSafeguard`) are completely removed. URLs that are still upstream's (not rebranded): the CDN, the Flamingo server (`deploy.properties.template`) and the Java runtime downloads.

## Engine Background
- Tortodrone is a clean-room game server engine implementing the Three Rings protocols. It executes in its own isolated process with standalone configuration in `<gameDir>/KnightLauncher/server/`.

## Known Gaps / Roadmap
- Built-in server management: `ServerHostManager` manages `tortodrone.jar`, server configuration (`server.yml`, `combat.yml`), and SQLite data persistence (`data/tortodrone.db`).
- Account management: Interactive management and inspection of accounts and knights via SQLite in `ServerSettingsDialog`.
- Maven wrapper is the takari 0.5.6 one, which does not verify checksums; moving to the Apache wrapper would allow pinning.
- `SKapsule` / `SKapsule-iOS` mirror `ModManager.FILTER_LIST` by hand; keep them in step.

## Documentation Maintenance
- Update this `AGENTS.md` file whenever architectural patterns, configuration keys, or core systems change.
- Keep `README.md` updated with new user-facing capabilities, features, and setup instructions.

