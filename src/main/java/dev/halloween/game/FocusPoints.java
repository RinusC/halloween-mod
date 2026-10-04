package dev.halloween.game;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Points on a victim's body that the Shape has to hold his gaze on while stalking. Shared by server and client. */
public final class FocusPoints {
	public static final int COUNT = 5;

	private FocusPoints() {
	}

	public static Vec3 get(LivingEntity entity, int index, float partialTick) {
		double x = Mth.lerp(partialTick, entity.xo, entity.getX());
		double y = Mth.lerp(partialTick, entity.yo, entity.getY());
		double z = Mth.lerp(partialTick, entity.zo, entity.getZ());
		float yaw = entity.yBodyRot * Mth.DEG_TO_RAD;
		// Unit vector pointing to the entity's side.
		double sideX = Mth.cos(yaw);
		double sideZ = Mth.sin(yaw);
		double h = entity.getBbHeight();
		return switch (Math.floorMod(index, COUNT)) {
			case 0 -> new Vec3(x, y + entity.getEyeHeight(), z);
			case 1 -> new Vec3(x, y + h * 0.68, z);
			case 2 -> new Vec3(x + sideX * 0.32, y + h * 0.72, z + sideZ * 0.32);
			case 3 -> new Vec3(x - sideX * 0.32, y + h * 0.72, z - sideZ * 0.32);
			default -> new Vec3(x, y + h * 0.45, z);
		};
	}

	public static Vec3 bodyCenter(LivingEntity entity) {
		return entity.position().add(0, entity.getBbHeight() * 0.55, 0);
	}
}
