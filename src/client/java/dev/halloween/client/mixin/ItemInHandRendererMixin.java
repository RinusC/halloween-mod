package dev.halloween.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.halloween.client.ViewArms;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In a match survivors and the Runt get our hands in first person (see ViewArms), not vanilla's. */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {
	@Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
	private void halloween$hands(float frameInterp, PoseStack poseStack, SubmitNodeCollector collector, LocalPlayer player, int lightCoords, CallbackInfo ci) {
		if (ViewArms.render((ItemInHandRenderer) (Object) this, frameInterp, poseStack, collector, player, lightCoords)) {
			ci.cancel();
		}
	}
}
