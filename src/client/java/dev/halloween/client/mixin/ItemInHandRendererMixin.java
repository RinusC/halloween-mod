package dev.halloween.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.halloween.Role;
import dev.halloween.client.ClientState;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The Runt is no person: no human arm at the bottom of his screen (see RuntRenderer). */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {
	@Inject(method = "renderPlayerArm", at = @At("HEAD"), cancellable = true)
	private void halloween$noRuntArm(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, float inverseArmHeight, float attackValue,
		HumanoidArm arm, CallbackInfo ci) {
		if (ClientState.myRole() == Role.RUNT && dev.halloween.client.RuntRenderer.enabled) {
			ci.cancel();
		}
	}
}
