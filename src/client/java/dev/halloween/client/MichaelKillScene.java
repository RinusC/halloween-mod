package dev.halloween.client;

import dev.halloween.game.MichaelKill;
import dev.halloween.net.HwNet;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.util.Mth;

/**
 * Michael's kills after a struggle (game.MichaelKill) as every client near sees them: his moves and the body of the one
 * he kills, on the player models (his own model takes his pose from it, see MichaelRenderer), and how his knife sits in
 * his hand (along the arm, point first, when it goes in). The server moves them both.
 */
public final class MichaelKillScene {
	private static final class Scene {
		int kind;
		int michael;
		int victim;
		long start;
		int ticks;
		float yaw;
		/** Lifted: how high his arm goes (radians from hanging). */
		float raise;
	}

	/** By the one he kills. */
	private static final Map<Integer, Scene> SCENES = new HashMap<>();

	private MichaelKillScene() {
	}

	public static void receive(HwNet.MichaelKill p) {
		if (p.kind() == MichaelKill.NONE) {
			SCENES.remove(p.victimId());
			return;
		}
		Scene s = new Scene();
		s.kind = p.kind();
		s.michael = p.michaelId();
		s.victim = p.victimId();
		s.start = p.start();
		s.ticks = p.ticks();
		s.yaw = p.yaw();
		s.raise = (float) p.a().x;
		SCENES.put(s.victim, s);
	}

	public static void clear() {
		SCENES.clear();
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null) {
			SCENES.clear();
			return;
		}
		long now = mc.level.getGameTime();
		SCENES.values().removeIf(s -> now - s.start > s.ticks + 4);
	}

	private static float now() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? 0 : mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}

	private static Scene live(Scene s) {
		return s != null && now() - s.start <= s.ticks ? s : null;
	}

	private static Scene ofMichael(int id) {
		for (Scene s : SCENES.values()) {
			if (s.michael == id && live(s) != null) {
				return s;
			}
		}
		return null;
	}

	/** Which way this one faces in a kill of his (him towards them, them towards him), or NaN. */
	public static float bodyYaw(int id) {
		Scene s = live(SCENES.get(id));
		if (s != null) {
			return s.yaw + 180f;
		}
		Scene m = ofMichael(id);
		return m != null ? m.yaw : Float.NaN;
	}

	/**
	 * How much of his arm's raise his wrist takes back (MichaelRenderer.WRIST, the point kept down), or 0 while the
	 * knife goes in: along the arm, point first. NaN: not in a kill of his.
	 */
	public static float wrist(int id) {
		Scene s = ofMichael(id);
		if (s == null) {
			return Float.NaN;
		}
		float t = now() - s.start;
		boolean along = s.kind == MichaelKill.PIN ? t > MichaelKill.P_SLAM + 6 && t < MichaelKill.P_LEAVE + 2 || t > MichaelKill.P_RETURN + 4 && t < MichaelKill.P_PULL + 4
			: t > 2 && t < MichaelKill.L_FLING + 8;
		return along ? 0f : Float.NaN;
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0f, 1f);
		return x * x * (3 - 2 * x);
	}

	/** A jolt at {@code at}: up to 1 in two ticks, dying away. */
	private static float jolt(float t, float at) {
		float k = t - at;
		return k < 0 ? 0f : k < 2 ? k / 2f : (float) Math.exp(-(k - 2) / 4f);
	}

	/** Him or them in one of his kills, posed; false if neither. */
	public static boolean pose(HumanoidModel<?> m, int id) {
		Scene s = live(SCENES.get(id));
		boolean him = false;
		if (s == null) {
			s = ofMichael(id);
			him = true;
		}
		if (s == null) {
			return false;
		}
		float t = now() - s.start;
		float now = now();
		m.rightArm.yRot = 0;
		m.leftArm.yRot = 0;
		m.rightArm.zRot = 0;
		m.leftArm.zRot = 0;
		m.rightLeg.yRot = 0;
		m.leftLeg.yRot = 0;
		m.rightLeg.zRot = 0;
		m.leftLeg.zRot = 0;
		m.head.yRot = 0;
		m.head.zRot = 0;
		m.body.xRot = 0;
		m.body.yRot = 0;
		if (s.kind == MichaelKill.PIN) {
			if (him) {
				pinHim(m, t, now);
			} else {
				pinThem(m, t, now);
			}
		} else if (him) {
			liftHim(m, t, s.raise);
		} else {
			liftThem(m, t, now);
		}
		return true;
	}

	/** Walking: the legs swinging as he goes ({@code k} how much). */
	private static void walk(HumanoidModel<?> m, float now, float k) {
		m.rightLeg.xRot = Mth.sin(now * 0.9f) * 0.55f * k;
		m.leftLeg.xRot = -Mth.sin(now * 0.9f) * 0.55f * k;
	}

	private static void pinHim(HumanoidModel<?> m, float t, float now) {
		float throat;
		float knife;
		float knifeY = 0f;
		m.rightLeg.xRot = 0;
		m.leftLeg.xRot = 0;
		if (t <= MichaelKill.P_SLAM) {
			// Walking them back into the wall by the throat.
			throat = Mth.lerp(t / MichaelKill.P_SLAM, -1.6f, -1.75f);
			knife = -0.1f;
			walk(m, now, 1f - smooth((t - MichaelKill.P_SLAM + 3) / 3f));
		} else if (t < MichaelKill.P_STAB - 2) {
			// Up the wall; the knife drawn back.
			float k = smooth((t - MichaelKill.P_SLAM) / 9f);
			throat = Mth.lerp(k, -1.75f, -2.15f);
			knife = Mth.lerp(k, -0.1f, 0.75f);
			knifeY = 0.25f * k;
		} else if (t < MichaelKill.P_STAB + 0.5f) {
			// Driven in.
			float k = smooth((t - MichaelKill.P_STAB + 2) / 2.5f);
			throat = -2.15f;
			knife = Mth.lerp(k, 0.75f, -1.95f);
			knifeY = Mth.lerp(k, 0.25f, -0.05f);
		} else if (t < MichaelKill.P_LEAVE) {
			// Leaning on it, deeper.
			throat = -2.15f;
			knife = -1.95f - 0.12f * jolt(t, 30) + Mth.sin(now * 2.1f) * 0.02f;
			knifeY = -0.05f;
		} else if (t < MichaelKill.P_RETURN) {
			// Lets go; steps back and looks at them, the head slowly tilting.
			float k = smooth((t - MichaelKill.P_LEAVE) / 6f);
			throat = Mth.lerp(k, -2.15f, -0.05f);
			knife = Mth.lerp(k, -1.95f, -0.08f);
			if (t > MichaelKill.P_LEAVE + 4 && t < MichaelKill.P_BACK) {
				walk(m, now, 0.6f);
			}
			float tilt = smooth((t - MichaelKill.P_BACK) / 16f);
			m.head.zRot = 0.5f * tilt;
			m.head.xRot = 0.05f * tilt;
		} else {
			// In again; the knife pulled out; looking down at them.
			float reach = smooth((t - MichaelKill.P_RETURN) / 8f);
			float yank = smooth((t - MichaelKill.P_PULL) / 2f);
			float rest = smooth((t - MichaelKill.P_PULL - 3) / 6f);
			throat = -0.05f;
			knife = Mth.lerp(rest, Mth.lerp(yank, Mth.lerp(reach, -0.08f, -1.95f), -0.85f), -0.15f);
			if (t < MichaelKill.P_RETURN + 8) {
				walk(m, now, 0.6f);
			}
			m.head.zRot = Mth.lerp(reach, 0.5f, 0.15f);
			m.head.xRot = Mth.lerp(rest, 0.05f, 0.45f);
		}
		m.leftArm.xRot = throat;
		m.leftArm.yRot = 0.3f;
		m.rightArm.xRot = knife;
		m.rightArm.yRot = knifeY;
		if (t < MichaelKill.P_LEAVE) {
			m.head.xRot = t < MichaelKill.P_SLAM ? 0.1f : -0.05f;
		}
	}

	private static void pinThem(HumanoidModel<?> m, float t, float now) {
		float a = Mth.sin(now * 1.3f);
		float l = Mth.sin(now * 1.6f);
		if (t < MichaelKill.P_STAB) {
			// Clawing at his arm, stumbling back, then kicking up the wall; slammed against it.
			m.rightArm.xRot = -2.1f + a * 0.3f;
			m.leftArm.xRot = -2.1f - a * 0.3f;
			m.rightArm.yRot = -0.45f;
			m.leftArm.yRot = 0.45f;
			m.rightArm.zRot = 0.3f;
			m.leftArm.zRot = -0.3f;
			m.rightLeg.xRot = l * 0.65f;
			m.leftLeg.xRot = -l * 0.65f;
			m.head.xRot = -0.5f - 0.5f * jolt(t, MichaelKill.P_SLAM) + Mth.cos(now * 1.1f) * 0.06f;
			m.head.zRot = Mth.sin(now * 0.5f) * 0.12f;
			return;
		}
		// Run through: hands to the knife, kicking weaker and weaker, then still, the head hanging.
		float j = jolt(t, MichaelKill.P_STAB);
		float weak = smooth((t - 44) / 20f);
		float life = 1f - weak;
		m.rightArm.xRot = Mth.lerp(weak, -0.95f + a * 0.08f, 0.05f) - 0.4f * j;
		m.leftArm.xRot = Mth.lerp(weak, -0.95f - a * 0.08f, 0.05f) - 0.4f * j;
		m.rightArm.yRot = Mth.lerp(weak, -0.5f, 0f);
		m.leftArm.yRot = Mth.lerp(weak, 0.5f, 0f);
		m.rightArm.zRot = Mth.lerp(weak, 0f, 0.15f) + 0.5f * j;
		m.leftArm.zRot = Mth.lerp(weak, 0f, -0.15f) - 0.5f * j;
		m.rightLeg.xRot = l * 0.5f * life - 0.5f * j + 0.05f;
		m.leftLeg.xRot = -l * 0.45f * life - 0.4f * j - 0.05f;
		m.head.xRot = Mth.lerp(weak, -0.2f, 0.7f) + 0.6f * j;
		m.head.zRot = 0.2f * weak;
	}

	private static void liftHim(HumanoidModel<?> m, float t, float raise) {
		// The knife arm: drawn back low, driven up into them, raised overhead with them on it, flung out to his side.
		m.rightArm.xRot = -MichaelKill.liftArm(t, raise);
		float fling = t < MichaelKill.L_FLING ? 0f : (float) Math.sin(Mth.clamp((t - MichaelKill.L_FLING) / 14f, 0f, 1f) * Math.PI);
		m.rightArm.zRot = 0.55f * fling;
		// The flick: blood off the blade.
		float flick = jolt(t, 77) * (t > 77 ? 1f : 0f);
		m.rightArm.xRot -= 0.45f * flick;
		// The other hand at their throat until they are on the knife.
		float free = smooth((t - MichaelKill.L_RAISE) / 8f);
		m.leftArm.xRot = Mth.lerp(free, -1.6f, -0.15f);
		m.leftArm.yRot = Mth.lerp(free, 0.3f, 0f);
		// A lunge into it.
		float lunge = smooth((t - MichaelKill.L_COCK) / 3f) * (1f - smooth((t - MichaelKill.L_RAISE) / 10f));
		m.rightLeg.xRot = -0.35f * lunge;
		m.leftLeg.xRot = 0.2f * lunge;
		// Looking at them, up at them held high, the head tilting; then after the body.
		float up = smooth((t - MichaelKill.L_RAISE) / 20f) * (1f - smooth((t - MichaelKill.L_FLING) / 6f));
		m.head.xRot = 0.1f - 0.55f * up + 0.4f * smooth((t - MichaelKill.L_FLING - 2) / 8f);
		m.head.zRot = 0.42f * smooth((t - MichaelKill.L_HIGH - 2) / 14f) * (1f - smooth((t - MichaelKill.L_FLING) / 8f)) + 0.15f * smooth((t - MichaelKill.L_LAND) / 10f);
		m.head.yRot = -0.5f * smooth((t - MichaelKill.L_FLING) / 8f);
	}

	private static void liftThem(HumanoidModel<?> m, float t, float now) {
		float a = Mth.sin(now * 1.3f);
		float l = Mth.sin(now * 1.6f);
		if (t < MichaelKill.L_STAB) {
			m.rightArm.xRot = -2.1f + a * 0.3f;
			m.leftArm.xRot = -2.1f - a * 0.3f;
			m.rightArm.yRot = -0.45f;
			m.leftArm.yRot = 0.45f;
			m.rightArm.zRot = 0.3f;
			m.leftArm.zRot = -0.3f;
			m.rightLeg.xRot = l * 0.6f;
			m.leftLeg.xRot = -l * 0.6f;
			m.head.xRot = -0.6f + Mth.cos(now * 1.1f) * 0.06f;
			return;
		}
		float j = jolt(t, MichaelKill.L_STAB) + 0.6f * jolt(t, 18);
		if (t < MichaelKill.L_RAISE) {
			// Doubled over it, hands to the belly.
			m.rightArm.xRot = -0.65f + a * 0.06f - 0.3f * j;
			m.leftArm.xRot = -0.65f - a * 0.06f - 0.3f * j;
			m.rightArm.yRot = -0.6f;
			m.leftArm.yRot = 0.6f;
			m.rightLeg.xRot = -0.4f * j + l * 0.15f;
			m.leftLeg.xRot = -0.3f * j - l * 0.15f;
			m.head.xRot = 0.45f + 0.3f * j;
			return;
		}
		if (t < MichaelKill.L_FLING) {
			// Up on the knife: clawing at his arm, kicking; weaker; then hanging over the blade, the head back.
			float weak = smooth((t - MichaelKill.L_HIGH + 4) / 16f);
			float life = 1f - weak;
			m.rightArm.xRot = Mth.lerp(weak, -1.05f + a * 0.2f, 0.1f);
			m.leftArm.xRot = Mth.lerp(weak, -1.05f - a * 0.2f, 0.15f);
			m.rightArm.yRot = Mth.lerp(weak, -0.4f, 0f);
			m.leftArm.yRot = Mth.lerp(weak, 0.4f, 0f);
			m.rightArm.zRot = Mth.lerp(weak, 0f, 0.35f);
			m.leftArm.zRot = Mth.lerp(weak, 0f, -0.35f);
			m.rightLeg.xRot = l * 0.6f * life + 0.1f * weak;
			m.leftLeg.xRot = -l * 0.55f * life + 0.05f * weak;
			m.head.xRot = Mth.lerp(weak, -0.45f + Mth.sin(now * 0.9f) * 0.1f, -0.65f);
			m.head.zRot = 0.2f * weak;
			return;
		}
		// Flung off: slack, flung wide.
		m.rightArm.xRot = -0.3f;
		m.leftArm.xRot = -0.2f;
		m.rightArm.zRot = 0.9f;
		m.leftArm.zRot = -0.9f;
		m.rightLeg.xRot = 0.15f;
		m.leftLeg.xRot = -0.1f;
		m.head.xRot = -0.3f;
		m.head.zRot = 0.3f;
	}
}
