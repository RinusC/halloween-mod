package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Breaker boxes: a lever touching an iron block. Flipping it switches every copper bulb in
 * {@link HwConfig#breakerRadius} on or off. The lever never emits redstone, so wiring can't interfere.
 */
public final class LightSystem {
	private LightSystem() {
	}

	public static boolean isBreaker(Level level, BlockPos pos) {
		if (!level.getBlockState(pos).is(Blocks.LEVER)) {
			return false;
		}
		for (Direction dir : Direction.values()) {
			if (level.getBlockState(pos.relative(dir)).is(Blocks.IRON_BLOCK)) {
				return true;
			}
		}
		return false;
	}

	public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		BlockPos pos = hit.getBlockPos();
		if (!isBreaker(level, pos) || player.isSpectator()) {
			return InteractionResult.PASS;
		}
		if (!(level instanceof ServerLevel serverLevel)) {
			return InteractionResult.SUCCESS;
		}
		BlockState state = level.getBlockState(pos);
		boolean on = !state.getValue(LeverBlock.POWERED);
		level.setBlock(pos, state.setValue(LeverBlock.POWERED, on), Block.UPDATE_CLIENTS);
		setLights(serverLevel, pos, on);
		level.playSound(null, pos, HwSounds.BREAKER, SoundSource.BLOCKS, 1f, on ? 1.1f : 0.8f);
		return InteractionResult.SUCCESS;
	}

	public static void setLights(ServerLevel level, BlockPos center, boolean on) {
		int r = HwConfig.get().breakerRadius;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
			BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof CopperBulbBlock && state.getValue(CopperBulbBlock.LIT) != on) {
				level.setBlock(pos, state.setValue(CopperBulbBlock.LIT, on), Block.UPDATE_CLIENTS);
			}
		}
	}
}
