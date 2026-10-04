package dev.halloween.game;

import dev.halloween.HwConfig;

/** Per-player state of whoever plays the Shape. */
public final class ShapeState {
	public float bloodthirst;
	public int tier;
	public boolean senseHeld;
	public float fatigue;
	public int attackCooldown;
	public int grabCooldown;
	public int jumpCooldown;
	public int jumpCooldownMax = 1;
	public int shadowTicks;
	/** Frozen after a victim breaks free. */
	public int recoverTicks;
	public GrabSession grab;

	public boolean busy() {
		return this.grab != null || this.recoverTicks > 0;
	}

	public boolean senseActive() {
		return this.senseHeld && this.grab == null && this.shadowTicks == 0;
	}

	public void addBloodthirst(float amount) {
		this.bloodthirst = Math.min(100f, this.bloodthirst + amount);
	}

	/** Recomputes the tier from the meter; returns true when it went up. */
	public boolean updateTier() {
		float[] thresholds = HwConfig.get().bloodthirstTierThresholds;
		int newTier = 0;
		for (int i = 0; i < thresholds.length; i++) {
			if (this.bloodthirst >= thresholds[i]) {
				newTier = i;
			}
		}
		boolean up = newTier > this.tier;
		this.tier = newTier;
		return up;
	}
}
