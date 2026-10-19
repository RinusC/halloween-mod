package dev.halloween.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.halloween.client.ClientAnims;
import dev.halloween.client.HwRenderState;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Knocked down: the whole body goes over backwards from the feet, and comes up again (see ClientAnims). */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	/** The Runt is drawn as his creature, not as a person (see RuntRenderer). */
	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At("HEAD"), cancellable = true)
	private void halloween$runt(LivingEntityRenderState state, PoseStack poseStack, net.minecraft.client.renderer.SubmitNodeCollector collector,
		net.minecraft.client.renderer.state.level.CameraRenderState camera, CallbackInfo ci) {
		if (dev.halloween.client.RuntRenderer.submit(state, poseStack, collector)) {
			ci.cancel();
		}
	}

	/** Michael is drawn as his own model in place of the player model (see MichaelRenderer); the layers stay. */
	@org.spongepowered.asm.mixin.injection.Redirect(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"))
	@SuppressWarnings({"rawtypes", "unchecked"})
	private void halloween$michael(net.minecraft.client.renderer.SubmitNodeCollector collector, net.minecraft.client.model.Model model, Object state, PoseStack pose,
		net.minecraft.client.renderer.rendertype.RenderType renderType, int light, int overlay, int tint, net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,
		int outline, net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumbling) {
		// In an opening scene the one it is about (and him, behind them) catch a little light, made out in the night.
		light = dev.halloween.client.HeroScene.light(state, light);
		if (!dev.halloween.client.MichaelRenderer.submit(collector, model, state, pose, light, overlay, tint, outline)
			&& !dev.halloween.client.SurvivorModels.submit(collector, model, state, pose, light, overlay, tint, outline)) {
			collector.submitModel(model, state, pose, renderType, light, overlay, tint, sprite, outline, crumbling);
		}
	}

	/** Michael towers over everyone (to look at: his hitbox is a player's). */
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
	private void halloween$tall(net.minecraft.world.entity.LivingEntity entity, LivingEntityRenderState state, float partialTicks, CallbackInfo ci) {
		// Each survivor as tall as they are.
		state.scale *= dev.halloween.client.SurvivorModels.tall(entity.getId());
		if (dev.halloween.client.MichaelRenderer.isMichael(entity)) {
			state.scale *= dev.halloween.client.MichaelRenderer.TALL;
			int once = ClientAnims.once(entity.getId());
			if (once == dev.halloween.net.HwNet.Anim.WINDOW_THROW || once == dev.halloween.net.HwNet.Anim.DEMO_TOSS) {
				// Throwing someone out of a window: turned to it (the server keeps him so), whatever the player does.
				state.bodyRot = entity.getYRot();
			} else if (once == dev.halloween.net.HwNet.Anim.DEMO_SEARCH || once == dev.halloween.net.HwNet.Anim.DEMO_LOCKER) {
				// At a wardrobe his body stays turned to it (as the server set him), wherever the player looks meanwhile.
				state.bodyRot = halloween$FIXED.computeIfAbsent(entity.getId(), k -> entity.getYRot());
			} else {
				halloween$FIXED.remove(entity.getId());
			}
			if (dev.halloween.client.MichaelRenderer.demogorgon()
				&& ClientAnims.hold(entity.getId()) == dev.halloween.net.HwNet.Anim.DEVOUR) {
				// Eating: his body faces the one under him, whichever way the player looks.
				for (net.minecraft.world.entity.Entity e : entity.level().getEntities(entity, entity.getBoundingBox().inflate(2.5))) {
					if (ClientAnims.hold(e.getId()) == dev.halloween.net.HwNet.Anim.PINNED || ClientAnims.hold(e.getId()) == dev.halloween.net.HwNet.Anim.DEAD) {
						// Squarely at them (their feet to his, see below): the same line both ways.
						state.bodyRot = halloween$yawTo(entity.getPosition(partialTicks), e.getPosition(partialTicks));
						break;
					}
				}
			}
		} else if ((ClientAnims.hold(entity.getId()) == dev.halloween.net.HwNet.Anim.PINNED || ClientAnims.hold(entity.getId()) == dev.halloween.net.HwNet.Anim.DEAD) && dev.halloween.client.MichaelRenderer.demogorgon()) {
			// Down under the Demogorgon: lying straight out from him, feet to his, whichever way they look.
			for (net.minecraft.world.entity.Entity e : entity.level().getEntities(entity, entity.getBoundingBox().inflate(2.5))) {
				if (ClientAnims.hold(e.getId()) == dev.halloween.net.HwNet.Anim.DEVOUR) {
					state.bodyRot = halloween$yawTo(entity.getPosition(partialTicks), e.getPosition(partialTicks));
					break;
				}
			}
		}
	}

	@org.spongepowered.asm.mixin.Unique
	private static final java.util.Map<Integer, Float> halloween$FIXED = new java.util.HashMap<>();

	@org.spongepowered.asm.mixin.Unique
	private static float halloween$yawTo(net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 to) {
		return (float) (Math.toDegrees(Math.atan2(to.z - from.z, to.x - from.x)) - 90.0);
	}

	@Inject(method = "setupRotations", at = @At("TAIL"))
	private void halloween$knockedDown(LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float entityScale, CallbackInfo ci) {
		float angle = ClientAnims.fallAngle(((HwRenderState) state).halloween$entityId());
		if (angle > 0f) {
			// Lifted a little as it goes over, so the back lies on the ground rather than in it.
			poseStack.translate(0f, 0.18f * (float) Math.sin(Math.toRadians(angle)), 0f);
			// Over towards where they face; thrown down under the Demogorgon, onto their back the other way.
			boolean back = ClientAnims.onBack(((HwRenderState) state).halloween$entityId());
			poseStack.mulPose(Axis.XP.rotationDegrees(back ? angle : -angle));
		}
	}
}
