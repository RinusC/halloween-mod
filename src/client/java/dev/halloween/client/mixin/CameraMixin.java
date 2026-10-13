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

	/** A shot: the camera jolts; the kick coming back down (see ClientGuns). */
	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void halloween$gunJolt(float partialTicks, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || dev.halloween.client.Cutscene.active()) {
			return;
		}
		float[] jolt = dev.halloween.client.ClientGuns.frame(mc, partialTicks);
		if (jolt != null && !this.detached) {
			this.setRotation(this.yRot() + jolt[0], this.xRot() + jolt[1]);
		}
	}

	/** The midnight earthquake: the view shakes in a tremor, whatever the camera (see ClientEvents). */
	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void halloween$quakeShake(float partialTicks, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || dev.halloween.client.Cutscene.active()) {
			return;
		}
		float[] shake = dev.halloween.client.ClientEvents.quakeShake(mc.level.getGameTime() + partialTicks);
		if (shake != null) {
			this.setRotation(this.yRot() + shake[0], this.xRot() + shake[1]);
		}
	}

	/** The Demogorgon on all fours, in first person: the eyes low to the ground too. */
	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void halloween$demoLow(float partialTicks, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (this.detached || mc.player == null || dev.halloween.client.Cutscene.active() || !dev.halloween.client.MichaelRenderer.demogorgon()
			|| !dev.halloween.client.MichaelRenderer.isMichael(mc.player)) {
			return;
		}
		float drop = dev.halloween.client.DemogorgonModel.firstPersonDrop(mc.player.getId());
		if (drop != 0f) {
			this.setPosition(this.position().add(0, drop, 0));
		}
	}

	/** How far over the shoulder the camera is now (eased back out after a wall), and when the last frame was. */
	@org.spongepowered.asm.mixin.Unique
	private static double halloween$shoulderLen;
	@org.spongepowered.asm.mixin.Unique
	private static long halloween$lastFrame;

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
		if (dev.halloween.client.MichaelRenderer.enabled) {
			// His model is taller than he is: the camera up to where his eyes are drawn (not through a ceiling).
			// The Demogorgon: higher standing, low to the ground on all fours.
			double lift = dev.halloween.client.MichaelRenderer.demogorgon() ? dev.halloween.client.DemogorgonModel.cameraLift(mc.player.getId())
				: (dev.halloween.client.MichaelRenderer.TALL - 1f) * mc.player.getEyeHeight();
			Vec3 up = from.add(0, Math.max(0, lift), 0);
			HitResult roof = mc.level.clip(new ClipContext(from, up, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
			from = roof.getType() == HitResult.Type.MISS ? up : from.add(0, Math.max(0, roof.getLocation().y - from.y - 0.15), 0);
			if (lift < 0) {
				from = from.add(0, lift, 0);
			}
			this.setPosition(from);
		}
		Vector3fc left = this.leftVector();
		Vec3 to = from.subtract(left.x() * offset, left.y() * offset, left.z() * offset);
		Vec3 dir = to.subtract(from).normalize();
		double allowed = offset;
		HitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
		if (hit.getType() != HitResult.Type.MISS) {
			allowed = Math.max(0, hit.getLocation().distanceTo(from) - 0.2);
		}
		// In at once (never into the wall), back out gently: running past posts and door frames, the view doesn't jump.
		long now = System.nanoTime();
		double dt = halloween$lastFrame == 0 ? 0 : Math.min(0.1, (now - halloween$lastFrame) / 1.0E9);
		halloween$lastFrame = now;
		halloween$shoulderLen = allowed < halloween$shoulderLen ? allowed : halloween$shoulderLen + (allowed - halloween$shoulderLen) * Math.min(1, dt * 6);
		this.setPosition(from.add(dir.scale(Math.min(halloween$shoulderLen, allowed))));
	}

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void halloween$focusZoom(float partialTicks, CallbackInfoReturnable<Float> cir) {
		float zoom = ClientState.fovMultiplier();
		if (zoom != 1f) {
			cir.setReturnValue(cir.getReturnValueF() * zoom);
		}
	}
}
