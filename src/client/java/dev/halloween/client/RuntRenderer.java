package dev.halloween.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.halloween.Halloween;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;

/**
 * The Runt drawn as the creature made in Blockbench (models/entity/runt.json) instead of a person: it idles,
 * crawls when it moves, lunges with its jaw and tongue while winding up a pounce or strangling someone, and
 * lies twisted when knocked down.
 */
public final class RuntRenderer {
	private static final Identifier TEXTURE = Halloween.id("textures/entity/runt.png");
	/** The creature is made big in Blockbench: this is how much of it the Runt is. */
	public static float size = 0.45f;
	/** Where he hangs on someone's back (Blockbench units, before he is turned upright): up, and back off them. */
	public static float clingUp = 25f;
	public static float clingBack = -14f;
	/** For tests: draw him as a person again. */
	public static boolean enabled = true;
	private static RuntModel model;

	private RuntRenderer() {
	}

	static final class RuntModel extends Model<AvatarRenderState> {
		final BbModel bb;

		RuntModel(BbModel bb) {
			super(bb.root, RenderTypes::entityCutout);
			this.bb = bb;
		}

		void cling(float seconds) {
			clingPose(this.bb, seconds);
		}

		@Override
		public void setupAnim(AvatarRenderState s) {
			this.resetPose();
			int id = ((HwRenderState) s).halloween$entityId();
			float seconds = s.ageInTicks / 20f;
			float fall = ClientAnims.fallAngle(id);
			if (fall > 0f) {
				// Knocked down: twisted, limbs everywhere.
				this.bb.apply("animation", 0f, Math.min(1f, fall / 60f));
				return;
			}
			if (ClientAnims.hold(id) == HwNet.Anim.STRANGLE) {
				this.cling(seconds);
				return;
			}
			BbModel.Animation lunge = this.bb.animation("animation2");
			if (lunge != null && s.hasPose(Pose.CROUCHING)) {
				// Winding up a pounce: jaw and tongue going.
				this.bb.apply("idle", seconds, 1f);
				this.bb.apply("animation2", seconds % lunge.length(), 1f);
				return;
			}
			float walk = Mth.clamp(s.walkAnimationSpeed * 2.5f, 0f, 1f);
			this.bb.apply("idle", seconds, 1f - walk);
			this.bb.apply("walk", s.walkAnimationPos * 0.12f, walk);
		}
	}

	/**
	 * On someone's back: hanging upright against them, belly to their back, every limb round them. The arms go
	 * round their chest and cross in front, the legs round their waist, the long neck bends over their shoulder
	 * so the head hangs in front of their face, mouth working; the arms squeeze in turns, the head shakes.
	 * (Degrees as in Blockbench: the creature lies along -z, belly down; it is turned up by the base bone.)
	 */
	private static void clingPose(BbModel bb, float t) {
		// The face from the idle pose (mouth and tongue as they rest), the rest of the body all ours.
		bb.apply("idle", t, 1f);
		for (String bone : new String[]{"base", "waist", "body", "neck3", "neck2", "neck", "head", "L_arm", "L_lowerarm", "R_arm", "R_lowerarm",
			"L_leg", "L_lowerleg", "L_foot", "R_leg", "R_lowerleg", "R_foot"}) {
			bb.reset(bone);
		}
		float squeeze = (float) Math.sin(t * 5f) * 6f;
		float shake = (float) Math.sin(t * 7.5f);
		// Up on end, belly against their back.
		bb.move("base", 0f, clingUp, clingBack);
		bb.turn("base", 90f, 0f, shake * 2.5f);
		// The neck over their shoulder, the head hanging down in front of their face, shaking.
		bb.turn("neck3", -25f, 0f, 0f);
		bb.turn("neck2", -30f, 4f, 0f);
		bb.turn("neck", -30f, 4f, 0f);
		bb.turn("head", -25f, shake * 10f, 0f);
		bb.turn("jaw", -12f - 10f * (0.5f + 0.5f * (float) Math.sin(t * 9f)), 0f, 0f);
		// Arms drawn in (they are longer than anyone is wide) and round their chest, crossing in front,
		// squeezing in turns.
		bb.scale("L_arm", 0.55f);
		bb.scale("R_arm", 0.55f);
		bb.turn("L_arm", 0f, -10f, 75f + squeeze);
		bb.turn("L_lowerarm", 0f, 0f, 105f + squeeze);
		bb.turn("R_arm", 0f, 10f, -75f - squeeze);
		bb.turn("R_lowerarm", 0f, 0f, -105f - squeeze);
		for (String f : new String[]{"middle", "pointer", "pinky"}) {
			bb.turn("L_" + f + "_finger", 0f, 0f, 40f);
			bb.turn("R_" + f + "_finger", 0f, 0f, -40f);
		}
		// Legs round their waist.
		bb.turn("L_leg", 0f, 0f, -55f);
		bb.turn("L_lowerleg", 0f, 0f, 110f);
		bb.turn("L_foot", 0f, 0f, 30f);
		bb.turn("R_leg", 0f, 0f, 55f);
		bb.turn("R_lowerleg", 0f, 0f, -110f);
		bb.turn("R_foot", 0f, 0f, -30f);
	}

	private static RuntModel model() {
		if (model == null) {
			model = new RuntModel(BbModel.load("/assets/halloween/models/entity/runt.json", Set.of("earg")));
		}
		return model;
	}

	/** Is this the Runt (his role, or his skin)? */
	public static boolean isRunt(LivingEntityRenderState state) {
		Minecraft mc = Minecraft.getInstance();
		Entity e = mc.level == null ? null : mc.level.getEntity(((HwRenderState) state).halloween$entityId());
		return e != null && ClientState.ROLES.get(e.getUUID()) == Role.RUNT;
	}

	/** Draws the Runt as the creature. True if it was him (the person's model is then not drawn). */
	public static boolean submit(LivingEntityRenderState state, PoseStack pose, SubmitNodeCollector collector) {
		if (!enabled || !(state instanceof AvatarRenderState avatar) || !isRunt(state)) {
			return false;
		}
		if (state.isInvisible) {
			return true;
		}
		RuntModel m = model();
		pose.pushPose();
		pose.mulPose(Axis.YP.rotationDegrees(180f - state.bodyRot));
		pose.scale(size, size, size);
		pose.scale(-1f, -1f, 1f);
		pose.translate(0f, -1.501f, 0f);
		// His own body, seen from behind his back while he rides someone, is see-through: it would fill his view.
		Minecraft mc = Minecraft.getInstance();
		boolean own = mc.player != null && mc.player.getId() == ((HwRenderState) state).halloween$entityId() && !mc.options.getCameraType().isFirstPerson();
		collector.submitModel(m, avatar, pose, own ? RenderTypes.entityTranslucent(TEXTURE) : m.renderType(TEXTURE), state.lightCoords,
			LivingEntityRenderer.getOverlayCoords(state, 0f), own ? 0x50FFFFFF : -1, null, state.outlineColor, null);
		pose.popPose();
		return true;
	}
}
