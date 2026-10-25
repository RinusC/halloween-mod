#!/bin/sh
# Vecna's music from the owner's three WAV files (on the Desktop) into seamless loops, see tools/VecnaMusic.java.
# Run from the project root. The chase file is cut at its marks: Terror Radius, Approaching, Proximity & Close,
# Chase, Exposed Chase, Chase, Post Chase Terror Radius.
set -e
JAVA="$PWD/.jdk/jdk-25.0.4.1+1/bin/java"
SRC="$HOME/Desktop"
OUT=src/main/resources/assets/halloween/sounds/vecna
"$JAVA" tools/VecnaMusic.java "$SRC/выживший_векна_.wav" "$OUT/music_survivor"
"$JAVA" tools/VecnaMusic.java "$SRC/Векна.wav" "$OUT/music_calm"
"$JAVA" tools/VecnaMusic.java "$SRC/Погоня_векна_.wav" "$OUT/music_chase" 0 40 40 72 72 103 103 132 132 151 151 165 165 9999
