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

	public record Hold(BlockPos pos, String label, boolean danger, int ticks, Check valid, Done done) {
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
		Work work = WORK.get(player.getUUID());
		if (work != null && work.hold.pos().equals(pos)) {
			work.lastPing = now;
			return;
		}
		Hold hold = Loot.resolve(player, pos);
		if (hold == null) {
			hold = Exits.resolve(player, pos);
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
					ServerPlayNetworking.send(player, HwNet.Progress.NONE);
				}
				continue;
			}
			work.ticks++;
			if (work.ticks >= work.hold.ticks()) {
				RELEASE.put(entry.getKey(), now);
				it.remove();
				ServerPlayNetworking.send(player, HwNet.Progress.NONE);
				work.hold.done().run(player);
				Match.markDirty();
			} else if (work.ticks % 2 == 0) {
				ServerPlayNetworking.send(player, new HwNet.Progress(work.hold.label(), work.ticks / (float) work.hold.ticks(), work.hold.danger()));
			}
		}
	}

	/** Is this player in the middle of a hold action? */
	public static boolean working(ServerPlayer player) {
		return WORK.containsKey(player.getUUID());
	}

	public static void clear() {
		WORK.clear();
		RELEASE.clear();
	}
}
