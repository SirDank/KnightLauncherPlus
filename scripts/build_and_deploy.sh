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
      SK_DIR="$2"
      shift 2
      ;;
    *)
      shift
      ;;
  esac
done

find_sk_dir() {
  if [[ -n "$SK_DIR" && -f "$SK_DIR/code/projectx-pcode.jar" ]]; then
    echo "$SK_DIR"
    return 0
  fi

  for v in "$SK_GAME_DIR" "$SK_DIR" "$SPIRAL_KNIGHTS_DIR"; do
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

TARGET_SK_DIR="$(find_sk_dir)"
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

if [[ "$SKIP_BUILD" != "true" ]]; then
  echo "[*] Building KnightLauncherPlus (env=$ENV_MODE) via Maven Wrapper..."
  ./mvnw -Denv="$ENV_MODE" -DskipTests clean package
fi

TARGET_JAR="$REPO_ROOT/target/KnightLauncher.jar"
if [[ ! -f "$TARGET_JAR" ]]; then
  echo "Error: Expected artifact '$TARGET_JAR' was not found." >&2
  exit 1
fi

DEST_JAR="$TARGET_SK_DIR/KnightLauncher.jar"
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
