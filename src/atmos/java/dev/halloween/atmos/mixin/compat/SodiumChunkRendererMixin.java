package dev.halloween.atmos.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Binds the game's Globals for Sodium's terrain pass, next to its own (see SodiumChunkShaderMixin). */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer")
public class SodiumChunkRendererMixin {
	@WrapOperation(method = "render", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/systems/RenderPass;setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"))
	private void halloween$globals(RenderPass pass, String name, GpuBufferSlice value, Operation<Void> original) {
		original.call(pass, name, value);
		if (RenderSystem.getGlobalSettingsUniform() != null) {
			pass.setUniform("Globals", RenderSystem.getGlobalSettingsUniform());
		}
	}
}
