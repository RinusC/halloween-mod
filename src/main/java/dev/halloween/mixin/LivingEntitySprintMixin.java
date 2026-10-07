package dev.halloween.mixin;

import dev.halloween.game.SprintRules;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Survivors run a little faster (6 m/s), and not at all while out of breath (see SprintRules, Stamina). */
@Mixin(LivingEntity.class)
public class LivingEntitySprintMixin {
	@Inject(method = "setSprinting", at = @At("HEAD"), cancellable = true)
	private void halloween$outOfBreath(boolean sprinting, CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (sprinting && SprintRules.exhausted(self)) {
			ci.cancel();
		}
	}

	@Inject(method = "setSprinting", at = @At("TAIL"))
	private void halloween$survivorRun(boolean sprinting, CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		AttributeInstance speed = self.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed == null) {
			return;
		}
		speed.removeModifier(SprintRules.MODIFIER.id());
		if (sprinting && SprintRules.fast(self)) {
			speed.addTransientModifier(SprintRules.MODIFIER);
		}
	}
}
