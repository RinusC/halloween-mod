package dev.halloween.client;

import dev.halloween.net.HwNet;
import java.util.Set;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;

/**
 * The Demogorgon on all fours (art/demogorgon.bbmodel, made by tools/demogorgon_model.pl), moved here rather than by
 * a player's pose. He crawls on spider legs, the diagonal pairs together, the spine rolling; breathes, his head
 * twitching and jerking now and then, the petals of it stirring. Striking, he rears, claws high, the petals flung
 * open on the teeth, and rakes down. Tearing a way into the Upside Down (HwNet.Anim.RIFT, five seconds) he stands
 * up on his hind legs clawing at the air, the head thrown back and shaking. Coming down from a ceiling
 * (HwNet.Anim.EMERGE) his legs are splayed, the petals open (the drop itself: MichaelRenderer.emerge).
 */
final class DemogorgonModel extends Model<AvatarRenderState> {
	/** The petals' turn about their own base: closed (tips together) and wide open. */
	private static final float CLOSED = 0.1f;
	private static final float OPEN = -1.05f;

	private final ModelPart torso;
	private final ModelPart neck;
	private final ModelPart head;
	private final ModelPart fr;
	private final ModelPart frLow;
	private final ModelPart fl;
	private final ModelPart flLow;
	private final ModelPart hr;
	private final ModelPart hrLow;
	private final ModelPart hl;
	private final ModelPart hlLow;
	private final ModelPart[] petals = new ModelPart[5];

	private DemogorgonModel(BbModel bb) {
		super(bb.root, RenderTypes::entityCutout);
		this.torso = bb.part("torso");
		this.neck = bb.part("neck");
		this.head = bb.part("head");
		this.fr = bb.part("front_right");
		this.frLow = bb.part("front_right_lower");
		this.fl = bb.part("front_left");
		this.flLow = bb.part("front_left_lower");
		this.hr = bb.part("hind_right");
		this.hrLow = bb.part("hind_right_lower");
		this.hl = bb.part("hind_left");
		this.hlLow = bb.part("hind_left_lower");
		for (int k = 0; k < 5; k++) {
			this.petals[k] = bb.part("petal" + k);
		}
	}

	static DemogorgonModel load() {
		return new DemogorgonModel(BbModel.load("/assets/halloween/models/entity/demogorgon.json", Set.of()));
	}

	@Override
	public void setupAnim(AvatarRenderState s) {
		this.resetPose();
		int id = ((HwRenderState) s).halloween$entityId();
		float t = s.ageInTicks;
		float speed = Mth.clamp(s.walkAnimationSpeed * 1.4f, 0f, 1f);
		float p = s.walkAnimationPos * 0.85f;

		// The crawl: front right with hind left, front left with hind right.
		swing(this.fr, this.frLow, p, speed);
		swing(this.hl, this.hlLow, p, speed);
		swing(this.fl, this.flLow, p + Mth.PI, speed);
		swing(this.hr, this.hrLow, p + Mth.PI, speed);
		this.torso.y += Math.abs(Mth.sin(p)) * 0.8f * speed + Mth.sin(t * 0.12f) * 0.25f;
		this.torso.yRot += Mth.sin(p) * 0.09f * speed;
		this.torso.zRot += Mth.cos(p) * 0.06f * speed;

		// The head where he looks; it twitches, and now and then jerks in a spasm.
		float yaw = Mth.clamp(s.yRot, -70f, 70f) * Mth.DEG_TO_RAD;
		float pitch = Mth.clamp(s.xRot, -60f, 60f) * Mth.DEG_TO_RAD;
		this.neck.yRot += yaw * 0.5f;
		this.head.yRot += yaw * 0.5f;
		this.neck.xRot += pitch * 0.4f;
		this.head.xRot += pitch * 0.4f;
		boolean spasm = Math.floorMod((int) (t / 31f), 4) == 0;
		this.head.zRot += Mth.sin(t * 0.07f) * 0.08f + (spasm ? Mth.sin(t * 2.6f) * 0.16f : 0f);
		this.head.xRot += spasm ? Mth.cos(t * 3.1f) * 0.08f : 0f;
		float open = 0.08f + 0.06f * Mth.sin(t * 0.21f) + (spasm ? 0.12f : 0f);

		if (s.attackTime > 0f) {
			// Rears up, both claws high, then rakes them down; the petals open on the teeth.
			float a = Mth.clamp(s.attackTime, 0f, 1f);
			float e = Mth.sin(a * Mth.PI);
			this.torso.xRot -= 0.55f * e;
			this.hr.xRot += 0.55f * e;
			this.hl.xRot += 0.55f * e;
			float claw = a < 0.5f ? -2.3f * (a / 0.5f) : -2.3f + 2.7f * ((a - 0.5f) / 0.5f);
			this.fr.xRot += claw;
			this.fl.xRot += claw * 0.85f;
			this.head.xRot -= 0.4f * e;
			open = Math.max(open, e);
		}

		float rift = ClientAnims.demoTime(id, HwNet.Anim.RIFT);
		if (rift >= 0f) {
			// Up on his hind legs tearing the air open, the head back and shaking.
			float in = Mth.clamp(rift / 15f, 0f, 1f);
			float up = in * in * (3f - 2f * in);
			this.torso.xRot -= 1.05f * up;
			this.hr.xRot += 1.05f * up;
			this.hl.xRot += 1.05f * up;
			this.fr.xRot += up * (-2.4f + Mth.sin(rift * 0.55f) * 0.75f);
			this.fl.xRot += up * (-2.4f + Mth.sin(rift * 0.55f + Mth.PI) * 0.75f);
			this.frLow.xRot += up * 0.7f * Mth.sin(rift * 0.55f);
			this.flLow.xRot += up * 0.7f * Mth.sin(rift * 0.55f + Mth.PI);
			this.fr.zRot -= up * 0.25f;
			this.fl.zRot += up * 0.25f;
			this.head.xRot -= 0.65f * up;
			this.head.zRot += Mth.sin(rift * 1.7f) * 0.18f * up;
			open = Math.max(open, up);
		}

		float emerge = ClientAnims.demoTime(id, HwNet.Anim.EMERGE);
		if (emerge >= 0f && emerge < 30f) {
			// Dropping from the ceiling: legs flung wide, petals open, closing as he lands and crouches.
			float k = 1f - emerge / 30f;
			this.fr.zRot -= 0.7f * k;
			this.fl.zRot += 0.7f * k;
			this.hr.zRot -= 0.6f * k;
			this.hl.zRot += 0.6f * k;
			this.torso.y += 1.5f * k;
			open = Math.max(open, k);
		}

		float petal = Mth.lerp(Mth.clamp(open, 0f, 1f), CLOSED, OPEN);
		for (ModelPart part : this.petals) {
			if (part != null) {
				part.xRot = petal;
			}
		}
	}

	/** A leg swinging with the gait, its shank lifted as it comes forward. */
	private static void swing(ModelPart leg, ModelPart lower, float p, float speed) {
		if (leg == null || lower == null) {
			return;
		}
		leg.xRot += Mth.sin(p) * 0.55f * speed;
		lower.xRot += Math.max(0f, -Mth.cos(p)) * 0.7f * speed;
	}
}
