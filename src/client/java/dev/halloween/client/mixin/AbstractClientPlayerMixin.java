package dev.halloween.client.mixin;

import dev.halloween.client.ClientSkins;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** In a match players wear their role's skin: Michael, a survivor of the game, the police uniform. */
@Mixin(AbstractClientPlayer.class)
public class AbstractClientPlayerMixin {
	@Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
	private void halloween$matchSkin(CallbackInfoReturnable<PlayerSkin> cir) {
		PlayerSkin skin = ClientSkins.forEntity(((AbstractClientPlayer) (Object) this).getId());
		if (skin != null) {
			cir.setReturnValue(skin);
		}
	}
}
