package dev.halloween.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.halloween.Halloween;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.WolfRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

/**
 * The side job's dog (game.SideJobs) drawn as its own model (models/entity/dog.json, tools/dog_model.pl) in place of
 * a wolf: it stands tense, walks, gallops; while still on its chain it growls at anyone, and once free it growls and
 * barks when Michael is near. Every wolf in the game is that dog (there are no others).
 */
public final class DogRenderer {
	private static final Identifier TEXTURE = Halloween.id("textures/entity/dog.png");
	/** The model is built a little over life size (16 units a block): drawn at this, about 0.9 blocks at the shoulder. */
	public static float size = 0.82f;
	/** For tests: hold a gait (walk speed 0..1) or the growl, whatever the dog does; negative to leave it be. */
	public static float forceSpeed = -1f;
	public static float forceGrowl = -1f;
	public static boolean enabled = true;
	private static DogModel model;

	private DogRenderer() {
	}

	static final class DogModel extends Model<WolfRenderState> {
		final BbModel bb;

		DogModel(BbModel bb) {
			super(bb.root, RenderTypes::entityCutout);
			this.bb = bb;
		}

		@Override
		public void setupAnim(WolfRenderState s) {
			this.resetPose();
			float seconds = s.ageInTicks / 20f;
			float speed = forceSpeed >= 0 ? forceSpeed : Mth.clamp(s.walkAnimationSpeed * 1.6f, 0f, 1f);
			float run = Mth.clamp((speed - 0.55f) / 0.3f, 0f, 1f);
			float walk = Mth.clamp(speed * 3f, 0f, 1f) - run;
			this.bb.apply("idle", seconds, 1f - walk - run);
			float stride = forceSpeed >= 0 ? seconds : s.walkAnimationPos * 0.1f;
			this.bb.apply("walk", stride, walk);
			this.bb.apply("run", forceSpeed >= 0 ? seconds : s.walkAnimationPos * 0.07f, run);
			// Looking where it looks.
			this.bb.turn("head", Mth.clamp(-s.xRot, -30f, 25f), Mth.clamp(-s.yRot, -50f, 50f) * 0.7f, 0f);
			this.bb.turn("neck", 0f, Mth.clamp(-s.yRot, -50f, 50f) * 0.3f, 0f);
			float growl = forceGrowl >= 0 ? forceGrowl : growl(s);
			if (growl > 0f) {
				this.bb.apply("growl", seconds, growl);
				if (growl > 0.9f && forceGrowl < 0 && (int) (seconds / 2f) % 2 == 0) {
					// Barking at him, in fits.
					this.bb.apply("bark", seconds % 2f, 1f);
				}
			}
		}
	}

	/** How much it growls: on its chain at everyone a little, free at Michael when he is near (fully). */
	private static float growl(WolfRenderState s) {
		Minecraft mc = Minecraft.getInstance();
		Entity dog = mc.level == null ? null : mc.level.getEntity(((HwRenderState) s).halloween$entityId());
		Entity shape = mc.level == null ? null : mc.level.getEntity(ClientState.shapeEntityId);
		if (dog != null && shape != null && !shape.isInvisible() && dog.distanceTo(shape) <= 16f) {
			return 1f;
		}
		// Still chained (nobody's yet: no collar colour): low and wary.
		return s.collarColor == null ? 0.55f : 0f;
	}

	private static DogModel model() {
		if (model == null) {
			model = new DogModel(BbModel.load("/assets/halloween/models/entity/dog.json", Set.of()));
		}
		return model;
	}

	/** Draws a wolf as the dog. True if it did (the wolf's own model and layers are then not drawn). */
	public static boolean submit(LivingEntityRenderState state, PoseStack pose, SubmitNodeCollector collector) {
		if (!enabled || !(state instanceof WolfRenderState wolf)) {
			return false;
		}
		if (state.isInvisible) {
			return true;
		}
		DogModel m = model();
		pose.pushPose();
		pose.mulPose(Axis.YP.rotationDegrees(180f - state.bodyRot));
		pose.scale(size, size, size);
		pose.scale(-1f, -1f, 1f);
		pose.translate(0f, -1.501f, 0f);
		collector.submitModel(m, wolf, pose, m.renderType(TEXTURE), state.lightCoords, LivingEntityRenderer.getOverlayCoords(state, 0f), -1, null,
			state.outlineColor, null);
		pose.popPose();
		return true;
	}
}
