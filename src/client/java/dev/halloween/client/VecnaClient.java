package dev.halloween.client;

import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.Vec3;

/**
 * Vecna on the client. For him: the Anchor key held and let go (HwNet.VecnaAnchorKey), and the Eyes (his camera
 * on the survivor the server names). For a survivor his curse has got to the second stage: phantoms of him (a
 * mannequin only this client has, drawn as the killer by MichaelRenderer) standing off to one side at a distance,
 * gone when looked at, come too close to, or after a few seconds.
 */
public final class VecnaClient {
	private static boolean sentAnchor;
	/** The camera was put on someone else for the Eyes (and must come back). */
	private static boolean eyesCamera;
	/** For tests: frames he was drawn while looking through someone's eyes. */
	public static int selfDrawn;

	private VecnaClient() {
	}

	public static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		CurseVisions.tick(mc);
		tickEyes(mc);
		tickJolts(mc);
		tickRift(mc);
		tickTremble(mc);
		VecnaLightning.tick(mc);
	}

	/** Two slow waves and a quick one: where the trembling hands have the view now (degrees, before scaling). */
	private static float tremble(float t, float phase) {
		return Mth.sin(t * 0.21f + phase) * 0.6f + Mth.sin(t * 0.53f + phase * 2.1f) * 0.3f + Mth.sin(t * 1.9f + phase * 0.7f) * 0.1f;
	}

	/**
	 * The curse at its last stage: their hands shake. The view (and with it the aim) is moved by the change in a slow
	 * wobble each tick, so it drifts and comes back rather than walking off.
	 */
	private static void tickTremble(Minecraft mc) {
		boolean on = ClientState.curse.stage() >= 4 && ClientState.myRole().survivor() && !mc.player.isSpectator() && mc.gui.screen() == null;
		if (!on) {
			return;
		}
		float t = mc.level.getGameTime();
		float amp = 1.6f;
		float dYaw = (tremble(t, 0f) - tremble(t - 1, 0f)) * amp;
		float dPitch = (tremble(t, 2.3f) - tremble(t - 1, 2.3f)) * amp * 0.7f;
		mc.player.setYRot(mc.player.getYRot() + dYaw);
		mc.player.setXRot(Mth.clamp(mc.player.getXRot() + dPitch, -90f, 90f));
	}

	// ---------------------------------------------------------------- his keys and his Eyes

	/** In place of the Impulse press: the Anchor key down and up (true while consumed here). */
	static void tickAnchorKey(Minecraft mc) {
		boolean held = HalloweenClient.down(HalloweenClient.KEY_IMPULSE) && mc.gui.screen() == null;
		if (held != sentAnchor) {
			sentAnchor = held;
			ClientPlayNetworking.send(new HwNet.VecnaAnchorKey(held));
		}
	}

	/** Looking through someone's eyes right now? */
	public static boolean eyesActive() {
		return eyesCamera;
	}

	// ---------------------------------------------------------------- going into someone's eyes

	/** His flight into them (ticks), and their head's jerk as he gets there (ticks). */
	private static final float FLY = 11f;
	private static final float JOLT = 20f;
	/** Whose head jerks, from when (game time, after the flight). */
	private static final java.util.Map<Integer, Float> JOLTS = new java.util.HashMap<>();
	/** His flight: when it began (-1: none), into whom, and where his camera was. */
	private static float flyStart = -1;
	private static int flyTarget = -1;
	private static Vec3 flyFrom = Vec3.ZERO;
	private static float flyYaw;
	private static float flyPitch;
	/** When this client's own head jerks (to hear him come in then). */
	private static float myJoltAt = -1;

	private static float now(float partial) {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? 0 : mc.level.getGameTime() + partial;
	}

	private static float partial() {
		return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}

	/** Vecna goes into this one's eyes (HwNet.VecnaEyesEnter): for him the flight, for everyone their head jerks as he gets there. */
	public static void eyesEnter(int entity) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		float now = now(partial());
		JOLTS.put(entity, now + FLY);
		if (entity == mc.player.getId() || MichaelRenderer.vecna() && ClientState.myRole() == Role.SHAPE) {
			myJoltAt = now + FLY;
		}
		if (MichaelRenderer.vecna() && ClientState.myRole() == Role.SHAPE) {
			var cam = mc.gameRenderer.mainCamera();
			flyFrom = cam.position();
			flyYaw = cam.yRot();
			flyPitch = cam.xRot();
			flyStart = now;
			flyTarget = entity;
		}
	}

	private static void tickJolts(Minecraft mc) {
		float now = mc.level.getGameTime();
		JOLTS.values().removeIf(at -> now - at > JOLT);
		if (myJoltAt >= 0 && now >= myJoltAt) {
			myJoltAt = -1;
			// In the headset a jolt through both hands too.
			ClientVr.buzz(true, 0.25f, 1f);
			ClientVr.buzz(false, 0.25f, 1f);
			// Him coming in, right in their head.
			mc.level.playLocalSound(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(), HwSounds.VECNA_EYES_OPEN, SoundSource.HOSTILE, 0.8f, 1.25f, false);
		}
	}

	/** How far into the jerk this one's head is (ticks), or -1. */
	private static float joltAge(int entity, float partial) {
		Float at = JOLTS.get(entity);
		if (at == null) {
			return -1;
		}
		float age = now(partial) - at;
		return age < 0 || age > JOLT ? -1 : age;
	}

	/**
	 * The jerk, 0..1 parts: snap (thrown back at once, easing off), second (a smaller one a moment later), shiver (a
	 * quick tremor dying away); and to which side the head twists.
	 */
	private static float[] jolt(int entity, float age) {
		float snap = age < 1.5f ? age / 1.5f : (float) Math.exp(-(age - 1.5f) * 0.28f);
		float second = Math.max(0f, 1f - Math.abs(age - 7f) / 1.4f);
		float shiver = Mth.sin(age * 4.3f) * Math.max(0f, 1f - age / JOLT);
		float side = (entity & 1) == 0 ? 1f : -1f;
		return new float[] {snap, second, shiver, side};
	}

	/** Their model's head as he comes in: thrown back and twisted, a second jerk, a shiver (over the pose it has). */
	public static void poseJolt(net.minecraft.client.model.HumanoidModel<?> m, int entity) {
		float age = joltAge(entity, partial());
		if (age < 0) {
			return;
		}
		float[] j = jolt(entity, age);
		m.head.xRot += -0.9f * j[0] - 0.4f * j[1] + 0.12f * j[2];
		m.head.yRot += j[3] * (0.35f * j[0] - 0.2f * j[1]) + 0.1f * j[2];
		m.head.zRot += j[3] * (0.45f * j[0] - 0.25f * j[1]) + 0.15f * j[2];
	}

	/** In first person, the eyes he came into (theirs, or his in them): the view jerks up and aside (yaw, pitch), or null. */
	public static float[] cameraJolt(float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getCameraEntity() == null || ClientVr.active()) {
			return null;
		}
		int id = mc.getCameraEntity().getId();
		float age = joltAge(id, partial);
		if (age < 0) {
			return id == mc.player.getId() ? CurseVisions.startle(partial) : null;
		}
		float[] j = jolt(id, age);
		return new float[] {j[3] * (14f * j[0] - 6f * j[1]) + 2.5f * j[2], -26f * j[0] - 10f * j[1] + 2f * j[2]};
	}

	/**
	 * In the headset the view can't be turned against the head: the eyes carried along his flight (the head still
	 * free), and at the jerk thrown back and up with a shiver. Where the eyes go, or null.
	 */
	public static Vec3 vrEyes(Vec3 headset, float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return null;
		}
		Vec3 eyes = null;
		double[] fly = flightCamera(partial);
		if (fly != null) {
			// Where he stands in the room is kept: the flight carries the room along.
			eyes = new Vec3(fly[0], fly[1], fly[2]).add(headset.subtract(mc.player.getEyePosition(partial)));
		}
		Entity cam = mc.getCameraEntity();
		float age = cam == null ? -1 : joltAge(cam.getId(), partial);
		if (age >= 0) {
			float[] j = jolt(cam.getId(), age);
			Vec3 fwd = ClientVr.headForward();
			Vec3 side = new Vec3(-fwd.z, 0, fwd.x);
			double back = 0.28 * j[0] + 0.12 * j[1];
			eyes = (eyes != null ? eyes : headset).add(fwd.scale(-back)).add(0, 0.12 * j[0] + 0.05 * j[1], 0)
				.add(side.scale(j[3] * (0.12 * j[0] - 0.05 * j[1]) + 0.04 * j[2]));
		}
		return eyes;
	}

	/** In the headset the darkening and the red blink are the fog's (a fill on the HUD would hang before the face): {strength, r, g, b, distance}. */
	public static float[] vrTint(float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getCameraEntity() == null) {
			return null;
		}
		float t = flight(partial);
		float tunnel = t < 0 ? 0f : 0.55f * (float) Math.pow(Mth.clamp((t - 0.15f) / 0.85f, 0f, 1f), 1.5);
		float age = joltAge(mc.getCameraEntity().getId(), partial);
		float blink = age < 0 ? (t > 0.75f ? (t - 0.75f) / 0.25f : 0f) : Math.max(0f, 1f - age / 9f);
		float k = Math.max(tunnel, 0.85f * blink * blink);
		return k > 0.01f ? new float[] {k, 0.12f, 0f, 0.01f, Mth.lerp(k, 48f, 3f)} : null;
	}

	/** How far through his flight (0..1), or -1 when not flying (and the flight given up if they are gone). */
	private static float flight(float partial) {
		if (flyStart < 0) {
			return -1;
		}
		Minecraft mc = Minecraft.getInstance();
		float t = (now(partial) - flyStart) / FLY;
		Entity target = mc.level == null ? null : mc.level.getEntity(flyTarget);
		if (t >= 1f || target == null || t > 0.4f && ClientState.vecna.eyesTarget() != flyTarget) {
			flyStart = -1;
			return -1;
		}
		return Math.max(0f, t);
	}

	/** Mid-flight the body is seen (his left behind, theirs coming at him); the last frames already through their eyes. */
	public static boolean flightShowsBody(float partial) {
		float t = flight(partial);
		return t >= 0 && t < 0.92f;
	}

	/**
	 * His camera flying into them: drawn back a little as if gathering, then hurled at their head faster and faster,
	 * over an arc, looking at them; at the end turning to look where they look. {x, y, z, yaw, pitch}, or null.
	 */
	public static double[] flightCamera(float partial) {
		float t = flight(partial);
		if (t < 0) {
			return null;
		}
		Entity target = Minecraft.getInstance().level.getEntity(flyTarget);
		Vec3 to = target.getEyePosition(partial);
		Vec3 path = to.subtract(flyFrom);
		double dist = path.length();
		Vec3 dir = dist < 1.0E-4 ? Vec3.ZERO : path.scale(1 / dist);
		float u = Mth.clamp((t - 0.15f) / 0.85f, 0f, 1f);
		float ease = (float) Math.pow(u, 2.4);
		float gather = t < 0.15f ? Mth.sin(t / 0.15f * Mth.HALF_PI) : 1f - Mth.clamp(u * 3f, 0f, 1f);
		Vec3 at = flyFrom.add(path.scale(ease))
			.add(dir.scale(-0.6 * gather))
			.add(0, 0.25 * gather + Mth.sin(ease * Mth.PI) * Math.min(1.8, dist * 0.12), 0);
		// Looking at them; first turned from where he looked, last to where they look.
		Vec3 look = to.subtract(at);
		float lookYaw = look.lengthSqr() < 1.0E-6 ? flyYaw : (float) (Math.atan2(-look.x, look.z) * 180 / Math.PI);
		float lookPitch = look.lengthSqr() < 1.0E-6 ? flyPitch : (float) (-Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)) * 180 / Math.PI);
		float in = smooth(Mth.clamp(t / 0.3f, 0f, 1f));
		float out = smooth(Mth.clamp((u - 0.7f) / 0.3f, 0f, 1f));
		float yaw = flyYaw + Mth.wrapDegrees(lookYaw - flyYaw) * in;
		float pitch = Mth.lerp(in, flyPitch, lookPitch);
		yaw += Mth.wrapDegrees(target.getViewYRot(partial) - yaw) * out;
		pitch = Mth.lerp(out, pitch, target.getViewXRot(partial));
		return new double[] {at.x, at.y, at.z, yaw, pitch};
	}

	/** The view widening as he picks up speed, back again as he gets there. */
	public static float flightFov(float partial) {
		float t = flight(partial);
		if (t < 0) {
			return 1f;
		}
		float u = Mth.clamp((t - 0.15f) / 0.85f, 0f, 1f);
		return 1f + 0.4f * Mth.sin(Mth.PI * (float) Math.pow(u, 1.6));
	}

	private static float smooth(float x) {
		return x * x * (3 - 2 * x);
	}

	/** Over the screen: the edges closing in dark as he flies, a dark red blink as he comes into someone's head (his or theirs). */
	public static void eyesOverlay(net.minecraft.client.gui.GuiGraphicsExtractor g, float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (ClientVr.active() || mc.getCameraEntity() == null) {
			return;
		}
		float t = flight(partial);
		float tunnel = t < 0 ? 0f : (float) Math.pow(Mth.clamp((t - 0.15f) / 0.85f, 0f, 1f), 1.5);
		float age = joltAge(mc.getCameraEntity().getId(), partial);
		float blink = age < 0 ? (t > 0.75f ? (t - 0.75f) / 0.25f : 0f) : Math.max(0f, 1f - age / 9f);
		int w = g.guiWidth();
		int h = g.guiHeight();
		if (tunnel > 0) {
			// Rings of dark, thicker towards the edge.
			for (int i = 0; i < 6; i++) {
				int inset = Math.round((i + 1) * 0.045f * Math.min(w, h) * tunnel);
				int a = Math.round(40 * tunnel);
				int c = a << 24 | 0x0A0004;
				g.fill(0, 0, w, inset, c);
				g.fill(0, h - inset, w, h, c);
				g.fill(0, inset, inset, h - inset, c);
				g.fill(w - inset, inset, w, h - inset, c);
			}
		}
		if (blink > 0) {
			int a = Math.round(200 * blink * blink);
			g.fill(0, 0, w, h, a << 24 | 0x1A0002);
		}
	}

	private static void tickEyes(Minecraft mc) {
		int id = MichaelRenderer.vecna() && ClientState.myRole() == Role.SHAPE ? ClientState.vecna.eyesTarget() : -1;
		Entity target = id < 0 ? null : mc.level.getEntity(id);
		if (target != null) {
			if (mc.getCameraEntity() != target) {
				mc.setCameraEntity(target);
			}
			eyesCamera = true;
		} else if (eyesCamera) {
			eyesCamera = false;
			mc.setCameraEntity(mc.player);
		}
	}

	// ---------------------------------------------------------------- the rift: the town gone under

	private static long riftFlashAt = -1000;
	private static long riftNextFlash;
	private static long riftNextDrone;
	/** Glowing crack cells near this player (looked for once a second). */
	private static final List<BlockPos> CRACKS = new ArrayList<>();

	/** The rift is open: everyone in the match lives in the Upside Down now. */
	public static boolean riftOpen() {
		return ClientState.ritual.rift() && ClientState.myRole() != Role.NONE;
	}

	/** Red lightning's glow in the murk now, 0..1 (a double flicker). */
	public static float riftFlash(float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return 0f;
		}
		float t = mc.level.getGameTime() - riftFlashAt + partial;
		if (t < 0 || t > 12) {
			return 0f;
		}
		return t < 3 ? 1f - t / 3f : t > 5 && t < 8 ? 0.7f * (1f - (t - 5) / 3f) : 0f;
	}

	private static void tickRift(Minecraft mc) {
		if (!riftOpen()) {
			return;
		}
		long now = mc.level.getGameTime();
		var r = mc.level.getRandom();
		if (now >= riftNextFlash) {
			riftFlashAt = now;
			riftNextFlash = now + 70 + r.nextInt(120);
			// Orange lightning forking across the sky with it.
			VecnaLightning.strike(mc);
			mc.level.playLocalSound(mc.player.getX(), mc.player.getY() + 20, mc.player.getZ(), HwSounds.VECNA_THUNDER, SoundSource.AMBIENT,
				0.7f, 0.85f + r.nextFloat() * 0.25f, false);
		}
		if (now >= riftNextDrone) {
			riftNextDrone = now + 160 + r.nextInt(80);
			mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(), HwSounds.VECNA_RIFT_DRONE, SoundSource.AMBIENT, 0.8f, 0.9f + r.nextFloat() * 0.2f, false);
		}
		// Over the cracks near by: embers, a red haze, smoke curling up.
		if (now % 20 == 0) {
			CRACKS.clear();
			BlockPos me = mc.player.blockPosition();
			BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
			for (int dx = -20; dx <= 20; dx++) {
				for (int dz = -20; dz <= 20; dz++) {
					for (int dy = -6; dy <= 3; dy++) {
						p.set(me.getX() + dx, me.getY() + dy, me.getZ() + dz);
						if (mc.level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.SHROOMLIGHT) && mc.level.getBlockState(p.above()).getCollisionShape(mc.level, p.above()).isEmpty()) {
							CRACKS.add(p.immutable());
							break;
						}
					}
				}
			}
		}
		for (int i = 0; i < Math.min(8, CRACKS.size()); i++) {
			BlockPos c = CRACKS.get(r.nextInt(CRACKS.size()));
			double x = c.getX() + r.nextDouble();
			double z = c.getZ() + r.nextDouble();
			double y = c.getY() + 1.02;
			switch (r.nextInt(6)) {
				case 0, 1 -> mc.level.addParticle(dev.halloween.HwParticles.VECNA_EMBER, x, y, z, 0, 0.01, 0);
				case 2 -> mc.level.addParticle(dev.halloween.HwParticles.VECNA_SMOKE, x, y, z, 0, 0.02, 0);
				case 3 -> {
					if (r.nextInt(3) == 0) {
						mc.level.addParticle(dev.halloween.HwParticles.VECNA_MIST, x, y + 0.6, z, 0, 0.005, 0);
					}
				}
				default -> mc.level.addParticle(dev.halloween.HwParticles.VECNA_ASH, x, y + r.nextDouble() * 1.5, z, 0, 0.01, 0);
			}
		}
		// Spores and ash drifting in the air all round.
		for (int i = 0; i < 6; i++) {
			double x = mc.player.getX() + (r.nextDouble() - 0.5) * 24;
			double y = mc.player.getY() + r.nextDouble() * 8 - 1;
			double z = mc.player.getZ() + (r.nextDouble() - 0.5) * 24;
			mc.level.addParticle(i % 3 == 0 ? dev.halloween.HwParticles.VECNA_ASH : dev.halloween.HwParticles.VECNA_SPORE, x, y, z, 0, 0, 0);
		}
	}

	// ---------------------------------------------------------------- phantoms (CurseVisions)

	public static boolean isPhantomId(int id) {
		return CurseVisions.isPhantomId(id);
	}

	public static boolean isPhantom(Entity e) {
		return CurseVisions.isPhantom(e);
	}

	/** For tests: a phantom now, wherever this client is (not only for the cursed); true if one stands. */
	public static boolean testPhantom() {
		return CurseVisions.testPhantom();
	}

	public static void reset() {
		VecnaLightning.clear();
		Minecraft mc = Minecraft.getInstance();
		CurseVisions.reset();
		if (eyesCamera && mc.player != null) {
			mc.setCameraEntity(mc.player);
		}
		eyesCamera = false;
		sentAnchor = false;
		JOLTS.clear();
		flyStart = -1;
		myJoltAt = -1;
	}
}
