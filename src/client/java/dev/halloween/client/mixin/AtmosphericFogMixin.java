package dev.halloween.client.mixin;

import dev.halloween.client.ClientWeather;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A foggy match night: the fog closes in (see ClientWeather). */
@Mixin(AtmosphericFogEnvironment.class)
public class AtmosphericFogMixin {
	@Inject(method = "setupFog", at = @At("TAIL"))
	private void halloween$fog(FogData data, Camera camera, ClientLevel level, float renderDistance, DeltaTracker delta, CallbackInfo ci) {
		ClientWeather.apply(data);
	}
}
