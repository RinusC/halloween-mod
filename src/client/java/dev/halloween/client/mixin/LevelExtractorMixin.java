package dev.halloween.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vecna looking through someone's Eyes still sees himself (the game draws its own player only from his own camera). */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
	@Shadow
	private EntityRenderState extractEntity(Entity entity, float partialTickTime) {
		throw new AssertionError();
	}

	@Inject(method = "extractVisibleEntities", at = @At("TAIL"))
	private void halloween$selfInEyes(Camera camera, Frustum frustum, DeltaTracker deltaTracker, LevelRenderState output, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && dev.halloween.client.VecnaClient.eyesActive() && camera.entity() != mc.player) {
			output.entityRenderStates.add(this.extractEntity(mc.player, deltaTracker.getGameTimeDeltaPartialTick(true)));
			dev.halloween.client.VecnaClient.selfDrawn++;
		}
	}
}
