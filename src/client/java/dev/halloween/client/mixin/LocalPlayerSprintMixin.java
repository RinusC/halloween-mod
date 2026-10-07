package dev.halloween.client.mixin;

import dev.halloween.client.ClientState;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Out of breath: the local player can't start running, and a run stops (see game.Stamina). */
@Mixin(LocalPlayer.class)
public class LocalPlayerSprintMixin {
	@Inject(method = "isSprintingPossible", at = @At("HEAD"), cancellable = true)
	private void halloween$outOfBreath(boolean allowedInShallowWater, CallbackInfoReturnable<Boolean> cir) {
		if (ClientState.staminaExhausted) {
			cir.setReturnValue(false);
		}
	}
}
