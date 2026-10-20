package dev.halloween.game;

import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Hold-use interactions with match objects (search, repair, call, open, escape...). The client pings
 * about every 4 ticks while the button is held; the action completes after its duration if the player
 * keeps holding, stays close and the action is still possible.
 */
public final class Holds {
	@FunctionalInterface
	public interface Check {
		boolean test(ServerPlayer player);
	}

	@FunctionalInterface
	public interface Done {
		void run(ServerPlayer player);
	}

	/** {@code checks}: reaction checks pop up while holding and shorten it. */
	public record Hold(BlockPos pos, String label, boolean danger, int ticks, Check valid, Done done, boolean checks) {
		public Hold(BlockPos pos, String label, boolean danger, int ticks, Check valid, Done done) {
			this(pos, label, danger, ticks, valid, done, false);
		}
	}

	private static final class Work {
		final Hold hold;
		int ticks;
		int lastPing;

		Work(Hold hold, int now) {
			this.hold = hold;
			this.lastPing = now;
		}
	}

	private static final Map<UUID, Work> WORK = new HashMap<>();
	/** After an action completes, the button must be let go before the next one starts (no accidental chains). */
	private static final Map<UUID, Integer> RELEASE = new HashMap<>();

	private Holds() {
	}

	public static void ping(ServerPlayer player, BlockPos pos) {
		if (!Match.running() || player.isSpectator() || MatchManager.role(player) == Role.NONE) {
			return;
		}
		int now = player.level().getServer().getTickCount();
		if (RELEASE.containsKey(player.getUUID())) {
			RELEASE.put(player.getUUID(), now);
			return;
		}
		if (LockerBlock.is(player.level().getBlockState(pos))) {
			// Either half of a locker is the same locker.
			pos = LockerBlock.base(player.level(), pos);
		}
		Work work = WORK.get(player.getUUID());
		if (work != null && work.hold.pos().equals(pos)) {
			work.lastPing = now;
			return;
		}
		if (Booths.isBooth(pos)) {
			// A telephone booth lifts Vecna's curse; it is nobody's phone to call the police from or cut.
			Holds.Hold booth = Booths.resolve(player, pos);
			if (booth != null) {
				WORK.put(player.getUUID(), new Work(booth, now));
			}
			return;
		}
		// A match event's object first (a ringing phone, the bag, the transformer, the crate in the fire).
		Hold hold = Events.resolve(player, pos);
		if (hold == null) {
			hold = GunCase.resolve(player, pos);
			if (hold == null && GunCase.isCase(pos)) {
				return;
			}
		}
		if (hold == null) {
			hold = Loot.resolve(player, pos);
		}
		if (hold == null) {
			hold = Exits.resolve(player, pos);
		}
		if (hold == null) {
			hold = Lockers.resolve(player, pos);
		}
		if (hold == null) {
			hold = LightSystem.resolve(player, pos);
		}
		if (hold != null) {
			WORK.put(player.getUUID(), new Work(hold, now));
		}
	}

	public static void tick(MinecraftServer server) {
		int now = server.getTickCount();
		RELEASE.values().removeIf(last -> now - last > 6);
		Iterator<Map.Entry<UUID, Work>> it = WORK.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Work> entry = it.next();
			Work work = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			boolean keep = player != null && Match.running() && !player.isSpectator()
				&& now - work.lastPing <= 8
				&& player.getEyePosition().distanceTo(Vec3.atCenterOf(work.hold.pos())) <= 4.5
				&& !MatchManager.isGrabbed(player) && !MatchManager.isStunned(player)
				&& work.hold.valid().test(player);
			if (!keep) {
				it.remove();
				if (player != null) {
					SkillChecks.stop(player);
				}
				if (player != null) {
					ServerPlayNetworking.send(player, HwNet.Progress.NONE);
				}
				continue;
			}
			work.ticks++;
			if (work.hold.checks()) {
				work.ticks += SkillChecks.tick(player, SkillChecks.kindFor(work.hold.label()));
			}
			if (work.ticks >= work.hold.ticks()) {
				RELEASE.put(entry.getKey(), now);
				it.remove();
				SkillChecks.stop(player);
				ServerPlayNetworking.send(player, HwNet.Progress.NONE);
				work.hold.done().run(player);
				Tutorial.holdDone(player, work.hold.label());
				Match.markDirty();
			} else if (work.ticks % 2 == 0) {
				ServerPlayNetworking.send(player, new HwNet.Progress(work.hold.label(), work.ticks / (float) work.hold.ticks(), work.hold.danger()));
			}
		}
	}

	/** Drops whatever this player was holding use on. */
	public static void clear(UUID player) {
		WORK.remove(player);
	}

	/** Rummaging through a container right now? */
	public static boolean searching(ServerPlayer player) {
		Work work = WORK.get(player.getUUID());
		return work != null && work.hold.label().equals("halloween.hud.progress.search");
	}

	/** Is this player in the middle of a hold action? */
	public static boolean working(ServerPlayer player) {
		return WORK.containsKey(player.getUUID());
	}

	public static void clear() {
		WORK.clear();
		SkillChecks.clear();
		RELEASE.clear();
	}
}
