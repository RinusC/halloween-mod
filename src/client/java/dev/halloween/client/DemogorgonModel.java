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
		/** Sinking into the Upside Down, climbing out of it: since when; the pose seen last. */
		float sinkStart;
		float emergeStart;
		int lastPose = -1;
		/** A blow: when it began (a swing seen starting), so it plays over its own time, not the swing's few ticks. */
		float strikeStart = -100;
		float lastAttack;
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
		if (pose != c.lastPose) {
			if (pose == HwNet.DemoPose.SINKING) {
				c.sinkStart = now;
			} else if (pose == HwNet.DemoPose.EMERGING) {
				c.emergeStart = now;
			}
			c.lastPose = pose;
		}
		boolean crossing = pose == HwNet.DemoPose.SINKING || pose == HwNet.DemoPose.EMERGING;
		// Down quickly when he spots someone, back up slowly (sinking and climbing out he stands).
		c.four.set(pose >= HwNet.DemoPose.HUNTING && !crossing ? 1f : 0f, now, crossing ? 1f : 7f, crossing ? 1f : 16f);
		c.charge.set(charging ? 1f : 0f, now, 5f, 4f);
		c.lunge.set(lunging ? 1f : 0f, now, 1.5f, 7f);
		return c;
	}

	/** How far down on all fours his eyes are (0 standing, 1 down): standing at a wardrobe. */
	private static float eyesDown(int id) {
		if (ClientAnims.once(id) == HwNet.Anim.DEMO_LOCKER || ClientAnims.once(id) == HwNet.Anim.DEMO_ROAR || ClientAnims.once(id) == HwNet.Anim.DEMO_SEARCH) {
			return 0f;
		}
		return update(id, now()).four.at(now(), 7f, 16f);
	}

	/** How much lower (negative) or higher his eyes are than a player's: on all fours lower. */
	public static float cameraLift(int id) {
		float four = eyesDown(id);
		return Mth.lerp(four, LIFT_UP, LIFT_DOWN);
	}

	/** In first person only the drop on all fours. */
	public static float firstPersonDrop(int id) {
		float four = eyesDown(id);
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
		// Going for a grab: no standing up and reaching like a man; he springs at them on all fours.
		boolean reach = ClientAnims.once(id) == HwNet.Anim.REACH;
		float pounceT = reach ? Mth.clamp(ClientAnims.onceTime(id) / 12f, 0f, 1f) : 0f;
		float pounce = reach ? Mth.sin(pounceT * Mth.PI) : 0f;
		// At a wardrobe (roaring at it, a claw through it): standing, a scene of its own.
		boolean atLocker = ClientAnims.once(id) == HwNet.Anim.DEMO_LOCKER;
		// His roar (the Impulse): standing too.
		boolean roaring = ClientAnims.once(id) == HwNet.Anim.DEMO_ROAR;
		atLocker |= roaring;
		boolean searching = ClientAnims.once(id) == HwNet.Anim.DEMO_SEARCH;
		atLocker |= searching;
		boolean busy = ClientAnims.busy(id) && !devour && !reach && !atLocker;
		float four = busy || atLocker ? 0f : devour ? 1f : Math.max(pounce, c.four.at(now, 7f, 16f));
		float charge = busy || devour ? 0f : c.charge.at(now, 5f, 4f) * (1f - pounce);
		float lunge = busy || devour ? 0f : Math.max(pounce, c.lunge.at(now, 1.5f, 7f));
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
		this.bb.apply("lunge", pounce > 0f ? pounceT * 0.4f : (now - c.lungeStart) / 20f, wLunge);

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
		if (s.attackTime > 0f && c.lastAttack <= 0f) {
			c.strikeStart = now;
		}
		c.lastAttack = s.attackTime;
		float a = (now - c.strikeStart) / 11f;
		if (a >= 0f && a < 1f && !devour && !busy) {
			open = Math.max(open, four > 0.5f ? this.bite(a, four) : this.swipe(a, 1f - four));
		}
		if (pounce > 0f) {
			open = Math.max(open, pounce);
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
		if (atLocker) {
			open = Math.max(open, roaring ? this.roar(ClientAnims.onceTime(id), t) : searching ? this.searchLocker(ClientAnims.onceTime(id), t)
				: this.atLocker(ClientAnims.onceTime(id), t));
		}
		open = Math.max(open, this.crossing(c, now, t));
		this.bb.apply("mouth", Mth.clamp(open, 0f, 1f), 1f);
	}

	/**
	 * Standing, a blow: the right claw drawn up and back over the shoulder, the body twisting away, then swept down
	 * and across, the body turning into it, the left arm thrown back for balance; the arms leave the gait for it.
	 * Returns how far the mouth opens.
	 */
	private float swipe(float a, float w) {
		float take = Mth.clamp(Mth.sin(a * Mth.PI) * 2f, 0f, 1f) * w;
		for (String side : new String[]{"right", "left"}) {
			for (String bone : new String[]{"_shoulder", "_upper_arm", "_forearm", "_hand"}) {
				ModelPart p = this.bb.part(side + bone);
				if (p != null) {
					p.xRot = Mth.lerp(take, p.xRot, p.getInitialPose().xRot());
					p.yRot = Mth.lerp(take, p.yRot, p.getInitialPose().yRot());
					p.zRot = Mth.lerp(take, p.zRot, p.getInitialPose().zRot());
				}
			}
		}
		// Wind-up (to 0.4), the sweep (to 0.6), the follow-through settling back.
		float up = a < 0.4f ? smooth(a / 0.4f) : 1f;
		float sweep = a < 0.4f ? 0f : smooth(Math.min(1f, (a - 0.4f) / 0.2f));
		float back = a < 0.65f ? 0f : smooth((a - 0.65f) / 0.35f);
		float arm = (1f - back) * w;
		// Blockbench degrees: x swings a hanging arm forwards (up), z out to its side (+ his right).
		this.bb.turn("right_upper_arm", (150f * up - 110f * sweep) * arm, 0f, (35f * up - 75f * sweep) * arm);
		this.bb.turn("right_forearm", (40f * up - 30f * sweep) * arm, 0f, 0f);
		this.bb.turn("right_hand", -20f * sweep * arm, 0f, 0f);
		this.bb.turn("left_upper_arm", (-25f * up + 15f * sweep) * arm, 0f, -15f * arm);
		this.bb.turn("chest", (6f * up - 14f * sweep) * arm, (-22f * up + 45f * sweep) * arm, 0f);
		this.bb.turn("waist", 0f, (-10f * up + 18f * sweep) * arm, 0f);
		this.bb.turn("head", -18f * sweep * arm, (12f * up - 20f * sweep) * arm, 0f);
		for (int k = 0; k < 3; k++) {
			this.bb.turn("right_finger" + k, (-20f * up + 45f * sweep) * arm, 0f, (k - 1) * 14f * up * arm);
			this.bb.turn("right_finger" + k + "_tip", 30f * sweep * arm, 0f, 0f);
		}
		return Mth.sin(Math.min(1f, a * 1.4f) * Mth.PI) * 0.8f * w;
	}

	/**
	 * On all fours, a blow is a bite: the body thrown forwards, the neck stretched out, the head snapping forward
	 * with the petals flung wide, then shut on what is there. Returns how far the mouth opens.
	 */
	private float bite(float a, float w) {
		float k = Mth.sin(a * Mth.PI) * w;
		this.bb.move("hips", 0f, 0f, -3.5f * k);
		this.bb.turn("chest", -8f * k, 0f, 0f);
		this.bb.turn("neck", -22f * k, 0f, 0f);
		this.bb.turn("head", -16f * k + (a > 0.55f ? 10f * Mth.sin((a - 0.55f) / 0.45f * Mth.PI) * w : 0f), 0f, 0f);
		for (String side : new String[]{"right", "left"}) {
			this.bb.turn(side + "_upper_arm", 10f * k, 0f, 0f);
		}
		// Wide on the way in, snapped shut at the end.
		return a < 0.5f ? smooth(a / 0.5f) * w : (1f - smooth((a - 0.5f) / 0.25f)) * w;
	}

	/**
	 * At a wardrobe: roaring at it, the neck thrust out and the mouth gaping wide; then the right claw driven
	 * straight through it, raised with what it caught on it, swung down to fling them off, and drawn back. Returns how
	 * far the mouth is open.
	 */
	/**
	 * Searching a wardrobe: the claw drawn back bent, driven in straight through the door and held there, still, while he
	 * growls at it, the head thrust at the door and the petals open; then torn out. Returns how far the mouth is open.
	 */
	private float searchLocker(float s, float t) {
		float cock = smooth(s / 6f) * (1f - smooth((s - 7f) / 2.5f));
		float in = smooth((s - 7f) / 3f) * (1f - smooth((s - 30f) / 5f));
		float growl = smooth((s - 9f) / 4f) * (1f - smooth((s - 28f) / 6f));
		float shake = Mth.sin(t * 3.1f) * growl;
		this.bb.turn("right_upper_arm", 55f * cock + 60f * in, 0f, 20f * cock);
		this.bb.turn("right_forearm", 110f * cock - 4f * in, 0f, 0f);
		this.bb.turn("right_hand", 30f * cock - 15f * in, 0f, 0f);
		this.bb.turn("chest", -8f * cock + 12f * in, -14f * cock, 2f * shake);
		this.bb.turn("neck", -20f * growl, 0f, 0f);
		this.bb.turn("head", -12f * growl + 4f * shake, 0f, 0f);
		for (int k = 0; k < 3; k++) {
			this.bb.turn("right_finger" + k, -30f * in, 0f, 0f);
		}
		return Math.max(0.15f * in, growl * (0.75f + 0.1f * Mth.sin(t * 2.3f)));
	}

	/** The roar of his Impulse (three seconds): rearing, the neck thrust out, the petals flung wide, all of him shaking. */
	private float roar(float s, float t) {
		float roar = smooth(s / 5f) * (1f - smooth((s - 50f) / 10f));
		float shake = Mth.sin(t * 2.9f) * roar;
		this.bb.turn("chest", -14f * roar, 0f, 3f * shake);
		this.bb.turn("neck", -28f * roar, 0f, 0f);
		this.bb.turn("head", -18f * roar + 5f * shake, 6f * Mth.sin(t * 1.7f) * roar, 0f);
		this.bb.turn("right_upper_arm", 25f * roar, 0f, 40f * roar);
		this.bb.turn("left_upper_arm", 25f * roar, 0f, -40f * roar);
		this.bb.turn("right_forearm", 50f * roar, 0f, 0f);
		this.bb.turn("left_forearm", 50f * roar, 0f, 0f);
		return roar;
	}

	private float atLocker(float s, float t) {
		float roar = smooth(s / 6f) * (1f - smooth((s - 23f) / 6f));
		// The roar: the neck thrust out at the wardrobe, the petals flung wide open, the head shaking with it.
		this.bb.turn("chest", -6f * roar, 0f, 0f);
		this.bb.turn("neck", -24f * roar, 0f, 0f);
		this.bb.turn("head", (-14f + 4f * Mth.sin(t * 2.9f)) * roar, 5f * Mth.sin(t * 1.7f) * roar, 0f);
		this.bb.turn("right_upper_arm", 12f * roar, 0f, 0f);
		this.bb.turn("left_upper_arm", 12f * roar, 0f, 0f);
		float thrust = smooth((s - 27f) / 3f) * (1f - smooth((s - 56f) / 8f));
		float lift = smooth((s - 34f) / 14f);
		float fling = smooth((s - 50f) / 5f);
		this.bb.turn("right_upper_arm", thrust * (95f + 30f * lift - 105f * fling), 0f, 0f);
		// Drawn back first, bent hard at the elbow, the claw at his shoulder; then shot out straight, and once in, bent
		// back the other way (the arm arching, the hand thrown back) as it bears the weight.
		float cock = smooth((s - 19f) / 6f) * (1f - smooth((s - 27f) / 2.5f));
		float arch = thrust * (1f - fling);
		this.bb.turn("right_upper_arm", 55f * cock, 0f, 20f * cock);
		this.bb.turn("right_forearm", 110f * cock - 28f * arch, 0f, 0f);
		this.bb.turn("right_hand", 30f * cock - 32f * arch, 0f, 0f);
		this.bb.turn("chest", -8f * cock, -14f * cock, 0f);
		this.bb.turn("chest", 12f * thrust * (1f - lift * 0.5f), 0f, 0f);
		this.bb.turn("left_upper_arm", 20f * thrust, 0f, -20f * thrust);
		for (int k = 0; k < 3; k++) {
			this.bb.turn("right_finger" + k, -30f * thrust, 0f, 0f);
		}
		return Math.max(roar, 0.5f * thrust);
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0f, 1f);
		return x * x * (3 - 2 * x);
	}

	/**
	 * Into the ground and out of it: sinking, he goes down to the shoulders and under, claws up, the head thrown
	 * back; climbing out (five seconds) the claws come first, tearing at the air, then the head and the rest, the
	 * mouth wide all the while. Returns how far the mouth is open.
	 */
	private float crossing(Carriage c, float now, float t) {
		int pose = forcedPose >= 0 ? forcedPose : c.pose;
		float depth;
		float claw;
		if (pose == HwNet.DemoPose.SINKING) {
			float k = Mth.clamp((now - c.sinkStart) / 30f, 0f, 1f);
			depth = k * k * 46f;
			claw = Math.min(1f, k * 2f);
		} else if (pose == HwNet.DemoPose.EMERGING) {
			float k = Mth.clamp((now - c.emergeStart) / 100f, 0f, 1f);
			float up = Mth.clamp((k - 0.1f) / 0.75f, 0f, 1f);
			depth = (1f - up * up * (3 - 2 * up)) * 46f;
			claw = k < 0.9f ? 1f : (1f - k) * 10f;
		} else {
			return 0f;
		}
		ModelPart hips = this.bb.part("hips");
		if (hips != null) {
			hips.y += depth;
		}
		// Claws up, raking at the air in turn; the head back, shaking.
		for (String side : new String[]{"right", "left"}) {
			ModelPart arm = this.bb.part(side + "_upper_arm");
			ModelPart fore = this.bb.part(side + "_forearm");
			float ph = side.equals("right") ? 0f : Mth.PI;
			if (arm != null) {
				arm.xRot += claw * (-2.7f + 0.35f * Mth.sin(t * 0.7f + ph));
				arm.zRot += claw * (side.equals("right") ? -0.3f : 0.3f);
			}
			if (fore != null) {
				fore.xRot += claw * (-0.4f + 0.4f * Mth.sin(t * 0.7f + ph + 1f));
			}
		}
		ModelPart head = this.bb.part("head");
		if (head != null) {
			head.xRot += -0.5f * claw;
			head.zRot += 0.2f * Mth.sin(t * 1.9f) * claw;
		}
		return 0.6f + 0.35f * Mth.sin(t * 0.5f) * 0.5f + 0.3f * claw;
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
