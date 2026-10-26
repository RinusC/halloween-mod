package dev.halloween.mixin;

import dev.halloween.Role;
import dev.halloween.game.Inventory;
import dev.halloween.game.MatchManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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

	/** A survivor's pockets are filled by our rules: vanilla would put anything into the free slot of the rounds. */
	@Redirect(method = "playerTouch", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"))
	private boolean halloween$pockets(net.minecraft.world.entity.player.Inventory inventory, ItemStack stack) {
		if (inventory.player instanceof ServerPlayer sp && MatchManager.role(sp).survivor()) {
			return Inventory.pickUp(sp, stack);
		}
		return inventory.add(stack);
	}
}
