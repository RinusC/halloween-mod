package dev.halloween.game;

import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Marks for the team: a survivor points at something and every survivor sees it for a while, with who marked it.
 * Michael can only be marked by someone who sees him (and the mark follows him while it lasts); a place, a thing to
 * search, something lying about or a way out by anyone. He never sees them.
 */
public final class Pings {
	public static final int PLACE = 0;
	public static final int KILLER = 1;
	public static final int ITEM = 2;
	public static final int SEARCH = 3;
	public static final int EXIT = 4;

	private static final int TICKS = 160;
	private static final int WAIT = 30;
	private static final Map<UUID, Integer> READY_AT = new HashMap<>();

	private Pings() {
	}

	public static void clear() {
		READY_AT.clear();
	}

	public static void onPing(ServerPlayer player, Vec3 pos, int kind, int entityId) {
		if (!Match.running() || !MatchManager.role(player).survivor() || player.isSpectator()) {
			return;
		}
		ServerLevel level = player.level();
		int now = level.getServer().getTickCount();
		if (now < READY_AT.getOrDefault(player.getUUID(), 0) || pos.distanceTo(player.getEyePosition()) > 96 || kind < PLACE || kind > EXIT) {
			return;
		}
		READY_AT.put(player.getUUID(), now + WAIT);
		if (kind == KILLER) {
			Entity e = level.getEntity(entityId);
			if (e instanceof ServerPlayer shape && MatchManager.role(shape) == Role.SHAPE && !shape.isSpectator()
				&& Sight.canSee(level, player.getEyePosition(), FocusPoints.bodyCenter(shape), player)) {
				killer(player, shape, TICKS);
				return;
			}
			kind = PLACE;
		}
		send(level, new HwNet.TeamPing(pos, kind, -1, name(player), TICKS));
		AiStats.add(player, "ping");
	}

	/** Michael marked for everyone on the team (a mark, or Richard's warning). */
	public static void killer(ServerPlayer from, ServerPlayer shape, int ticks) {
		send(from.level(), new HwNet.TeamPing(shape.position(), KILLER, shape.getId(), name(from), ticks));
	}

	/** A mark from something other than a survivor (the radio, the dog): {@code from} "#radio", "#dog". */
	public static void mark(ServerLevel level, net.minecraft.world.phys.Vec3 pos, int kind, int entityId, String from, int ticks) {
		send(level, new HwNet.TeamPing(pos, kind, entityId, from, ticks));
	}

	private static String name(ServerPlayer player) {
		String hero = Heroes.hero(player);
		return hero != null ? hero : player.getScoreboardName();
	}

	private static void send(ServerLevel level, HwNet.TeamPing ping) {
		for (ServerPlayer p : level.players()) {
			Role role = MatchManager.role(p);
			if (role != Role.NONE && role != Role.SHAPE && role != Role.RUNT && !Bots.isBot(p)) {
				ServerPlayNetworking.send(p, ping);
			}
		}
	}
}
