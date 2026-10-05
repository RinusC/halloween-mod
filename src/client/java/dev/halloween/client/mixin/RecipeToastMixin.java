package dev.halloween.client.mixin;

import dev.halloween.client.ClientState;
import net.minecraft.client.gui.components.toasts.RecipeToast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No "new recipe" pop-ups in a match: the items are props, nothing gets crafted. */
@Mixin(RecipeToast.class)
public class RecipeToastMixin {
	@Inject(method = "addOrUpdate", at = @At("HEAD"), cancellable = true)
	private static void halloween$noRecipeToasts(ToastManager toastManager, RecipeDisplay recipe, CallbackInfo ci) {
		if (ClientState.inMatch()) {
			ci.cancel();
		}
	}
}
