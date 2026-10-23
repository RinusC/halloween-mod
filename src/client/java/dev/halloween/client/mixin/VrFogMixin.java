package dev.halloween.client.mixin;

import dev.halloween.client.VrShots;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Black between the scenes' shots in the headset: the fog closes in to the eyes and goes black (see VrShots). */
@Mixin(FogRenderer.class)
public class VrFogMixin {
	@Inject(method = "setupFog", at = @At("RETURN"))
	private void halloween$vrBlack(Camera camera, int renderDistance, DeltaTracker delta, float darken, ClientLevel level, CallbackInfoReturnable<FogData> cir) {
		float partial = delta.getGameTimeDeltaPartialTick(false);
		float[] tint = dev.halloween.client.ClientVr.fogTint(partial);
		if (tint != null) {
			// A glare, a daze, a flash: the fog closes in and takes the colour, as much as it says.
			FogData fog = cir.getReturnValue();
			float t = tint[0];
			fog.environmentalStart = Mth.lerp(t, fog.environmentalStart, 0f);
			fog.environmentalEnd = Mth.lerp(t, fog.environmentalEnd, Math.min(fog.environmentalEnd, tint[4]));
			fog.skyEnd = Mth.lerp(t, fog.skyEnd, Math.min(fog.skyEnd, tint[4]));
			fog.color.set(Mth.lerp(t, fog.color.x(), tint[1]), Mth.lerp(t, fog.color.y(), tint[2]), Mth.lerp(t, fog.color.z(), tint[3]), fog.color.w());
		}
		float k = VrShots.black(partial);
		if (k <= 0f) {
			return;
		}
		FogData fog = cir.getReturnValue();
		// Eased, so the last of the world goes quickly rather than hanging about as a thick fog.
		float e = 1f - (1f - k) * (1f - k);
		fog.environmentalStart = Mth.lerp(e, fog.environmentalStart, 0f);
		fog.environmentalEnd = Mth.lerp(e, fog.environmentalEnd, 0.05f);
		fog.renderDistanceStart = Mth.lerp(e, fog.renderDistanceStart, 0f);
		fog.renderDistanceEnd = Mth.lerp(e, fog.renderDistanceEnd, 0.05f);
		fog.skyEnd = Mth.lerp(e, fog.skyEnd, 0.05f);
		fog.cloudEnd = Mth.lerp(e, fog.cloudEnd, 0.05f);
		fog.color.set(fog.color.x() * (1f - k), fog.color.y() * (1f - k), fog.color.z() * (1f - k), fog.color.w());
	}
}
