package dev.halloween.client.mixin;

import dev.halloween.client.ClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Marked victims are outlined for the Shape while they're out of his sight. */
@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
	private void halloween$markedVictims(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (ClientState.shouldGlow(entity)) {
			cir.setReturnValue(true);
		}
	}
}
