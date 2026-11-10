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

The server runs on your own Spiral Knights install (nothing from the game is bundled with the launcher), so KnightLauncherPlus has to be in your game folder and the game installed and up to date.

**One-time setup**: the server needs two scenes, Haven (`1`) and the Training Hall (`294`). The game downloads scenes from the official servers when you visit them and saves them in its `scenes` folder; the launcher does not ship them. Start Spiral Knights, log in, and visit Haven and the Training Hall once. The next time you press **Start Server**, the launcher copies them into `KnightLauncher/server/scenes/` by itself. Until then, **Start Server** explains what is missing.

1. Navigate to the **Host Server** tab in KnightLauncherPlus.
2. Optional: open **Server Settings** to change the port (default `47624`) or tick **Allow LAN/VPN** if friends should join. By default the server listens on this computer only.
3. Click **Start Server**. The live console shows the startup log; a failure to start is shown in a dialog.
4. Click **Connect & Play** to select **Local Server** and launch the game.

**Server Settings**: the button next to *Connect & Play* opens a window with every server setting (port, LAN/VPN access, connection limits, update rates, walking speed, map bounds, combat timing). Each setting has a description and a reset button; changes apply the next time the server starts and are stored in `KnightLauncher/server/server.properties`.

The server's output is also saved in `KnightLauncher/server/server.log` inside your game folder (the previous run in `server.log.old`).

> **Warning**: the server is an emulator for private play. The first time you sign in with a new username and password, the server creates that account; afterwards the same password is needed, and the server keeps only a salted hash of it. Anyone who can reach the port can still create an account of their own. Only open it to LAN/VPN networks you trust, never to the internet. It accepts at most 32 connections and drops any that do not sign in within 10 seconds.
>
> Logging in, Haven, the Training Hall and combat work with the real game. Accounts, knights (each with its own crowns and items), in-game mail, chat and whispers, friends and parties of up to 8 work and are tested against the game's own protocol.
>
> Server console commands: `status`, `players`, `say <text>`, `kick <id>`, `account list|reset <name> <password>|delete <name>`, `knight list <account>`. Type them in the box at the bottom of the Host Server console and press Enter.

Adding a custom server (**Add Server**) points your installed game at that server's address, key and content URL. Only add servers you trust.

## Built-in Server (Tortodrone Engine)

KnightLauncherPlus integrates **Tortodrone** as its standalone clean-room game server engine:

- **Process Model**: Clean-room execution in a separate JVM process decoupled from the launcher build and game jars.
- **Module Packaging**: Packaged under `rsrc/modules/tortodrone/` (`tortodrone.jar` + `content.zip`) and extracted to `<gameDir>/KnightLauncher/server/`.
- **Configuration (`config/server.yml` & `config/combat.yml`)**:
  - Port, LAN/VPN access, maximum clients, startup town map, log mode, welcome message, and combat dodge cooldown.
- **SQLite Database (`data/tortodrone.db`)**:
  - `accounts`: Account authentication and Game Master permissions.
  - `knights`: Character records, appearance, and identifiers.
  - `world_characters`: Currency balances (crowns, energy) and character state.
  - `inventory_items`: Equipped and bag items, accessories, and variants.
- **Administration UI**:
  - Interactive Server Hosting tab with real-time logs, player counters, and command console.
  - Server Settings dialog for live configuration and account administration (create, delete, toggle GM, grant currency/items).

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
- The server emulator speaks the open-source Three Rings (Narya/Clyde) protocol that the game is built on.
- Spiral Knights is created and owned by Grey Havens / SEGA. KnightLauncherPlus is an independent open-source project.
