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
		/** Knocked down (FALL), getting up (RISE): when each began; fallStart below 0 means standing. */
		float fallStart = -1;
		float riseStart = -1;
		/** Down on their back (thrown down under the Demogorgon) rather than face down. */
		boolean onBack;
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
				s.fallStart = -1;
				s.riseStart = -1;
			}
			case HwNet.Anim.FALL -> {
				s.fallStart = now();
				s.riseStart = -1;
				s.onBack = false;
			}
			case HwNet.Anim.RISE -> {
				if (s.fallStart >= 0) {
					s.riseStart = now();
				}
			}
			case HwNet.Anim.HOLD, HwNet.Anim.STRUGGLE, HwNet.Anim.STRANGLE, HwNet.Anim.DEVOUR, HwNet.Anim.LOCKER_HOLD, HwNet.Anim.IMPALED, HwNet.Anim.VECNA_CAST -> s.hold = payload.kind();
			case HwNet.Anim.DEAD -> {
				// Stays down (flat at once if they were not), slack.
				s.hold = payload.kind();
				if (s.fallStart < 0) {
					s.fallStart = now() - 10;
				}
				s.riseStart = -1;
			}
			case HwNet.Anim.LIMP -> {
				// Carried, dead or dying: up off the floor, no longer lying.
				s.hold = payload.kind();
				s.once = HwNet.Anim.NONE;
				s.fallStart = -1;
				s.riseStart = -1;
				s.onBack = false;
			}
			case HwNet.Anim.PINNED -> {
				// Thrown down on their back.
				s.hold = payload.kind();
				s.fallStart = now();
				s.riseStart = -1;
				s.onBack = true;
			}
			case HwNet.Anim.REACH, HwNet.Anim.KNIFE_DOWN, HwNet.Anim.DEMO_TOSS -> {
				s.once = payload.kind();
				s.onceStart = now();
			}
			default -> {
				// Torn free or stabbed: the hold is over (off the ground again if they were pinned down).
				if (s.hold == HwNet.Anim.PINNED && s.fallStart >= 0) {
					s.riseStart = now();
				}
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
			case HwNet.Anim.LOCKER_STAB -> 18;
			case HwNet.Anim.LOCKER_SMASH -> 52;
			case HwNet.Anim.LOCKER_SMASHED -> 60;
			case HwNet.Anim.DEMO_LOCKER -> 64;
			case HwNet.Anim.LOCKER_SEARCH -> 40;
			case HwNet.Anim.DEMO_ROAR -> 60;
			case HwNet.Anim.DEMO_SEARCH -> 40;
			case HwNet.Anim.WINDOW_THROW, HwNet.Anim.DEMO_TOSS -> 30;
			case HwNet.Anim.KICK -> 20;
			case HwNet.Anim.PULL -> 13;
			default -> 0;
		};
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0f, 1f);
		return x * x * (3 - 2 * x);
	}

	/** Up to full over {@code in} ticks, held for {@code hold}, back down over {@code out}. */
	private static float ease(float t, float in, float hold, float out) {
		if (t < in) {
			float k = t / in;
			return k * k * (3 - 2 * k);
		}
		return t < in + hold ? 1 : Math.max(0, 1 - (t - in - hold) / out);
	}

	/** Is this entity in a grab animation, or down, right now (a held pose, a one-off move still playing, a fall)? */
	public static boolean busy(int entityId) {
		State s = STATES.get(entityId);
		if (s == null) {
			return false;
		}
		return s.hold != HwNet.Anim.NONE || s.fallStart >= 0 || s.once != HwNet.Anim.NONE && now() - s.onceStart <= duration(s.once);
	}

	/** Is this entity down on its back (see fallAngle), not on its face? */
	public static boolean onBack(int entityId) {
		State s = STATES.get(entityId);
		return s != null && s.onBack;
	}

	/** The one-off move this entity is playing (HwNet.Anim), or NONE. */
	public static int once(int entityId) {
		State s = STATES.get(entityId);
		return s == null || s.once == HwNet.Anim.NONE || now() - s.onceStart > duration(s.once) ? HwNet.Anim.NONE : s.once;
	}

	/** Ticks since its one-off move began. */
	public static float onceTime(int entityId) {
		State s = STATES.get(entityId);
		return s == null ? 0f : now() - s.onceStart;
	}

	/** The hold this entity is in (HwNet.Anim: HOLD, STRUGGLE, STRANGLE, PINNED, DEVOUR), or NONE. */
	public static int hold(int entityId) {
		State s = STATES.get(entityId);
		return s == null ? HwNet.Anim.NONE : s.hold;
	}

	/** How far over backwards this entity is (degrees): 90 lying flat, 0 standing. */
	public static float fallAngle(int entityId) {
		State s = STATES.get(entityId);
		if (s == null || s.fallStart < 0) {
			return 0f;
		}
		float now = now();
		if (s.riseStart >= 0) {
			float t = (now - s.riseStart) / 18f;
			if (t >= 1f) {
				s.fallStart = -1;
				s.riseStart = -1;
				return 0f;
			}
			// Up on an elbow, then onto the feet: slow at first.
			return 90f * (1f - t * t * (3 - 2 * t));
		}
		float t = Math.min(1f, (now - s.fallStart) / 7f);
		// Over like a felled tree, a little bounce at the end.
		float k = t * t;
		return t < 1f ? 90f * k : 90f - Math.max(0f, 4f - (now - s.fallStart - 7f)) * 1.5f;
	}

	/** Lays the entity's grab pose over the model's ordinary one. */
	public static void apply(HumanoidModel<?> m, int entityId) {
		if (VecnaDeathScene.pose(m, entityId)) {
			// Vecna taking them: lifted, the bones breaking.
			return;
		}
		if (HeroScene.pose(m, entityId)) {
			// In their opening scene, at what they do.
			return;
		}
		// Vecna coming into their eyes: the head jerks.
		VecnaClient.poseJolt(m, entityId);
		State s = STATES.get(entityId);
		if (s != null && s.hold == HwNet.Anim.PINNED) {
			// On their back under him (the body laid over by fallAngle): arms up pushing at him, legs kicking,
			// the head twisting away from his mouth.
			float now = now();
			float a = Mth.sin(now * 1.1f);
			float b = Mth.sin(now * 1.3f + 1f);
			m.rightArm.xRot = -1.5f + a * 0.3f;
			m.leftArm.xRot = -1.5f - a * 0.3f;
			m.rightArm.yRot = -0.25f;
			m.leftArm.yRot = 0.25f;
			m.rightArm.zRot = 0.25f + b * 0.15f;
			m.leftArm.zRot = -0.25f - b * 0.15f;
			m.rightLeg.xRot = -0.35f + Mth.sin(now * 0.9f) * 0.6f;
			m.leftLeg.xRot = -0.35f - Mth.sin(now * 0.9f) * 0.6f;
			m.rightLeg.zRot = 0.12f;
			m.leftLeg.zRot = -0.12f;
			m.head.yRot = Mth.sin(now * 0.6f) * 0.7f;
			m.head.xRot = -0.25f;
			return;
		}
		if (s != null && s.fallStart >= 0) {
			// Down: arms flung out, legs a little apart, head lolling.
			float k = fallAngle(entityId) / 90f;
			m.rightArm.zRot = Mth.lerp(k, m.rightArm.zRot, 1.1f);
			m.leftArm.zRot = Mth.lerp(k, m.leftArm.zRot, -1.1f);
			m.rightArm.xRot = Mth.lerp(k, m.rightArm.xRot, 0f);
			m.leftArm.xRot = Mth.lerp(k, m.leftArm.xRot, 0f);
			m.rightLeg.zRot = Mth.lerp(k, m.rightLeg.zRot, 0.15f);
			m.leftLeg.zRot = Mth.lerp(k, m.leftLeg.zRot, -0.15f);
			m.rightLeg.xRot = Mth.lerp(k, m.rightLeg.xRot, 0f);
			m.leftLeg.xRot = Mth.lerp(k, m.leftLeg.xRot, 0f);
			m.head.xRot = Mth.lerp(k, m.head.xRot, -0.3f);
			return;
		}
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
		} else if (s.hold == HwNet.Anim.VECNA_CAST) {
			// Vecna's Grip: the left hand thrust out at shoulder height, shaking with the strain; the right arm hangs.
			float shake = Mth.sin(now * 2.3f) * 0.035f + Mth.sin(now * 3.7f) * 0.02f;
			m.leftArm.xRot = -1.62f + shake;
			m.leftArm.yRot = 0.12f;
			m.leftArm.zRot = shake;
			m.rightArm.xRot = 0.1f;
			m.rightArm.yRot = 0;
			m.rightArm.zRot = 0.08f;
			m.head.xRot = 0.05f;
		} else if (s.hold == HwNet.Anim.LOCKER_HOLD) {
			// At a wardrobe: one hand at their throat pinning them to its door, the other braced on it above them.
			m.leftArm.xRot = -1.55f + Mth.sin(now * 0.35f) * 0.04f;
			m.leftArm.yRot = 0.3f;
			m.leftArm.zRot = 0;
			m.rightArm.xRot = -2.15f;
			m.rightArm.yRot = -0.4f;
			m.rightArm.zRot = 0;
			m.head.xRot = 0.15f;
		} else if (s.hold == HwNet.Anim.LIMP) {
			// Hanging from his hand or his jaws: arms and legs dangling, swinging a little, the head fallen forward.
			float sway = Mth.sin(now * 0.45f) * 0.12f;
			m.rightArm.xRot = 0.15f + sway;
			m.leftArm.xRot = 0.15f - sway;
			m.rightArm.yRot = 0;
			m.leftArm.yRot = 0;
			m.rightArm.zRot = 0.18f;
			m.leftArm.zRot = -0.18f;
			m.rightLeg.xRot = 0.12f + sway;
			m.leftLeg.xRot = -0.06f - sway;
			m.head.xRot = 0.75f;
			m.head.zRot = 0.2f;
		} else if (s.hold == HwNet.Anim.IMPALED) {
			// Run through on his claw and lifted: arms and legs hanging, jerking now and then, the head thrown back.
			float jerk = Mth.sin(now * 1.7f) * Mth.sin(now * 0.31f);
			m.rightArm.xRot = -0.45f + jerk * 0.25f;
			m.leftArm.xRot = -0.45f - jerk * 0.2f;
			m.rightArm.zRot = 0.35f;
			m.leftArm.zRot = -0.35f;
			m.rightLeg.xRot = 0.25f + jerk * 0.3f;
			m.leftLeg.xRot = -0.1f - jerk * 0.3f;
			m.head.xRot = -0.7f + jerk * 0.1f;
		} else if (s.hold == HwNet.Anim.STRANGLE) {
			// The Runt on someone's back: both hands round their throat, squeezing and shaking, legs locked round them.
			float shake = Mth.sin(now * 2.3f) * 0.07f;
			float squeeze = Mth.sin(now * 0.7f) * 0.06f;
			m.rightArm.xRot = -1.75f + shake;
			m.leftArm.xRot = -1.75f - shake;
			m.rightArm.yRot = -0.55f - squeeze;
			m.leftArm.yRot = 0.55f + squeeze;
			m.rightArm.zRot = 0.1f;
			m.leftArm.zRot = -0.1f;
			m.rightLeg.xRot = -1.3f;
			m.leftLeg.xRot = -1.3f;
			m.rightLeg.yRot = 0.45f;
			m.leftLeg.yRot = -0.45f;
			m.head.xRot = 0.35f + Mth.sin(now * 1.7f) * 0.05f;
			m.head.zRot = Mth.sin(now * 0.9f) * 0.12f;
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
			case HwNet.Anim.LOCKER_STAB -> {
				// The knife drawn back and driven into the wardrobe door, held there a moment.
				float back = t < 5 ? smooth(t / 5f) : 0f;
				float in = t < 5 ? 0f : smooth((t - 5) / 2f);
				float out = t < 13 ? 0f : smooth((t - 13) / 5f);
				m.rightArm.xRot = Mth.lerp(out, back * 0.8f + in * -1.55f, m.rightArm.xRot);
				m.rightArm.yRot = Mth.lerp(out, -0.15f * in, m.rightArm.yRot);
				m.leftArm.xRot = Mth.lerp(out, -0.5f * (back + in), m.leftArm.xRot);
				m.head.xRot = Mth.lerp(out, 0.15f * in, m.head.xRot);
			}
			case HwNet.Anim.WINDOW_THROW -> {
				// One hand at the throat lifts them high, slowly; then they are flung through the glass with a single
				// sweep of that arm, and it comes down. The knife hangs at his side, the head never turns.
				float up = smooth(t / 9f);
				float hurl = smooth((t - 9f) / 3f);
				float down = smooth((t - 14f) / 10f);
				m.leftArm.xRot = Mth.lerp(down, Mth.lerp(hurl, Mth.lerp(up, -1.6f, -2.75f), -1.15f), -0.1f);
				m.leftArm.yRot = Mth.lerp(down, 0.3f * (1f - hurl), 0f);
				m.leftArm.zRot = 0;
				m.rightArm.xRot = -0.15f;
				m.rightArm.yRot = 0;
				m.rightArm.zRot = 0.05f;
				m.head.xRot = Mth.lerp(up, 0.1f, -0.15f) + 0.25f * hurl * (1f - down);
			}
			case HwNet.Anim.LOCKER_SEARCH -> {
				// The door pulled open, a hand on the frame, the other rummaging inside, head in and looking about; the door pushed to.
				float open = smooth(t / 6f);
				float shut = smooth((t - 32f) / 6f);
				float in = smooth((t - 6f) / 4f) * (1f - shut);
				float w = 1f - smooth((t - 36f) / 4f);
				float pull = open * (1f - in) * (1f - shut);
				m.rightArm.xRot = Mth.lerp(w, m.rightArm.xRot, -1.5f * pull + (-1.35f + Mth.sin(t * 0.9f) * 0.25f) * in + -1.55f * shut);
				m.rightArm.yRot = Mth.lerp(w, m.rightArm.yRot, 0.35f * pull + Mth.sin(t * 0.5f) * 0.3f * in);
				m.leftArm.xRot = Mth.lerp(w, m.leftArm.xRot, -1.9f * in + -1.2f * pull);
				m.leftArm.yRot = Mth.lerp(w, m.leftArm.yRot, 0.55f * in);
				m.head.xRot = Mth.lerp(w, m.head.xRot, 0.35f * in);
				m.head.yRot = Mth.lerp(w, m.head.yRot, Mth.sin(t * 0.3f) * 0.6f * in);
			}
			case HwNet.Anim.LOCKER_SMASH -> {
				// Their head pulled forward and slammed back into the door, three times; then his knife pulled out of it.
				float x = -1.55f;
				float nod = 0f;
				for (int hit : new int[]{8, 20, 32}) {
					float d = t - hit;
					if (d >= -6 && d < 0) {
						x = Mth.lerp(smooth((d + 6) / 6f), -1.55f, -1.0f);
						nod = -0.15f * smooth((d + 6) / 6f);
					} else if (d >= 0 && d < 4) {
						x = Mth.lerp(d / 4f, -1.9f, -1.55f);
						nod = 0.3f * (1f - d / 4f);
					}
				}
				float let = t < 40 ? 0f : smooth((t - 40) / 10f);
				m.leftArm.xRot = Mth.lerp(let, x, -0.2f);
				m.leftArm.yRot = Mth.lerp(let, 0.3f, 0f);
				m.leftArm.zRot = 0;
				// The other hand on the wardrobe, then to the knife and back with it.
				float reach = t < 35 ? 0f : smooth((t - 35) / 5f);
				float pull = t < 40 ? 0f : smooth((t - 40) / 8f);
				m.rightArm.xRot = Mth.lerp(pull, Mth.lerp(reach, -2.15f, -1.5f), -0.3f);
				m.rightArm.yRot = Mth.lerp(pull, Mth.lerp(reach, -0.4f, -0.1f), 0f);
				m.rightArm.zRot = 0;
				m.head.xRot = 0.15f + nod;
			}
			case HwNet.Anim.LOCKER_SMASHED -> {
				// Clawing at his arm, the head snapping back into the door at each blow, going limp.
				float limp = Mth.clamp((t - 8) / 30f, 0f, 1f);
				float a = Mth.sin(now * 0.9f) * (1f - limp);
				m.rightArm.xRot = Mth.lerp(limp, -2.0f + a * 0.25f, -0.1f);
				m.leftArm.xRot = Mth.lerp(limp, -2.0f - a * 0.25f, -0.1f);
				m.rightArm.yRot = Mth.lerp(limp, -0.45f, 0f);
				m.leftArm.yRot = Mth.lerp(limp, 0.45f, 0f);
				m.rightArm.zRot = Mth.lerp(limp, 0.35f, 0.1f);
				m.leftArm.zRot = Mth.lerp(limp, -0.35f, -0.1f);
				m.rightLeg.xRot = Mth.lerp(limp, Mth.sin(now * 0.8f) * 0.5f, -0.1f);
				m.leftLeg.xRot = Mth.lerp(limp, -Mth.sin(now * 0.8f) * 0.5f, 0.05f);
				float head = Mth.lerp(limp, -0.35f, 0.55f);
				for (int hit : new int[]{8, 20, 32}) {
					float d = t - hit;
					if (d >= -5 && d < 0) {
						head += 0.35f * smooth((d + 5) / 5f);
					} else if (d >= 0 && d < 5) {
						head -= 0.8f * (1f - d / 5f);
					}
				}
				m.head.xRot = head;
				m.head.zRot = limp * 0.25f;
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
			case HwNet.Anim.KICK -> {
				// What lies across the way stamped apart: the knee up high, a stamp down through it, the head going
				// down with it; the knife arm out for balance.
				float up = smooth(t / 7f);
				float stamp = smooth((t - 7f) / 2.5f);
				float back = smooth((t - 12f) / 8f);
				float leg = Mth.lerp(stamp, Mth.lerp(up, 0f, -1.45f), -0.3f);
				m.rightLeg.xRot = Mth.lerp(back, leg, m.rightLeg.xRot);
				m.rightLeg.zRot = Mth.lerp(back, 0.06f * up, m.rightLeg.zRot);
				m.leftLeg.xRot = Mth.lerp(back, 0.15f * up * (1f - stamp), m.leftLeg.xRot);
				m.leftArm.xRot = Mth.lerp(back, Mth.lerp(up, m.leftArm.xRot, -0.7f) + 0.5f * stamp, m.leftArm.xRot);
				m.leftArm.zRot = Mth.lerp(back, -0.35f * up, m.leftArm.zRot);
				m.rightArm.zRot = Mth.lerp(back, 0.45f * up, m.rightArm.zRot);
				m.rightArm.xRot = Mth.lerp(back, Mth.lerp(up, m.rightArm.xRot, -0.35f), m.rightArm.xRot);
				m.head.xRot = Mth.lerp(back, 0.25f * up + 0.3f * stamp, m.head.xRot);
			}
			case HwNet.Anim.PULL -> {
				// An arm flung out to the side for it and yanked back hard as it goes over.
				float out = smooth(t / 4f);
				float yank = smooth((t - 4f) / 4f);
				float back = smooth((t - 9f) / 4f);
				m.rightArm.zRot = Mth.lerp(back, Mth.lerp(yank, Mth.lerp(out, m.rightArm.zRot, 1.25f), 0.5f), m.rightArm.zRot);
				m.rightArm.xRot = Mth.lerp(back, Mth.lerp(yank, Mth.lerp(out, m.rightArm.xRot, -0.9f), 0.9f), m.rightArm.xRot);
				m.head.yRot = Mth.lerp(back, m.head.yRot - 0.5f * out * (1f - yank), m.head.yRot);
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
