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
 * in the Grip (HwNet.Anim.VECNA_CAST) his left arm is thrust out, clawed and trembling (cast, laid over); a blow is a
 * rake of the right claw (strike). The head where he looks, a jerk of it now and then (twitch), the jaw working. In a
 * grab, a kill, a fall his arms, legs and head take the player model's pose (ClientAnims), still floating.
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
		int id = ((HwRenderState) s).halloween$entityId();
		float now = now();
		State st = STATES.computeIfAbsent(id, k -> new State());
		float t = s.ageInTicks;
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
		}

		// Now and then the head jerks.
		int cycle = 140 + Math.floorMod(id * 31, 80);
		float phase = Math.floorMod((int) t + id * 17, cycle) + (t - (int) t);
		if (phase < 28f && st.cast < 0.5f) {
			this.bb.apply("twitch", phase / 20f, Mth.sin(phase / 28f * Mth.PI));
		}
		this.look(s, st.cast);
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
