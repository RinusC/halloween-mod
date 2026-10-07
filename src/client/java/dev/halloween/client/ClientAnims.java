package dev.halloween.client;

import dev.halloween.net.HwNet;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.util.Mth;

/**
 * Grab animations, laid over the usual pose of a person (players and townsfolk): the Shape reaching for someone,
 * holding them by the throat with the knife raised and stabbing down, staggering back when they tear free,
 * clutching the wound when stabbed; the victim clawing at his arm with legs kicking and head thrown back, pushing
 * off, stabbing him. The server says who does what ({@link HwNet.Anim}); held poses last until it says otherwise,
 * one-off moves play out over their own few ticks.
 */
public final class ClientAnims {
	private static final class State {
		int hold;
		int once;
		float onceStart;
	}

	private static final Map<Integer, State> STATES = new HashMap<>();
	/** For tests: what each entity was last told to do. */
	public static final Map<Integer, Integer> LAST = new HashMap<>();

	private ClientAnims() {
	}

	public static void receive(HwNet.Anim payload) {
		State s = STATES.computeIfAbsent(payload.entityId(), k -> new State());
		LAST.put(payload.entityId(), payload.kind());
		switch (payload.kind()) {
			case HwNet.Anim.NONE -> {
				s.hold = HwNet.Anim.NONE;
				s.once = HwNet.Anim.NONE;
			}
			case HwNet.Anim.HOLD, HwNet.Anim.STRUGGLE -> s.hold = payload.kind();
			case HwNet.Anim.REACH, HwNet.Anim.KNIFE_DOWN -> {
				s.once = payload.kind();
				s.onceStart = now();
			}
			default -> {
				// Torn free or stabbed: the hold is over.
				s.hold = HwNet.Anim.NONE;
				s.once = payload.kind();
				s.onceStart = now();
			}
		}
	}

	public static void clear() {
		STATES.clear();
	}

	private static float now() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? 0 : mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}

	private static float duration(int kind) {
		return switch (kind) {
			case HwNet.Anim.REACH, HwNet.Anim.BREAK_FREE, HwNet.Anim.STAB -> 12;
			case HwNet.Anim.STAGGER -> 16;
			case HwNet.Anim.STABBED -> 18;
			case HwNet.Anim.KNIFE_DOWN -> 8;
			default -> 0;
		};
	}

	/** Up to full over {@code in} ticks, held for {@code hold}, back down over {@code out}. */
	private static float ease(float t, float in, float hold, float out) {
		if (t < in) {
			float k = t / in;
			return k * k * (3 - 2 * k);
		}
		return t < in + hold ? 1 : Math.max(0, 1 - (t - in - hold) / out);
	}

	/** Lays the entity's grab pose over the model's ordinary one. */
	public static void apply(HumanoidModel<?> m, int entityId) {
		State s = STATES.get(entityId);
		if (s == null || s.hold == HwNet.Anim.NONE && s.once == HwNet.Anim.NONE) {
			return;
		}
		float now = now();
		if (s.hold == HwNet.Anim.HOLD) {
			// One hand at the throat, the knife raised in the other.
			m.leftArm.xRot = -1.6f + Mth.sin(now * 0.35f) * 0.04f;
			m.leftArm.yRot = 0.3f;
			m.leftArm.zRot = 0;
			m.rightArm.xRot = -2.7f;
			m.rightArm.yRot = -0.2f;
			m.rightArm.zRot = 0.05f;
			m.head.xRot = 0.1f;
		} else if (s.hold == HwNet.Anim.STRUGGLE) {
			// Clawing at his arm, kicking, head thrown back, choking.
			float a = Mth.sin(now * 0.9f);
			float l = Mth.sin(now * 0.8f);
			m.rightArm.xRot = -2.0f + a * 0.25f;
			m.rightArm.yRot = -0.45f;
			m.rightArm.zRot = 0.35f;
			m.leftArm.xRot = -2.0f - a * 0.25f;
			m.leftArm.yRot = 0.45f;
			m.leftArm.zRot = -0.35f;
			m.rightLeg.xRot = l * 0.55f;
			m.leftLeg.xRot = -l * 0.55f;
			m.head.xRot = -0.45f + Mth.cos(now * 1.1f) * 0.08f;
			m.head.zRot = Mth.sin(now * 0.5f) * 0.12f;
		}
		if (s.once == HwNet.Anim.NONE) {
			return;
		}
		float t = now - s.onceStart;
		if (t > duration(s.once)) {
			s.once = HwNet.Anim.NONE;
			return;
		}
		switch (s.once) {
			case HwNet.Anim.REACH -> {
				// Lunging for someone, both hands out.
				float k = ease(t, 3, 5, 4);
				m.rightArm.xRot = Mth.lerp(k, m.rightArm.xRot, -1.7f);
				m.leftArm.xRot = Mth.lerp(k, m.leftArm.xRot, -1.7f);
				m.rightArm.yRot = Mth.lerp(k, m.rightArm.yRot, -0.12f);
				m.leftArm.yRot = Mth.lerp(k, m.leftArm.yRot, 0.12f);
				m.head.xRot = Mth.lerp(k, m.head.xRot, 0.15f);
			}
			case HwNet.Anim.KNIFE_DOWN -> {
				// The knife comes down and goes up again.
				float k = t < 2 ? t / 2 : Math.max(0, 1 - (t - 2) / 6);
				m.rightArm.xRot = Mth.lerp(k, -2.9f, -0.7f);
			}
			case HwNet.Anim.BREAK_FREE -> {
				// Shoving him off with both hands.
				float k = ease(t, 2, 4, 6);
				m.rightArm.xRot = Mth.lerp(k, m.rightArm.xRot, -1.45f);
				m.leftArm.xRot = Mth.lerp(k, m.leftArm.xRot, -1.45f);
				m.rightArm.yRot = Mth.lerp(k, m.rightArm.yRot, 0);
				m.leftArm.yRot = Mth.lerp(k, m.leftArm.yRot, 0);
				m.head.xRot = Mth.lerp(k, m.head.xRot, 0.2f);
			}
			case HwNet.Anim.STAGGER -> {
				// Thrown back a step, arms flung out.
				float k = ease(t, 2, 6, 8);
				m.rightArm.xRot = Mth.lerp(k, m.rightArm.xRot, 0.4f);
				m.leftArm.xRot = Mth.lerp(k, m.leftArm.xRot, 0.4f);
				m.rightArm.zRot = Mth.lerp(k, m.rightArm.zRot, 0.7f);
				m.leftArm.zRot = Mth.lerp(k, m.leftArm.zRot, -0.7f);
				m.head.xRot = Mth.lerp(k, m.head.xRot, -0.35f);
			}
			case HwNet.Anim.STAB -> {
				// Up with the knife and into him.
				float x = t < 4 ? Mth.lerp(t / 4, -0.5f, -2.7f) : t < 6 ? Mth.lerp((t - 4) / 2, -2.7f, -1.0f) : -1.0f;
				float k = t > 8 ? Math.max(0, 1 - (t - 8) / 4) : 1;
				m.rightArm.xRot = Mth.lerp(k, m.rightArm.xRot, x);
				m.rightArm.yRot = Mth.lerp(k, m.rightArm.yRot, -0.25f);
				m.leftArm.xRot = Mth.lerp(k, m.leftArm.xRot, -1.2f);
			}
			case HwNet.Anim.STABBED -> {
				// A hand to the wound, head down: he lets go.
				float k = ease(t, 3, 9, 6);
				m.leftArm.xRot = Mth.lerp(k, m.leftArm.xRot, -1.1f);
				m.leftArm.yRot = Mth.lerp(k, m.leftArm.yRot, 0.7f);
				m.leftArm.zRot = Mth.lerp(k, m.leftArm.zRot, 0.2f);
				m.rightArm.xRot = Mth.lerp(k, m.rightArm.xRot, -0.2f);
				m.head.xRot = Mth.lerp(k, m.head.xRot, 0.45f);
			}
			default -> {
			}
		}
	}
}
