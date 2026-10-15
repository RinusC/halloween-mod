package dev.halloween.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
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
		}
		// The nearest house with someone in it beats: louder the nearer, louder still looked at.
		long t = now % BEAT;
		if (t == 0 || t == DUB) {
			Vec3 eye = mc.player.getEyePosition();
			Vec3 look = mc.player.getViewVector(1f);
			float best = 0f;
			for (Vec3 h : hearts()) {
				Vec3 to = h.subtract(eye);
				double d = to.length();
				float v = (float) Mth.clamp(1.0 - d / 70.0, 0.08, 1.0);
				if (d > 0.1 && to.normalize().dot(look) > 0.93) {
					v = Math.min(1f, v * 1.6f + 0.15f);
				}
				best = Math.max(best, v);
			}
			if (best > 0f) {
				play(mc, SoundEvents.WARDEN_HEARTBEAT, best * (t == 0 ? 1f : 0.7f), t == 0 ? 0.75f : 0.65f);
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

	/** A heartbeat every 22 ticks: the first beat, the second five ticks after. */
	private static final int BEAT = 22;
	private static final int DUB = 5;

	/** Where the hearts hang: over each house with someone in it (as it stands down here). */
	public static List<Vec3> hearts() {
		List<BlockPos> c = ClientState.demoHouses;
		List<Vec3> out = new ArrayList<>();
		for (int i = 0; i + 1 < c.size(); i += 2) {
			BlockPos lo = c.get(i);
			BlockPos hi = c.get(i + 1);
			out.add(new Vec3((lo.getX() + hi.getX()) / 2.0, hi.getY() + 1.5, (lo.getZ() + hi.getZ()) / 2.0));
		}
		return out;
	}

	/** Is this column inside a house with someone in it (for the minimap)? */
	public static boolean occupied(int x, int z) {
		List<BlockPos> c = ClientState.demoHouses;
		for (int i = 0; i + 1 < c.size(); i += 2) {
			BlockPos lo = c.get(i);
			BlockPos hi = c.get(i + 1);
			if (x > lo.getX() && x < hi.getX() && z > lo.getZ() && z < hi.getZ()) {
				return true;
			}
		}
		return false;
	}

	/** The swell of the beat now (0..1): two pulses, a strong one and a weaker one. */
	public static float beat(float partial) {
		float t = beatTime(partial);
		float a = t < 4 ? 1f - t / 4f : 0f;
		float b = t >= DUB && t < DUB + 4 ? (1f - (t - DUB) / 4f) * 0.7f : 0f;
		return Math.max(a, b);
	}

	/** How far through the beat (0..1), for the ring going out. */
	public static float beatPhase(float partial) {
		return Mth.clamp(beatTime(partial) / 16f, 0f, 1f);
	}

	private static float beatTime(float partial) {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? 99f : (mc.level.getGameTime() % BEAT) + partial;
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
