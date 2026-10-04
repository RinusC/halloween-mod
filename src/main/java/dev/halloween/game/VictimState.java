package dev.halloween.game;

import dev.halloween.HwConfig;
import net.minecraft.world.entity.LivingEntity;

/** Stalk progress and current focus point of one victim (civilian player or test mannequin). */
public final class VictimState {
	public final LivingEntity entity;
	public float stalk;
	public int focus;
	public int focusTimer;
	public boolean grabbed;

	public VictimState(LivingEntity entity) {
		this.entity = entity;
	}

	public boolean weakened() {
		return this.stalk >= HwConfig.get().stalkWeakenedThreshold;
	}

	public boolean marked() {
		return this.stalk >= HwConfig.get().stalkMarkedThreshold;
	}
}
