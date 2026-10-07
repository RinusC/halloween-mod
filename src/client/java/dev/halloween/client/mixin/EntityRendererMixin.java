package dev.halloween.client.mixin;

import dev.halloween.client.HwRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Each render state learns its entity's id as it is filled in. */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void halloween$rememberEntity(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
		((HwRenderState) state).halloween$setEntityId(entity.getId());
	}
}
