package dev.halloween.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Michael's knife, held point down: as his arm goes up the wrist turns so the point still comes down first (the
 * blocky arm can't bend at the elbow). Turned about the grip, just before the knife is drawn.
 */
@Mixin(ItemInHandLayer.class)
public abstract class ItemInHandLayerMixin {
	/** A gun raised to the eye (GunPoses): turned at the wrist to point where they look, not where the arm does. */
	@Inject(method = "submitArmWithItem", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
	private void halloween$gunWrist(ArmedEntityRenderState state, net.minecraft.client.renderer.item.ItemStackRenderState item, net.minecraft.world.item.ItemStack stack,
		HumanoidArm arm, PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, int light, CallbackInfo ci) {
		if (arm == HumanoidArm.RIGHT && state instanceof dev.halloween.client.HwRenderState hw) {
			float turn = dev.halloween.client.GunPoses.wrist(hw.halloween$entityId());
			if (turn != 0f) {
				pose.mulPose(Axis.XP.rotation(turn));
			}
		}
	}

	/** The Demogorgon holds nothing in his claws, nor Vecna in his hands (his arms are his own, not the player model's). */
	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void halloween$demoNoItem(ArmedEntityRenderState state, net.minecraft.client.renderer.item.ItemStackRenderState item, net.minecraft.world.item.ItemStack stack,
		HumanoidArm arm, PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, int light, CallbackInfo ci) {
		if ((dev.halloween.client.MichaelRenderer.demogorgon() || dev.halloween.client.MichaelRenderer.vecna()) && dev.halloween.client.MichaelRenderer.isMichael(state)) {
			ci.cancel();
		}
	}

	@Inject(method = "submitArmWithItem", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
	private void halloween$michaelWrist(ArmedEntityRenderState state, net.minecraft.client.renderer.item.ItemStackRenderState item, net.minecraft.world.item.ItemStack stack,
		HumanoidArm arm, PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, int light, CallbackInfo ci) {
		if (arm == HumanoidArm.RIGHT && ((RenderLayer<?, ?>) (Object) this).getParentModel() instanceof HumanoidModel<?> m
			&& dev.halloween.client.MichaelRenderer.isMichael(state)) {
			pose.mulPose(Axis.XP.rotation(dev.halloween.client.MichaelRenderer.WRIST * m.rightArm.xRot));
		}
	}
}
