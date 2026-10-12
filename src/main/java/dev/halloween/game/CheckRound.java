package dev.halloween.game;

import dev.halloween.net.HwNet;
import net.minecraft.util.RandomSource;

/**
 * One reaction check as the server keeps it: its kind (see {@link HwNet.Check}), its zones, and how far the player has
 * got (the first of two arcs, the taps so far).
 */
final class CheckRound {
	static final int PENDING = 0;
	static final int HIT = 1;
	static final int MISS = 2;

	int kind = HwNet.Check.RING;
	float zoneStart;
	float zoneWidth;
	float zone2;
	int need;
	int stage;

	/**
	 * A fresh check of this kind. {@code width}: the zone (the arcs a little narrower, the closing circle's band as
	 * it is); {@code taps}: how many taps a MASH wants.
	 */
	void roll(int kind, float width, int taps, RandomSource random) {
		this.kind = kind;
		this.stage = 0;
		this.need = taps;
		this.zone2 = 0;
		switch (kind) {
			case HwNet.Check.DOUBLE -> {
				this.zoneWidth = width * 0.8f;
				this.zoneStart = 0.1f + random.nextFloat() * 0.3f;
				float gap = 0.15f + random.nextFloat() * 0.15f;
				this.zone2 = Math.min(0.97f - this.zoneWidth, this.zoneStart + this.zoneWidth + gap);
			}
			case HwNet.Check.PULSE -> {
				this.zoneWidth = width;
				this.zoneStart = 0.2f + random.nextFloat() * (0.45f - width / 2);
			}
			case HwNet.Check.MASH -> {
				this.zoneWidth = 0;
				this.zoneStart = 0;
			}
			default -> {
				this.zoneWidth = width;
				this.zoneStart = 0.1f + random.nextFloat() * (0.8f - width);
			}
		}
	}

	/** A press {@code elapsed} ticks into the check, the mark moving at {@code period}: hit, miss, or not decided yet. */
	int press(double elapsed, int period) {
		float mark = HwNet.Check.mark(this.kind, elapsed, period);
		return switch (this.kind) {
			case HwNet.Check.MASH -> ++this.stage >= this.need ? HIT : PENDING;
			case HwNet.Check.DOUBLE -> {
				if (this.stage == 0) {
					if (HwNet.Check.in(mark, this.zoneStart, this.zoneWidth)) {
						this.stage = 1;
						yield PENDING;
					}
					yield MISS;
				}
				yield HwNet.Check.in(mark, this.zone2, this.zoneWidth) ? HIT : MISS;
			}
			default -> HwNet.Check.in(mark, this.zoneStart, this.zoneWidth) ? HIT : MISS;
		};
	}

	/** Ticks after which it is lost by time alone (the circle shut, two turns gone by), or -1: the caller's own limit. */
	int lostAfter(int period) {
		return switch (this.kind) {
			case HwNet.Check.PULSE -> period + 4;
			case HwNet.Check.DOUBLE -> period * 2 + 4;
			default -> -1;
		};
	}
}
