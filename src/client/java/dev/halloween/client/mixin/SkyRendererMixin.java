package dev.halloween.client.mixin;

import dev.halloween.client.ClientWeather;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The stars and the moon fade behind a match's fog (they are drawn with none of their own, see ClientWeather.skyClarity). */
@Mixin(SkyRenderer.class)
public class SkyRendererMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void halloween$haze(ClientLevel level, float partialTicks, Camera camera, SkyRenderState state, CallbackInfo ci) {
		float clear = ClientWeather.skyClarity();
		state.starBrightness *= clear;
		state.rainBrightness *= clear;
	}
}
