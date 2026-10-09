package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import net.minecraft.world.level.block.Block;
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
	/** When the Shape last threw each locker open: bots and townsfolk keep away from those for a while. */
	private static final Map<BlockPos, Integer> OPENED = new HashMap<>();
	/** Bots and townsfolk getting in quietly: how far along {count, last tick}, and into which locker. */
	private static final Map<UUID, int[]> CLIMBING = new HashMap<>();
	/** Who came out for air, until when they can't get into a locker again (nobody spends a whole match inside). */
	private static final Map<UUID, Integer> BREATHING = new HashMap<>();

	public static boolean needsAir(LivingEntity who) {
		Integer until = BREATHING.get(who.getUUID());
		return until != null && who.level().getServer().getTickCount() < until;
	}

	/** The server tick from which they may get into a locker again (0: any time). */
	public static int airBackAt(LivingEntity who) {
		Integer until = BREATHING.get(who.getUUID());
		return until == null ? 0 : until;
	}
	private static final Map<UUID, BlockPos> CLIMBING_INTO = new HashMap<>();

	private Lockers() {
	}

	public static boolean isInside(Entity entity) {
		return entity != null && INSIDE.containsKey(entity.getUUID());
	}

	public static LivingEntity occupant(BlockPos base) {
		UUID id = OCCUPIED.get(base);
		Inside inside = id == null ? null : INSIDE.get(id);
		return inside == null ? null : inside.who;
	}

	/** Who sits in this locker (tests, debugging), or null. */
	public static String occupantName(BlockPos base) {
		UUID id = OCCUPIED.get(base);
		Inside inside = id == null ? null : INSIDE.get(id);
		return inside == null ? null : inside.who.getPlainTextName();
	}

	public static boolean occupied(BlockPos base) {
		return OCCUPIED.containsKey(base);
	}

	private static Vec3 center(BlockPos base) {
		return Vec3.atBottomCenterOf(base);
	}

	/**
	 * A wardrobe whose door is up against something (a town built before a fix: one stood facing the fridge)
	 * is turned to a side that is free, the back to a wall where it can.
	 */
	public static void faceTheRoom(ServerLevel level, BlockPos base, BlockState state) {
		Direction facing = state.getValue(LockerBlock.FACING);
		if (free(level, base.relative(facing))) {
			return;
		}
		Direction best = null;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			if (d != facing && free(level, base.relative(d)) && (best == null || !free(level, base.relative(d.getOpposite())))) {
				best = d;
			}
		}
		if (best == null) {
			return;
		}
		level.setBlock(base, state.setValue(LockerBlock.FACING, best), Block.UPDATE_CLIENTS);
		BlockState upper = level.getBlockState(base.above());
		if (LockerBlock.is(upper)) {
			level.setBlock(base.above(), upper.setValue(LockerBlock.FACING, best), Block.UPDATE_CLIENTS);
		}
	}

	private static boolean free(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
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
		} else if (role == Role.RUNT) {
			Runt.openLocker(player, clicked);
		} else if (role.survivor()) {
			if (isInside(player)) {
				leave(player, false);
			} else {
				enter(player, clicked);
			}
		}
	}

	/**
	 * Hold use on a locker: on the run you are in at once (and loud), otherwise you climb in slowly and
	 * quietly. Null when there is nothing to hold.
	 */
	public static Holds.Hold resolve(ServerPlayer player, BlockPos clicked) {
		ServerLevel level = player.level();
		BlockPos base = LockerBlock.base(level, clicked);
		if (!Match.running() || !MatchManager.role(player).survivor() || !LockerBlock.is(level.getBlockState(base)) || isInside(player)) {
			return null;
		}
		if (needsAir(player)) {
			player.sendOverlayMessage(Component.translatable("halloween.locker.need_air").withStyle(ChatFormatting.GRAY));
			return null;
		}
		if (player.isSprinting() || OCCUPIED.containsKey(base)) {
			enter(player, base, true);
			return null;
		}
		return new Holds.Hold(base, "halloween.hud.progress.locker", false, HwConfig.get().lockerQuietTicks,
			p -> !OCCUPIED.containsKey(base) && !isInside(p), p -> enter(p, base, false));
	}

	/**
	 * Bots and townsfolk standing at a locker call this every tick: in at once if running, otherwise after
	 * the same quiet climb a player makes. 1 inside, 0 still climbing, -1 can't get in.
	 */
	public static int climbIn(LivingEntity who, BlockPos clicked) {
		ServerLevel level = (ServerLevel) who.level();
		BlockPos base = LockerBlock.base(level, clicked);
		if (isInside(who)) {
			return 1;
		}
		if (needsAir(who)) {
			// Just out for air: somewhere else will have to do for now.
			CLIMBING.remove(who.getUUID());
			return -1;
		}
		if (!LockerBlock.is(level.getBlockState(base)) || OCCUPIED.containsKey(base) || who.position().distanceTo(center(base)) > 2.6) {
			CLIMBING.remove(who.getUUID());
			return -1;
		}
		if (who.isSprinting()) {
			CLIMBING.remove(who.getUUID());
			return enter(who, base, true) ? 1 : -1;
		}
		int now = level.getServer().getTickCount();
		int[] climb = CLIMBING.get(who.getUUID());
		if (climb == null || !base.equals(CLIMBING_INTO.get(who.getUUID())) || now - climb[1] > 2) {
			climb = new int[]{0, now};
			CLIMBING.put(who.getUUID(), climb);
			CLIMBING_INTO.put(who.getUUID(), base.immutable());
		}
		climb[0]++;
		climb[1] = now;
		if (climb[0] < HwConfig.get().lockerQuietTicks) {
			return 0;
		}
		CLIMBING.remove(who.getUUID());
		return enter(who, base, false) ? 1 : -1;
	}

	/** Climbs into the locker at {@code clicked} at once, loud if on the run. Returns true when inside. */
	public static boolean enter(LivingEntity who, BlockPos clicked) {
		return enter(who, clicked, who.isSprinting());
	}

	/** Climbs into the locker at {@code clicked} at once. Returns true when inside. */
	public static boolean enter(LivingEntity who, BlockPos clicked, boolean loud) {
		ServerLevel level = (ServerLevel) who.level();
		BlockPos base = LockerBlock.base(level, clicked);
		BlockState state = level.getBlockState(base);
		if (!Match.running() || !LockerBlock.is(state) || isInside(who) || MatchManager.isHeld(who) || needsAir(who)) {
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
		if (outOfAir) {
			BREATHING.put(who.getUUID(), level.getServer().getTickCount() + HwConfig.get().lockerBreathTicks);
		}
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
		OPENED.put(base.immutable(), level.getServer().getTickCount());
		Vec3 c = center(base);
		level.playSound(null, c.x, c.y + 1, c.z, HwSounds.LOCKER_SLAM, SoundSource.HOSTILE, 1.6f, 0.7f);
		UUID id = OCCUPIED.get(base);
		Inside inside = id == null ? null : INSIDE.get(id);
		if (inside == null) {
			shape.sendOverlayMessage(Component.translatable("halloween.locker.empty").withStyle(ChatFormatting.GRAY));
			return;
		}
		AiStats.add(inside.who, "locker.pulled_out");
		LivingEntity victim = inside.who;
		leave(victim, false);
		MatchManager.grabOut(shape, victim);
	}

	/** Whose breathing in a locker each Michael is hearing now (entity ids). */
	private static final Map<UUID, Set<Integer>> HEARD = new HashMap<>();

	/**
	 * Hiding doesn't stop you breathing: Michael close to an occupied locker hears it, quietly, from inside
	 * (only he, and only while he is near). Someone panting anyway (out of breath, panicking) everyone hears already.
	 */
	private static void breathing(MinecraftServer server) {
		HwConfig cfg = HwConfig.get();
		for (ServerPlayer shape : server.getPlayerList().getPlayers()) {
			Set<Integer> before = HEARD.getOrDefault(shape.getUUID(), Set.of());
			Set<Integer> now = new HashSet<>();
			if (MatchManager.role(shape) == Role.SHAPE && Match.running() && !Bots.isBot(shape)) {
				for (Inside inside : INSIDE.values()) {
					if (center(inside.base).distanceTo(shape.position()) < cfg.lockerHearRange) {
						now.add(inside.who.getId());
						if (!before.contains(inside.who.getId())) {
							ServerPlayNetworking.send(shape, new HwNet.Pant(inside.who.getId(), true, Voices.female(inside.who), cfg.lockerBreathVolume));
						}
					}
				}
			}
			for (int id : before) {
				if (!now.contains(id)) {
					net.minecraft.world.entity.Entity e = shape.level().getEntity(id);
					if (!Stamina.panting(e)) {
						ServerPlayNetworking.send(shape, new HwNet.Pant(id, false, false, 0f));
					}
				}
			}
			if (now.isEmpty()) {
				HEARD.remove(shape.getUUID());
			} else {
				HEARD.put(shape.getUUID(), now);
			}
		}
	}

	/**
	 * Someone of Michael's side throws a locker open from in front of it: a slam, nobody goes back in there for a
	 * while, and whoever was inside steps out (returned; null if it was empty or out of reach).
	 */
	public static LivingEntity throwOpen(ServerPlayer by, BlockPos clicked) {
		ServerLevel level = by.level();
		BlockPos base = LockerBlock.base(level, clicked);
		if (!LockerBlock.is(level.getBlockState(base)) || by.position().distanceTo(Vec3.atBottomCenterOf(front(level, base))) > HwConfig.get().grabRange + 0.5) {
			return null;
		}
		OPENED.put(base.immutable(), level.getServer().getTickCount());
		Vec3 c = center(base);
		level.playSound(null, c.x, c.y + 1, c.z, HwSounds.LOCKER_SLAM, SoundSource.HOSTILE, 1.6f, 0.8f);
		UUID id = OCCUPIED.get(base);
		Inside inside = id == null ? null : INSIDE.get(id);
		if (inside == null) {
			by.sendOverlayMessage(Component.translatable("halloween.locker.empty").withStyle(ChatFormatting.GRAY));
			return null;
		}
		AiStats.add(inside.who, "locker.pulled_out");
		LivingEntity hider = inside.who;
		leave(hider, false);
		return hider;
	}

	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 10 == 0 && (!INSIDE.isEmpty() || !HEARD.isEmpty())) {
			breathing(server);
		}
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

	/**
	 * The nearest free locker that is at least {@code minDist} from {@code avoid} (where the Shape last saw
	 * whoever is hiding: he searches around there), or null.
	 */
	public static BlockPos nearestFreeAway(ServerLevel level, BlockPos from, int radius, Vec3 avoid, double minDist) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(from.offset(-radius, -2, -radius), from.offset(radius, 2, radius))) {
			BlockState state = level.getBlockState(pos);
			if (!LockerBlock.is(state) || state.getValue(LockerBlock.HALF) != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
				|| OCCUPIED.containsKey(pos) || avoid != null && avoid.distanceTo(Vec3.atBottomCenterOf(pos)) < minDist
				|| recentlyOpened(level, pos)) {
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

	/** The Shape threw this locker open not long ago: he knows it, so it is no hiding place now. */
	public static boolean recentlyOpened(ServerLevel level, BlockPos base) {
		Integer at = OPENED.get(base);
		return at != null && level.getServer().getTickCount() - at < HwConfig.get().lockerAvoidAfterOpenTicks;
	}

	public static void clear(MinecraftServer server) {
		OPENED.clear();
		HEARD.clear();
		BREATHING.clear();
		CLIMBING.clear();
		CLIMBING_INTO.clear();
		for (Inside inside : new ArrayList<>(INSIDE.values())) {
			leave(inside.who, false);
		}
		INSIDE.clear();
		OCCUPIED.clear();
	}
}
