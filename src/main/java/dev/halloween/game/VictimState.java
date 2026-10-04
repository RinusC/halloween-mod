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
