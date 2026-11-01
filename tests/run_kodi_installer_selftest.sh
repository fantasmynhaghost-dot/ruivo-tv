#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out=$(mktemp -d); trap 'rm -rf "$out"' EXIT
javac -d "$out" app/src/main/java/br/sintonia/tv/addons/RuivoAddonProvider.java app/src/main/java/br/sintonia/tv/addons/RuivoContentItem.java app/src/main/java/br/sintonia/tv/addons/KodiAddonAdapter.java app/src/main/java/br/sintonia/tv/addons/KodiAddonInstaller.java tests/KodiAddonInstallerSelfTest.java
java -cp "$out" KodiAddonInstallerSelfTest app/src/main/assets/kodi-addons/plugin.video.ruivo.demo.zip
