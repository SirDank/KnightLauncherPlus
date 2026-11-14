<p align="center">
    <img src="src/main/resources/rsrc/img/icon-128.png" height="128">
</p>
<h1 align="center">KnightLauncherPlus</h1>
<p align="center">
    <a href="LICENSE"><img alt="GitHub license" src="https://img.shields.io/github/license/SirDank/KnightLauncherPlus?style=flat-square"></a>
    <a href="https://github.com/SirDank/KnightLauncherPlus/issues"><img alt="GitHub issues" src="https://img.shields.io/github/issues/SirDank/KnightLauncherPlus?style=flat-square"></a>
    <a href="https://github.com/SirDank/KnightLauncherPlus/stargazers"><img alt="GitHub stars" src="https://img.shields.io/github/stars/SirDank/KnightLauncherPlus?style=flat-square"></a>
    <a href="https://github.com/SirDank/KnightLauncherPlus/releases/"><img alt="Total downloads" src="https://img.shields.io/github/downloads/SirDank/KnightLauncherPlus/total.svg"></a>
    <a href="https://dankware.alwaysdata.net/discord"><img alt="Discord" src="https://img.shields.io/badge/Discord-Dankware-5865F2?style=flat-square&logo=discord&logoColor=white" target="_blank"></a>
</p>

Open source game launcher for Spiral Knights with extended features, custom server support, and a built-in game server emulator.

[![Buy Me A Coffee](https://img.shields.io/badge/Buy%20Me%20A%20Coffee-Donate-yellow?style=flat-square&logo=buy-me-a-coffee&logoColor=black)](https://buymeacoffee.com/sirdankk)

> **Notice**: KnightLauncherPlus is an independent hard fork of [KnightLauncher](https://github.com/lucasluqui/KnightLauncher) maintained by [SirDank](https://github.com/SirDank). It incorporates extended private server functionality and an embedded server host while staying updated with upstream client modernizations.

-----

## Features
- **Built-in Game Server Hosting**: Run your own Spiral Knights server directly from the launcher with 1-click startup, persistent SQLite database (Schema 16), live console monitoring, Haven & Training Hall world entry, combat, chat, mail, friends, and parties of up to 8.
- **Custom & Private Server Integration**: Join custom servers or your own local/LAN server with dedicated per-server settings and stock client compatibility.
- **Modern Dark Black Theme**: Redesigned neutral dark theme across all launcher interfaces.
- **Streamlined Modding**: Install and uninstall game mods easily without worrying about game updates.
- **Traffic Logger Mod**: Clean-room client telemetry capture mod to record and inspect Presents/Narya RPCs and DObject events for server development and protocol verification.
- **Editor Suite**: Launch into Silverweave, Model, Scene, Particle, and Crucible editors to create custom content or preview game models.
- **Graphics Enhancements**: Render scale (50–200%: supersampling above 100%, faster rendering below) and contrast-adaptive sharpening, injected at launch through a javaagent that hooks LWJGL — game files stay untouched (the bundled Java runtime may be swapped for a full one, kept and restored) and it survives game updates. The game's own settings already cover anisotropic filtering (up to 16x), MSAA and an fps limiter.
- **Advanced Java Configuration**: Intuitive UI for configuring JVM runtimes (Java 25 LTS), memory limits, and GC parameters.
- **File Verification & Rebuilding**: Re-install and verify game files with ease.
- **Discord Integration**: Rich Presence showing current in-game status and activity.
- **Alt Account Mode**: Launch lightweight secondary clients with reduced system resource overhead.

## Downloading & Installing

1. **Prerequisites**:
   - Java 25 or Java 8+ installed. The launcher will automatically configure or update to Java 25 LTS when launched.
2. **Download**:
   - Download the latest `KnightLauncher-x.x.x.zip` from [Releases](https://github.com/SirDank/KnightLauncherPlus/releases/latest).
3. **Extract**:
   - Extract the ZIP archive into your Spiral Knights directory (where `getdown-pro.jar` is located).
     - **Windows (Steam)**: `<SteamFolder>\steamapps\common\Spiral Knights`
     - **Windows (Standalone)**: `<UserFolder>\AppData\Local\Spiral Knights\app`
     - **macOS / Linux**: Inside your game's application directory.
4. **Run**:
   - Launch `KnightLauncher.jar` or use `KnightLauncher_windows.bat` / `KnightLauncher_mac_linux.sh`.

## Server Hosting Quick Start

The server is the [Tortodrone](https://github.com/N0rule/Tortodrone) engine, started from the **Host Server** tab. The launcher carries the engine jar; the first **Start** also downloads the engine's content pack (about 20 MB, checked against a pinned SHA-256) into `KnightLauncher/server/`, so it needs a connection once. It needs nothing from your game install except a copy of the game to connect with.

1. Open the **Host Server** tab.
2. Optional: **Server Settings** changes the port (default `47624`), LAN/VPN access (off by default: the server then listens on this computer only), player limit, start town, welcome message, log mode and log rotation, and combat timing. Every value is checked against what Tortodrone accepts before it is saved. Changes apply the next time the server starts; the window offers to restart a running one.
3. **Start**. The console shows the startup log (also saved in `KnightLauncher/server/server.log`, the previous run in `server.log.old`); a failure to start is shown in a dialog.
4. Create accounts in **Server Settings → Accounts & Knights** (the server has to be running), then **Connect & Play** selects **Local Server** and launches the game. That tab also resets passwords, toggles game master, deletes accounts and knights, edits a knight's crowns, energy, rank and prestige, and grants currency and items; each action shows the server's own answer. **Players** lists who is connected. **Maintenance** has the address and public key your friends need (also copied by the clipboard button on the Host Server tab), backups and restore, and a start-over that keeps the server key by default so friends do not need a new one.

The box under the console sends commands to the server's own console (the server tells you what it understands).

> **Warning**: this is a private-play server. Only open it to LAN/VPN networks you trust, never to the internet.

Adding a custom server (**Add Server**) points your installed game at that server's address, key and content URL. Only add servers you trust.

Removing the Official anti-cheat check (`safeguard`) means the launcher no longer blocks cheat mods on the Official servers. Using them there can get your account banned.

## Built-in Server (Tortodrone Engine)

KnightLauncherPlus integrates **Tortodrone** as its standalone clean-room game server engine:

- **Process Model**: Clean-room execution in a separate JVM process decoupled from the launcher build and game jars.
- **Installation**: the engine jar is bundled with the launcher (with its license and notices); the pinned release (`tortodrone_version`, `tortodrone_url`, `tortodrone_sha256` in `build.properties`) is downloaded once for its content pack and default configuration (without comments) and unpacked to `<gameDir>/KnightLauncher/server/`.
- **Configuration (`config/server.yml` & `config/combat.yml`)**:
  - Port, LAN/VPN access, maximum clients, startup town map, log mode, welcome message, and combat dodge cooldown.
- **SQLite Database (`data/tortodrone.db`)**:
  - `accounts`: Account authentication and Game Master permissions.
  - `knights`: Character records, appearance, and identifiers.
  - `world_characters`: Currency balances (crowns, energy) and character state.
  - `inventory_items`: Equipped and bag items, accessories, and variants.
- **Administration UI**:
  - Interactive Server Hosting tab with real-time logs, player counters, and command console.
  - Server Settings window: configuration, accounts and knights, online players, join information, backups and restore. Accounts and knights are changed through the running server itself; kick, ban and broadcast are not offered because the server has no console command for them.

## Building & Quick Deploying

1. Prerequisites:
   - JDK 25 installed (`javac` and `java` on `PATH`).
   - Git installed.
   - (Maven is automatically handled via the bundled `mvnw` wrapper; standalone build without requiring game jar dependencies).
2. Clone repository:
   ```bash
   git clone https://github.com/SirDank/KnightLauncherPlus.git
   cd KnightLauncherPlus
   ```
3. **1-Click Build & Deploy** (close the launcher first; it replaces `KnightLauncher.jar`):
   Automatically detects your Spiral Knights installation directory, builds the production JAR, and deploys it:
   ```cmd
   scripts\build_and_deploy.bat
   # Or on macOS / Linux:
   ./scripts/build_and_deploy.sh
   # To launch directly after deployment (-Run on Windows, --run on macOS / Linux):
   scripts\build_and_deploy.bat -Run
   ```
   Deploy script arguments:
   - `-Run` / `--run`: Automatically launches the game with the deployed jar.
   - `-Dev` / `--dev`: Builds targeting the dev environment (`127.0.0.1`).
   - `-SkipBuild` / `--skip-build`: Skips Maven packaging and deploys the existing artifact from `target/`.
   - `-DryRun` / `--dry-run`: Detects game install and prints steps without building or copying.
   - `-SkDir <path>` / `--sk-dir <path>`: Explicitly specifies the Spiral Knights installation directory.
4. **VS Code Tasks**:
   Open the repository in VS Code and use `Ctrl+Shift+B` or **Run Task** for preconfigured actions:
   - `Build & Deploy`: Default build task, compiles and deploys to the detected game folder.
   - `Build, Deploy & Run`: Builds, deploys, and launches KnightLauncherPlus.
   - `Deploy Only (Skip Build)`: Deploys the current artifact without rebuilding.
   - `Deploy Dry Run`: Tests game folder detection without writing files.
   - `Maven: Test`: Runs the full automated test suite.
   - `Maven: Package (Prod)`: Packages the standalone production JAR.
5. **Manual Build via Maven Wrapper**:
   ```bash
   cp deploy.properties.template deploy.properties   # once; the build bakes it into the jar
   ./mvnw clean package
   # Or for production release:
   ./mvnw -Denv=prod clean package
   ```
   Tests that need the game's data files skip themselves when the game is not installed; see `AGENTS.md` for running them against a game install.

## Community & Support
- **Discord**: [Join my Discord](https://dankware.alwaysdata.net/discord)
- **Support the Project**: [Buy Me A Coffee](https://buymeacoffee.com/sirdankk)

-----

## Credits & Upstream
- Built upon the foundations of [KnightLauncher](https://github.com/lucasluqui/KnightLauncher) by Lucas Allegri.
- The built-in server is [Tortodrone](https://github.com/N0rule/Tortodrone) by N0rule, under the [PolyForm Noncommercial License 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0). The launcher bundles its unmodified jar together with the license text and `Required Notice:` (see `src/main/resources/rsrc/modules/tortodrone/`); it runs in its own process, and the noncommercial terms apply to that program for everyone who receives it. Its content pack contains data derived from the original game, which its author does not license for redistribution, so the launcher does not contain it: it downloads the pack on first use.
- Spiral Knights is created and owned by Grey Havens / SEGA. KnightLauncherPlus is an independent open-source project.
