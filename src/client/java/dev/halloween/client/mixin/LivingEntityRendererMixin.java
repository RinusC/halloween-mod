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

	@Inject(method = "setupRotations", at = @At("TAIL"))
	private void halloween$knockedDown(LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float entityScale, CallbackInfo ci) {
		float angle = ClientAnims.fallAngle(((HwRenderState) state).halloween$entityId());
		if (angle > 0f) {
			// Lifted a little as it goes over, so the back lies on the ground rather than in it.
			poseStack.translate(0f, 0.18f * (float) Math.sin(Math.toRadians(angle)), 0f);
			poseStack.mulPose(Axis.XP.rotationDegrees(-angle));
		}
	}
}
