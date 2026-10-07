package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * Door locks. Civilians lock a closed door by crouching and holding use on it; unlocking takes twice
 * as long. A locked door is a wall for the Shape: he breaks it with blows (the lock is ruined for good)
 * or slips through any door while in Shape Jump. State lives for the session and is cleared by reset.
 */
public final class DoorLocks {
	private static final Set<BlockPos> LOCKED = new HashSet<>();
	private static final Set<BlockPos> BROKEN = new HashSet<>();
	private static final Map<BlockPos, Integer> DAMAGE = new HashMap<>();
	/** Who locked each door (a Barricade perk makes it tougher). */
	private static final Map<BlockPos, UUID> LOCKED_BY = new HashMap<>();
	private static final Map<UUID, Work> WORK = new HashMap<>();

	/** A civilian holding use on a door. */
	private static final class Work {
		final BlockPos door;
		final boolean unlocking;
		final int needed;
		int ticks;
		int lastPing;

		Work(BlockPos door, boolean unlocking, int needed, int now) {
			this.door = door;
			this.unlocking = unlocking;
			this.needed = needed;
			this.lastPing = now;
		}
	}

	private DoorLocks() {
	}

	public static boolean isDoor(BlockState state) {
		return state.getBlock() instanceof DoorBlock;
	}

	/** Lower half of the door at {@code pos}. */
	public static BlockPos base(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return isDoor(state) && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos.immutable();
	}

	public static boolean isLocked(Level level, BlockPos pos) {
		return LOCKED.contains(base(level, pos));
	}

	public static boolean isLockBroken(Level level, BlockPos pos) {
		return BROKEN.contains(base(level, pos));
	}

	public static void clear(MinecraftServer server) {
		LOCKED.clear();
		LOCKED_BY.clear();
		BROKEN.clear();
		DAMAGE.clear();
		for (UUID id : WORK.keySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null) {
				ServerPlayNetworking.send(player, HwNet.Progress.NONE);
			}
		}
		WORK.clear();
		sync(server);
	}

	// ---------------------------------------------------------------- vanilla use

	/** Locked doors don't open by hand; crouching civilians lock instead of opening. */
	public static InteractionResult onUseBlock(Player player, Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!isDoor(state) || !(player instanceof ServerPlayer sp)) {
			return InteractionResult.PASS;
		}
		Role role = MatchManager.role(sp);
		if (role.survivor() && sp.isShiftKeyDown()) {
			return InteractionResult.FAIL;
		}
		if (isLocked(level, pos)) {
			Component msg = role == Role.SHAPE
				? Component.translatable("halloween.msg.door_locked.shape")
				: Component.translatable("halloween.msg.door_locked.civilian");
			sp.sendOverlayMessage(msg.copy().withStyle(ChatFormatting.GRAY));
			level.playSound(null, pos, HwSounds.DOOR_RATTLE, SoundSource.BLOCKS, 0.8f, 1f);
			return InteractionResult.FAIL;
		}
		return InteractionResult.PASS;
	}

	// ---------------------------------------------------------------- lock / unlock

	/** A civilian holding use on a door; arrives about every 4 ticks while held. */
	public static void ping(ServerPlayer player, BlockPos clicked) {
		ServerLevel level = player.level();
		if (!MatchManager.role(player).survivor() || player.isSpectator() || !isDoor(level.getBlockState(clicked))) {
			return;
		}
		BlockPos door = base(level, clicked);
		if (!close(player, door)) {
			return;
		}
		int now = level.getServer().getTickCount();
		Work work = WORK.get(player.getUUID());
		if (work != null && work.door.equals(door)) {
			work.lastPing = now;
			return;
		}
		if (BROKEN.contains(door)) {
			player.sendOverlayMessage(Component.translatable("halloween.msg.lock_broken").withStyle(ChatFormatting.GRAY));
			return;
		}
		HwConfig cfg = HwConfig.get();
		boolean unlocking = LOCKED.contains(door);
		if (!unlocking && level.getBlockState(door).getValue(DoorBlock.OPEN)) {
			// Locking pulls the door shut first.
			setOpen(level, door, false);
		}
		int unlock = Perks.has(player, Perks.Perk.TRESPASSER) ? Math.round(cfg.unlockTicks * cfg.perkTrespasserFactor) : cfg.unlockTicks;
		WORK.put(player.getUUID(), new Work(door, unlocking, unlocking ? unlock : cfg.lockTicks, now));
	}

	private static boolean close(ServerPlayer player, BlockPos door) {
		return player.getEyePosition().distanceTo(Vec3.atCenterOf(door.above())) <= 3.6;
	}

	public static void tick(MinecraftServer server) {
		int now = server.getTickCount();
		Iterator<Map.Entry<UUID, Work>> it = WORK.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Work> entry = it.next();
			Work work = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			boolean keep = player != null
				&& now - work.lastPing <= 8
				&& player.isShiftKeyDown()
				&& MatchManager.role(player).survivor()
				&& !MatchManager.isGrabbed(player)
				&& isDoor(player.level().getBlockState(work.door))
				&& close(player, work.door)
				&& !BROKEN.contains(work.door)
				&& LOCKED.contains(work.door) == work.unlocking;
			if (!keep) {
				it.remove();
				if (player != null) {
					ServerPlayNetworking.send(player, HwNet.Progress.NONE);
				}
				continue;
			}
			work.ticks++;
			String label = work.unlocking ? "halloween.hud.progress.unlock" : "halloween.hud.progress.lock";
			if (work.ticks >= work.needed) {
				it.remove();
				ServerLevel level = player.level();
				if (work.unlocking) {
					LOCKED.remove(work.door);
					LOCKED_BY.remove(work.door);
				} else {
					setOpen(level, work.door, false);
					LOCKED.add(work.door);
					LOCKED_BY.put(work.door, player.getUUID());
					DAMAGE.remove(work.door);
				}
				level.playSound(null, work.door, work.unlocking ? HwSounds.UNLOCK : HwSounds.LOCK, SoundSource.BLOCKS, 1f, 1f);
				ServerPlayNetworking.send(player, HwNet.Progress.NONE);
				player.sendOverlayMessage(Component.translatable(work.unlocking ? "halloween.msg.unlocked" : "halloween.msg.locked"));
				sync(server);
			} else if (work.ticks % 2 == 0) {
				ServerPlayNetworking.send(player, new HwNet.Progress(label, work.ticks / (float) work.needed, false));
			}
		}
	}

	// ---------------------------------------------------------------- breaking

	/** The Shape struck a door. Returns true if it was a locked door (the blow is used up on it). */
	static boolean hit(ServerPlayer shape, BlockPos pos, boolean heavy) {
		ServerLevel level = shape.level();
		if (!isDoor(level.getBlockState(pos))) {
			return false;
		}
		BlockPos door = base(level, pos);
		if (!LOCKED.contains(door)) {
			return false;
		}
		HwConfig cfg = HwConfig.get();
		int damage = DAMAGE.merge(door, heavy ? cfg.doorHeavyHitPoints : cfg.doorLightHitPoints, Integer::sum);
		Vec3 c = Vec3.atCenterOf(door.above());
		// Everyone around hears him battering at it.
		Bots.alert(Vec3.atCenterOf(door), level.getServer().getTickCount());
		Townsfolk.hearNoise(level, Vec3.atCenterOf(door), level.getServer().getTickCount());
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(door)), c.x, c.y - 0.5, c.z, 12, 0.3, 0.6, 0.3, 0.1);
		int needed = Perks.has(LOCKED_BY.get(door), Perks.Perk.BARRICADE) ? (int) Math.ceil(cfg.doorBreakPoints * cfg.perkBarricadeFactor) : cfg.doorBreakPoints;
		if (damage >= needed) {
			LOCKED.remove(door);
			LOCKED_BY.remove(door);
			BROKEN.add(door);
			DAMAGE.remove(door);
			setOpen(level, door, true);
			level.playSound(null, door, HwSounds.DOOR_BREAK, SoundSource.BLOCKS, 2.5f, 0.9f);
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(door)), c.x, c.y - 0.5, c.z, 40, 0.4, 0.8, 0.4, 0.2);
			sync(level.getServer());
		} else {
			level.playSound(null, door, HwSounds.DOOR_HIT, SoundSource.BLOCKS, 1.8f, 1f);
			shape.sendOverlayMessage(Component.translatable("halloween.msg.door_hits", needed - damage));
		}
		return true;
	}

	private static void setOpen(Level level, BlockPos door, boolean open) {
		BlockState lower = level.getBlockState(door);
		BlockState upper = level.getBlockState(door.above());
		if (isDoor(lower)) {
			level.setBlock(door, lower.setValue(DoorBlock.OPEN, open), Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
		}
		if (isDoor(upper)) {
			level.setBlock(door.above(), upper.setValue(DoorBlock.OPEN, open), Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
		}
	}

	// ---------------------------------------------------------------- sync

	/** Locks a door outright (townsfolk do the waiting themselves). */
	public static void forceLock(ServerLevel level, BlockPos pos) {
		BlockPos door = base(level, pos);
		if (!isDoor(level.getBlockState(door)) || LOCKED.contains(door) || BROKEN.contains(door)) {
			return;
		}
		setOpen(level, door, false);
		LOCKED.add(door);
		DAMAGE.remove(door);
		level.playSound(null, door, HwSounds.LOCK, SoundSource.BLOCKS, 1f, 1f);
		sync(level.getServer());
	}

	/** Unlocks a door outright (officers do the waiting themselves). */
	public static void forceUnlock(ServerLevel level, BlockPos pos) {
		BlockPos door = base(level, pos);
		if (!LOCKED.remove(door)) {
			return;
		}
		level.playSound(null, door, HwSounds.UNLOCK, SoundSource.BLOCKS, 1f, 1f);
		sync(level.getServer());
	}

	public static void sync(MinecraftServer server) {
		HwNet.DoorSync sync = new HwNet.DoorSync(new ArrayList<>(LOCKED), new ArrayList<>(BROKEN));
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(player, sync);
		}
	}

	public static void syncTo(ServerPlayer player) {
		ServerPlayNetworking.send(player, new HwNet.DoorSync(new ArrayList<>(LOCKED), new ArrayList<>(BROKEN)));
	}
}
