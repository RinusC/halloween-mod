package dev.halloween.client;

import dev.halloween.HwParticles;
import dev.halloween.HwSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Vecna's opening scene (game.Cutscenes.VECNA): he wakes in the Upside Down on the ground of his lair. Black, a clock
 * tolling; the red sky above and ash falling; whispers about the town and a few red flashes of what he sees; he sits up,
 * looks at his hands, his head jerks up, his eyes open (a flash, thunder), the name, and the camera is his own again.
 */
final class VecnaIntro {
	private static final float[] FLASHES = {0.30f, 0.345f, 0.385f, 0.47f};
	private static final float FLASH_LEN = 0.014f;
	private static final float EYES = 0.76f;
	private static float last;

	private VecnaIntro() {
	}

	static void begin() {
		last = 0f;
	}

	/** One sound at the camera, when the scene passes {@code at}. */
	private static void cue(float f, float at, SoundEvent sound, float volume, float pitch) {
		Minecraft mc = Minecraft.getInstance();
		if (last < at && f >= at && mc.player != null) {
			Vec3 e = mc.player.getEyePosition();
			mc.level.playLocalSound(e.x, e.y, e.z, sound, SoundSource.HOSTILE, volume, pitch, false);
		}
	}

	/** Every client tick: the sounds, and ash and spores drifting down through the red air. */
	static void tick(Minecraft mc, float f) {
		if (mc.level == null || mc.player == null) {
			return;
		}
		cue(f, 0.02f, HwSounds.VECNA_CLOCK_CHIME, 1.1f, 0.7f);
		cue(f, 0.05f, HwSounds.VECNA_RIFT_DRONE, 0.9f, 0.8f);
		cue(f, 0.12f, HwSounds.VECNA_WHISPER, 0.9f, 0.8f);
		for (int i = 0; i < 7; i++) {
			float at = 0.2f + i * 0.045f;
			cue(f, at, i % 2 == 0 ? HwSounds.VECNA_CLOCK_TICK : HwSounds.VECNA_CLOCK_TOCK, 0.6f + i * 0.07f, 0.9f);
		}
		for (float flash : FLASHES) {
			cue(f, flash, HwSounds.VECNA_PHANTOM_VANISH, 0.9f, 0.7f);
		}
		cue(f, 0.40f, HwSounds.VECNA_VINE_SLITHER, 0.8f, 0.8f);
		cue(f, 0.52f, HwSounds.VECNA_BONE_CRACK, 0.9f, 0.9f);
		cue(f, 0.58f, HwSounds.VECNA_WHISPER, 1.0f, 0.7f);
		cue(f, EYES - 0.02f, HwSounds.VECNA_BONE_CRACK, 1.0f, 1.2f);
		cue(f, EYES, HwSounds.VECNA_EYES_OPEN, 1.3f, 0.9f);
		cue(f, EYES, HwSounds.VECNA_THUNDER, 1.0f, 0.8f);
		cue(f, 0.9f, HwSounds.VECNA_CLOCK_CHIME, 1.0f, 0.6f);
		last = f;
		if (f > 0.05f && f < 0.9f) {
			Vec3 e = mc.player.getEyePosition();
			var r = mc.level.getRandom();
			for (int i = 0; i < 5; i++) {
				double x = e.x + (r.nextDouble() - 0.5) * 12;
				double z = e.z + (r.nextDouble() - 0.5) * 12;
				mc.level.addParticle(i % 3 == 0 ? HwParticles.VECNA_SPORE : HwParticles.VECNA_ASH, x, e.y + 1 + r.nextDouble() * 5, z, 0, -0.02, 0);
			}
		}
	}

	/** Lying on his back looking at the sky, sitting up, at his hands, his head jerking up, then his own eyes. */
	static double[] camera(float f, float partial) {
		Minecraft mc = Minecraft.getInstance();
		Vec3 eye = mc.player.getEyePosition(partial);
		float yaw0 = mc.player.getViewYRot(partial);
		float pitch0 = mc.player.getViewXRot(partial);
		Vec3 back = Vec3.directionFromRotation(0, yaw0).multiply(-1, 0, -1);
		float time = (mc.level.getGameTime() + partial);
		// How high the head is above the ground: lying (the ground is where his feet are), then up.
		double ground = eye.y - mc.player.getEyeHeight();
		double lying = ground + 0.3 + Math.sin(time * 0.12) * 0.012;
		float rise = smooth((f - 0.42f) / 0.22f);
		double y = Mth.lerp(rise, lying, eye.y);
		double back0 = (1f - rise) * 0.55;
		float pitch;
		if (f < 0.42f) {
			pitch = Mth.lerp(f / 0.42f, -86f, -64f) + (float) Math.sin(time * 0.12) * 1.8f;
		} else if (f < 0.55f) {
			pitch = Mth.lerp(smooth((f - 0.42f) / 0.13f), -64f, 8f);
		} else if (f < 0.68f) {
			pitch = Mth.lerp(smooth((f - 0.55f) / 0.13f), 8f, 58f);
		} else if (f < EYES - 0.02f) {
			pitch = Mth.lerp(smooth((f - 0.68f) / 0.06f), 58f, -18f);
		} else {
			pitch = Mth.lerp(smooth((f - 0.74f) / 0.16f), -18f, pitch0);
		}
		// Hands trembling, the head twitching once as the eyes open.
		float yaw = yaw0 + (float) Math.sin(time * 0.05) * (f < 0.6f ? 9f : 3f) * (1f - smooth((f - 0.74f) / 0.16f));
		float shake = 0.002f + 0.012f * flash(f);
		if (f > EYES - 0.02f && f < EYES + 0.03f) {
			yaw += 7f * Mth.sin((f - EYES + 0.02f) / 0.05f * Mth.PI);
			shake += 0.01f;
		}
		double sx = Math.sin(time * 2.3) * shake;
		double sy = Math.sin(time * 3.1 + 1) * shake;
		float k = smooth((f - 0.82f) / 0.18f);
		Vec3 pos = new Vec3(eye.x + back.x * back0 + sx, y + sy, eye.z + back.z * back0);
		pos = pos.lerp(eye, k);
		return new double[]{pos.x, pos.y, pos.z, yaw, pitch};
	}

	/** 1 inside a red flash, else 0. */
	private static float flash(float f) {
		for (float at : FLASHES) {
			if (f >= at && f < at + FLASH_LEN) {
				return 1f;
			}
		}
		return 0f;
	}

	/** Black at first and in the flashes; for the headset, still black between. */
	private static float black(float f) {
		float fade = 1f - smooth(f / 0.1f);
		return Math.max(fade, flash(f) * 0.85f);
	}

	static VrShots.Shot vrShot(float f) {
		return VrShots.Shot.own(Math.max(black(f), 0f));
	}

	static void overlay(GuiGraphicsExtractor g, Minecraft mc, float f) {
		int w = g.guiWidth();
		int h = g.guiHeight();
		boolean vr = ClientVr.active();
		float bars = f < 0.88f ? 1f : 1f - (f - 0.88f) / 0.12f;
		int bar = vr ? 0 : Math.round(h * 0.11f * bars);
		float time = mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		if (!vr) {
			// A red edge to the picture, beating faster the nearer his eyes open.
			float beat = 0.5f + 0.5f * Mth.sin(time * (0.35f + 0.5f * f));
			redEdges(g, w, h, bar, (0.35f + 0.35f * f + 0.2f * beat) * (1f - smooth((f - 0.8f) / 0.2f)));
			g.fill(0, 0, w, bar, 0xFF000000);
			g.fill(0, h - bar, w, h, 0xFF000000);
		}
		// Whispers, here and there on the screen, fading in and out.
		String[] words = {"w1", "w2", "w3", "w4", "w5"};
		float[][] spots = {{0.28f, 0.34f}, {0.7f, 0.42f}, {0.35f, 0.62f}, {0.66f, 0.3f}, {0.5f, 0.55f}};
		for (int i = 0; i < words.length; i++) {
			float from = 0.14f + i * 0.1f;
			float t = Cutscene.fade(f, from, from + 0.14f, 0.04f);
			if (t > 0) {
				Cutscene.bigText(g, mc, Component.translatable("halloween.cutscene.vecna." + words[i]), (int) (w * spots[i][0]), (int) (h * spots[i][1]), 1.3f,
					Cutscene.alpha(t * 0.8f, 0x9A1010));
			}
		}
		float title = Cutscene.fade(f, 0.62f, 0.9f, 0.05f);
		if (title > 0) {
			Cutscene.bigText(g, mc, Component.translatable("halloween.cutscene.vecna"), w / 2, h * 3 / 5, 3.2f, Cutscene.alpha(title, 0xC01212));
			Cutscene.bigText(g, mc, Component.translatable("halloween.cutscene.vecna.sub"), w / 2, h * 3 / 5 + 26, 1.1f, Cutscene.alpha(title, 0xD8C8B8));
		}
		if (!vr) {
			if (flash(f) > 0) {
				g.fill(0, 0, w, h, 0xE0300606);
			}
			// The eyes open: a flash that burns out.
			float burn = 1f - (f - EYES) / 0.09f;
			if (f >= EYES && burn > 0) {
				g.fill(0, 0, w, h, Cutscene.alpha(burn * 0.9f, 0xFF4020));
			}
			g.fill(0, 0, w, h, Cutscene.alpha(1f - smooth(f / 0.1f), 0x000000));
		}
	}

	/** Dark red bands along all four sides, {@code strength} 0..1 at their darkest. */
	private static void redEdges(GuiGraphicsExtractor g, int w, int h, int bar, float strength) {
		int steps = 10;
		int bx = w / 6 / steps;
		int by = h / 6 / steps;
		for (int i = 0; i < steps; i++) {
			int a = (int) (170 * Mth.clamp(strength, 0f, 1f) * (1f - i / (float) steps));
			int c = a << 24 | 0x500000;
			g.fill(i * bx, bar, (i + 1) * bx, h - bar, c);
			g.fill(w - (i + 1) * bx, bar, w - i * bx, h - bar, c);
			g.fill(0, bar + i * by, w, bar + (i + 1) * by, c);
			g.fill(0, h - bar - (i + 1) * by, w, h - bar - i * by, c);
		}
	}

	private static float smooth(float t) {
		t = Mth.clamp(t, 0f, 1f);
		return t * t * (3 - 2 * t);
	}
}
