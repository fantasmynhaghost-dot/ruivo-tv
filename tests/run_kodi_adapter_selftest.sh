#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
ADDONS="$ROOT/app/src/main/java/br/sintonia/tv/addons"
javac -source 17 -target 17 -d "$OUT" \
  "$ADDONS/RuivoContentItem.java" \
  "$ADDONS/RuivoAddonProvider.java" \
  "$ADDONS/KodiAddonAdapter.java" \
  "$ROOT/tests/KodiAddonAdapterSelfTest.java"
java -cp "$OUT" KodiAddonAdapterSelfTest
