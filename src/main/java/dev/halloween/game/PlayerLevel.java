package dev.halloween.game;

/**
 * The player's level (like a battle pass): it grows with every point ever earned, from matches and the lobby farm;
 * spending points on perks and heroes never takes it back. Level 2 costs 6000, each one after it 2000 more than the
 * one before (8000, 10000, ...), up to {@link #MAX}. Each level opens two things to wear (see Cosmetics).
 */
public final class PlayerLevel {
	public static final int MAX = 10;
	private static final int FIRST = 6000;
	private static final int STEP = 2000;

	private PlayerLevel() {
	}

	/** What it takes to go from level-1 to this level (level 2 and up). */
	public static int cost(int level) {
		return FIRST + STEP * (level - 2);
	}

	/** Points earned in all to reach this level (0 for level 1). */
	public static int total(int level) {
		int sum = 0;
		for (int l = 2; l <= Math.min(level, MAX); l++) {
			sum += cost(l);
		}
		return sum;
	}

	public static int of(int earned) {
		int level = 1;
		while (level < MAX && earned >= total(level + 1)) {
			level++;
		}
		return level;
	}

	/** How far into the current level (0..1); 1 at the top level. */
	public static float progress(int earned) {
		int level = of(earned);
		if (level >= MAX) {
			return 1f;
		}
		return (earned - total(level)) / (float) cost(level + 1);
	}
}
