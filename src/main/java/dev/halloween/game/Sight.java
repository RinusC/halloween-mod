package dev.halloween.game;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Line-of-sight checks where glass counts as see-through, so stalking through windows works. */
public final class Sight {
	private Sight() {
	}

	public static boolean isGlass(BlockState state) {
		return state.is(BlockTags.IMPERMEABLE) || state.getBlock() instanceof IronBarsBlock && !state.is(Blocks.IRON_BARS);
	}

	public static boolean canSee(Level level, Vec3 from, Vec3 to, Entity viewer) {
		if (!level.isClientSide() && Events.smokeBetween(from, to)) {
			// Smoke from a burning house (see Events).
			return false;
		}
		Vec3 start = from;
		Vec3 dir = to.subtract(from).normalize();
		for (int i = 0; i < 6; i++) {
			BlockHitResult hit = level.clip(new ClipContext(start, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
			if (hit.getType() == HitResult.Type.MISS) {
				return true;
			}
			BlockPos pos = hit.getBlockPos();
			if (!isGlass(level.getBlockState(pos))) {
				return false;
			}
			start = hit.getLocation().add(dir.scale(0.3));
			if (start.distanceToSqr(from) >= to.distanceToSqr(from)) {
				return true;
			}
		}
		return false;
	}

	/** Angle in degrees between a view direction and the direction from eye to target. */
	public static double angleTo(Vec3 eye, Vec3 look, Vec3 target) {
		Vec3 dir = target.subtract(eye);
		double len = dir.length();
		if (len < 1.0E-4) {
			return 0;
		}
		double dot = look.dot(dir.scale(1 / len));
		return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
	}
}
