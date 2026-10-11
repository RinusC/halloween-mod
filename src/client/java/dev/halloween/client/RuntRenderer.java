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
	/** Smaller on someone's back: hands round their throat, not swallowing them whole. */
	public static float ridingSize = 0.28f;
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
			BbModel.Animation lunge = this.bb.animation("animation2");
			if (lunge != null && (ClientAnims.hold(id) == HwNet.Anim.STRANGLE || s.hasPose(Pose.CROUCHING))) {
				// At someone's throat, or winding up a pounce: jaw and tongue going.
				this.bb.apply("idle", seconds, 1f);
				this.bb.apply("animation2", seconds % lunge.length(), 1f);
				return;
			}
			float walk = Mth.clamp(s.walkAnimationSpeed * 2.5f, 0f, 1f);
			this.bb.apply("idle", seconds, 1f - walk);
			this.bb.apply("walk", s.walkAnimationPos * 0.12f, walk);
		}
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
		float s = ClientAnims.hold(((HwRenderState) state).halloween$entityId()) == HwNet.Anim.STRANGLE ? ridingSize : size;
		pose.scale(s, s, s);
		pose.scale(-1f, -1f, 1f);
		pose.translate(0f, -1.501f, 0f);
		collector.submitModel(m, avatar, pose, m.renderType(TEXTURE), state.lightCoords, LivingEntityRenderer.getOverlayCoords(state, 0f), -1, null,
			state.outlineColor, null);
		pose.popPose();
		return true;
	}
}
