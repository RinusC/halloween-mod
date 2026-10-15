package dev.halloween.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * The Upside Down as the Demogorgon lives it (only he is ever down there): a thick red murk (ClientWeather), red
 * lightning flickering through it every few seconds with thunder rolling far off, a low drone and something
 * breathing, whispers of the town above, now and then a heartbeat. The screen side is in HwHud.upsideDown.
 */
public final class ClientUpsideDown {
	private static long nextFlash;
	private static long flashAt = -1000;
	private static long nextDrone;
	private static long nextWhisper;

	private ClientUpsideDown() {
	}

	/** Is this player the Demogorgon down below now? */
	public static boolean active() {
		return MichaelRenderer.demogorgon() && ClientState.demo.below();
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null || !active()) {
			return;
		}
		long now = mc.level.getGameTime();
		RandomSource r = mc.level.getRandom();
		if (now >= nextFlash) {
			// Red lightning: a double flicker, the thunder a moment later and far away.
			flashAt = now;
			nextFlash = now + 120 + r.nextInt(180);
			play(mc, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.5f, 0.45f + r.nextFloat() * 0.15f);
		}
		if (now >= nextDrone) {
			nextDrone = now + 80 + r.nextInt(60);
			play(mc, SoundEvents.AMBIENT_BASALT_DELTAS_MOOD.value(), 0.9f, 0.5f);
			if (r.nextInt(3) == 0) {
				play(mc, SoundEvents.WARDEN_HEARTBEAT, 0.7f, 0.6f);
			}
		}
		if (now >= nextWhisper) {
			nextWhisper = now + 200 + r.nextInt(300);
			play(mc, r.nextBoolean() ? SoundEvents.AMBIENT_SOUL_SAND_VALLEY_ADDITIONS.value() : SoundEvents.SCULK_SHRIEKER_SHRIEK, 0.35f, 0.4f + r.nextFloat() * 0.2f);
		}
	}

	private static void play(Minecraft mc, SoundEvent sound, float volume, float pitch) {
		mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
	}

	/** How bright the lightning is now (0..1): two quick flickers. */
	public static float flash(float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return 0f;
		}
		float e = mc.level.getGameTime() + partial - flashAt;
		if (e < 0 || e > 10) {
			return 0f;
		}
		float a = e < 2 ? 1f - e / 2f : 0f;
		float b = e >= 4 && e < 10 ? Mth.sin((e - 4) / 6f * Mth.PI) * 0.7f : 0f;
		return Math.max(a, b);
	}
}
