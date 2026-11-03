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

> **Notice**: KnightLauncherPlus is an independent hard fork of [KnightLauncher](https://github.com/lucasluqui/KnightLauncher) maintained by [SirDank](https://github.com/SirDank) and Dankware. It incorporates extended private server functionality and an embedded server host while staying updated with upstream client modernizations.

-----

## Features
- **Built-in Game Server Hosting**: Run your own Spiral Knights server directly from the launcher with 1-click startup, zero external databases required, live console monitoring, and Haven & Training Hall world entry.
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

1. Navigate to the **Host Server** tab in KnightLauncherPlus.
2. Verify the port (default `47624`).
3. Click **Start Server**. The live console will display the server startup log and bound IP addresses.
4. Click **Connect to My Server** to select your server in the switcher, then click **Launch** to enter the game!

## Building From Source

1. Prerequisites:
   - JDK 25 installed (`javac` and `java` on `PATH`).
   - Apache Maven installed (`mvn` on `PATH`).
   - Git installed.
2. Clone repository:
   ```bash
   git clone https://github.com/SirDank/KnightLauncherPlus.git
   cd KnightLauncherPlus
   ```
3. Copy `projectx-pcode.jar` from your Spiral Knights `code/` folder into `lib/`:
   ```powershell
   Copy-Item "<GameDir>\code\projectx-pcode.jar" "lib\projectx-pcode.jar"
   ```
4. Build package:
   ```bash
   mvn clean package
   # Or for production release:
   mvn -Denv=prod clean package
   ```
5. Run:
   ```bash
   java -jar target/KnightLauncher.jar
   ```

## Community & Support
- **Discord**: [Join our Dankware Discord](https://dankware.alwaysdata.net/discord)
- **Support the Project**: [Buy Me A Coffee](https://buymeacoffee.com/sirdankk)

-----

## Credits & Upstream
- Built upon the foundations of [KnightLauncher](https://github.com/lucasluqui/KnightLauncher) by Lucas Allegri.
- Server emulator concepts derived from reverse-engineered Three Rings architectures and community projects (`spiral-knights-server`, `thetya`).
- Spiral Knights is created and owned by Grey Havens / SEGA. KnightLauncherPlus is an independent open-source project.
