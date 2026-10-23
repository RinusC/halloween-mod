package dev.halloween.game;

import dev.halloween.HwBlocks;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Things put in his way in a chase.
 * <ul>
 * <li><b>Pulled down behind you.</b> A survivor running past a trash can, a bookcase, a barrel or a hay bale who uses
 * it pulls it down behind them (walking, it is left be: some are searched). It lies across the way (DebrisBlock),
 * where they just ran; the first of Michael's side to run into it stumbles over it, slowed to a crawl for a moment
 * and kicking it aside, loud. Survivors step over it.</li>
 * </ul>
 * Everything pulled down is put back when the match ends.
 */
public final class Obstacles {
	private static final int STUMBLE_TICKS = 24;

	/** What stood where something was pulled down or now lies, to put back (the first state seen at each place). */
	private static final Map<BlockPos, BlockState> ORIGINAL = new LinkedHashMap<>();

	private Obstacles() {
	}

	/** What can be pulled down, as what it lies as; null for anything else. */
	public static DebrisBlock.Kind kindOf(BlockState state) {
		if (state.is(Blocks.CAULDRON) || state.is(Blocks.WATER_CAULDRON) || state.is(Blocks.COMPOSTER)) {
			return DebrisBlock.Kind.BIN;
		}
		if (state.is(Blocks.BOOKSHELF) || state.is(Blocks.CHISELED_BOOKSHELF)) {
			return DebrisBlock.Kind.SHELF;
		}
		if (state.is(Blocks.BARREL)) {
			return DebrisBlock.Kind.BARREL;
		}
		return state.is(Blocks.HAY_BLOCK) ? DebrisBlock.Kind.HAY : null;
	}

	public static InteractionResult onUse(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !Match.running() || Match.frozen()
			|| !MatchManager.role(sp).survivor() || sp.isSpectator()) {
			return InteractionResult.PASS;
		}
		BlockPos pos = hit.getBlockPos();
		BlockState state = level.getBlockState(pos);
		DebrisBlock.Kind kind = kindOf(state);
		if (kind != null && sp.isSprinting() && !Loot.isContainer(pos) && knock(sp, (ServerLevel) level, pos, state, kind)) {
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	// ---------------------------------------------------------------- pulled down

	private static boolean knock(ServerPlayer by, ServerLevel level, BlockPos pos, BlockState state, DebrisBlock.Kind kind) {
		// A bookcase two high comes down whole.
		BlockPos base = pos;
		while (level.getBlockState(base.below()).is(state.getBlock())) {
			base = base.below();
		}
		List<BlockPos> column = new ArrayList<>();
		for (BlockPos p = base; level.getBlockState(p).is(state.getBlock()) && column.size() < 3; p = p.above()) {
			column.add(p.immutable());
		}
		// Where it falls: behind them, where they just ran; else beside it, nearest to that; else where it stood.
		Vec3 look = by.getLookAngle().multiply(1, 0, 1);
		look = look.lengthSqr() < 1e-4 ? Vec3.ZERO : look.normalize();
		Vec3 behind = by.position().subtract(look.scale(1.1));
		BlockPos drop = BlockPos.containing(behind.x, by.getY() + 0.1, behind.z);
		final BlockPos from = base;
		if (!fits(level, drop, column) || drop.distSqr(base) > 9) {
			List<BlockPos> around = new ArrayList<>();
			for (Direction d : Direction.Plane.HORIZONTAL) {
				around.add(from.relative(d));
			}
			around.sort(Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(behind)));
			drop = around.stream().filter(p -> fits(level, p, column)).findFirst().orElse(from);
		}
		Direction facing = drop.equals(base) ? by.getDirection() : Direction.getApproximateNearest(Vec3.atCenterOf(drop).subtract(Vec3.atCenterOf(base)));
		for (BlockPos p : column) {
			ORIGINAL.putIfAbsent(p, level.getBlockState(p));
			level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
		ORIGINAL.putIfAbsent(drop, level.getBlockState(drop));
		level.setBlock(drop, HwBlocks.DEBRIS.defaultBlockState().setValue(DebrisBlock.FACING, facing).setValue(DebrisBlock.KIND, kind), Block.UPDATE_ALL);
		Vec3 at = Vec3.atCenterOf(drop);
		level.playSound(null, at.x, at.y, at.z, crash(kind), SoundSource.BLOCKS, 1.6f, 0.8f + level.getRandom().nextFloat() * 0.2f);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), at.x, at.y + 0.2, at.z, 30, 0.5, 0.3, 0.5, 0.1);
		// Loud: Michael hears where it came down.
		loud(level, at);
		AiStats.add(by, "obstacle.knock");
		return true;
	}

	/** Room for it to lie: empty, on solid ground, not where something else was just pulled from. */
	private static boolean fits(ServerLevel level, BlockPos p, List<BlockPos> column) {
		return column.contains(p) || level.getBlockState(p).isAir() && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP);
	}

	private static SoundEvent crash(DebrisBlock.Kind kind) {
		return switch (kind) {
			case BIN -> SoundEvents.ANVIL_LAND;
			case SHELF -> SoundEvents.CHISELED_BOOKSHELF_PICKUP;
			case BARREL -> SoundEvents.BARREL_CLOSE;
			case HAY -> SoundEvents.GRASS_BREAK;
		};
	}

	private static BlockState source(DebrisBlock.Kind kind) {
		return switch (kind) {
			case BIN -> Blocks.CAULDRON.defaultBlockState();
			case SHELF -> Blocks.BOOKSHELF.defaultBlockState();
			case BARREL -> Blocks.BARREL.defaultBlockState();
			case HAY -> Blocks.HAY_BLOCK.defaultBlockState();
		};
	}

	private static void loud(ServerLevel level, Vec3 at) {
		HwNet.NoiseBurst burst = new HwNet.NoiseBurst(at, true);
		for (ServerPlayer p : level.players()) {
			if (MatchManager.role(p) == Role.SHAPE && p.position().distanceTo(at) <= 40) {
				ServerPlayNetworking.send(p, burst);
			}
		}
	}

	/** Every tick: Michael's side running into what lies in the way. */
	public static void tick(MinecraftServer server) {
		if (!Match.running() || ORIGINAL.isEmpty()) {
			return;
		}
		ServerLevel level = server.overworld();
		for (ServerPlayer p : level.players()) {
			ShapeState st = MatchManager.role(p) == Role.SHAPE && !p.isSpectator() ? MatchManager.shapeState(p) : null;
			if (st == null || st.shadowTicks > 0) {
				continue;
			}
			AABB feet = p.getBoundingBox().setMaxY(p.getY() + 0.5);
			for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(feet.minX, feet.minY, feet.minZ), BlockPos.containing(feet.maxX, feet.maxY, feet.maxZ))) {
				BlockState state = level.getBlockState(pos);
				if (state.is(HwBlocks.DEBRIS)) {
					stumble(level, p, st, pos.immutable(), state.getValue(DebrisBlock.KIND));
					break;
				}
			}
		}
	}

	private static void stumble(ServerLevel level, ServerPlayer shape, ShapeState st, BlockPos pos, DebrisBlock.Kind kind) {
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		shape.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, STUMBLE_TICKS, 3, false, false));
		st.attackCooldown = Math.max(st.attackCooldown, 16);
		Vec3 at = Vec3.atCenterOf(pos);
		level.playSound(null, at.x, at.y, at.z, crash(kind), SoundSource.BLOCKS, 1.3f, 0.6f);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, source(kind)), at.x, at.y, at.z, 24, 0.5, 0.2, 0.5, 0.15);
		AiStats.add(shape, "obstacle.stumble");
	}

	/** The match is over: everything pulled down stands where it stood. */
	public static void clear(MinecraftServer server) {
		ServerLevel level = server.overworld();
		List<Map.Entry<BlockPos, BlockState>> back = new ArrayList<>(ORIGINAL.entrySet());
		for (int i = back.size() - 1; i >= 0; i--) {
			level.setBlock(back.get(i).getKey(), back.get(i).getValue(), Block.UPDATE_ALL);
		}
		ORIGINAL.clear();
	}
}
