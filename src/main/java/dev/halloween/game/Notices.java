package dev.halloween.game;

import dev.halloween.net.HwNet;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Short notices in the corner of the screen: match news, callouts, deaths. They replace chat lines. */
public final class Notices {
	private Notices() {
	}

	public static void send(ServerPlayer player, Component text) {
		ServerPlayNetworking.send(player, new HwNet.Notice(text));
	}

	public static void all(MinecraftServer server, Component text) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			send(player, text);
		}
	}
}
