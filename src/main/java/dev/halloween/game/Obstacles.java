package dev.halloween.game;

import dev.halloween.HwBlocks;
import dev.halloween.HwSounds;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
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
 * <li><b>A door in his face.</b> Closing a door on him as he comes through it catches him in the face: dazed for a
 * moment (no blow, no grab, slowed), thrown back a little, and it counts on his stun meter. Each door does it once in
 * a while, and each survivor too.</li>
 * </ul>
 * Everything pulled down is put back when the match ends.
 */
public final class Obstacles {
	private static final int STUMBLE_TICKS = 24;
	private static final int DAZE_TICKS = 26;
	private static final int DOOR_WAIT = 300;
	private static final int SLAMMER_WAIT = 160;
	private static final float SLAM_STUN = 0.2f;

	/** What stood where something was pulled down or now lies, to put back (the first state seen at each place). */
	private static final Map<BlockPos, BlockState> ORIGINAL = new LinkedHashMap<>();
	private static final Map<BlockPos, Integer> DOOR_READY = new HashMap<>();
	private static final Map<UUID, Integer> SLAMMER_READY = new HashMap<>();

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
		if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.OPEN)) {
			// Closing it: whatever else happens, the door still shuts (vanilla).
			slam(sp, (ServerLevel) level, state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos);
			return InteractionResult.PASS;
		}
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

	// ---------------------------------------------------------------- the door

	private static void slam(ServerPlayer by, ServerLevel level, BlockPos door) {
		int now = level.getServer().getTickCount();
		if (now < DOOR_READY.getOrDefault(door, 0) || now < SLAMMER_READY.getOrDefault(by.getUUID(), 0)) {
			return;
		}
		AABB doorway = new AABB(door).expandTowards(0, 1, 0).inflate(0.35, 0, 0.35);
		Vec3 center = Vec3.atBottomCenterOf(door);
		for (ServerPlayer shape : level.players()) {
			ShapeState st = MatchManager.role(shape) == Role.SHAPE && !shape.isSpectator() ? MatchManager.shapeState(shape) : null;
			if (st == null || st.shadowTicks > 0 || st.stunnedTicks > 0
				|| !shape.getBoundingBox().intersects(doorway) && shape.position().distanceTo(center) > 1.2) {
				continue;
			}
			DOOR_READY.put(door, now + DOOR_WAIT);
			SLAMMER_READY.put(by.getUUID(), now + SLAMMER_WAIT);
			st.recoverTicks = Math.max(st.recoverTicks, DAZE_TICKS);
			st.attackCooldown = Math.max(st.attackCooldown, DAZE_TICKS);
			shape.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, DAZE_TICKS, 4, false, false));
			// Thrown back out of the doorway, away from whoever shut it.
			Vec3 away = shape.position().subtract(by.position()).multiply(1, 0, 1);
			away = away.lengthSqr() < 1e-4 ? shape.getLookAngle().scale(-1).multiply(1, 0, 1) : away;
			shape.setDeltaMovement(away.normalize().scale(0.45).add(0, 0.1, 0));
			shape.hurtMarked = true;
			level.playSound(null, center.x, center.y + 1, center.z, HwSounds.DOOR_HIT, SoundSource.BLOCKS, 1.8f, 0.9f);
			level.playSound(null, shape.getX(), shape.getY() + 1.6, shape.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.HOSTILE, 1.4f, 0.6f);
			level.sendParticles(ParticleTypes.CRIT, shape.getX(), shape.getY() + 1.8, shape.getZ(), 14, 0.25, 0.1, 0.25, 0.05);
			Fight.stun(shape, SLAM_STUN, by);
			Notices.send(by, Component.translatable("halloween.obstacle.slam").withStyle(ChatFormatting.GOLD));
			if (!Bots.isBot(shape)) {
				shape.sendOverlayMessage(Component.translatable("halloween.obstacle.slammed").withStyle(ChatFormatting.DARK_RED));
			}
			AiStats.add(by, "obstacle.slam");
			return;
		}
	}

	/** The match is over: everything pulled down stands where it stood. */
	public static void clear(MinecraftServer server) {
		ServerLevel level = server.overworld();
		List<Map.Entry<BlockPos, BlockState>> back = new ArrayList<>(ORIGINAL.entrySet());
		for (int i = back.size() - 1; i >= 0; i--) {
			level.setBlock(back.get(i).getKey(), back.get(i).getValue(), Block.UPDATE_ALL);
		}
		ORIGINAL.clear();
		DOOR_READY.clear();
		SLAMMER_READY.clear();
	}
}
