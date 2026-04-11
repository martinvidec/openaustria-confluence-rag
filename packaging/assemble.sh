#!/bin/sh
# Assemble the distribution archives from the already-built Fat JAR.
#
# Usage: ./packaging/assemble.sh [version]
#   version: optional, e.g. "v0.1.0" or "0.1.0". Defaults to the version
#            in pom.xml (with -SNAPSHOT stripped).
#
# Produces:
#   dist/confluence-rag-<version>-unix.tar.gz
#   dist/confluence-rag-<version>-windows.zip
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
DIST="$ROOT/dist"
STAGE="$DIST/stage"

# Determine version
if [ "$#" -ge 1 ] && [ -n "$1" ]; then
    VERSION="${1#v}"
else
    VERSION=$(grep -m1 '<version>' "$ROOT/pom.xml" \
        | sed 's|.*<version>\(.*\)</version>.*|\1|' \
        | sed 's|-SNAPSHOT$||')
fi
[ -n "$VERSION" ] || { echo "error: cannot determine version"; exit 1; }

# Locate the built fat JAR (match both SNAPSHOT and release names)
JAR=$(find "$ROOT/target" -maxdepth 1 -type f -name 'confluence-rag-*.jar' \
    | grep -v 'sources\|javadoc\|original' \
    | head -1)
[ -n "$JAR" ] && [ -f "$JAR" ] || {
    echo "error: no fat JAR in target/ — run 'mvn package -DskipTests' first"
    exit 1
}

echo "Assembling confluence-rag $VERSION from $JAR"

rm -rf "$DIST"
mkdir -p "$DIST" "$STAGE"

NAME="confluence-rag-$VERSION"

# ---------- Unix archive (macOS + Linux) ----------
OUT="$STAGE/$NAME"
mkdir -p "$OUT/bin" "$OUT/lib" "$OUT/config"

cp "$JAR" "$OUT/lib/confluence-rag.jar"
cp "$ROOT/packaging/bin/confluence-rag" "$OUT/bin/"
chmod +x "$OUT/bin/confluence-rag"
cp "$ROOT/packaging/templates/config.env.example" "$OUT/config/"
cp "$ROOT/packaging/templates/README.txt" "$OUT/"
echo "$VERSION" > "$OUT/VERSION"

(cd "$STAGE" && tar czf "$DIST/$NAME-unix.tar.gz" "$NAME")
rm -rf "$OUT"
echo "  -> dist/$NAME-unix.tar.gz"

# ---------- Windows archive ----------
OUT="$STAGE/$NAME"
mkdir -p "$OUT/bin" "$OUT/lib" "$OUT/config"

cp "$JAR" "$OUT/lib/confluence-rag.jar"
cp "$ROOT/packaging/bin/confluence-rag.cmd" "$OUT/bin/"
cp "$ROOT/packaging/bin/confluence-rag.ps1" "$OUT/bin/"
cp "$ROOT/packaging/templates/config.env.example" "$OUT/config/"
cp "$ROOT/packaging/templates/README.txt" "$OUT/"
echo "$VERSION" > "$OUT/VERSION"

if command -v zip >/dev/null 2>&1; then
    (cd "$STAGE" && zip -rq "$DIST/$NAME-windows.zip" "$NAME")
else
    # Fallback: use python if zip is not available (CI runners have python)
    (cd "$STAGE" && python3 -c "
import shutil
shutil.make_archive('$DIST/$NAME-windows', 'zip', '.', '$NAME')
")
fi
rm -rf "$OUT"
echo "  -> dist/$NAME-windows.zip"

rmdir "$STAGE" 2>/dev/null || true

echo
echo "Done. Archives in $DIST:"
ls -lh "$DIST"
