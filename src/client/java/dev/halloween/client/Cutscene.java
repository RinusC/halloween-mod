package dev.halloween.client;

import dev.halloween.HwSounds;
import dev.halloween.game.Cutscenes;
import dev.halloween.net.HwNet;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The opening scene while the match counts down (game.Cutscenes picks what to show), instead of the numbers.
 * Survivors: the camera flies over the night town towards their house, letterboxed, with the place and the date,
 * and drops behind them into their eyes. Michael: through the mask's eye holes, breathing, he walks up to a lit
 * window with someone behind it; "He came home"; black, and his own camera over the shoulder.
 */
public final class Cutscene {
	private static HwNet.Cutscene scene;
	private static long start;
	/** Survivors: the flight's keyframes (positions) and when each is reached (share of the scene). */
	private static Vec3[] keys;
	private static final float[] TIMES = {0f, 0.6f, 0.86f, 1f};
	private static Vec3 focus;
	private static LoopSound breath;

	private Cutscene() {
	}

	public static void begin(HwNet.Cutscene payload) {
		if (payload.kind() == Cutscenes.KILLCAM) {
			KillCam.begin(payload);
			return;
		}
		if (payload.kind() >= Cutscenes.SHAPE_HERO) {
			// One of the survivors at what they do, through his mask: his walk up, their pose.
			HeroScene.watch(payload.kind() - Cutscenes.SHAPE_HERO, payload.c());
			payload = new HwNet.Cutscene(Cutscenes.SHAPE, payload.ticks(), payload.a(), payload.b(), payload.c());
		}
		Minecraft mc = Minecraft.getInstance();
		// A countdown too short for a scene (tests, a quick restart) plays none.
		if (mc.level == null || mc.player == null || payload.ticks() < 60) {
			scene = null;
			return;
		}
		scene = payload;
		start = mc.level.getGameTime();
		if (payload.kind() >= Cutscenes.HERO) {
			// Their own scene, by who they are.
			HeroScene.begin(payload);
			return;
		}
		if (payload.kind() == Cutscenes.SURVIVOR) {
			planFlight(mc, payload.a());
		} else {
			breath = new LoopSound(HwSounds.SHAPE_BREATH, SoundSource.HOSTILE, 0.55f, () -> maskOn(0f) ? 1 : 0);
			mc.getSoundManager().play(breath);
		}
	}

	public static void clear() {
		scene = null;
	}

	/** Share of the scene played (0..1), or -1 when none plays. */
	private static float progress(float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (scene == null || mc.level == null || ClientState.match.state() == 2 || ClientState.match.state() == 0 && mc.level.getGameTime() - start > 5) {
			return -1;
		}
		float f = (mc.level.getGameTime() - start + partial) / scene.ticks();
		if (f >= 1f) {
			scene = null;
			return -1;
		}
		return Math.max(0f, f);
	}

	public static boolean active() {
		return progress(0f) >= 0;
	}

	/** Survivors see themselves from outside; Michael looks out of his own eyes (the knife in hand). */
	public static boolean showsBody() {
		return scene != null && (scene.kind() == Cutscenes.SURVIVOR || scene.kind() >= Cutscenes.HERO);
	}

	/** Michael's scene ends on black and fades in on his own camera: the scene's camera lets go a little early. */
	private static boolean maskOn(float partial) {
		float f = progress(partial);
		return f >= 0 && scene != null && scene.kind() == Cutscenes.SHAPE && f < 0.92f;
	}

	/** The camera for this frame: position, yaw, pitch; null to leave it be. */
	public static double[] camera(float partial) {
		float f = progress(partial);
		if (f < 0) {
			return null;
		}
		if (scene.kind() >= Cutscenes.HERO) {
			return HeroScene.camera(f, partial);
		}
		Minecraft mc = Minecraft.getInstance();
		if (scene.kind() == Cutscenes.SHAPE) {
			if (f >= 0.92f) {
				return null;
			}
			// Walking up to the window, slowing down; the head bobbing and swaying a little with each step.
			float walk = Mth.clamp(f / 0.72f, 0f, 1f);
			float eased = 1f - (1f - walk) * (1f - walk);
			Vec3 pos = scene.a().lerp(scene.b(), eased);
			float step = eased * 13f;
			double bob = walk < 1f ? Math.sin(step * Math.PI) * 0.045 : 0;
			float sway = walk < 1f ? (float) Math.sin(step * Math.PI * 0.5) * 1.2f : 0f;
			float[] rot = lookAt(pos, scene.c());
			return new double[]{pos.x, pos.y + bob, pos.z, rot[0] + sway, rot[1]};
		}
		Vec3 eye = mc.player.getEyePosition(partial);
		if (f < TIMES[2]) {
			Vec3 pos = spline(f);
			float[] rot = lookAt(pos, focus);
			return new double[]{pos.x, pos.y, pos.z, rot[0], rot[1]};
		}
		// Dropping in behind them, then into their eyes, turning to where they look.
		float t = smooth((f - TIMES[2]) / (1f - TIMES[2]));
		Vec3 pos = keys[2].lerp(eye, t);
		float[] from = lookAt(keys[2], focus);
		float yaw = from[0] + Mth.wrapDegrees(mc.player.getViewYRot(partial) - from[0]) * t;
		float pitch = Mth.lerp(t, from[1], mc.player.getViewXRot(partial));
		return new double[]{pos.x, pos.y, pos.z, yaw, pitch};
	}

	private static void planFlight(Minecraft mc, Vec3 center) {
		Vec3 eye = mc.player.getEyePosition();
		Vec3 away = eye.subtract(center).multiply(1, 0, 1);
		away = away.lengthSqr() < 4 ? mc.player.getLookAngle().multiply(1, 0, 1).scale(-1) : away;
		away = away.normalize();
		Vec3 side = new Vec3(-away.z, 0, away.x);
		focus = eye.add(0, -0.5, 0);
		keys = new Vec3[]{
			eye.subtract(away.scale(70)).add(0, 38, 0),
			eye.subtract(away.scale(24)).add(side.scale(22)).add(0, 20, 0),
			behind(mc, eye, away),
			eye
		};
	}

	/** Somewhere a few blocks off where nothing stands between the camera and them (behind them if there is room). */
	private static Vec3 behind(Minecraft mc, Vec3 eye, Vec3 away) {
		Vec3 back = mc.player.getLookAngle().multiply(1, 0, 1);
		back = back.lengthSqr() < 1e-4 ? away : back.normalize();
		for (int i = 0; i < 8; i++) {
			double a = Math.PI * i / 4;
			Vec3 dir = new Vec3(-back.x * Math.cos(a) + back.z * Math.sin(a), 0, -back.z * Math.cos(a) - back.x * Math.sin(a));
			Vec3 at = eye.add(dir.scale(4.5)).add(0, 1.6, 0);
			if (mc.level.clip(new ClipContext(eye, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS) {
				return at;
			}
		}
		return eye.add(0, 3, 0);
	}

	/** Through the flight's keyframes, smoothly (Catmull-Rom). */
	private static Vec3 spline(float f) {
		int seg = f < TIMES[1] ? 0 : 1;
		float t = (f - TIMES[seg]) / (TIMES[seg + 1] - TIMES[seg]);
		Vec3 p0 = keys[Math.max(0, seg - 1)];
		Vec3 p1 = keys[seg];
		Vec3 p2 = keys[seg + 1];
		Vec3 p3 = keys[Math.min(keys.length - 1, seg + 2)];
		double t2 = t * t;
		double t3 = t2 * t;
		return p1.scale(2).add(p2.subtract(p0).scale(t)).add(p0.scale(2).subtract(p1.scale(5)).add(p2.scale(4)).subtract(p3).scale(t2))
			.add(p1.scale(3).subtract(p0).subtract(p2.scale(3)).add(p3).scale(t3)).scale(0.5);
	}

	private static float smooth(float t) {
		t = Mth.clamp(t, 0f, 1f);
		return t * t * (3 - 2 * t);
	}

	private static float[] lookAt(Vec3 from, Vec3 to) {
		Vec3 d = to.subtract(from);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
		return new float[]{yaw, pitch};
	}

	/** Bars, the mask, titles and fades over everything else; true while the scene plays (the HUD stays hidden). */
	public static boolean overlay(GuiGraphicsExtractor g, DeltaTracker delta) {
		float partial = delta.getGameTimeDeltaPartialTick(false);
		float f = progress(partial);
		if (f < 0) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		if (scene.kind() >= Cutscenes.HERO) {
			HeroScene.overlay(g, mc, f);
			return true;
		}
		int w = g.guiWidth();
		int h = g.guiHeight();
		if (scene.kind() == Cutscenes.SHAPE) {
			if (f < 0.92f) {
				// Letterboxed, a dark vignette round the edges: a film shot through his eyes.
				int bar = Math.round(h * 0.11f);
				g.fill(0, 0, w, bar, 0xFF000000);
				g.fill(0, h - bar, w, h, 0xFF000000);
				vignette(g, w, h, bar);
				float title = fade(f, 0.32f, 0.8f, 0.06f);
				if (title > 0) {
					bigText(g, mc, Component.translatable("halloween.cutscene.shape"), w / 2, h * 3 / 4, 1.6f, alpha(title, 0xB01818));
				}
			}
			// Black between the mask and his own eyes.
			float black = f < 0.05f ? 1 - f / 0.05f : f < 0.84f ? 0 : f < 0.92f ? (f - 0.84f) / 0.08f : 1 - (f - 0.92f) / 0.08f;
			g.fill(0, 0, w, h, alpha(Mth.clamp(black, 0, 1), 0x000000));
			return true;
		}
		// Letterbox, drawing back as the camera drops into their eyes.
		float bars = f < 0.9f ? 1f : 1f - (f - 0.9f) / 0.1f;
		int bar = Math.round(h * 0.11f * bars);
		g.fill(0, 0, w, bar, 0xFF000000);
		g.fill(0, h - bar, w, h, 0xFF000000);
		float t1 = fade(f, 0.08f, 0.45f, 0.06f);
		if (t1 > 0) {
			bigText(g, mc, Component.translatable("halloween.cutscene.town"), w / 2, h / 2 - 14, 1.8f, alpha(t1, 0xE8E2D4));
			bigText(g, mc, Component.translatable("halloween.cutscene.date"), w / 2, h / 2 + 8, 1.0f, alpha(t1, 0xE0A040));
		}
		float t2 = fade(f, 0.55f, 0.84f, 0.05f);
		if (t2 > 0) {
			bigText(g, mc, Component.translatable("halloween.cutscene.survivor"), w / 2, h - bar - 22, 1.0f, alpha(t2, 0xE8E2D4));
		}
		if (f < 0.06f) {
			g.fill(0, 0, w, h, alpha(1 - f / 0.06f, 0x000000));
		}
		return true;
	}

	/** Darker towards the sides. */
	private static void vignette(GuiGraphicsExtractor g, int w, int h, int bar) {
		int steps = 12;
		int band = w / 5 / steps;
		for (int i = 0; i < steps; i++) {
			int a = (int) (150 * (1f - i / (float) steps));
			g.fill(i * band, bar, (i + 1) * band, h - bar, a << 24);
			g.fill(w - (i + 1) * band, bar, w - i * band, h - bar, a << 24);
		}
	}

	static void bigText(GuiGraphicsExtractor g, Minecraft mc, Component text, int x, int y, float scale, int color) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.centeredText(mc.font, text, 0, -4, color);
		g.pose().popMatrix();
	}

	/** 0..1: fading in from {@code from}, out by {@code to}, over {@code edge}. */
	static float fade(float f, float from, float to, float edge) {
		if (f < from || f > to) {
			return 0;
		}
		return Mth.clamp(Math.min((f - from) / edge, (to - f) / edge), 0f, 1f);
	}

	static int alpha(float a, int rgb) {
		return ((int) (Mth.clamp(a, 0f, 1f) * 255) << 24) | rgb;
	}
}
