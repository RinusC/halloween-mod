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
		if (on && Match.running() && player instanceof net.minecraft.server.level.ServerPlayer sp && MatchManager.role(sp).survivor()) {
			// Getting the power back means joining the wires by hand (bots take a while instead, see resolve).
			if (!Bots.isBot(sp)) {
				Minigames.open(sp, dev.halloween.net.HwNet.Minigame.WIRES, pos);
			}
			return InteractionResult.SUCCESS;
		}
		level.setBlock(pos, state.setValue(LeverBlock.POWERED, on), Block.UPDATE_CLIENTS);
		setLights(serverLevel, pos, on);
		level.playSound(null, pos, HwSounds.BREAKER, SoundSource.BLOCKS, 1f, on ? 1.1f : 0.8f);
		return InteractionResult.SUCCESS;
	}

	/** A bot holding use on a dark breaker box: the wires take it a while. */
	public static Holds.Hold resolve(net.minecraft.server.level.ServerPlayer player, BlockPos pos) {
		ServerLevel level = player.level();
		if (!Bots.isBot(player) || !MatchManager.role(player).survivor() || !isBreaker(level, pos) || isOn(level, pos)) {
			return null;
		}
		BlockPos at = pos.immutable();
		return new Holds.Hold(at, "halloween.hud.progress.wires", false, HwConfig.get().botWiresTicks, p -> !isOn(level, at), p -> {
			flip(level, at, true);
			AiStats.add(p, "power.on");
		});
	}

	/** Switches a breaker (and its house lights) on or off, as if someone flipped it. */
	public static void flip(ServerLevel level, BlockPos breaker, boolean on) {
		BlockState state = level.getBlockState(breaker);
		if (!isBreaker(level, breaker) || state.getValue(LeverBlock.POWERED) == on) {
			return;
		}
		level.setBlock(breaker, state.setValue(LeverBlock.POWERED, on), Block.UPDATE_CLIENTS);
		setLights(level, breaker, on);
		level.playSound(null, breaker, HwSounds.BREAKER, SoundSource.BLOCKS, 1f, on ? 1.1f : 0.8f);
	}

	public static boolean isOn(ServerLevel level, BlockPos breaker) {
		return isBreaker(level, breaker) && level.getBlockState(breaker).getValue(LeverBlock.POWERED);
	}

	/** Breakers within {@code radius} of {@code center}. */
	public static java.util.List<BlockPos> breakers(ServerLevel level, BlockPos center, int radius) {
		java.util.List<BlockPos> list = new java.util.ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -3, -radius), center.offset(radius, 4, radius))) {
			if (level.getBlockState(pos).is(Blocks.LEVER) && isBreaker(level, pos)) {
				list.add(pos.immutable());
			}
		}
		return list;
	}

	/**
	 * Lights within reach of the breaker go on or off, but only those it is the nearest breaker to: each
	 * house keeps its own wiring even where two houses stand close.
	 */
	public static void setLights(ServerLevel level, BlockPos center, boolean on) {
		int r = HwConfig.get().breakerRadius;
		java.util.List<BlockPos> others = breakers(level, center, 2 * r);
		others.remove(center);
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
			BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof CopperBulbBlock && state.getValue(CopperBulbBlock.LIT) != on) {
				double mine = pos.distSqr(center);
				boolean nearer = false;
				for (BlockPos other : others) {
					boolean reaches = Math.abs(other.getX() - pos.getX()) <= r && Math.abs(other.getY() - pos.getY()) <= r && Math.abs(other.getZ() - pos.getZ()) <= r;
					nearer |= reaches && other.distSqr(pos) < mine;
				}
				if (!nearer) {
					level.setBlock(pos, state.setValue(CopperBulbBlock.LIT, on), Block.UPDATE_CLIENTS);
				}
			}
		}
	}
}
