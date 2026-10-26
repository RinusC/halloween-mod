package dev.halloween.client.mixin;

import dev.halloween.client.VrBindings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Our default controller bindings added to the action manifest Vivecraft writes for SteamVR (see VrBindings). */
@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.provider.openvr_lwjgl.MCOpenVR", remap = false)
public class VrBindingsMixin {
	@Inject(method = "generateActionManifest", at = @At("TAIL"), remap = false)
	private void halloween$picoBindings(CallbackInfo ci) {
		VrBindings.addDefaults();
	}
}
