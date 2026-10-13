package dev.halloween.client;

import dev.halloween.game.HwItems;
import dev.halloween.net.HwNet;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Other people's guns as everyone sees them (HwNet.GunPose: players aiming, bots and officers about to fire): a rifle
 * or shotgun shouldered, the cheek down on the stock, the left hand out under the fore-end; a revolver held out in
 * both hands at eye level; both following where they look. A shot kicks the arms up and the head back, and they
 * come down again. Raising and lowering take a few ticks.
 */
public final class GunPoses {
	private static final class State {
		boolean aimed;
		float since = -100;
		float shotAt = -100;
		/** How far up the gun was drawn last frame, and the turn its hand needs (see wrist). */
		float wrist;
	}

	private static final Map<Integer, State> STATES = new HashMap<>();
	/** For tests: everyone aims. */
	public static boolean testAll;

	private GunPoses() {
	}

	public static void receive(HwNet.GunPose payload) {
		State s = STATES.computeIfAbsent(payload.entityId(), k -> new State());
		float now = now();
		if (payload.state() == HwNet.GunPose.SHOT) {
			s.shotAt = now;
			if (!s.aimed) {
				// Fired without raising it first (a bot's snap shot): up for the shot all the same.
				s.aimed = true;
				s.since = now - 4;
			}
		} else if ((payload.state() == HwNet.GunPose.AIMED) != s.aimed) {
			s.aimed = payload.state() == HwNet.GunPose.AIMED;
			s.since = now;
		}
	}

	public static void clear() {
		STATES.clear();
	}

	private static float now() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? 0 : mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}

	/**
	 * The turn (about the hand's own x, as the item layer has it) that keeps the gun along the line of sight with
	 * the arm raised: the arm is up, but the gun must not point at the sky.
	 */
	public static float wrist(int entityId) {
		State s = STATES.get(entityId);
		return s == null ? 0f : s.wrist;
	}

	/** Lays the aiming pose over a person's model (players, bots, officers). */
	public static void apply(HumanoidModel<?> m, AvatarRenderState state, int entityId) {
		State s = testAll ? STATES.computeIfAbsent(entityId, k -> new State()) : STATES.get(entityId);
		if (s == null) {
			return;
		}
		s.wrist = 0f;
		float now = now();
		float up;
		if (testAll) {
			up = 1f;
		} else {
			float k = Mth.clamp((now - s.since) / 4f, 0f, 1f);
			up = s.aimed ? k : 1f - k;
		}
		if (up <= 0f) {
			return;
		}
		up = up * up * (3 - 2 * up);
		Minecraft mc = Minecraft.getInstance();
		Entity e = mc.level == null ? null : mc.level.getEntity(entityId);
		HwItems.Kind kind = e instanceof LivingEntity living ? HwItems.kindOf(living.getMainHandItem()) : null;
		boolean longGun = kind == HwItems.Kind.RIFLE || kind == HwItems.Kind.SHOTGUN;
		// The shot: up hard, then down over a few ticks.
		float since = s == null ? 100f : now - s.shotAt;
		float kick = since < 0 ? 0f : since < 1.2f ? since / 1.2f : Math.max(0f, 1f - (since - 1.2f) / (longGun ? 7f : 5f));
		kick *= kick;
		float strength = kind == HwItems.Kind.SHOTGUN ? 1.2f : kind == HwItems.Kind.RIFLE ? 1f : 0.65f;

		float yaw = m.head.yRot;
		float pitch = m.head.xRot;
		float rx;
		float ry;
		float rz;
		float lx;
		float ly;
		float lz;
		if (longGun) {
			// Shouldered: the right hand at the grip by the cheek, the left out under the fore-end; the head down on the stock.
			rx = -Mth.HALF_PI + pitch - 0.05f;
			ry = -0.25f + yaw;
			rz = 0f;
			lx = -Mth.HALF_PI + pitch - 0.12f;
			ly = 0.42f + yaw;
			lz = 0f;
			m.head.zRot = Mth.lerp(up, m.head.zRot, -0.12f);
			m.head.xRot = Mth.lerp(up, m.head.xRot, pitch + 0.06f);
		} else {
			// Out in both hands at eye level, arms straight.
			rx = -Mth.HALF_PI + pitch;
			ry = -0.18f + yaw;
			rz = 0f;
			lx = -Mth.HALF_PI + pitch + 0.02f;
			ly = 0.5f + yaw;
			lz = 0f;
		}
		float k = 0.42f * strength * kick;
		rx -= k;
		lx -= k * 0.8f;
		m.head.xRot -= 0.12f * strength * kick;
		m.body.xRot = Mth.lerp(up, m.body.xRot, 0f) - 0.05f * strength * kick;
		m.rightArm.xRot = Mth.lerp(up, m.rightArm.xRot, rx);
		m.rightArm.yRot = Mth.lerp(up, m.rightArm.yRot, ry);
		m.rightArm.zRot = Mth.lerp(up, m.rightArm.zRot, rz);
		m.leftArm.xRot = Mth.lerp(up, m.leftArm.xRot, lx);
		m.leftArm.yRot = Mth.lerp(up, m.leftArm.yRot, ly);
		m.leftArm.zRot = Mth.lerp(up, m.leftArm.zRot, lz);
		// The gun along the line of sight (less the kick: that throws the muzzle up).
		s.wrist = (m.rightArm.xRot + k - pitch) * up;
	}
}
