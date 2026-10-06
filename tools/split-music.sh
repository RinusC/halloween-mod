#!/bin/sh
# Cuts the four soundtrack files (Ogg Vorbis, in ~/Downloads) into the match music parts, without re-encoding.
# Run from the project root. Needs JOrbis from the Gradle cache (Minecraft depends on it).
set -e
JAVA="$PWD/.jdk/jdk-25.0.4.1+1/bin/java"
CP=$(find ~/.gradle/caches -name "jorbis-0.0.17.jar" | head -1)
SRC="$HOME/Downloads"
OUT=src/main/resources/assets/halloween/sounds/music
mkdir -p "$OUT"
"$JAVA" -cp "$CP" tools/OggSplit.java "$SRC/Ambience-Soundtrack-Halloween_-The-Game.ogg" "$OUT/ambience" 48000 195 394 587
"$JAVA" -cp "$CP" tools/OggSplit.java "$SRC/Stalking-Soundtrack-Halloween_-The-Game.ogg" "$OUT/stalking" 48000 555 664
"$JAVA" -cp "$CP" tools/OggSplit.java "$SRC/Being-Stalked-Soundtrack-Halloween_-The-Game.ogg" "$OUT/being_stalked" 48000 115 231
"$JAVA" -cp "$CP" tools/OggSplit.java "$SRC/Michael-Myers-Proximity-Soundtrack-Halloween_-The-Game.ogg" "$OUT/proximity" 48000 65 131 197
