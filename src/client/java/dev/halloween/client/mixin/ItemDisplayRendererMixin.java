package dev.halloween.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.halloween.client.RiftRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The Demogorgon's rift rides on an item display: drawn as its own 3D model, not as the item (see RiftRenderer). */
@Mixin(DisplayRenderer.ItemDisplayRenderer.class)
public class ItemDisplayRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Display$ItemDisplay;Lnet/minecraft/client/renderer/entity/state/ItemDisplayEntityRenderState;F)V",
		at = @At("TAIL"))
	private void halloween$rift(Display.ItemDisplay entity, ItemDisplayEntityRenderState state, float partialTicks, CallbackInfo ci) {
		RiftRenderer.extract(entity, state, partialTicks);
	}

	@Inject(method = "submitInner(Lnet/minecraft/client/renderer/entity/state/ItemDisplayEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IF)V",
		at = @At("HEAD"), cancellable = true)
	private void halloween$riftModel(ItemDisplayEntityRenderState state, PoseStack pose, SubmitNodeCollector collector, int light, float progress, CallbackInfo ci) {
		if (RiftRenderer.submit(state, pose, collector, light)) {
			ci.cancel();
		}
	}
}
