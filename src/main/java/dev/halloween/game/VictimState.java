package dev.halloween.game;

import dev.halloween.HwConfig;
import net.minecraft.world.entity.LivingEntity;

/** Stalk progress, mark and stagger of one victim (civilian player or test mannequin). */
public final class VictimState {
	public final LivingEntity entity;
	/** 0..3; the integer part is the stalk level. */
	public float stalk;
	public int markTicks;
	public int staggerTicks;
	public boolean grabbed;
	/** Passive stalking already paid out its one-time score. */
	public boolean passiveScored;
	/** The focus points (bit per point) burst after 3 s of stalking on them, and ticks stalked on each so far. */
	public int popped;
	public final int[] focusTicks = new int[FocusPoints.COUNT];

	public VictimState(LivingEntity entity) {
		this.entity = entity;
	}

	public int level() {
		return Math.min(3, (int) this.stalk);
	}

	public boolean marked() {
		return this.markTicks > 0;
	}

	public float damageTakenMultiplier() {
		return HwConfig.at(HwConfig.get().damageTakenByStalk, this.level());
	}
}
