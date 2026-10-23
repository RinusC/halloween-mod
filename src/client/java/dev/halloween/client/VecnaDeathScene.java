package dev.halloween.client;

import dev.halloween.game.VecnaDeath;
import dev.halloween.net.HwNet;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Vecna takes the cursed one (game.VecnaDeath), as every client sees it: their body lifted, head thrown back, the
 * bones breaking one by one (each limb wrenched the wrong way with a jolt), the neck last, then slack; and for everyone in
 * the match a slow shot circling the body from low down, letterboxed, nothing else on screen.
 */
public final class VecnaDeathScene {
	private static int victim = -1;
	private static int vecna = -1;
	private static long start;
	private static int ticks;
	/** Where the shot starts round them (degrees). */
	private static float orbit0;
	/** Where their body was last seen (the shot stays on it once they are gone). */
	private static Vec3 lastBody;

	private VecnaDeathScene() {
	}

	public static void begin(HwNet.VecnaDeath payload) {
		Minecraft mc = Minecraft.getInstance();
		victim = payload.victimId();
		vecna = payload.vecnaId();
		start = payload.start();
		ticks = payload.ticks();
		orbit0 = 0;
		lastBody = null;
		if (mc.level != null) {
			Entity v = mc.level.getEntity(victim);
			Entity k = mc.level.getEntity(vecna);
			if (v != null && k != null) {
				// From the far side of them, a little off the line: him below, behind them.
				Vec3 d = k.position().subtract(v.position());
				orbit0 = (float) Math.toDegrees(Math.atan2(-d.x, d.z)) + 180f + 30f;
			}
		}
	}

	public static void clear() {
		victim = -1;
	}

	/** Ticks into the scene (with the frame's share of a tick), or -1 when there is none. */
	private static float time(float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (victim < 0 || mc.level == null) {
			return -1;
		}
		float t = mc.level.getGameTime() - start + partial;
		if (t >= ticks || t < -2) {
			victim = -1;
			return -1;
		}
		return Math.max(0f, t);
	}

	/** The scene is playing and this client is in the match: everyone in it watches the shot. */
	public static boolean cinematic() {
		Minecraft mc = Minecraft.getInstance();
		return time(0f) >= 0 && mc.player != null && ClientState.myRole() != dev.halloween.Role.NONE;
	}

	/** The camera for this frame (position, yaw, pitch), or null. */
	public static double[] camera(float partial) {
		if (!cinematic()) {
			return null;
		}
		Minecraft mc = Minecraft.getInstance();
		Entity v = mc.level.getEntity(victim);
		if (v != null && !v.isSpectator()) {
			lastBody = v.getPosition(partial);
		}
		if (lastBody == null) {
			return null;
		}
		float t = time(partial);
		float f = t / ticks;
		// Circling slowly from low down (him in the picture too), drawing in a little; looking up at them.
		Vec3 body = lastBody.add(0, 1.0, 0);
		float a = (orbit0 + 60f * smooth(f)) * Mth.DEG_TO_RAD;
		double r = 7.4 - 1.6 * smooth(f);
		Vec3 pos = new Vec3(body.x - Mth.sin(a) * r, body.y - 1.6 + 0.6 * smooth(f), body.z + Mth.cos(a) * r);
		Vec3 d = body.subtract(pos);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
		return new double[]{pos.x, pos.y, pos.z, yaw, pitch};
	}

	/** The scene in the headset (VrShots): standing still, low down a few steps from the body, black in and out. */
	static VrShots.Shot vrShot(float partial) {
		if (!cinematic()) {
			return null;
		}
		Entity v = Minecraft.getInstance().level.getEntity(victim);
		if (v != null && !v.isSpectator()) {
			lastBody = v.getPosition(partial);
		}
		if (lastBody == null) {
			return null;
		}
		return new VrShots.Shot(1, lastBody.add(0, 1.0, 0), 6.6, -1.3, VrShots.cut(time(partial) / ticks, 0f, 1f, 0.06f), false);
	}

	/** Bars top and bottom, sliding in and out; true while the shot plays (the HUD stays hidden). */
	public static boolean overlay(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (!cinematic()) {
			return false;
		}
		if (ClientVr.active()) {
			return true;
		}
		float f = time(delta.getGameTimeDeltaPartialTick(false)) / ticks;
		float in = Mth.clamp(Math.min(f / 0.06f, (1f - f) / 0.06f), 0f, 1f);
		int bar = Math.round(g.guiHeight() * 0.12f * in);
		g.fill(0, 0, g.guiWidth(), bar, 0xFF000000);
		g.fill(0, g.guiHeight() - bar, g.guiWidth(), g.guiHeight(), 0xFF000000);
		return true;
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0f, 1f);
		return x * x * (3 - 2 * x);
	}

	/** A break at tick {@code at}: 0 before it, snapping to past 1 within two ticks, settling back to 1. */
	private static float snap(float t, int at) {
		float k = t - at;
		if (k <= 0) {
			return 0f;
		}
		if (k < 2) {
			return k / 2f * 1.25f;
		}
		return 1f + 0.25f * (float) Math.exp(-(k - 2) / 3f);
	}

	/** Their body as the scene has it now; false if they are not in it. */
	public static boolean pose(HumanoidModel<?> m, int entityId) {
		if (entityId != victim) {
			return false;
		}
		float t = time(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
		if (t < 0) {
			return false;
		}
		int[] c = VecnaDeath.CRACKS;
		float lift = smooth(t / 60f);
		// Alive until the neck: a tremble in everything; then slack, swaying.
		boolean alive = t < c[4];
		float tremble = alive ? 0.05f * Mth.sin(t * 2.7f) * Mth.sin(t * 0.9f) : 0f;
		float sway = Mth.sin(t * 0.12f) * 0.06f;
		// Lifted: arms drifting out from the sides, legs hanging, the head thrown back.
		m.rightArm.xRot = 0.15f + tremble;
		m.leftArm.xRot = 0.15f - tremble;
		m.rightArm.yRot = 0f;
		m.leftArm.yRot = 0f;
		m.rightArm.zRot = Mth.lerp(lift, 0.12f, 0.75f) + sway;
		m.leftArm.zRot = -Mth.lerp(lift, 0.12f, 0.75f) + sway;
		m.rightLeg.xRot = 0.05f + tremble;
		m.leftLeg.xRot = -0.05f - tremble;
		m.rightLeg.zRot = 0.08f;
		m.leftLeg.zRot = -0.08f;
		m.head.xRot = -0.85f * lift + tremble;
		m.head.yRot = 0f;
		m.head.zRot = 0f;
		// The bones, one by one: the right arm wrenched back, the left, the right leg, the left, the neck.
		float s0 = snap(t, c[0]);
		m.rightArm.xRot += 2.1f * s0;
		m.rightArm.yRot += 0.7f * s0;
		m.rightArm.zRot += 0.35f * s0;
		float s1 = snap(t, c[1]);
		m.leftArm.xRot += 2.0f * s1;
		m.leftArm.yRot -= 0.6f * s1;
		m.leftArm.zRot -= 0.3f * s1;
		float s2 = snap(t, c[2]);
		m.rightLeg.xRot += 1.15f * s2;
		m.rightLeg.zRot += 0.3f * s2;
		float s3 = snap(t, c[3]);
		m.leftLeg.xRot += 0.95f * s3;
		m.leftLeg.zRot -= 0.35f * s3;
		float s4 = snap(t, c[4]);
		m.head.zRot += 1.0f * s4;
		m.head.xRot += 0.35f * s4;
		m.head.yRot += 0.3f * s4;
		return true;
	}
}
