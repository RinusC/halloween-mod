package dev.halloween.client.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.BindGroupLayouts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sodium's terrain pipeline also takes the game's Globals (where the camera is, the time): our match fog needs them
 * on the terrain too (halloween:sodium_fog.glsl). Only with Sodium (see HwCompatPlugin).
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer")
public class SodiumChunkShaderMixin {
	@ModifyExpressionValue(method = "createShader", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline;builder([Lcom/mojang/blaze3d/pipeline/RenderPipeline$Snippet;)Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;"))
	private RenderPipeline.Builder halloween$globals(RenderPipeline.Builder builder) {
		return builder.withBindGroupLayout(BindGroupLayouts.GLOBALS);
	}
}
