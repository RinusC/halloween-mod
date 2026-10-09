package dev.halloween.mixin;

import dev.halloween.Role;
import dev.halloween.game.MatchManager;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Michael carries his knife and nothing else: he walks over what lies about without picking it up. */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void halloween$onlySurvivorsPickUp(Player player, CallbackInfo ci) {
		Role role = MatchManager.role(player);
		if (role != Role.NONE && !role.survivor()) {
			ci.cancel();
		}
	}
}
