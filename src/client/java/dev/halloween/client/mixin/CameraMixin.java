package dev.halloween.client.mixin;

import dev.halloween.client.ClientState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Over-the-shoulder camera for the Shape, and the zoom while focusing in Killer Sense. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private boolean detached;

	@Shadow
	public abstract Vec3 position();

	@Shadow
	public abstract Vector3fc leftVector();

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	public abstract float yRot();

	@Shadow
	public abstract float xRot();

	/** Panic: the view shakes (see ClientPanic). */
	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void halloween$panicShake(float partialTicks, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (this.detached || mc.level == null || dev.halloween.client.Cutscene.active()) {
			return;
		}
		float[] shake = dev.halloween.client.ClientPanic.shake(mc.level.getGameTime() + partialTicks);
		if (shake != null) {
			this.setRotation(this.yRot() + shake[0], this.xRot() + shake[1]);
		}
	}

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void halloween$shoulder(float partialTicks, CallbackInfo ci) {
		// The opening scene takes the camera.
		double[] cut = dev.halloween.client.Cutscene.camera(partialTicks);
		if (cut != null) {
			this.detached = dev.halloween.client.Cutscene.showsBody();
			this.setPosition(new Vec3(cut[0], cut[1], cut[2]));
			this.setRotation((float) cut[3], (float) cut[4]);
			return;
		}
		float offset = ClientState.shoulderOffset();
		Minecraft mc = Minecraft.getInstance();
		if (!this.detached || offset == 0 || mc.level == null || mc.player == null) {
			return;
		}
		Vec3 from = this.position();
		Vector3fc left = this.leftVector();
		Vec3 to = from.subtract(left.x() * offset, left.y() * offset, left.z() * offset);
		HitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
		if (hit.getType() != HitResult.Type.MISS) {
			Vec3 dir = to.subtract(from);
			double len = Math.max(0, hit.getLocation().distanceTo(from) - 0.2);
			to = from.add(dir.normalize().scale(len));
		}
		this.setPosition(to);
	}

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void halloween$focusZoom(float partialTicks, CallbackInfoReturnable<Float> cir) {
		float zoom = ClientState.fovMultiplier();
		if (zoom != 1f) {
			cir.setReturnValue(cir.getReturnValueF() * zoom);
		}
	}
}
