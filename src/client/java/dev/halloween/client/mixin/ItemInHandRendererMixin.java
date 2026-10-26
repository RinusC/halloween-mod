package dev.halloween.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.halloween.client.ViewArms;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In a match survivors and the Runt get our hands in first person (see ViewArms), not vanilla's. In the headset the
 * gun in the hand is drawn here too (by Vivecraft): where its sights are drawn is where it shoots (ClientGuns.drawn).
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {
	@Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
	private void halloween$hands(float frameInterp, PoseStack poseStack, SubmitNodeCollector collector, LocalPlayer player, int lightCoords, CallbackInfo ci) {
		if (dev.halloween.client.Cutscene.active() && dev.halloween.client.Cutscene.showsBody() || dev.halloween.client.KillCam.active() || dev.halloween.client.VecnaDeathScene.cinematic()) {
			// A scene seen from outside them: no hands in the picture.
			ci.cancel();
			return;
		}
		if (ViewArms.render((ItemInHandRenderer) (Object) this, frameInterp, poseStack, collector, player, lightCoords)) {
			ci.cancel();
		}
	}

	@Inject(method = "renderItem", at = @At("HEAD"))
	private void halloween$gunDrawn(LivingEntity entity, ItemStack stack, ItemDisplayContext context, PoseStack poseStack, SubmitNodeCollector collector, int light, CallbackInfo ci) {
		if (context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND) {
			dev.halloween.client.ClientGuns.drawn(entity, stack, poseStack.last().pose());
		}
	}
}
