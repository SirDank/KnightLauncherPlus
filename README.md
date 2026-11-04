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
- **Built-in Game Server Hosting**: Run your own Spiral Knights server directly from the launcher with 1-click startup, accounts and knights saved in one local file, live console monitoring, and Haven & Training Hall world entry, chat, friends, parties and experimental generated dungeons.
- **Custom & Private Server Integration**: Join custom servers or your own local/LAN server with dedicated per-server settings and stock client compatibility.
- **Modern Dark Black Theme**: Redesigned neutral dark theme across all launcher interfaces.
- **Streamlined Modding**: Install and uninstall game mods easily without worrying about game updates.
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
> **Dungeons (experimental)**: the game ships no dungeon layouts or enemy AI, so the server generates its own. The game keeps all its `/` chat commands inside the client (it refuses any it does not know), so the server uses the ones the game already sends to a server. As party leader in town type `/mission` (or `/mission 5 hard`: depth, then `easy`, `medium` or `hard`) to start a dungeon; everyone in your party goes in together. `/depth 5` sets where dungeons start and the depth of your next level. `/debug help` lists the rest: `/debug companions 3` brings three server-played knights (random but fair gear, finite health, they count toward the difficulty) and `/debug leave` takes you out of a dungeon (leaving the party does too). Monsters wake when you enter their room, bigger parties meet more and tougher ones, clearing a level gives each knight a piece of gear that fits the depth, and the end of the last room leads one level deeper. This has been tested against the game's protocol but not yet by playing it: how it looks and feels in the real client is still to be seen.
>
> Server console commands: `status`, `players`, `say <text>`, `kick <id>`, `account list|reset <name> <password>|delete <name>`, `knight list <account>`, `dungeon clear`. Type them in the box at the bottom of the Host Server console and press Enter.

Adding a custom server (**Add Server**) points your installed game at that server's address, key and content URL. Only add servers you trust.

Removing the Official anti-cheat check (`safeguard`) means the launcher no longer blocks cheat mods on the Official servers. Using them there can get your account banned.

## Building & Quick Deploying

1. Prerequisites:
   - JDK 25 installed (`javac` and `java` on `PATH`).
   - Spiral Knights installed: the deploy script copies the game's `projectx-pcode.jar` into `lib/` for you (refreshing it when the game updates).
   - Git installed.
   - (Maven is automatically handled via the bundled `mvnw` wrapper).
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
   Builds `prod` by default (the Flamingo server the release uses); add `-Dev` / `--dev` for a local Flamingo at `127.0.0.1`. The script copies `deploy.properties.template` to `deploy.properties` when it is missing, and backs up the installed `KnightLauncher.jar` as `KnightLauncher.jar.bak` before replacing it.
4. **Manual Build via Maven Wrapper** (copy `projectx-pcode.jar` from the game's `code/` folder into `lib/` first):
   ```bash
   cp deploy.properties.template deploy.properties   # once; the build bakes it into the jar
   ./mvnw clean package
   # Or for production release:
   ./mvnw -Denv=prod clean package
   ```
   Tests that need the game's data files skip themselves when the game is not installed; see `AGENTS.md` for running them against a game install.

## Community & Support
- **Discord**: [Join our Dankware Discord](https://dankware.alwaysdata.net/discord)
- **Support the Project**: [Buy Me A Coffee](https://buymeacoffee.com/sirdankk)

-----

## Credits & Upstream
- Built upon the foundations of [KnightLauncher](https://github.com/lucasluqui/KnightLauncher) by Lucas Allegri.
- The server emulator speaks the open-source Three Rings (Narya/Clyde) protocol that the game is built on.
- Spiral Knights is created and owned by Grey Havens / SEGA. KnightLauncherPlus is an independent open-source project.
