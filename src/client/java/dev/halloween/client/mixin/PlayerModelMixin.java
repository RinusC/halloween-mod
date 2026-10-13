package dev.halloween.client.mixin;

import dev.halloween.client.ClientAnims;
import dev.halloween.client.HwRenderState;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Michael's own walk and knife hand, then the grab animations (ClientAnims), go over the pose of players and townsfolk last. */
@Mixin(PlayerModel.class)
public class PlayerModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void halloween$grabPose(AvatarRenderState state, CallbackInfo ci) {
		dev.halloween.client.MichaelRenderer.pose((HumanoidModel<?>) (Object) this, state);
		dev.halloween.client.GunPoses.apply((HumanoidModel<?>) (Object) this, state, ((HwRenderState) state).halloween$entityId());
		ClientAnims.apply((HumanoidModel<?>) (Object) this, ((HwRenderState) state).halloween$entityId());
	}
}
