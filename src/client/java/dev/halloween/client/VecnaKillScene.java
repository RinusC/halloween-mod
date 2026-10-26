package dev.halloween.client;

import dev.halloween.game.VecnaKill;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Vecna's kills (game.VecnaKill) as every client near sees them: the body of the one dying (lifted, run through,
 * dragged under, bound to a wardrobe), his moves (the model's ascend, drag, locker_call, locker_hold, locker_kill), the
 * vines out of his back and palm or out of the ground (VecnaVines) and the light of it. The server moves the bodies; the
 * vines find their roots on his posed model (where VecnaModel says his back and hands are this frame).
 */
public final class VecnaKillScene {
	private static final class Scene {
		int kind;
		int vecna;
		int victim;
		long start;
		int ticks;
		float yaw;
		Vec3 a;
		Vec3 b;
		/** When the server broke it off (the vines let go over a few ticks); -1 while it plays. */
		float released = -1;
	}

	/** By the one dying. */
	private static final Map<Integer, Scene> SCENES = new HashMap<>();
	/** Points on his model (model space, VecnaModel puts them here as it poses him) and the matrix he was last drawn with. */
	private static final Map<Integer, Vector3f[]> ANCHORS = new HashMap<>();
	private static final Map<Integer, Matrix4f> MATRICES = new HashMap<>();
	private static final Map<Integer, Vec3> CAMERAS = new HashMap<>();
	/** The points: his back (left, middle, right), his right palm and further along its fingers, the same for the left. */
	static final int BACK_L = 0;
	static final int BACK_C = 1;
	static final int BACK_R = 2;
	static final int PALM_R = 3;
	static final int FINGERS_R = 4;
	static final int PALM_L = 5;
	static final int FINGERS_L = 6;
	private static final int RELEASE_TICKS = 7;

	/** The animation of his model now and how far into it (seconds). */
	record Act(String anim, float seconds) {
	}

	private VecnaKillScene() {
	}

	public static void receive(HwNet.VecnaKill p) {
		if (p.kind() == VecnaKill.NONE) {
			Scene s = SCENES.get(p.victimId());
			if (s != null && s.released < 0) {
				s.released = now();
			}
			return;
		}
		Scene s = new Scene();
		s.kind = p.kind();
		s.vecna = p.vecnaId();
		s.victim = p.victimId();
		s.start = p.start();
		s.ticks = p.ticks();
		s.yaw = p.yaw();
		s.a = p.a();
		s.b = p.b();
		SCENES.put(s.victim, s);
	}

	public static void clear() {
		SCENES.clear();
		ANCHORS.clear();
		MATRICES.clear();
		CAMERAS.clear();
	}

	private static float now() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? 0 : mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}

	private static float time(Scene s) {
		return now() - s.start;
	}

	/** Playing now (not broken off, not over). */
	private static boolean live(Scene s) {
		return s != null && s.released < 0 && time(s) <= s.ticks;
	}

	private static Scene ofVecna(int id) {
		for (Scene s : SCENES.values()) {
			if (s.vecna == id && live(s)) {
				return s;
			}
		}
		return null;
	}

	static void register() {
		LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> draw(ctx.poseStack(), ctx.submitNodeCollector()));
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null) {
			SCENES.clear();
			return;
		}
		float now = now();
		SCENES.values().removeIf(s -> s.released >= 0 ? now - s.released > RELEASE_TICKS + 2 : now - s.start > s.ticks + 4);
		if (SCENES.isEmpty()) {
			ANCHORS.clear();
			MATRICES.clear();
		}
	}

	// ---------------------------------------------------------------- him

	/** His animation now, if he is in one of his kills. */
	static Act act(int vecnaId) {
		Scene s = ofVecna(vecnaId);
		if (s == null) {
			return null;
		}
		float sec = time(s) / 20f;
		return switch (s.kind) {
			case VecnaKill.ASCEND -> new Act("ascend", sec);
			case VecnaKill.DRAG -> new Act("drag", sec);
			case VecnaKill.LOCKER_OPEN -> new Act("locker_call", sec);
			case VecnaKill.LOCKER_HOLD -> new Act("locker_hold", sec);
			case VecnaKill.LOCKER_KILL -> new Act("locker_kill", sec);
			default -> null;
		};
	}

	/** Whether his model should note where his back and hands are (a kill of his is playing). */
	static boolean wantsAnchors(int vecnaId) {
		for (Scene s : SCENES.values()) {
			if (s.vecna == vecnaId) {
				return true;
			}
		}
		return false;
	}

	static void anchors(int vecnaId, Vector3f[] points) {
		ANCHORS.put(vecnaId, points);
	}

	/** The matrix his model was drawn with (camera-relative) and where the camera was. */
	static void drawnWith(int vecnaId, Matrix4f m) {
		MATRICES.put(vecnaId, new Matrix4f(m));
		CAMERAS.put(vecnaId, Minecraft.getInstance().gameRenderer.mainCamera().position());
	}

	/** Which way this one's body faces in a kill (his towards them, theirs towards him or out of the wardrobe), or NaN. */
	public static float bodyYaw(int id) {
		Scene s = SCENES.get(id);
		if (live(s)) {
			return s.yaw + 180f;
		}
		Scene v = ofVecna(id);
		return v != null ? v.yaw : Float.NaN;
	}

	/** In a kill of his: their head isn't theirs to turn (the pose has it). */
	public static boolean holdsHead(int id) {
		return live(SCENES.get(id)) || ofVecna(id) != null;
	}

	// ---------------------------------------------------------------- them

	private static float smooth(float x) {
		x = Mth.clamp(x, 0f, 1f);
		return x * x * (3 - 2 * x);
	}

	/** A jolt at {@code at}: up to 1 in two ticks, dying away. */
	private static float jolt(float t, float at) {
		float k = t - at;
		return k < 0 ? 0f : k < 2 ? k / 2f : (float) Math.exp(-(k - 2) / 4f);
	}

	/** Something snapping at {@code at}: 0 before, past 1 in two ticks, settling back to 1. */
	private static float snap(float t, float at) {
		float k = t - at;
		return k <= 0 ? 0f : k < 2 ? k / 2f * 1.3f : 1f + 0.3f * (float) Math.exp(-(k - 2) / 3f);
	}

	/** The body of the one dying as the scene has it; false if they are in none. */
	public static boolean pose(HumanoidModel<?> m, int id) {
		Scene s = SCENES.get(id);
		if (!live(s)) {
			return false;
		}
		float t = time(s);
		float now = now();
		m.rightArm.yRot = 0;
		m.leftArm.yRot = 0;
		m.rightLeg.yRot = 0;
		m.leftLeg.yRot = 0;
		m.rightLeg.zRot = 0;
		m.leftLeg.zRot = 0;
		m.head.yRot = 0;
		m.head.zRot = 0;
		switch (s.kind) {
			case VecnaKill.ASCEND -> ascend(m, t, now);
			case VecnaKill.DRAG -> drag(m, t, now);
			case VecnaKill.LOCKER_OPEN, VecnaKill.LOCKER_HOLD -> bound(m, now, 1f);
			case VecnaKill.LOCKER_KILL -> lockerKill(m, t, now);
			default -> {
				return false;
			}
		}
		return true;
	}

	/** Held up by the throat: clawing at his hand, kicking, the head forced back. */
	private static void throat(HumanoidModel<?> m, float now, float strength) {
		float a = Mth.sin(now * 1.3f);
		float l = Mth.sin(now * 1.6f);
		m.rightArm.xRot = -2.1f + a * 0.3f;
		m.rightArm.yRot = -0.45f;
		m.rightArm.zRot = 0.3f;
		m.leftArm.xRot = -2.1f - a * 0.3f;
		m.leftArm.yRot = 0.45f;
		m.leftArm.zRot = -0.3f;
		m.rightLeg.xRot = l * 0.7f * strength;
		m.leftLeg.xRot = -l * 0.7f * strength;
		m.head.xRot = -0.5f - 0.2f * strength + Mth.cos(now * 1.1f) * 0.08f;
		m.head.zRot = Mth.sin(now * 0.5f) * 0.15f;
	}

	private static void ascend(HumanoidModel<?> m, float t, float now) {
		if (t < VecnaKill.A_LIFT) {
			throat(m, now, 0.6f + 0.4f * t / VecnaKill.A_LIFT);
			return;
		}
		// Hanging in the air, arms drawn out wide by nothing, legs together, the head back.
		float tremble = Mth.sin(now * 2.9f) * Mth.sin(now * 1.3f);
		float rightX = 0f, leftX = 0f, rightZ = 1.38f, leftZ = -1.38f, legX = 0.06f, headX = -0.6f;
		if (t < VecnaKill.A_FREE) {
			float f = smooth((t - VecnaKill.A_LIFT) / (VecnaKill.A_FREE - VecnaKill.A_LIFT));
			throat(m, now, 1f - f);
			m.rightArm.xRot = Mth.lerp(f, m.rightArm.xRot, rightX);
			m.leftArm.xRot = Mth.lerp(f, m.leftArm.xRot, leftX);
			m.rightArm.yRot = Mth.lerp(f, m.rightArm.yRot, 0f);
			m.leftArm.yRot = Mth.lerp(f, m.leftArm.yRot, 0f);
			m.rightArm.zRot = Mth.lerp(f, m.rightArm.zRot, rightZ);
			m.leftArm.zRot = Mth.lerp(f, m.leftArm.zRot, leftZ);
			m.rightLeg.xRot = Mth.lerp(f, m.rightLeg.xRot, legX);
			m.leftLeg.xRot = Mth.lerp(f, m.leftLeg.xRot, -legX);
			m.head.xRot = Mth.lerp(f, m.head.xRot, headX);
			return;
		}
		if (t < VecnaKill.A_SNAP) {
			// Run through: they jack-knife on it; then the convulsions, the head going back to the sky.
			float j = jolt(t, VecnaKill.A_IMPALE);
			float c = t < VecnaKill.A_IMPALE ? 0.03f : 0.1f;
			float back = smooth((t - VecnaKill.A_IMPALE) / 18f);
			float crack = jolt(t, 50) + jolt(t, 60);
			m.rightArm.xRot = rightX - 0.8f * j + tremble * c * 2 - 0.25f * crack;
			m.leftArm.xRot = leftX - 0.8f * j - tremble * c * 2 - 0.25f * crack;
			m.rightArm.zRot = rightZ - 0.5f * j + tremble * c;
			m.leftArm.zRot = leftZ + 0.5f * j - tremble * c;
			m.rightLeg.xRot = legX - 0.7f * j + Mth.sin(now * 3.3f) * c * 1.5f;
			m.leftLeg.xRot = -legX - 0.6f * j - Mth.sin(now * 3.7f) * c * 1.5f;
			m.rightLeg.zRot = 0.04f;
			m.leftLeg.zRot = -0.04f;
			m.head.xRot = Mth.lerp(back, headX, -1.0f) + 0.9f * j + tremble * c;
			m.head.zRot = tremble * c * 0.6f;
			return;
		}
		// The neck broken, sideways; slack, swaying a little on the vines; dropped.
		float sn = snap(t, VecnaKill.A_SNAP);
		float slack = smooth((t - VecnaKill.A_SNAP) / 8f);
		float sway = Mth.sin(now * 0.4f) * 0.06f * (1f - smooth((t - VecnaKill.A_RIP) / 6f));
		float bounce = (float) Math.exp(-(t - VecnaKill.A_SNAP) / 4f) * Mth.sin((t - VecnaKill.A_SNAP) * 1.1f) * 0.3f;
		m.rightArm.xRot = Mth.lerp(slack, rightX, 0.1f) + sway;
		m.leftArm.xRot = Mth.lerp(slack, leftX, 0.15f) - sway;
		m.rightArm.zRot = Mth.lerp(slack, rightZ, 0.25f) + bounce;
		m.leftArm.zRot = Mth.lerp(slack, leftZ, -0.25f) - bounce;
		m.rightLeg.xRot = 0.12f + sway;
		m.leftLeg.xRot = -0.05f - sway;
		m.head.xRot = Mth.lerp(slack, -1.0f, 0.35f);
		m.head.zRot = 1.05f * sn;
		m.head.yRot = 0.25f * sn;
	}

	private static void drag(HumanoidModel<?> m, float t, float now) {
		if (t < VecnaKill.D_SLAM) {
			throat(m, now, 1f);
			return;
		}
		float a = Mth.sin(now * 1.4f);
		float b = Mth.sin(now * 1.4f + 1.8f);
		if (t < VecnaKill.D_LET_GO) {
			// The ground gone from under them: arms out for balance, flailing, looking down into it.
			float f = smooth((t - VecnaKill.D_SLAM) / 4f);
			m.rightArm.xRot = Mth.lerp(f, -2.0f, -0.7f + a * 0.4f);
			m.leftArm.xRot = Mth.lerp(f, -2.0f, -0.7f + b * 0.4f);
			m.rightArm.zRot = Mth.lerp(f, 0.3f, 1.0f + b * 0.2f);
			m.leftArm.zRot = Mth.lerp(f, -0.3f, -1.0f - a * 0.2f);
			m.rightLeg.zRot = 0.2f;
			m.leftLeg.zRot = -0.2f;
			m.rightLeg.xRot = a * 0.3f;
			m.leftLeg.xRot = -a * 0.3f;
			m.head.xRot = Mth.lerp(f, -0.6f, 0.45f);
			return;
		}
		float tug = 0;
		for (int at : VecnaKill.D_TUGS) {
			tug += jolt(t, at);
		}
		if (t < 64) {
			// Reaching up at him, then clawing at the edge of the hole; flung up at every heave.
			float claw = smooth((t - 44) / 6f);
			float reachX = -2.6f + a * 0.35f;
			float clawX = -1.15f + a * 0.25f;
			m.rightArm.xRot = Mth.lerp(claw, reachX, clawX) - 0.6f * tug;
			m.leftArm.xRot = Mth.lerp(claw, -2.6f + b * 0.35f, -1.15f + b * 0.25f) - 0.6f * tug;
			m.rightArm.yRot = Mth.lerp(claw, -0.2f, 0.35f);
			m.leftArm.yRot = Mth.lerp(claw, 0.2f, -0.35f);
			m.rightArm.zRot = 0.25f;
			m.leftArm.zRot = -0.25f;
			m.head.xRot = -0.5f - 0.4f * tug + Mth.sin(now * 0.9f) * 0.06f;
			m.head.yRot = Mth.sin(now * 0.7f) * 0.3f;
			return;
		}
		float sn = snap(t, VecnaKill.D_SNAP);
		if (t < VecnaKill.D_SNAP) {
			// His hands on their head: clawing at his arms.
			float f = smooth((t - 64) / 5f);
			m.rightArm.xRot = Mth.lerp(f, -1.15f, -2.35f + a * 0.2f);
			m.leftArm.xRot = Mth.lerp(f, -1.15f, -2.35f - a * 0.2f);
			m.rightArm.yRot = -0.5f * f;
			m.leftArm.yRot = 0.5f * f;
			m.rightArm.zRot = 0.3f;
			m.leftArm.zRot = -0.3f;
			m.head.xRot = -0.6f;
			return;
		}
		// The head wrenched round; everything slack.
		float slack = smooth((t - VecnaKill.D_SNAP) / 5f);
		m.rightArm.xRot = Mth.lerp(slack, -2.35f, 0.1f);
		m.leftArm.xRot = Mth.lerp(slack, -2.35f, 0.15f);
		m.rightArm.zRot = Mth.lerp(slack, 0.3f, 0.2f);
		m.leftArm.zRot = Mth.lerp(slack, -0.3f, -0.2f);
		m.rightLeg.xRot = 0.1f;
		m.leftLeg.xRot = -0.05f;
		m.head.yRot = 1.65f * sn;
		m.head.zRot = 0.3f * sn;
		m.head.xRot = Mth.lerp(slack, -0.6f, 0.4f);
	}

	/** Bound tight by the vines against the wardrobe: only the legs and the head free to thrash. */
	private static void bound(HumanoidModel<?> m, float now, float strength) {
		float a = Mth.sin(now * 1.5f);
		float w = Mth.sin(now * 2.6f) * 0.06f * strength;
		m.rightArm.xRot = 0.05f + w;
		m.leftArm.xRot = 0.05f - w;
		m.rightArm.zRot = 0.07f;
		m.leftArm.zRot = -0.07f;
		m.rightLeg.xRot = a * 0.6f * strength;
		m.leftLeg.xRot = -a * 0.6f * strength;
		m.head.yRot = Mth.sin(now * 0.9f) * 0.6f * strength;
		m.head.xRot = -0.3f + Mth.sin(now * 1.7f) * 0.15f * strength;
	}

	private static void lockerKill(HumanoidModel<?> m, float t, float now) {
		if (t < VecnaKill.L_RIP) {
			float j = 0;
			for (int at : VecnaKill.L_SPEARS) {
				j += jolt(t, at);
			}
			float fade = 1f - smooth((t - VecnaKill.L_SPEARS[2]) / 16f);
			bound(m, now, 0.2f + 0.8f * fade * (t < VecnaKill.L_SPEARS[0] ? 1f : 0.6f));
			// The claw over their face pushes the head back against the door.
			float gripped = smooth(t / VecnaKill.L_GRIP);
			m.head.xRot = Mth.lerp(gripped, m.head.xRot, -0.45f) + 0.55f * j;
			m.head.yRot *= 1f - gripped * 0.8f;
			m.rightArm.zRot += 0.5f * j;
			m.leftArm.zRot -= 0.5f * j;
			m.rightLeg.xRot -= 0.6f * j;
			m.leftLeg.xRot -= 0.5f * j;
			return;
		}
		// Flung back inside; slumping out when the doors open.
		float sway = Mth.sin(now * 0.45f) * 0.1f;
		m.rightArm.xRot = 0.15f + sway;
		m.leftArm.xRot = 0.15f - sway;
		m.rightArm.zRot = 0.18f;
		m.leftArm.zRot = -0.18f;
		m.rightLeg.xRot = 0.12f;
		m.leftLeg.xRot = -0.06f;
		m.head.xRot = 0.75f;
		m.head.zRot = 0.25f;
	}

	// ---------------------------------------------------------------- the vines

	/** One of his points in the world now (his model as last drawn), or a guess from where he stands. */
	private static Vec3 anchor(int vecnaId, int which, Vec3 pos, Vec3 fwd, Vec3 side) {
		Vector3f[] pts = ANCHORS.get(vecnaId);
		Matrix4f m = MATRICES.get(vecnaId);
		Vec3 cam = CAMERAS.get(vecnaId);
		if (pts != null && m != null && cam != null && pts[which] != null) {
			Vector3f w = m.transformPosition(new Vector3f(pts[which]));
			return cam.add(w.x, w.y, w.z);
		}
		Vec3 back = pos.add(0, 2.2, 0).subtract(fwd.scale(0.2));
		return switch (which) {
			case BACK_L -> back.subtract(side.scale(0.22));
			case BACK_R -> back.add(side.scale(0.22));
			case PALM_R -> pos.add(0, 1.9, 0).add(fwd.scale(0.85)).add(side.scale(0.3));
			case FINGERS_R -> pos.add(0, 1.9, 0).add(fwd.scale(1.15)).add(side.scale(0.3));
			case PALM_L -> pos.add(0, 1.9, 0).add(fwd.scale(0.85)).subtract(side.scale(0.3));
			case FINGERS_L -> pos.add(0, 1.9, 0).add(fwd.scale(1.15)).subtract(side.scale(0.3));
			default -> back;
		};
	}

	private static void draw(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector) {
		Minecraft mc = Minecraft.getInstance();
		if (SCENES.isEmpty() || mc.level == null) {
			return;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		float now = now();
		List<VecnaVines.Vine> vines = new ArrayList<>();
		List<VecnaVines.Ray> rays = new ArrayList<>();
		List<VecnaVines.Crack> cracks = new ArrayList<>();
		for (Scene s : SCENES.values()) {
			Entity victim = mc.level.getEntity(s.victim);
			if (victim == null || victim.isSpectator()) {
				continue;
			}
			Entity vecna = mc.level.getEntity(s.vecna);
			Vec3 vpos = vecna != null ? vecna.getPosition(partial) : s.b;
			float t = (s.released >= 0 ? s.released : now) - s.start;
			// Broken off: the vines draw back into him.
			float let = s.released >= 0 ? 1f - smooth((now - s.released) / RELEASE_TICKS) : 1f;
			if (let <= 0f) {
				continue;
			}
			double ya = s.yaw * Mth.DEG_TO_RAD;
			Vec3 fwd = new Vec3(-Math.sin(ya), 0, Math.cos(ya));
			Vec3 side = fwd.cross(new Vec3(0, 1, 0));
			Vec3 p = victim.getPosition(partial);
			switch (s.kind) {
				case VecnaKill.ASCEND -> ascendVines(s, t, let, vpos, p, fwd, side, vines, rays, now);
				case VecnaKill.DRAG -> {
					dragVines(s, t, let, p, fwd, side, vines, rays, now);
					groundCracks(s, t, cracks, now);
				}
				case VecnaKill.LOCKER_OPEN, VecnaKill.LOCKER_HOLD, VecnaKill.LOCKER_KILL -> lockerVines(s, t, let, vpos, p, fwd, side, vines);
				default -> {
				}
			}
		}
		VecnaVines.submit(pose, collector, vines, rays, cracks, now);
	}

	private static final Vec3 UP = new Vec3(0, 1, 0);

	/** From his back up over his shoulder and on to them: a path that rears behind him first, {@code over} high. */
	private static List<Vec3> overShoulder(Vec3 from, Vec3 to, Vec3 fwd, Vec3 side, double sideways, double rear, double over) {
		return VecnaVines.bezier(from, from.add(0, over, 0).subtract(fwd.scale(rear)).add(side.scale(sideways)),
			to.add(0, over * 0.35, 0).subtract(fwd.scale(0.75)).add(side.scale(sideways * 0.4)), to, 22);
	}

	private static void ascendVines(Scene s, float t, float let, Vec3 vpos, Vec3 p, Vec3 fwd, Vec3 side, List<VecnaVines.Vine> vines,
		List<VecnaVines.Ray> rays, float now) {
		if (t < VecnaKill.A_LAUNCH) {
			return;
		}
		float rip = 1f - smooth((t - VecnaKill.A_RIP) / 6f);
		float grow = Mth.clamp((t - VecnaKill.A_LAUNCH) / (float) (VecnaKill.A_IMPALE - VecnaKill.A_LAUNCH), 0f, 1f) * rip * let;
		Vec3 chest = p.add(0, 1.15, 0);
		// Three out of his back over his shoulders, one out of his right palm: in at the front, out at the back.
		int[] roots = {BACK_L, BACK_C, BACK_R};
		double[] across = {-0.13, 0.02, 0.14};
		double[] high = {0.12, -0.18, 0.02};
		for (int i = 0; i < 3; i++) {
			Vec3 root = anchor(s.vecna, roots[i], vpos, fwd, side);
			Vec3 in = chest.add(side.scale(across[i])).add(0, high[i], 0);
			Vec3 out = in.add(fwd.scale(0.6)).add(0, -0.05 + 0.1 * i, 0);
			List<Vec3> path = VecnaVines.join(overShoulder(root, in, fwd, side, (i - 1) * 0.55, 0.3, 0.55), VecnaVines.line(in, out, 4));
			vines.add(new VecnaVines.Vine(path, 22, 0.058f, 0.034f, grow, 0.07f, i * 0.37f + 0.1f));
		}
		Vec3 palm = anchor(s.vecna, PALM_R, vpos, fwd, side);
		Vec3 fingers = anchor(s.vecna, FINGERS_R, vpos, fwd, side);
		Vec3 dir = fingers.subtract(palm).normalize();
		Vec3 in = chest.add(0, 0.05, 0);
		List<Vec3> lash = VecnaVines.join(VecnaVines.bezier(palm, palm.add(dir.scale(0.45)), in.subtract(fwd.scale(0.5)), in, 14),
			VecnaVines.line(in, in.add(fwd.scale(0.7)), 4));
		vines.add(new VecnaVines.Vine(lash, 14, 0.05f, 0.034f, grow, 0.03f, 0.9f));
		// Two more wind round them and tighten.
		if (t >= VecnaKill.A_COIL) {
			float wind = Mth.clamp((t - VecnaKill.A_COIL) / 16f, 0f, 1f) * rip * let;
			double tight = 0.31 - 0.04 * smooth((t - VecnaKill.A_COIL) / 22f);
			for (int i = 0; i < 2; i++) {
				Vec3 root = anchor(s.vecna, i == 0 ? BACK_L : BACK_R, vpos, fwd, side);
				List<Vec3> c = VecnaVines.coil(p, s.yaw + 180f, tight, 0.19, p.y + 1.42 - i * 0.12, p.y + 0.78, 2.1, i * Math.PI + Math.PI * 0.5, 40);
				List<Vec3> path = VecnaVines.join(overShoulder(root, c.get(0), fwd, side, (i == 0 ? -1 : 1) * 0.8, 0.2, 0.6), c);
				vines.add(new VecnaVines.Vine(path, 22, 0.045f, 0.032f, wind, 0.08f, 1.3f + i * 0.4f));
			}
		}
		// Red light out of their eyes and mouth, head thrown back, until the neck goes.
		if (t > VecnaKill.A_COIL && t < VecnaKill.A_SNAP + 2) {
			float on = smooth((t - VecnaKill.A_COIL) / 8f) * (1f - smooth((t - VecnaKill.A_SNAP) / 2f));
			float flicker = 0.75f + 0.25f * Mth.sin(now * 2.7f) * Mth.sin(now * 1.1f);
			Vec3 face = fwd.scale(-0.58).add(0, 0.81, 0);
			Vec3 eyes = p.add(0, 1.69, 0).add(fwd.scale(-0.04));
			for (int e = -1; e <= 1; e += 2) {
				Vec3 eye = eyes.add(side.scale(e * 0.065));
				rays.add(new VecnaVines.Ray(eye, eye.add(face.scale(1.6)).add(side.scale(e * 0.12)), 0.05f, 0.32f, 1f, 0.22f, 0.08f, 0.85f * on * flicker));
			}
			Vec3 mouth = p.add(0, 1.55, 0).add(fwd.scale(-0.12));
			rays.add(new VecnaVines.Ray(mouth, mouth.add(face.scale(1.2)), 0.08f, 0.36f, 1f, 0.3f, 0.1f, 0.55f * on * flicker));
		}
	}

	private static void dragVines(Scene s, float t, float let, Vec3 p, Vec3 fwd, Vec3 side, List<VecnaVines.Vine> vines, List<VecnaVines.Ray> rays, float now) {
		if (t < VecnaKill.D_SLAM) {
			return;
		}
		Vec3 hole = s.a;
		Vec3 deep = hole.add(0, -1.7, 0);
		float back = 1f - smooth((t - VecnaKill.D_LAID) / 8f);
		// Out of the pit and round their legs, from the feet up.
		float rise = Mth.clamp((t - VecnaKill.D_RISE) / 14f, 0f, 1f) * back * let;
		for (int i = 0; i < 3; i++) {
			double ph = i * Math.PI * 2 / 3;
			Vec3 root = deep.add(Math.cos(ph) * 0.25, 0, Math.sin(ph) * 0.25);
			List<Vec3> c = VecnaVines.coil(p, s.yaw + 180f, 0.27, 0.19, p.y + 0.05, p.y + 0.82 - i * 0.06, 2.4, ph, 36);
			List<Vec3> up = VecnaVines.bezier(root, root.add(0, 0.7, 0), c.get(0).add(0, -0.6, 0), c.get(0), 10);
			vines.add(new VecnaVines.Vine(VecnaVines.join(up, c), 10, 0.052f, 0.036f, rise, 0.05f, i * 0.53f));
		}
		// Two more at the second heave, round the waist.
		float waist = Mth.clamp((t - 40) / 12f, 0f, 1f) * back * let;
		for (int i = 0; i < 2; i++) {
			double ph = Math.PI * 0.25 + i * Math.PI;
			Vec3 root = deep.add(Math.cos(ph) * 0.2, 0, Math.sin(ph) * 0.2);
			List<Vec3> c = VecnaVines.coil(p, s.yaw + 180f, 0.3, 0.19, p.y + 0.72, p.y + 1.05, 1.6, ph, 26);
			List<Vec3> up = VecnaVines.bezier(root, root.add(0, 0.9, 0), c.get(0).add(0, -0.5, 0), c.get(0), 12);
			vines.add(new VecnaVines.Vine(VecnaVines.join(up, c), 12, 0.05f, 0.036f, waist, 0.05f, 2.1f + i * 0.3f));
		}
		// Free ones rearing out of the scar round the pit, swaying, tasting the air.
		float free = Mth.clamp((t - VecnaKill.D_RISE - 2) / 10f, 0f, 1f) * (1f - smooth((t - VecnaKill.D_HEAVE - 6) / 10f)) * let;
		for (int i = 0; i < 3; i++) {
			double ph = s.yaw * Mth.DEG_TO_RAD + 1.1 + i * 2.1;
			Vec3 root = hole.add(Math.cos(ph) * 0.95, -0.4, Math.sin(ph) * 0.95);
			double sw = Math.sin(now * 0.13 + i * 2) * 0.35;
			double sw2 = Math.cos(now * 0.11 + i) * 0.3;
			Vec3 top = root.add(sw, 1.25 + 0.15 * i, sw2);
			List<Vec3> path = VecnaVines.bezier(root, root.add(0, 0.6, 0), top.add(-sw * 0.5, -0.3, -sw2 * 0.5), top, 18);
			vines.add(new VecnaVines.Vine(path, 18, 0.06f, 0.028f, free, 0.12f, 3f + i * 0.7f));
		}
		// The pit's glow up into the dark.
		float glow = smooth((t - VecnaKill.D_SLAM) / 6f) * (1f - smooth((t - VecnaKill.D_CLOSE) / 4f));
		if (glow > 0.01f) {
			float flicker = 0.8f + 0.2f * Mth.sin(now * 1.9f) * Mth.sin(now * 0.7f);
			rays.add(new VecnaVines.Ray(hole.add(0, -0.3, 0), hole.add(0, 1.7, 0), 0.9f, 0.45f, 0.9f, 0.07f, 0.02f, 0.45f * glow * flicker));
		}
	}

	/**
	 * The ground split open round the pit: burning cracks running out of it over the charred ground, torn out in a
	 * moment when his claw comes down, flickering, cooling once the pit shuts. The same on every client (from where the pit is).
	 */
	private static void groundCracks(Scene s, float t, List<VecnaVines.Crack> cracks, float now) {
		if (t < VecnaKill.D_SLAM) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Vec3 hole = s.a;
		java.util.Random r = new java.util.Random(net.minecraft.core.BlockPos.containing(hole).asLong());
		float spread = smooth((t - VecnaKill.D_SLAM) / 5f);
		float cool = 1f - 0.75f * smooth((t - VecnaKill.D_CLOSE) / 10f);
		float flicker = 0.85f + 0.15f * Mth.sin(now * 1.7f) * Mth.sin(now * 0.6f + 1f);
		int arms = 5 + r.nextInt(2);
		double base = r.nextDouble() * Math.PI * 2;
		for (int i = 0; i < arms; i++) {
			double a = base + i * Math.PI * 2 / arms + (r.nextDouble() - 0.5) * 0.7;
			double len = 1.3 + r.nextDouble() * 1.2;
			List<Vec3> path = new ArrayList<>();
			Vec3 at = hole.add(Math.cos(a) * 0.45, 0.02, Math.sin(a) * 0.45);
			path.add(at);
			double gone = 0;
			while (gone < len * spread) {
				a += (r.nextDouble() - 0.5) * 0.9;
				double step = 0.22 + r.nextDouble() * 0.12;
				Vec3 next = at.add(Math.cos(a) * step, 0, Math.sin(a) * step);
				// Only over solid ground level with the pit (not out over an edge or into a wall).
				net.minecraft.core.BlockPos under = net.minecraft.core.BlockPos.containing(next.x, hole.y - 0.5, next.z);
				if (!mc.level.getBlockState(under).isCollisionShapeFullBlock(mc.level, under) || !mc.level.getBlockState(under.above()).getCollisionShape(mc.level, under.above()).isEmpty()) {
					break;
				}
				path.add(next);
				at = next;
				gone += step;
			}
			if (path.size() > 1) {
				cracks.add(new VecnaVines.Crack(path, 0.07f + 0.03f * (float) r.nextDouble(), 0.9f * cool * flicker));
			}
		}
	}

	private static void lockerVines(Scene s, float t, float let, Vec3 vpos, Vec3 p, Vec3 fwd, Vec3 side, List<VecnaVines.Vine> vines) {
		float grow;
		float rip = 1f;
		switch (s.kind) {
			case VecnaKill.LOCKER_OPEN -> grow = Mth.clamp((t - VecnaKill.O_VINES) / 10f, 0f, 1f);
			case VecnaKill.LOCKER_KILL -> {
				grow = 1f;
				rip = 1f - smooth((t - VecnaKill.L_RIP) / 6f);
			}
			default -> grow = 1f;
		}
		grow *= rip * let;
		// Three bind them to the door: round the chest and arms, the waist and arms, the legs.
		int[] roots = {BACK_L, BACK_C, BACK_R};
		double[][] bands = {{1.5, 1.18}, {1.12, 0.82}, {0.62, 0.28}};
		for (int i = 0; i < 3; i++) {
			Vec3 root = anchor(s.vecna, roots[i], vpos, fwd, side);
			List<Vec3> c = VecnaVines.coil(p, s.yaw + 180f, 0.46 - (i == 2 ? 0.14 : 0), 0.22, p.y + bands[i][0], p.y + bands[i][1], 1.7, Math.PI * (0.5 + 0.6 * i), 32);
			List<Vec3> path = VecnaVines.join(overShoulder(root, c.get(0), fwd, side, (i - 1) * 0.6, 0.25, 0.6), c);
			vines.add(new VecnaVines.Vine(path, 22, 0.05f, 0.036f, grow, 0.06f, i * 0.41f));
		}
		if (s.kind != VecnaKill.LOCKER_KILL || t < VecnaKill.L_GRIP) {
			return;
		}
		// Three rear up over his head like snakes, and strike one after another, through them into the door.
		float back = Mth.clamp((t - VecnaKill.L_GRIP) / 8f, 0f, 1f) * rip * let;
		for (int i = 0; i < 3; i++) {
			Vec3 root = anchor(s.vecna, roots[i], vpos, fwd, side);
			Vec3 rear = vpos.add(0, 3.0 + 0.18 * i, 0).add(side.scale((i - 1) * 0.55)).subtract(fwd.scale(0.15));
			Vec3 in = p.add(0, 1.28 - 0.2 * i, 0).add(side.scale((i - 1) * 0.12));
			Vec3 out = in.add(fwd.scale(0.5));
			List<Vec3> reared = VecnaVines.join(VecnaVines.bezier(root, root.add(0, 0.5, 0), rear.subtract(fwd.scale(0.4)).add(0, -0.3, 0), rear, 22),
				VecnaVines.line(rear, rear.add(fwd.scale(0.25)).add(0, -0.12, 0), 4));
			List<Vec3> struck = VecnaVines.join(overShoulder(root, in, fwd, side, (i - 1) * 0.4, 0.1, 0.7), VecnaVines.line(in, out, 4));
			float k = smooth((t - VecnaKill.L_SPEARS[i] + 2) / 2f);
			List<Vec3> path = new ArrayList<>(reared.size());
			for (int j = 0; j < reared.size(); j++) {
				path.add(reared.get(j).lerp(struck.get(j), k));
			}
			vines.add(new VecnaVines.Vine(path, 22, 0.06f, 0.035f, back, 0.09f * (1f - k) + 0.02f, 1.7f + i * 0.33f));
		}
	}
}
