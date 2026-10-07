package dev.halloween.client.mixin;

import dev.halloween.client.ClientWeather;
import dev.halloween.game.Weather;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Haddonfield is filled with the badlands biome (for its autumn grass and leaves), where it never rains: on a
 * rainy match night it rains there too.
 */
@Mixin(Biome.class)
public class BiomeRainMixin {
	@Inject(method = "hasPrecipitation", at = @At("HEAD"), cancellable = true)
	private void halloween$rain(CallbackInfoReturnable<Boolean> cir) {
		if (ClientWeather.kind == Weather.RAIN) {
			cir.setReturnValue(true);
		}
	}
}
