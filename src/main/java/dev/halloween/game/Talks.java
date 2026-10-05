package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Talking a townsperson round: the civilian picks what to ask in the radial menu (follow me, hide,
 * call the police), then talks for {@link HwConfig#talkTicks}; reaction checks shorten it. Using the
 * townsperson again (or walking off, or the townsperson panicking) breaks the talk off.
 */
public final class Talks {
	private static final class Talk {
		final ServerPlayer player;
		final Entity npc;
		final int option;
		int progress;

		Talk(ServerPlayer player, Entity npc, int option) {
			this.player = player;
			this.npc = npc;
			this.option = option;
		}
	}

	private static final Map<UUID, Talk> TALKS = new HashMap<>();

	private Talks() {
	}

	public static boolean isTalking(ServerPlayer player) {
		return TALKS.containsKey(player.getUUID());
	}

	/** From the radial menu: start talking to {@code npc} about {@code option}, or (-1) break off. */
	public static void request(ServerPlayer player, int entityId, int option) {
		if (option < 0 || isTalking(player)) {
			cancel(player, true);
			return;
		}
		start(player, player.level().getEntity(entityId), option);
	}

	public static boolean start(ServerPlayer player, Entity npc, int option) {
		if (npc == null || !Townsfolk.canTalk(player, npc)) {
			return false;
		}
		if (option == Townsfolk.CALL && !Exits.policeCallable()) {
			// Nothing to tell the police until somebody finds a body.
			player.sendOverlayMessage(Component.translatable("halloween.hint.police_no_body").withStyle(ChatFormatting.GRAY));
			return false;
		}
		if (option == Townsfolk.FOLLOW && Townsfolk.followsPlayer(npc, player)) {
			// "Stay here" to someone already following you needs no persuading.
			Townsfolk.beginTalk(player, npc);
			Townsfolk.apply(player, npc, option);
			return true;
		}
		Townsfolk.beginTalk(player, npc);
		TALKS.put(player.getUUID(), new Talk(player, npc, option));
		AiStats.add(player, "talk.start");
		return true;
	}

	public static void cancel(ServerPlayer player, boolean byPlayer) {
		Talk talk = TALKS.remove(player.getUUID());
		if (talk == null) {
			return;
		}
		Townsfolk.endTalk(talk.npc);
		SkillChecks.stop(player);
		ServerPlayNetworking.send(player, HwNet.Progress.NONE);
		if (!byPlayer) {
			player.sendOverlayMessage(Component.translatable("halloween.npc.talk_broken").withStyle(ChatFormatting.GRAY));
		}
	}

	public static void tick(MinecraftServer server) {
		if (TALKS.isEmpty()) {
			return;
		}
		HwConfig cfg = HwConfig.get();
		for (Talk talk : new ArrayList<>(TALKS.values())) {
			ServerPlayer player = talk.player;
			boolean ok = Match.running() && !player.isRemoved() && !player.isSpectator() && MatchManager.role(player).survivor()
				&& !MatchManager.isGrabbed(player) && Townsfolk.listensTo(talk.npc, player)
				&& player.distanceTo(talk.npc) <= cfg.talkRange + 0.5;
			if (!ok) {
				cancel(player, false);
				continue;
			}
			talk.progress += 1 + SkillChecks.tick(player);
			if (talk.progress >= cfg.talkTicks) {
				TALKS.remove(player.getUUID());
				SkillChecks.stop(player);
				ServerPlayNetworking.send(player, HwNet.Progress.NONE);
				Townsfolk.apply(player, talk.npc, talk.option);
				AiStats.add(player, "talk.done");
			} else if (talk.progress % 2 == 0) {
				ServerPlayNetworking.send(player, new HwNet.Progress("halloween.hud.progress.talk." + talk.option,
					talk.progress / (float) cfg.talkTicks, false));
			}
		}
	}

	public static void clear() {
		TALKS.clear();
	}
}
