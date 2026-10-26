package dev.halloween.client;

import dev.halloween.net.HwNet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;

/**
 * Vecna (art/vecna.bbmodel, made by tools/vecna_model.pl): a skeleton of his own, and he floats. Hanging in the air
 * when still (float), drifting forwards when he moves (glide), weighted by his speed. While he aims, throws or draws
 * in the Grip (HwNet.Anim.VECNA_CAST) his left arm is thrust out, clawed and trembling (cast, laid over); a blow is the
 * right arm thrust out and a lash shooting from the palm (strike). The head where he looks, a jerk of it now and then (twitch), the jaw working. Holding
 * someone he has them by the throat in his left hand, the right claw raised over them (throttle), and drives it in to
 * kill (rend); hit or shaken off he reels back (recoil). In a fall his arms, legs and head take the player model's pose
 * (ClientAnims), still floating.
 */
public final class VecnaModel extends Model<AvatarRenderState> {
	private static final class State {
		float cast;
		float castSince = -100;
		boolean casting;
		float strikeStart = -100;
		float lastAttack;
	}

	private static final Map<Integer, State> STATES = new HashMap<>();
	/** How high he floats (blocks, before his size), for the camera. */
	public static final float LIFT = 5.3f / 16f;

	final BbModel bb;
	/** The player model whose pose he takes in a grab (the renderer's own). */
	HumanoidModel<?> source;

	private VecnaModel(BbModel bb) {
		super(bb.root, RenderTypes::entityCutout);
		this.bb = bb;
	}

	static VecnaModel load() {
		return new VecnaModel(BbModel.load("/assets/halloween/models/entity/vecna.json", Set.of()));
	}

	/** For tests: a blow from this one now (a mannequin's swing does not play on the client). */
	public static void testStrike(int id) {
		STATES.computeIfAbsent(id, k -> new State()).strikeStart = now();
	}

	public static void clear() {
		STATES.clear();
	}

	private static float now() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? 0 : mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0f, 1f);
		return x * x * (3 - 2 * x);
	}

	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public void setupAnim(AvatarRenderState s) {
		this.resetPose();
		// The lash in his palm: nothing until he strikes.
		this.lash(0f);
		int id = ((HwRenderState) s).halloween$entityId();
		float now = now();
		State st = STATES.computeIfAbsent(id, k -> new State());
		float t = s.ageInTicks;
		VecnaKillScene.Act act = VecnaKillScene.act(id);
		if (act != null) {
			// One of his kills (VecnaKillScene): hanging in the air, the scene's moves laid over; where his back and
			// hands are noted for the vines.
			this.bb.apply("float", t / 20f, 1f);
			this.bb.apply(act.anim(), act.seconds(), 1f);
			this.anchors(id);
			return;
		}
		float speed = Mth.clamp(s.walkAnimationSpeed * 1.8f, 0f, 1f);
		this.bb.apply("float", t / 20f, 1f - speed);
		this.bb.apply("glide", t / 20f, speed);

		// The Grip: the arm thrust out while he aims, throws and draws them in; eased in and out.
		boolean casting = ClientAnims.hold(id) == HwNet.Anim.VECNA_CAST;
		if (casting != st.casting) {
			st.casting = casting;
			st.castSince = now;
		}
		float k = smooth((now - st.castSince) / 5f);
		st.cast = casting ? k : 1f - k;
		if (st.cast > 0.001f) {
			this.bb.apply("cast", t / 20f, st.cast);
		}

		int hold = ClientAnims.hold(id);
		int once = ClientAnims.once(id);
		if (hold == HwNet.Anim.HOLD || hold == HwNet.Anim.STRUGGLE || hold == HwNet.Anim.LOCKER_HOLD || isClaw(once)) {
			// By the throat in his left hand, the right claw over them; driven in when he kills.
			this.bb.apply("throttle", t / 20f, 1f);
			if (isClaw(once)) {
				this.bb.apply("rend", ClientAnims.onceTime(id) / 20f * 0.6f / Math.max(0.3f, onceSeconds(once)), 1f);
			}
			return;
		}
		if (once == HwNet.Anim.STAGGER || once == HwNet.Anim.STABBED) {
			// Hit, or shaken off: he reels back.
			this.bb.apply("recoil", Math.min(0.8f, ClientAnims.onceTime(id) / 20f), 1f);
			this.look(s, 0f);
			return;
		}
		boolean busy = ClientAnims.busy(id) && !casting;
		if (busy && this.source != null) {
			// A grab, a kill, a fall: his arms, legs and head as the player model has them.
			((HumanoidModel) this.source).setupAnim(s);
			take("right_upper_arm", this.source.rightArm);
			take("left_upper_arm", this.source.leftArm);
			take("right_thigh", this.source.rightLeg);
			take("left_thigh", this.source.leftLeg);
			take("head", this.source.head);
			this.bb.apply("jaw", 0.55f + 0.15f * Mth.sin(t * 0.3f), 1f);
			return;
		}

		// A blow: the right claw raking across.
		if (s.attackTime > 0f && st.lastAttack <= 0f) {
			st.strikeStart = now;
		}
		st.lastAttack = s.attackTime;
		float a = (now - st.strikeStart) / 20f;
		if (a >= 0f && a < 0.55f) {
			this.bb.apply("strike", a, 1f);
			// Out of the palm in a tenth of a second, held while it whips, drawn back in.
			float out = a < 0.1f ? a / 0.1f : a < 0.38f ? 1f : 1f - (a - 0.38f) / 0.17f;
			this.lash(smooth(out));
		}

		// Now and then the head jerks.
		int cycle = 140 + Math.floorMod(id * 31, 80);
		float phase = Math.floorMod((int) t + id * 17, cycle) + (t - (int) t);
		if (phase < 28f && st.cast < 0.5f) {
			this.bb.apply("twitch", phase / 20f, Mth.sin(phase / 28f * Mth.PI));
		}
		this.look(s, st.cast);
	}

	/** Where the vines come out of him as he is posed now: between and on his shoulder blades, his palms (and along his fingers, for the way out). */
	private void anchors(int id) {
		if (!VecnaKillScene.wantsAnchors(id)) {
			return;
		}
		org.joml.Vector3f[] p = new org.joml.Vector3f[7];
		p[VecnaKillScene.BACK_L] = this.bb.locate("chest", 1.6f, -3.8f, 1.9f);
		p[VecnaKillScene.BACK_C] = this.bb.locate("chest", 0f, -3.0f, 2.1f);
		p[VecnaKillScene.BACK_R] = this.bb.locate("chest", -1.6f, -3.8f, 1.9f);
		p[VecnaKillScene.PALM_R] = this.bb.locate("right_hand", 0f, 1.0f, 0.3f);
		p[VecnaKillScene.FINGERS_R] = this.bb.locate("right_hand", 0f, 4.5f, 0.3f);
		p[VecnaKillScene.PALM_L] = this.bb.locate("left_hand", 0f, 1.0f, 0.3f);
		p[VecnaKillScene.FINGERS_L] = this.bb.locate("left_hand", 0f, 4.5f, 0.3f);
		VecnaKillScene.anchors(id, p);
	}

	/** How far the lash is out of his palm (0 hidden, 1 its full length). */
	private void lash(float out) {
		ModelPart p = this.bb.part("lash_0");
		if (p != null) {
			p.xScale = out;
			p.zScale = out;
			p.yScale = out;
			p.visible = out > 0.01f;
		}
	}

	/** The quick moves of a knife-killer that he makes with his claw instead. */
	private static boolean isClaw(int once) {
		return once == HwNet.Anim.STAB || once == HwNet.Anim.KNIFE_DOWN || once == HwNet.Anim.LOCKER_STAB || once == HwNet.Anim.REACH;
	}

	/** How long that move lasts on the knife-killer (seconds; see ClientAnims). */
	private static float onceSeconds(int once) {
		return switch (once) {
			case HwNet.Anim.KNIFE_DOWN -> 0.4f;
			case HwNet.Anim.LOCKER_STAB -> 0.9f;
			default -> 0.6f;
		};
	}

	/** The head where he looks: some in the neck, the rest in the head. */
	private void look(AvatarRenderState s, float cast) {
		float yaw = Mth.clamp(s.yRot, -70f, 70f) * Mth.DEG_TO_RAD;
		float pitch = Mth.clamp(s.xRot, -55f, 55f) * Mth.DEG_TO_RAD;
		ModelPart n = this.bb.part("neck");
		ModelPart h = this.bb.part("head");
		if (n != null) {
			n.yRot += yaw * 0.35f;
			n.xRot += pitch * 0.3f;
		}
		if (h != null) {
			h.yRot += yaw * 0.65f;
			h.xRot += pitch * 0.6f;
		}
	}

	private void take(String bone, ModelPart from) {
		ModelPart to = this.bb.part(bone);
		if (to != null) {
			to.xRot = from.xRot;
			to.yRot = from.yRot;
			to.zRot = from.zRot;
		}
	}
}
