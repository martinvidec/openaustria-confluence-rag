#!/bin/sh
# Confluence RAG — installer for macOS / Linux
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/martinvidec/openaustria-confluence-rag/main/install.sh | sh
#
# Environment overrides:
#   INSTALL_PREFIX   Override install dir (default: $HOME/.confluence-rag)
#   BIN_PREFIX       Where to symlink the CLI (default: $HOME/.local/bin)
#   VERSION          Install a specific release (e.g. v0.1.0), default: latest
#
# This script only installs the app itself. It does NOT install Java, Ollama
# or Qdrant — run `confluence-rag doctor` after install to verify prerequisites.
set -eu

REPO="martinvidec/openaustria-confluence-rag"
INSTALL_DIR="${INSTALL_PREFIX:-$HOME/.confluence-rag}"
BIN_DIR="${BIN_PREFIX:-$HOME/.local/bin}"
VERSION="${VERSION:-latest}"

# ------------------------------- helpers -----------------------------------

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
info() { printf '>> %s\n' "$*"; }

detect_os() {
    uname_s=$(uname -s)
    case "$uname_s" in
        Darwin) echo darwin ;;
        Linux)  echo linux ;;
        *) die "unsupported OS: $uname_s" ;;
    esac
}

detect_arch() {
    uname_m=$(uname -m)
    case "$uname_m" in
        x86_64|amd64) echo x86_64 ;;
        arm64|aarch64) echo arm64 ;;
        *) die "unsupported architecture: $uname_m" ;;
    esac
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

# ------------------------------- main --------------------------------------

require_cmd curl
require_cmd tar
require_cmd uname

OS=$(detect_os)
ARCH=$(detect_arch)
info "OS: $OS ($ARCH)"

# Resolve version → download URL
api_url="https://api.github.com/repos/$REPO/releases/$VERSION"
case "$VERSION" in latest) api_url="https://api.github.com/repos/$REPO/releases/latest" ;; esac

info "Fetching release info from $api_url"
release_json=$(curl -fsSL "$api_url" 2>/dev/null) || die "cannot reach GitHub API (version '$VERSION' may not exist yet)"

tag=$(echo "$release_json" | grep -m1 '"tag_name"' | sed 's/.*"tag_name": *"\([^"]*\)".*/\1/')
[ -n "$tag" ] || die "cannot parse tag from release"

# The Fat JAR is platform-independent, so the same -unix archive works for all Unix.
asset_url=$(echo "$release_json" \
    | grep -o '"browser_download_url": *"[^"]*-unix.tar.gz"' \
    | head -1 \
    | sed 's/.*": *"\([^"]*\)".*/\1/')
[ -n "$asset_url" ] || die "no -unix.tar.gz asset found in release $tag"

info "Installing confluence-rag $tag"

# Backup existing install directory
if [ -d "$INSTALL_DIR" ]; then
    BACKUP="$INSTALL_DIR.bak.$$"
    info "Existing install at $INSTALL_DIR — moving to $BACKUP"
    mv "$INSTALL_DIR" "$BACKUP"
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

info "Downloading $asset_url"
curl -fsSL "$asset_url" -o "$TMP/confluence-rag.tar.gz" || die "download failed"

info "Extracting to $INSTALL_DIR"
mkdir -p "$INSTALL_DIR"
tar -xzf "$TMP/confluence-rag.tar.gz" -C "$TMP"

inner=$(find "$TMP" -maxdepth 1 -type d -name 'confluence-rag-*' | head -1)
[ -d "$inner" ] || die "unexpected archive layout"

# Move archive contents into INSTALL_DIR
cp -r "$inner/." "$INSTALL_DIR/"

# Ensure subdirs that aren't in the archive
mkdir -p "$INSTALL_DIR/data" "$INSTALL_DIR/logs"

# Make the wrapper executable
chmod +x "$INSTALL_DIR/bin/confluence-rag"

# Symlink into BIN_DIR
mkdir -p "$BIN_DIR"
ln -sf "$INSTALL_DIR/bin/confluence-rag" "$BIN_DIR/confluence-rag"

# Migrate config from backup if present
if [ -n "${BACKUP:-}" ] && [ -f "$BACKUP/config/config.env" ]; then
    info "Preserving previous config from $BACKUP"
    mkdir -p "$INSTALL_DIR/config"
    cp "$BACKUP/config/config.env" "$INSTALL_DIR/config/config.env"
    chmod 600 "$INSTALL_DIR/config/config.env"
    if [ -f "$BACKUP/data/sync-state.json" ]; then
        cp "$BACKUP/data/sync-state.json" "$INSTALL_DIR/data/sync-state.json"
    fi
fi

# Figure out PATH hint
path_hint=""
case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *) path_hint="$BIN_DIR" ;;
esac

cat <<EOF

$(printf '\033[32m✓\033[0m') Confluence RAG $tag installed to $INSTALL_DIR
EOF
if [ -n "$path_hint" ]; then
    cat <<EOF

$(printf '\033[33m!\033[0m') $path_hint is not in your PATH.
  Add this to your ~/.zshrc / ~/.bashrc:
    export PATH="\$PATH:$path_hint"
  Then open a new terminal.

EOF
fi
cat <<EOF
Next steps:
  confluence-rag init        # configure Confluence URL, auth, spaces, pull models
  confluence-rag doctor      # verify Java, Ollama, Qdrant
  confluence-rag start       # run on http://localhost:8080
  confluence-rag ingest      # trigger initial ingest

Prerequisites (install separately if missing):
  Java 17+   https://adoptium.net/
  Ollama     https://ollama.com/download
  Qdrant     https://qdrant.tech/documentation/quick-start/

More:
  confluence-rag help
  https://github.com/$REPO
EOF
