package dev.halloween.client.mixin;

import dev.halloween.client.ClientState;
import dev.halloween.client.HalloweenClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MinecraftMixin {
	/** Victims the Shape currently senses, has marked or caught with the impulse are outlined. */
	@Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
	private void halloween$outlines(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (ClientState.outlineColor(entity) != -1 || dev.halloween.client.ClientHeroes.glows(entity)) {
			cir.setReturnValue(true);
		}
	}

	/** While Killer Sense is held the use button means "focus", so it must not grab or interact. */
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void halloween$focusInsteadOfUse(CallbackInfo ci) {
		if (HalloweenClient.handleShapeUse()) {
			ci.cancel();
		}
	}
}
