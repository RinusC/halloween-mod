package dev.halloween.atmos.mixin;

import dev.halloween.atmos.Fog;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The match night's fog over the vanilla one (see Fog). */
@Mixin(AtmosphericFogEnvironment.class)
public class AtmosphericFogMixin {
	@Inject(method = "setupFog", at = @At("TAIL"))
	private void halloween$fog(FogData data, Camera camera, ClientLevel level, float renderDistance, DeltaTracker delta, CallbackInfo ci) {
		Fog.apply(data);
	}
}
