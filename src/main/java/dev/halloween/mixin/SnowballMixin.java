package dev.halloween.mixin;

import dev.halloween.game.Gear;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bricks, rocks, flares and the like fly as snowballs carrying their item; where they land is ours to decide. */
@Mixin(Snowball.class)
public abstract class SnowballMixin {
	@Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
	private void halloween$landed(HitResult hit, CallbackInfo ci) {
		Snowball self = (Snowball) (Object) this;
		if (!self.level().isClientSide() && self.entityTags().contains(Gear.THROW_TAG) && Gear.landed(self, hit)) {
			ci.cancel();
		}
	}
}
