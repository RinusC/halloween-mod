package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Hands-on jobs played on a panel over the world: joining the wires of a breaker box to switch the
 * power back on, and guiding the tool along a line to fix the car with a repair kit. The player stands
 * still while the panel is open; Esc walks away and the client remembers how far they got. Slipping
 * off the line makes a racket the Shape hears. Bots don't play: they just take a while (see the holds).
 */
public final class Minigames {
	private record Session(int kind, BlockPos pos, long seed) {
	}

	private static final Map<UUID, Session> OPEN = new HashMap<>();

	private Minigames() {
	}

	/** Opens (or keeps open) the panel for this job. */
	public static void open(ServerPlayer player, int kind, BlockPos pos) {
		Session now = OPEN.get(player.getUUID());
		if (now != null && now.kind == kind && now.pos.equals(pos)) {
			return;
		}
		// The same box or car gets the same layout all match, so coming back picks up where you left off.
		long seed = pos.asLong() * 31 + kind * 7919L + Match.startTick();
		Session session = new Session(kind, pos.immutable(), seed);
		OPEN.put(player.getUUID(), session);
		Holds.clear(player.getUUID());
		ServerPlayNetworking.send(player, new HwNet.Minigame(kind, session.pos, seed));
	}

	public static boolean active(ServerPlayer player) {
		return OPEN.containsKey(player.getUUID());
	}

	public static void result(ServerPlayer player, HwNet.MinigameResult result) {
		Session session = OPEN.get(player.getUUID());
		if (session == null || session.kind != result.kind() || !session.pos.equals(result.pos())) {
			return;
		}
		switch (result.outcome()) {
			case HwNet.MinigameResult.DONE -> {
				OPEN.remove(player.getUUID());
				finish(player, session);
			}
			case HwNet.MinigameResult.SLIP -> noise(player.level(), session.pos);
			default -> OPEN.remove(player.getUUID());
		}
	}

	private static void finish(ServerPlayer player, Session session) {
		ServerLevel level = player.level();
		if (session.kind == HwNet.Minigame.WIRES) {
			if (LightSystem.isBreaker(level, session.pos) && !LightSystem.isOn(level, session.pos)) {
				LightSystem.flip(level, session.pos, true);
				AiStats.add(player, "power.on");
			}
		} else if (session.kind == HwNet.Minigame.CURVE && Exits.canRepairCar(player)) {
			Exits.repairCar(player);
		}
		Match.markDirty();
	}

	/** A clang of tools: loud, and the Shape gets a mark where it came from. */
	private static void noise(ServerLevel level, BlockPos pos) {
		Vec3 c = Vec3.atCenterOf(pos);
		level.playSound(null, c.x, c.y, c.z, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 1.6f, 1.5f);
		HwConfig cfg = HwConfig.get();
		for (ServerPlayer p : level.players()) {
			if (MatchManager.role(p) == Role.SHAPE) {
				ServerPlayNetworking.send(p, new HwNet.Impulse(List.of(), List.of(c), cfg.minigameNoiseRevealTicks));
			}
		}
	}

	/** Closes panels of whoever was caught, knocked out, dragged away or is out of the match. */
	public static void tick(MinecraftServer server) {
		Iterator<Map.Entry<UUID, Session>> it = OPEN.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Session> e = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			boolean keep = player != null && Match.running() && !player.isSpectator() && MatchManager.role(player).survivor()
				&& !MatchManager.isGrabbed(player) && player.getEyePosition().distanceTo(Vec3.atCenterOf(e.getValue().pos)) <= 4.5;
			if (!keep) {
				it.remove();
				if (player != null) {
					ServerPlayNetworking.send(player, HwNet.Minigame.close(e.getValue().pos));
				}
			}
		}
	}

	public static void clear() {
		OPEN.clear();
	}
}
