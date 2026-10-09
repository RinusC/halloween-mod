package dev.halloween.game;

/** Per-player state of whoever plays the Shape. */
public final class ShapeState {
	public static final float BLOODTHIRST_MAX = 300f;

	/** 0..300: three levels of 100 points. */
	public float bloodthirst;
	public int score;

	public boolean senseHeld;
	public boolean focusHeld;
	public float fatigue;

	public int attackCooldown;
	public int comboCount;
	public int comboTimer;
	public int grabCooldown;
	public int pushCooldown;
	public int dashTicks;
	public int dashCooldown;
	public int dashCooldownMax = 1;
	public int jumpCooldown;
	public int jumpCooldownMax = 1;
	public int impulseCooldown;
	public int impulseCooldownMax = 1;
	/** 0 = not in Shape Jump, otherwise how many ticks it has lasted (it has no time limit). */
	public int shadowTicks;
	/** Frozen after a victim breaks free. */
	public int recoverTicks;
	public GrabSession grab;

	/** Stun meter 0..1 filled by survivors' weapons; stunnedTicks counts down while he is knocked down. */
	public float stun;
	public int stunnedTicks;
	/** Slowed after a survivor shoved him. */
	public int pushedTicks;
	/** Caught in a bear trap: can't take a step until this runs out. */
	public int trappedTicks;
	/** In a flashlight beam (counts as light), and blinded by it. */
	public int litTicks;
	public int blindTicks;

	public double lastX;
	public double lastZ;
	public boolean still;
	public double fallDistance;

	/** Camera ray reported by the client (over-the-shoulder aim) and the server tick it arrived. */
	public net.minecraft.world.phys.Vec3 aimOrigin;
	public net.minecraft.world.phys.Vec3 aimDirection;
	public int aimTick = -1000;

	public boolean busy() {
		return this.grab != null || this.recoverTicks > 0 || this.stunnedTicks > 0;
	}

	public boolean senseActive() {
		return this.senseHeld && this.grab == null && this.shadowTicks == 0 && this.blindTicks == 0 && this.stunnedTicks == 0;
	}

	public boolean focusActive() {
		return this.senseActive() && this.focusHeld;
	}

	/** Bloodthirst tier index 0..2 (levels 1..3). */
	public int tier() {
		return Math.min(2, (int) (this.bloodthirst / 100f));
	}

	/** Bloodthirst level 1..3 as shown on the ring. */
	public int level() {
		return this.tier() + 1;
	}

	/** Changes the meter; returns true when the level went up. */
	public boolean addBloodthirst(float amount) {
		int before = this.tier();
		this.bloodthirst = Math.max(0f, Math.min(BLOODTHIRST_MAX, this.bloodthirst + amount));
		return this.tier() > before;
	}
}
