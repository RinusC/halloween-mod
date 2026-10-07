package dev.halloween.client.mixin;

import dev.halloween.client.ClientDarkness;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A match night's darkness (see ClientDarkness). */
@Mixin(LightmapRenderStateExtractor.class)
public class LightmapExtractorMixin {
	@Inject(method = "extract", at = @At("TAIL"))
	private void halloween$dark(LightmapRenderState state, float partialTicks, CallbackInfo ci) {
		ClientDarkness.apply(state);
	}
}
