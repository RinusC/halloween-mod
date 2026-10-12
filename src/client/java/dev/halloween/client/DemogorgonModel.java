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
 * The Demogorgon (art/demogorgon.bbmodel, made by tools/demogorgon_model.pl): a skeleton of his own, moved by the
 * model's animations blended by weight. Upright (idle, walk) while nobody is about; down on all fours (fours_idle,
 * crawl) while he sees a survivor or saw one in the last ten seconds; coiled (charge) and flung forwards (lunge) in
 * a Shred, the petals of his head opening as he charges and wide in the lunge. A blow rakes down with both claws
 * (strike, strike_fours over whichever stance), the petals flung open. Now and then a spasm (twitch); the head
 * where he looks. In a grab, a kill, a fall he stands and his limbs take the player model's pose (ClientAnims).
 * How he carries himself comes from the server (HwNet.DemoPose) and is eased from one stance to the next.
 */
public final class DemogorgonModel extends Model<AvatarRenderState> {
	/** One stance's weight easing towards 0 or 1 since it last changed. */
	private static final class Ease {
		float from;
		float target;
		float since;

		float at(float now, float in, float out) {
			float k = Mth.clamp((now - this.since) / (this.target > this.from ? in : out), 0f, 1f);
			return Mth.lerp(k * k * (3 - 2 * k), this.from, this.target);
		}

		void set(float target, float now, float in, float out) {
			if (target != this.target) {
				this.from = this.at(now, in, out);
				this.target = target;
				this.since = now;
			}
		}
	}

	private static final class Carriage {
		int pose = HwNet.DemoPose.UPRIGHT;
		final Ease four = new Ease();
		final Ease charge = new Ease();
		final Ease lunge = new Ease();
		float chargeStart;
		float lungeStart;
	}

	private static final Map<Integer, Carriage> CARRIAGE = new HashMap<>();
	/** For tests: every Demogorgon in this pose (-1: as the server says). */
	public static int forcedPose = -1;
	/** Camera: how far above (standing) and below (on all fours) a player's eyes his are, in blocks. */
	private static final float LIFT_UP = 0.45f;
	private static final float LIFT_DOWN = -0.45f;

	final BbModel bb;
	/** The player model whose pose he takes in a grab (the renderer's own). */
	HumanoidModel<?> source;

	private DemogorgonModel(BbModel bb) {
		super(bb.root, RenderTypes::entityCutout);
		this.bb = bb;
	}

	static DemogorgonModel load() {
		return new DemogorgonModel(BbModel.load("/assets/halloween/models/entity/demogorgon.json", Set.of()));
	}

	public static void receive(HwNet.DemoPose payload) {
		carriage(payload.entityId()).pose = payload.pose();
	}

	public static void clear() {
		CARRIAGE.clear();
	}

	private static Carriage carriage(int id) {
		return CARRIAGE.computeIfAbsent(id, k -> new Carriage());
	}

	private static float now() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? 0 : mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}

	/** Brings an entity's stances up to date (eased towards what its pose asks for). */
	private static Carriage update(int id, float now) {
		Carriage c = carriage(id);
		int pose = forcedPose >= 0 ? forcedPose : c.pose;
		boolean charging = pose == HwNet.DemoPose.CHARGING;
		boolean lunging = pose == HwNet.DemoPose.LUNGING;
		if (charging && c.charge.target == 0f) {
			c.chargeStart = now;
		}
		if (lunging && c.lunge.target == 0f) {
			c.lungeStart = now;
		}
		// Down quickly when he spots someone, back up slowly.
		c.four.set(pose >= HwNet.DemoPose.HUNTING ? 1f : 0f, now, 7f, 16f);
		c.charge.set(charging ? 1f : 0f, now, 5f, 4f);
		c.lunge.set(lunging ? 1f : 0f, now, 1.5f, 7f);
		return c;
	}

	/** How much lower (negative) or higher his eyes are than a player's: on all fours lower. */
	public static float cameraLift(int id) {
		float four = update(id, now()).four.at(now(), 7f, 16f);
		return Mth.lerp(four, LIFT_UP, LIFT_DOWN);
	}

	/** In first person only the drop on all fours. */
	public static float firstPersonDrop(int id) {
		float four = update(id, now()).four.at(now(), 7f, 16f);
		return four * LIFT_DOWN;
	}

	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public void setupAnim(AvatarRenderState s) {
		this.resetPose();
		int id = ((HwRenderState) s).halloween$entityId();
		float now = now();
		Carriage c = update(id, now);
		float t = s.ageInTicks;
		// Over someone thrown down, tearing at them (the kill's bites too): on all fours, a stance of its own.
		boolean devour = ClientAnims.hold(id) == HwNet.Anim.DEVOUR;
		boolean busy = ClientAnims.busy(id) && !devour;
		float four = busy ? 0f : devour ? 1f : c.four.at(now, 7f, 16f);
		float charge = busy || devour ? 0f : c.charge.at(now, 5f, 4f);
		float lunge = busy || devour ? 0f : c.lunge.at(now, 1.5f, 7f);
		float speed = Mth.clamp(s.walkAnimationSpeed * 1.6f, 0f, 1f);
		// One loop of a gait per stride of the player's walk (cos(pos * 0.6662)); on all fours a quicker step.
		float stride = s.walkAnimationPos * 0.6662f / Mth.TWO_PI;

		// The whole-body stances, weighted to add up to one.
		float wLunge = four * lunge;
		float wCharge = four * charge * (1f - lunge);
		float wFours = devour ? 0f : Math.max(0f, four - wLunge - wCharge);
		float wStand = 1f - four;
		BbModel.Animation walk = this.bb.animation("walk");
		BbModel.Animation crawl = this.bb.animation("crawl");
		this.bb.apply("idle", t / 20f, wStand * (1f - speed));
		this.bb.apply("walk", stride * walk.length(), wStand * speed);
		this.bb.apply("fours_idle", t / 20f, wFours * (1f - speed));
		this.bb.apply("crawl", stride * crawl.length(), wFours * speed);
		this.bb.apply("charge", t / 20f, wCharge);
		this.bb.apply("lunge", (now - c.lungeStart) / 20f, wLunge);

		// A blow: both claws raking down (rearing up on all fours), the petals flung open.
		float open = 0.04f + 0.03f * Mth.sin(t * 0.09f);
		if (devour) {
			this.bb.apply("devour", t / 20f, 1f);
			// The mouth wide, gaping wider as the head plunges; each blow of the kill a bite.
			open = 0.8f + 0.2f * Mth.sin(t * 0.63f);
			if (ClientAnims.once(id) == HwNet.Anim.KNIFE_DOWN) {
				this.bb.apply("bite", ClientAnims.onceTime(id) / 20f, 1f);
				open = 1f;
			}
		}
		if (s.attackTime > 0f && !devour) {
			float a = Mth.clamp(s.attackTime, 0f, 1f);
			this.bb.apply("strike", a * 0.5f, 1f - four);
			this.bb.apply("strike_fours", a * 0.5f, four);
			open = Math.max(open, Mth.sin(Math.min(1f, a * 1.5f) * Mth.PI));
		}
		// Charging: the bud parting wider and wider, quivering; the lunge: wide open.
		if (wCharge > 0f) {
			float k = Mth.clamp((now - c.chargeStart) / 20f, 0f, 1f);
			open = Math.max(open, wCharge * (0.25f + 0.55f * k + 0.06f * Mth.sin(t * 2.3f)));
		}
		open = Math.max(open, lunge);

		// Now and then a spasm: the head jerking, the petals twitching.
		int cycle = 110 + Math.floorMod(id * 37, 60);
		float phase = Math.floorMod((int) t + id * 13, cycle) + (t - (int) t);
		if (phase < 32f && lunge < 0.5f && !busy && !devour) {
			float w = Mth.sin(phase / 32f * Mth.PI);
			this.bb.apply("twitch", phase / 20f, w);
			open = Math.max(open, 0.18f * w + 0.08f * Mth.sin(t * 3.1f) * w);
		}

		if (busy && this.source != null) {
			// A grab, a kill, a fall: his arms, legs and head as the player model has them.
			((HumanoidModel) this.source).setupAnim(s);
			take("right_upper_arm", this.source.rightArm);
			take("left_upper_arm", this.source.leftArm);
			take("right_thigh", this.source.rightLeg);
			take("left_thigh", this.source.leftLeg);
			take("head", this.source.head);
			ModelPart hips = this.bb.part("hips");
			if (hips != null) {
				hips.yRot += this.source.body.yRot;
			}
			if (ClientAnims.hold(id) != HwNet.Anim.NONE) {
				open = Math.max(open, 0.45f + 0.1f * Mth.sin(t * 0.4f));
			}
		} else if (!devour) {
			this.look(s, four, lunge);
		}
		this.bb.apply("mouth", Mth.clamp(open, 0f, 1f), 1f);
	}

	/**
	 * The head where he looks: half in the neck, the rest in the head. A turn about the upright is laid on each as
	 * turns about its parent's own axes (on all fours the neck juts forwards, the head is level).
	 */
	private void look(AvatarRenderState s, float four, float lunge) {
		float yaw = Mth.clamp(s.yRot, -75f, 75f) * Mth.DEG_TO_RAD * (1f - 0.7f * lunge);
		float pitch = Mth.clamp(s.xRot, -60f, 60f) * Mth.DEG_TO_RAD * (1f - 0.7f * lunge);
		float chest = Mth.lerp(four, -13f, -83f) * Mth.DEG_TO_RAD;
		float neck = Mth.lerp(four, -5f, -37f) * Mth.DEG_TO_RAD;
		ModelPart n = this.bb.part("neck");
		ModelPart h = this.bb.part("head");
		if (n != null) {
			n.yRot += yaw * 0.4f * Mth.cos(chest);
			n.zRot += yaw * 0.4f * Mth.sin(chest);
			n.xRot += pitch * 0.35f;
		}
		if (h != null) {
			h.yRot += yaw * 0.6f * Mth.cos(neck);
			h.zRot += yaw * 0.6f * Mth.sin(neck);
			h.xRot += pitch * 0.55f;
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
