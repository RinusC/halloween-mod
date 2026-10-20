package dev.halloween.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.halloween.Halloween;
import dev.halloween.Role;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

/**
 * Michael drawn as the model made for him in Blockbench (art/michael.bbmodel: models/entity/michael.json) instead
 * of a player with his skin. The model has a player's bones, so the pose the game works out for the player model
 * (looking about, the grabs, the knife coming down, falling) is copied onto it, and the knife, drawn on the
 * player model's hand, stays in his. Taller than everyone (to look at only: his hitbox and eyes are a player's).
 * His own walk: the model's "walk" in place of the swinging one, the knife hand hanging, the knife held point
 * down, and coming down from overhead when he strikes.
 */
public final class MichaelRenderer {
	private static final Identifier TEXTURE = Halloween.id("textures/entity/michael.png");
	/** The Demogorgon, when he is this match's killer (art/demogorgon.bbmodel, a skeleton of his own: see DemogorgonModel). */
	private static final Identifier DEMO_TEXTURE = Halloween.id("textures/entity/demogorgon.png");
	/** 2.1 blocks tall instead of 1.8. */
	public static final float TALL = 2.1f / 1.8f;
	/** How much of the arm's raise his wrist takes back, so the knife's point stays down (see mixin.ItemInHandLayerMixin). */
	public static final float WRIST = 0.85f;
	/** For tests: draw him as a player with his skin again. */
	public static boolean enabled = true;
	private static MichaelModel model;
	private static DemogorgonModel demoModel;
	/** For tests: draw this match's killer as the Demogorgon whoever he is. */
	public static boolean forceDemo;

	private MichaelRenderer() {
	}

	static final class MichaelModel extends Model<AvatarRenderState> {
		final BbModel bb;
		/** The player model whose pose he takes (the renderer's own, the one his knife is drawn on). */
		HumanoidModel<?> source;

		MichaelModel(BbModel bb) {
			super(bb.root, RenderTypes::entityCutout);
			this.bb = bb;
		}

		@Override
		@SuppressWarnings({"unchecked", "rawtypes"})
		public void setupAnim(AvatarRenderState s) {
			this.resetPose();
			if (this.source == null) {
				return;
			}
			((HumanoidModel) this.source).setupAnim(s);
			copy("head", this.source.head);
			copy("body", this.source.body);
			copy("right_arm", this.source.rightArm);
			copy("left_arm", this.source.leftArm);
			copy("right_leg", this.source.rightLeg);
			copy("left_leg", this.source.leftLeg);
		}

		private void copy(String bone, ModelPart from) {
			ModelPart to = this.bb.part(bone);
			if (to != null) {
				to.loadPose(from.storePose());
			}
		}
	}

	private static DemogorgonModel demoModel() {
		if (demoModel == null) {
			demoModel = DemogorgonModel.load();
		}
		return demoModel;
	}

	private static MichaelModel model() {
		if (model == null) {
			model = new MichaelModel(BbModel.load("/assets/halloween/models/entity/michael.json", Set.of()));
		}
		return model;
	}

	/** Is this match's killer the Demogorgon? */
	public static boolean demogorgon() {
		return forceDemo || ClientState.killer == 1;
	}

	/** Is this match's killer Vecna? (Drawn as Michael until he has his own model.) */
	public static boolean vecna() {
		return ClientState.killer == 2;
	}

	/** Is this entity Michael (in a match)? */
	public static boolean isMichael(Entity e) {
		if (!enabled || e == null) {
			return false;
		}
		if (HeroScene.isGhost(e)) {
			// Him, standing behind a survivor in their opening scene.
			return true;
		}
		Role role = ClientState.ROLES.get(e.getUUID());
		// The masks event: every survivor wears his face.
		return role == Role.SHAPE || role != null && role.survivor() && ClientEvents.masks() && e.isAlive();
	}

	public static boolean isMichael(EntityRenderState state) {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && isMichael(mc.level.getEntity(((HwRenderState) state).halloween$entityId()));
	}

	/**
	 * His walk and his knife hand, over the player model's own pose (before the grabs are laid on: see
	 * mixin.PlayerModelMixin). Done on the player model itself so that the knife follows.
	 */
	public static void pose(HumanoidModel<?> m, AvatarRenderState s) {
		if (!enabled || !isMichael(s)) {
			return;
		}
		if (demogorgon()) {
			// His own skeleton and animations (DemogorgonModel); the player model keeps its pose for his grabs.
			return;
		}
		// No swinging walk: legs and arms from rest, then his heavy one laid on.
		m.rightLeg.xRot = 0f;
		m.leftLeg.xRot = 0f;
		// The knife hand hangs at his side, a little out; the other arm loose.
		m.rightArm.xRot = s.isCrouching ? 0.32f : -0.08f;
		m.rightArm.yRot = 0f;
		m.rightArm.zRot = 0.07f;
		m.leftArm.xRot = s.isCrouching ? 0.4f : 0f;
		m.leftArm.yRot = 0f;
		m.leftArm.zRot = -0.05f;
		BbModel.Animation walk = model().bb.animation("walk");
		if (walk != null) {
			float weight = Mth.clamp(s.walkAnimationSpeed * 1.5f, 0f, 1f);
			// One stride of the player's walk (cos(pos * 0.6662)) is one loop of his.
			float seconds = s.walkAnimationPos * 0.6662f / Mth.TWO_PI * walk.length();
			model().bb.applyTo("walk", seconds, weight, Map.of("head", m.head, "body", m.body, "right_arm", m.rightArm, "left_arm", m.leftArm,
				"right_leg", m.rightLeg, "left_leg", m.leftLeg));
		}
		if (s.attackTime > 0f) {
			// The knife up over his head, then down hard, point first.
			float a = s.attackTime;
			float up = Math.min(1f, a / 0.35f);
			float down = a < 0.35f ? 0f : Math.min(1f, (a - 0.35f) / 0.2f);
			float back = a < 0.7f ? 0f : (a - 0.7f) / 0.3f;
			float x = Mth.lerp(up, -0.08f, -2.9f);
			x = Mth.lerp(down, x, -0.55f);
			x = Mth.lerp(back, x, -0.08f);
			m.rightArm.xRot = x;
			m.rightArm.yRot = -0.15f * (up - back);
			m.rightArm.zRot = 0.05f;
			// The player's swing turns the body and moves the shoulders with it: not his.
			m.body.yRot = 0f;
			m.rightArm.x = -5f;
			m.rightArm.z = 0f;
			m.leftArm.x = 5f;
			m.leftArm.z = 0f;
		}
	}

	/**
	 * Draws him in place of the player model (from mixin.LivingEntityRendererMixin, where the renderer submits its
	 * model). True if it was him; the renderer's layers (the knife in his hand) are drawn as ever.
	 */
	public static boolean submit(SubmitNodeCollector collector, Model<?> playerModel, Object state, PoseStack pose, int light, int overlay, int tint,
		int outline) {
		if (!enabled || !(state instanceof AvatarRenderState avatar) || !(playerModel instanceof HumanoidModel<?> humanoid) || !isMichael(avatar)) {
			return false;
		}
		Model<AvatarRenderState> m;
		if (demogorgon()) {
			demoModel().source = humanoid;
			m = demoModel();
		} else {
			model().source = humanoid;
			m = model();
		}
		// As the player model would be: whole; see-through (invisible, to those who see the invisible); or only his
		// outline (invisible but glowing).
		Identifier texture = demogorgon() ? DEMO_TEXTURE : TEXTURE;
		net.minecraft.client.renderer.rendertype.RenderType type = tint != -1 ? RenderTypes.entityTranslucentCullItemTarget(texture)
			: avatar.isInvisible ? RenderTypes.outline(texture) : RenderTypes.entityCutout(texture);
		collector.submitModel(m, avatar, pose, type, light, overlay, tint, null, outline, null);
		return true;
	}
}
