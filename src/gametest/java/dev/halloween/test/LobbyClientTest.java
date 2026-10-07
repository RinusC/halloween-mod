package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientState;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Lobby;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The lobby: built, players sent in, a stand explains, the Ready button, a parkour run with a record, back after a match. */
public class LobbyClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-lobby");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("lobby")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runCommand("hw lobby");
			ctx.waitTicks(40);
			check(server.computeOnServer(s -> Lobby.exists(s) && Lobby.inside(player(s))), "the gym is built and we're in it");
			ctx.waitTicks(20);
			check(ctx.computeOnClient(mc -> ClientState.lobby.inLobby() && !ClientState.lobby.status().getString().isEmpty()), "the lobby line shows");
			ctx.takeScreenshot("lobby_spawn");

			// A stand.
			server.runOnServer(s -> {
				BlockPos at = Lobby.station(Lobby.origin(s), "goal");
				player(s).teleportTo(s.overworld(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, java.util.Set.of(), 90f, 10f, false);
			});
			ctx.waitTicks(20);
			check(ctx.computeOnClient(mc -> ClientState.lobby.panel().size() >= 3), "a stand tells what it's about");
			ctx.takeScreenshot("lobby_stand");

			// Ready on your own: noted, but a match needs two.
			server.runOnServer(s -> Lobby.onUse(player(s), Lobby.readyButton(Lobby.origin(s))));
			ctx.waitTicks(30);
			check(server.computeOnServer(s -> Lobby.ready(player(s)) && !dev.halloween.game.Match.frozen() && !dev.halloween.game.Match.running()),
				"ready alone: noted, no match");

			// Parkour: start pad, off it, the finish.
			List<BlockPos> path = server.computeOnServer(s -> Lobby.path(Lobby.origin(s)));
			BlockPos start = path.getFirst();
			BlockPos finish = path.getLast();
			server.runOnServer(s -> player(s).teleportTo(s.overworld(), start.getX() + 0.5, start.getY() + 1, start.getZ() + 0.5, java.util.Set.of(), -90f, 20f, false));
			ctx.waitTicks(10);
			ctx.takeScreenshot("lobby_parkour");
			BlockPos second = path.get(1);
			server.runOnServer(s -> player(s).teleportTo(s.overworld(), second.getX() + 0.5, second.getY() + 2.5, second.getZ() + 0.5, java.util.Set.of(), -90f, 20f, false));
			ctx.waitTicks(5);
			server.runOnServer(s -> player(s).teleportTo(s.overworld(), finish.getX() + 0.5, finish.getY() + 1, finish.getZ() + 0.5, java.util.Set.of(), -90f, 20f, false));
			ctx.waitTicks(20);
			check(server.computeOnServer(s -> Lobby.record(s, player(s).getScoreboardName()) != null), "a parkour run is timed and kept");

			// A match takes us out, its end brings us back.
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = 1;
				HwConfig.get().botsFillTo = 0;
			});
			server.runCommand("hw start");
			ctx.waitTicks(40);
			check(server.computeOnServer(s -> !Lobby.inside(player(s))), "the match takes us to town");
			server.runCommand("hw stop");
			ctx.waitTicks(20);
			check(server.computeOnServer(s -> Lobby.inside(player(s))), "and we're back in the gym after");
			server.runOnServer(s -> {
				BlockPos o = Lobby.origin(s);
				player(s).teleportTo(s.overworld(), o.getX() + Lobby.path(o).getFirst().getX() - o.getX() + 0.5, o.getY() + 1, o.getZ() + 3.5, java.util.Set.of(), 30f, -5f, false);
			});
			ctx.waitTicks(20);
			ctx.takeScreenshot("lobby_court");
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds;
				HwConfig.get().botsFillTo = new HwConfig().botsFillTo;
			});
		}
		LOG.info("Lobby client test finished OK");
	}

	private static ServerPlayer player(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!(p instanceof BotPlayer)) {
				return p;
			}
		}
		throw new IllegalStateException("no player");
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
