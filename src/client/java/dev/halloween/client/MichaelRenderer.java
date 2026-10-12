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
	/** The Demogorgon, when he is this match's killer (art/demogorgon.bbmodel, the same bones, a bud of petals for a head). */
	private static final Identifier DEMO_TEXTURE = Halloween.id("textures/entity/demogorgon.png");
	/** 2.1 blocks tall instead of 1.8. */
	public static final float TALL = 2.1f / 1.8f;
	/** How much of the arm's raise his wrist takes back, so the knife's point stays down (see mixin.ItemInHandLayerMixin). */
	public static final float WRIST = 0.85f;
	/** For tests: draw him as a player with his skin again. */
	public static boolean enabled = true;
	private static MichaelModel model;
	private static MichaelModel demoModel;

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
			if (this.bb.part("petal0") != null) {
				// The Demogorgon's head: a closed bud, flung open as he strikes.
				float open = s.attackTime > 0f ? Mth.sin(Math.min(1f, s.attackTime * 1.6f) * Mth.PI) : 0f;
				for (int k = 0; k < 5; k++) {
					ModelPart petal = this.bb.part("petal" + k);
					if (petal != null) {
						petal.xRot = Mth.lerp(open, -0.22f, 1.2f);
					}
				}
			}
		}

		private void copy(String bone, ModelPart from) {
			ModelPart to = this.bb.part(bone);
			if (to != null) {
				to.loadPose(from.storePose());
			}
		}
	}

	private static MichaelModel model() {
		if (demogorgon()) {
			if (demoModel == null) {
				demoModel = new MichaelModel(BbModel.load("/assets/halloween/models/entity/demogorgon.json", Set.of()));
			}
			return demoModel;
		}
		if (model == null) {
			model = new MichaelModel(BbModel.load("/assets/halloween/models/entity/michael.json", Set.of()));
		}
		return model;
	}

	/** Is this match's killer the Demogorgon? */
	public static boolean demogorgon() {
		return ClientState.killer == 1;
	}

	/** Is this entity Michael (in a match)? */
	public static boolean isMichael(Entity e) {
		return enabled && e != null && ClientState.ROLES.get(e.getUUID()) == Role.SHAPE;
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
			demoPose(m, s);
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

	/** The Demogorgon's stalking gait (his model's "walk"), long arms hanging forward; a strike rakes down with both. */
	private static void demoPose(HumanoidModel<?> m, AvatarRenderState s) {
		m.rightLeg.xRot = 0f;
		m.leftLeg.xRot = 0f;
		m.rightArm.xRot = -0.18f;
		m.rightArm.yRot = 0f;
		m.rightArm.zRot = 0.1f;
		m.leftArm.xRot = -0.18f;
		m.leftArm.yRot = 0f;
		m.leftArm.zRot = -0.1f;
		BbModel.Animation walk = model().bb.animation("walk");
		if (walk != null) {
			float weight = Mth.clamp(s.walkAnimationSpeed * 1.5f, 0f, 1f);
			float seconds = s.walkAnimationPos * 0.6662f / Mth.TWO_PI * walk.length();
			model().bb.applyTo("walk", seconds, weight, Map.of("head", m.head, "body", m.body, "right_arm", m.rightArm, "left_arm", m.leftArm,
				"right_leg", m.rightLeg, "left_leg", m.leftLeg));
		}
		if (s.attackTime > 0f) {
			// Both claws up over him, then raking down.
			float a = s.attackTime;
			float up = Math.min(1f, a / 0.3f);
			float down = a < 0.3f ? 0f : Math.min(1f, (a - 0.3f) / 0.25f);
			float x = Mth.lerp(down, Mth.lerp(up, -0.18f, -2.6f), -0.4f);
			m.rightArm.xRot = x;
			m.leftArm.xRot = x;
			m.rightArm.zRot = 0.25f * (1 - down);
			m.leftArm.zRot = -0.25f * (1 - down);
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
		MichaelModel m = model();
		m.source = humanoid;
		// As the player model would be: whole; see-through (invisible, to those who see the invisible); or only his
		// outline (invisible but glowing).
		Identifier texture = demogorgon() ? DEMO_TEXTURE : TEXTURE;
		net.minecraft.client.renderer.rendertype.RenderType type = tint != -1 ? RenderTypes.entityTranslucentCullItemTarget(texture)
			: avatar.isInvisible ? RenderTypes.outline(texture) : RenderTypes.entityCutout(texture);
		collector.submitModel(m, avatar, pose, type, light, overlay, tint, null, outline, null);
		return true;
	}
}
