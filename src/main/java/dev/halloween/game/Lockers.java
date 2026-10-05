package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Hiding in lockers. Whoever is inside gives the Shape nothing: no stalking, no Killer Sense marks or
 * Shape Jump beams, no Detection Impulse. One person per locker; getting in on the run slams the door
 * loud enough for the Shape to hear where; after {@link HwConfig#lockerMaxTicks} the air runs out and
 * they have to come out. The Shape can throw any locker open: if someone is inside, he has them.
 */
public final class Lockers {
	private static final class Inside {
		final LivingEntity who;
		final BlockPos base;
		final Direction facing;
		int ticks;
		boolean wasShift = true;

		Inside(LivingEntity who, BlockPos base, Direction facing) {
			this.who = who;
			this.base = base;
			this.facing = facing;
		}
	}

	private static final Map<UUID, Inside> INSIDE = new HashMap<>();
	private static final Map<BlockPos, UUID> OCCUPIED = new HashMap<>();

	private Lockers() {
	}

	public static boolean isInside(Entity entity) {
		return entity != null && INSIDE.containsKey(entity.getUUID());
	}

	public static boolean occupied(BlockPos base) {
		return OCCUPIED.containsKey(base);
	}

	private static Vec3 center(BlockPos base) {
		return Vec3.atBottomCenterOf(base);
	}

	/** Where you stand to get in, and where you come out. */
	public static BlockPos front(ServerLevel level, BlockPos base) {
		BlockState state = level.getBlockState(base);
		return base.relative(state.getValue(LockerBlock.FACING));
	}

	/** Use on a locker from the client: survivors get in (or out), the Shape throws it open. */
	public static void use(ServerPlayer player, BlockPos clicked) {
		if (!Match.running() || player.isSpectator()) {
			return;
		}
		Role role = MatchManager.role(player);
		if (role == Role.SHAPE) {
			shapeOpen(player, clicked);
		} else if (role.survivor()) {
			if (isInside(player)) {
				leave(player, false);
			} else {
				enter(player, clicked);
			}
		}
	}

	/** Climbs into the locker at {@code clicked}. Returns true when inside. */
	public static boolean enter(LivingEntity who, BlockPos clicked) {
		ServerLevel level = (ServerLevel) who.level();
		BlockPos base = LockerBlock.base(level, clicked);
		BlockState state = level.getBlockState(base);
		if (!Match.running() || !LockerBlock.is(state) || isInside(who) || MatchManager.isHeld(who)) {
			return false;
		}
		ServerPlayer player = who instanceof ServerPlayer p ? p : null;
		if (OCCUPIED.containsKey(base)) {
			if (player != null) {
				player.sendOverlayMessage(Component.translatable("halloween.locker.taken").withStyle(ChatFormatting.GRAY));
			}
			return false;
		}
		Direction facing = state.getValue(LockerBlock.FACING);
		if (who.position().distanceTo(center(base)) > 2.6) {
			return false;
		}
		boolean loud = who.isSprinting();
		Vec3 c = center(base);
		place(who, c, facing.toYRot());
		who.setSprinting(false);
		who.setInvisible(true);
		INSIDE.put(who.getUUID(), new Inside(who, base.immutable(), facing));
		OCCUPIED.put(base.immutable(), who.getUUID());
		HwConfig cfg = HwConfig.get();
		if (loud) {
			level.playSound(null, c.x, c.y + 1, c.z, HwSounds.LOCKER_SLAM, SoundSource.PLAYERS, 2.5f, 0.9f);
			// The Shape hears where.
			for (ServerPlayer p : level.players()) {
				if (MatchManager.role(p) == Role.SHAPE) {
					ServerPlayNetworking.send(p, new HwNet.Impulse(List.of(), List.of(c), cfg.lockerSlamRevealTicks));
					p.sendOverlayMessage(Component.translatable("halloween.msg.locker_slam").withStyle(ChatFormatting.GOLD));
				}
			}
		} else {
			level.playSound(null, c.x, c.y + 1, c.z, HwSounds.LOCKER_CLOSE, SoundSource.PLAYERS, 0.35f, 1.1f);
		}
		if (player != null) {
			player.sendOverlayMessage(Component.translatable("halloween.locker.inside", cfg.lockerMaxTicks / 20).withStyle(ChatFormatting.GRAY));
		}
		Holds.clear(who.getUUID());
		return true;
	}

	/** Steps out in front of the locker. */
	public static void leave(LivingEntity who, boolean outOfAir) {
		Inside inside = INSIDE.remove(who.getUUID());
		if (inside == null) {
			return;
		}
		OCCUPIED.remove(inside.base);
		who.setInvisible(false);
		ServerLevel level = (ServerLevel) who.level();
		BlockPos out = inside.base.relative(inside.facing);
		place(who, Vec3.atBottomCenterOf(out), inside.facing.toYRot());
		level.playSound(null, out, HwSounds.LOCKER_CLOSE, SoundSource.PLAYERS, outOfAir ? 1.2f : 0.4f, 0.9f);
		if (outOfAir && who instanceof ServerPlayer player) {
			player.sendOverlayMessage(Component.translatable("halloween.locker.out_of_air").withStyle(ChatFormatting.GOLD));
		}
	}

	private static void place(LivingEntity who, Vec3 pos, float yaw) {
		if (who instanceof ServerPlayer player) {
			player.teleportTo(player.level(), pos.x, pos.y, pos.z, Set.of(), yaw, 0f, false);
		} else {
			who.teleportTo(pos.x, pos.y, pos.z);
			who.setYRot(yaw);
			who.setYHeadRot(yaw);
			who.setYBodyRot(yaw);
		}
		who.setDeltaMovement(Vec3.ZERO);
	}

	/** The Shape throws a locker open; whoever hides inside is pulled out into his hands. */
	public static void shapeOpen(ServerPlayer shape, BlockPos clicked) {
		ServerLevel level = shape.level();
		BlockPos base = LockerBlock.base(level, clicked);
		ShapeState state = MatchManager.shapeState(shape);
		if (!LockerBlock.is(level.getBlockState(base)) || state == null || state.busy() || state.shadowTicks > 0 || state.attackCooldown > 0) {
			return;
		}
		if (shape.position().distanceTo(Vec3.atBottomCenterOf(front(level, base))) > HwConfig.get().grabRange + 0.5) {
			return;
		}
		state.attackCooldown = 20;
		Vec3 c = center(base);
		level.playSound(null, c.x, c.y + 1, c.z, HwSounds.LOCKER_SLAM, SoundSource.HOSTILE, 1.6f, 0.7f);
		UUID id = OCCUPIED.get(base);
		Inside inside = id == null ? null : INSIDE.get(id);
		if (inside == null) {
			shape.sendOverlayMessage(Component.translatable("halloween.locker.empty").withStyle(ChatFormatting.GRAY));
			return;
		}
		LivingEntity victim = inside.who;
		leave(victim, false);
		MatchManager.grabOut(shape, victim);
	}

	public static void tick(MinecraftServer server) {
		if (INSIDE.isEmpty()) {
			return;
		}
		HwConfig cfg = HwConfig.get();
		for (Inside inside : new ArrayList<>(INSIDE.values())) {
			LivingEntity who = inside.who;
			boolean gone = who.isRemoved() || !who.isAlive() || who instanceof ServerPlayer p && (p.isSpectator() || MatchManager.role(p) == Role.NONE);
			if (gone || !Match.running() && !Match.frozen()) {
				INSIDE.remove(who.getUUID());
				OCCUPIED.remove(inside.base);
				who.setInvisible(false);
				continue;
			}
			inside.ticks++;
			Vec3 c = center(inside.base);
			if (who.position().distanceToSqr(c) > 0.0025) {
				if (who instanceof ServerPlayer player) {
					player.teleportTo(player.level(), c.x, c.y, c.z, Set.of(Relative.X_ROT, Relative.Y_ROT), 0f, 0f, false);
				} else {
					who.teleportTo(c.x, c.y, c.z);
				}
				who.setDeltaMovement(Vec3.ZERO);
			}
			if (who instanceof ServerPlayer player) {
				boolean shift = player.isShiftKeyDown();
				if (shift && !inside.wasShift && inside.ticks > 10) {
					leave(player, false);
					continue;
				}
				inside.wasShift = shift;
				if (inside.ticks % 20 == 0) {
					int left = (cfg.lockerMaxTicks - inside.ticks) / 20;
					player.sendOverlayMessage(Component.translatable("halloween.locker.air", left).withStyle(left <= 10 ? ChatFormatting.GOLD : ChatFormatting.GRAY));
				}
			}
			if (inside.ticks >= cfg.lockerMaxTicks) {
				leave(who, true);
			}
		}
	}

	/** The nearest locker nobody is in, within {@code radius} blocks, or null. */
	public static BlockPos nearestFree(ServerLevel level, BlockPos from, int radius) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(from.offset(-radius, -2, -radius), from.offset(radius, 2, radius))) {
			BlockState state = level.getBlockState(pos);
			if (!LockerBlock.is(state) || state.getValue(LockerBlock.HALF) != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
				|| OCCUPIED.containsKey(pos)) {
				continue;
			}
			double d = pos.distSqr(from);
			if (d < bestDist) {
				bestDist = d;
				best = pos.immutable();
			}
		}
		return best;
	}

	public static void clear(MinecraftServer server) {
		for (Inside inside : new ArrayList<>(INSIDE.values())) {
			leave(inside.who, false);
		}
		INSIDE.clear();
		OCCUPIED.clear();
	}
}
