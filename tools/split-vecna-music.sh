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
# The grandfather clock (every 15 s a chime): a quiet loop over the music while the rift is open (four whole periods,
# so it loops as it is), and one chime for each kill before the rift opens.
CLOCK="$SRC/Stranger Things Grandfather Clock Chimes EVERY 15 Seconds_[cut_341sec].wav"
"$JAVA" -Dxfade=0 tools/VecnaMusic.java "$CLOCK" "$OUT/music_clock_loop" 15 75
"$JAVA" -Dxfade=0 -Dfadeout=1.2 tools/VecnaMusic.java "$CLOCK" "$OUT/music_clock_chime" 14.95 19.5
