#!/usr/bin/env bash
set -e

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

SKIP_BUILD=false
DEV_BUILD=false
RUN_APP=false
DRY_RUN=false
SK_DIR=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build|-SkipBuild)
      SKIP_BUILD=true
      shift
      ;;
    --dev|-Dev)
      DEV_BUILD=true
      shift
      ;;
    --run|-Run)
      RUN_APP=true
      shift
      ;;
    --dry-run|-DryRun)
      DRY_RUN=true
      shift
      ;;
    --sk-dir|-SkDir)
      if [[ $# -lt 2 || -z "$2" ]]; then
        echo "Error: --sk-dir needs a path." >&2
        exit 2
      fi
      SK_DIR="$2"
      shift 2
      ;;
    *)
      echo "Error: unknown option '$1'. Options: --skip-build --dev --run --dry-run --sk-dir <path>" >&2
      exit 2
      ;;
  esac
done

find_sk_dir() {
  if [[ -n "$SK_DIR" ]]; then
    if [[ -f "$SK_DIR/code/projectx-pcode.jar" ]]; then
      echo "$SK_DIR"
      return 0
    fi
    echo "Error: --sk-dir '$SK_DIR' does not contain code/projectx-pcode.jar" >&2
    return 1
  fi

  for v in "$SK_GAME_DIR" "$SPIRAL_KNIGHTS_DIR"; do
    if [[ -n "$v" && -f "$v/code/projectx-pcode.jar" ]]; then
      echo "$v"
      return 0
    fi
  done

  # Linux & macOS candidates
  local candidates=(
    "$HOME/.local/share/Steam/steamapps/common/Spiral Knights"
    "$HOME/.steam/steam/steamapps/common/Spiral Knights"
    "$HOME/.steam/root/steamapps/common/Spiral Knights"
    "$HOME/Library/Application Support/Steam/steamapps/common/Spiral Knights"
    "/Applications/Spiral Knights.app/Contents/Resources/Java"
  )

  for c in "${candidates[@]}"; do
    if [[ -d "$c" && -f "$c/code/projectx-pcode.jar" ]]; then
      echo "$c"
      return 0
    fi
  done

  echo ""
}

TARGET_SK_DIR="$(find_sk_dir)" || exit 1
if [[ -z "$TARGET_SK_DIR" ]]; then
  echo "Error: Could not automatically detect Spiral Knights installation directory." >&2
  echo "Please set SK_GAME_DIR environment variable or pass --sk-dir <path>." >&2
  exit 1
fi

echo "[*] Detected Spiral Knights Directory: $TARGET_SK_DIR"

if [[ "$DRY_RUN" == "true" ]]; then
  echo "[*] Dry run completed successfully."
  exit 0
fi

ENV_MODE="prod"
if [[ "$DEV_BUILD" == "true" ]]; then
  ENV_MODE="dev"
fi

# The build bakes deploy.properties into the jar; a fresh clone only has the template.
if [[ ! -f deploy.properties ]]; then
  cp deploy.properties.template deploy.properties
fi

# Compiling needs the game's own jar, which can't be committed: take it from the game install
# (and refresh it when the game updated).
if [[ "$SKIP_BUILD" != "true" ]] && ! cmp -s "$TARGET_SK_DIR/code/projectx-pcode.jar" lib/projectx-pcode.jar; then
  echo "[*] Copying projectx-pcode.jar from the game -> lib/"
  cp -f "$TARGET_SK_DIR/code/projectx-pcode.jar" lib/projectx-pcode.jar
fi

if [[ "$SKIP_BUILD" != "true" ]]; then
  echo "[*] Building KnightLauncherPlus (env=$ENV_MODE) via Maven Wrapper..."
  sh ./mvnw -Denv="$ENV_MODE" -DskipTests clean package
fi

TARGET_JAR="$REPO_ROOT/target/KnightLauncher.jar"
if [[ ! -f "$TARGET_JAR" ]]; then
  echo "Error: Expected artifact '$TARGET_JAR' was not found." >&2
  exit 1
fi

DEST_JAR="$TARGET_SK_DIR/KnightLauncher.jar"
if [[ -f "$DEST_JAR" ]]; then
  echo "[*] Backing up the installed launcher -> $DEST_JAR.bak (close the launcher before deploying)"
  cp -f "$DEST_JAR" "$DEST_JAR.bak"
fi
echo "[*] Deploying KnightLauncher.jar -> $DEST_JAR..."
cp -f "$TARGET_JAR" "$DEST_JAR"

if [[ -f "$REPO_ROOT/scripts/KnightLauncher_mac_linux.sh" ]]; then
  cp -f "$REPO_ROOT/scripts/KnightLauncher_mac_linux.sh" "$TARGET_SK_DIR/KnightLauncher_mac_linux.sh"
  chmod +x "$TARGET_SK_DIR/KnightLauncher_mac_linux.sh" 2>/dev/null || true
fi

echo "[+] Deployment complete! Installed to: $DEST_JAR"

if [[ "$RUN_APP" == "true" ]]; then
  echo "[*] Launching KnightLauncher..."
  (cd "$TARGET_SK_DIR" && java -jar KnightLauncher.jar &)
fi
