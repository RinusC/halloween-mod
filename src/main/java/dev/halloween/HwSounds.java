package dev.halloween;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;

/**
 * Sound events. assets/halloween/sounds.json maps them to vanilla placeholder files;
 * a resource pack can override that file with the real recordings.
 */
public final class HwSounds {
	public static final SoundEvent SENSE_BREATH = register("shape.sense_breath");
	public static final SoundEvent SHADOW_ENTER = register("shape.shadow_enter");
	public static final SoundEvent STINGER = register("shape.stinger");
	public static final SoundEvent TIER_UP = register("shape.tier_up");
	public static final SoundEvent STAB = register("shape.stab");
	public static final SoundEvent GRAB = register("shape.grab");
	public static final SoundEvent WINDOW_SMASH = register("shape.window_smash");
	public static final SoundEvent THEME = register("theme.proximity");
	public static final SoundEvent QTE_HIT = register("qte.hit");
	public static final SoundEvent QTE_MISS = register("qte.miss");
	public static final SoundEvent ESCAPE = register("qte.escape");
	public static final SoundEvent BREAKER = register("world.breaker");
	public static final SoundEvent HEAVY_STEP = register("shape.heavy_step");
	public static final SoundEvent PUSH = register("shape.push");
	public static final SoundEvent IMPULSE = register("shape.impulse");
	public static final SoundEvent SWING = register("shape.swing");

	private HwSounds() {
	}

	private static SoundEvent register(String path) {
		var id = Halloween.id(path);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
	}

	public static void init() {
	}
}
