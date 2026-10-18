package dev.halloween.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * Aiming for the over-the-shoulder camera: everything the Shape does goes along the ray through
 * the centre of the screen, so the reticle is always where the hit lands.
 */
public final class CameraAim {
	private CameraAim() {
	}

	public static Vec3 origin() {
		return Minecraft.getInstance().gameRenderer.mainCamera().position();
	}

	public static Vec3 direction() {
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		Vector3fc f = camera.forwardVector();
		return new Vec3(f.x(), f.y(), f.z());
	}

	/** The block under the screen centre within reach of the Shape's eyes, or null. */
	public static net.minecraft.core.BlockPos pickBlock(double reach) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return null;
		}
		Vec3 camera = origin();
		Vec3 dir = direction();
		Vec3 eye = mc.player.getEyePosition();
		Vec3 from = camera.add(dir.scale(Math.max(0, eye.subtract(camera).dot(dir))));
		BlockHitResult block = mc.level.clip(new ClipContext(from, from.add(dir.scale(reach + 1)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		if (block.getType() == HitResult.Type.MISS || eye.distanceTo(block.getLocation()) > reach + 0.6) {
			return null;
		}
		return block.getBlockPos();
	}

	/**
	 * The living entity under the screen centre, if the Shape can reach it from his eyes.
	 * Returns null when a block is in the way or the target is out of reach.
	 */
	public static LivingEntity pickLiving(double reach) {
		return pickLiving(reach, false);
	}

	/** {@code throughGlass}: windows don't stop the ray (a grab at someone climbing through one). */
	public static LivingEntity pickLiving(double reach, boolean throughGlass) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return null;
		}
		Vec3 camera = origin();
		Vec3 dir = direction();
		Vec3 eye = mc.player.getEyePosition();
		// Start the ray level with the Shape, not at the camera behind him, so walls or posts
		// between the camera and his back never block the hit. It is still the same screen-centre line.
		Vec3 from = camera.add(dir.scale(Math.max(0, eye.subtract(camera).dot(dir))));
		double max = reach + 1;
		Vec3 to = from.add(dir.scale(max));
		BlockHitResult block = mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
		// A window doesn't stop the reach: someone climbing through it can be caught.
		if (block.getType() != HitResult.Type.MISS && !(throughGlass && dev.halloween.game.Sight.isGlass(mc.level.getBlockState(block.getBlockPos())))) {
			to = block.getLocation();
		}
		AABB area = new AABB(from, to).inflate(1);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(mc.player, from, to, area,
			e -> e != mc.player && !e.isSpectator() && e.isPickable() && e instanceof LivingEntity, from.distanceToSqr(to));
		if (hit == null || !(hit.getEntity() instanceof LivingEntity living)) {
			return null;
		}
		Entity target = hit.getEntity();
		Vec3 closest = target.getBoundingBox().getCenter();
		double fromEye = Math.min(eye.distanceTo(closest), eye.distanceTo(hit.getLocation()));
		return fromEye <= reach + 0.6 ? living : null;
	}
}
