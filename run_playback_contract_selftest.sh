#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out=$(mktemp -d); trap 'rm -rf "$out"' EXIT
javac -Xlint:all -d "$out" playback-contract/src/main/java/br/sintonia/tv/playback/*.java playback-contract/src/test/java/br/sintonia/tv/playback/*.java
java -cp "$out" br.sintonia.tv.playback.PlaybackContractSelfTest
