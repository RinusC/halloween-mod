package dev.halloween.client.mixin;

import dev.halloween.client.ClientState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Outline colour per victim as the Shape sees it: white in Killer Sense, red when marked, orange from the impulse. */
@Mixin(Entity.class)
public class EntityMixin {
	@Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
	private void halloween$outlineColor(CallbackInfoReturnable<Integer> cir) {
		int color = ClientState.outlineColor((Entity) (Object) this);
		if (color != -1) {
			cir.setReturnValue(color);
		}
	}
}
