package dev.halloween.test;

import dev.halloween.client.CharacterSelectScreen;
import dev.halloween.client.ClientDarkness;
import dev.halloween.client.ClientState;
import dev.halloween.client.LevelScreen;
import dev.halloween.game.Lobby;
import dev.halloween.game.Perks;
import dev.halloween.game.PlayerLevel;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** The player's level (HW_ONLY=level): the thresholds, points earned raising it, spending not; the lobby plate and the road. */
public class LevelClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("level")) {
			return;
		}
		check(PlayerLevel.total(2) == 6000 && PlayerLevel.total(3) == 14000 && PlayerLevel.cost(10) == 22000, "6000 to level 2, then 2000 more each");
		check(PlayerLevel.of(5999) == 1 && PlayerLevel.of(6000) == 2 && PlayerLevel.of(10_000_000) == PlayerLevel.MAX, "levels from points");
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runCommand("hw lobby");
			ctx.waitTicks(60);
			check(server.computeOnServer(s -> Lobby.exists(s) && Lobby.inside(player(s))), "in the lobby");
			ctx.runOnClient(mc -> ClientDarkness.enabled = false);
			server.runOnServer(s -> {
				Perks.give(s, player(s).getUUID(), 19000);
				Perks.sync(player(s));
			});
			ctx.waitTicks(10);
			check(ctx.computeOnClient(mc -> PlayerLevel.of(ClientState.earned)) == 3, "19000 earned: level 3 on the client");
			ctx.takeScreenshot("level_hud");
			ctx.runOnClient(mc -> mc.gui.setScreen(new LevelScreen(null)));
			ctx.waitTicks(30);
			ctx.takeScreenshot("level_screen");
			ctx.getInput().resizeWindow(1920, 1080);
			ctx.waitTicks(10);
			ctx.takeScreenshot("level_screen_1080");
			ctx.getInput().resizeWindow(854, 480);
			ctx.runOnClient(mc -> mc.gui.setScreen(new CharacterSelectScreen()));
			ctx.waitTicks(15);
			ctx.takeScreenshot("level_select_plate");
			ctx.runOnClient(mc -> mc.gui.setScreen(null));
			server.runOnServer(s -> {
				Perks.give(s, player(s).getUUID(), 200000);
				Perks.sync(player(s));
			});
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> PlayerLevel.of(Perks.earned(s, player(s).getUUID()))) == PlayerLevel.MAX, "max level");
			ctx.runOnClient(mc -> mc.gui.setScreen(new LevelScreen(null)));
			ctx.waitTicks(30);
			ctx.takeScreenshot("level_screen_max");
		}
	}

	private static ServerPlayer player(MinecraftServer s) {
		return s.getPlayerList().getPlayers().getFirst();
	}

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("level: " + what);
		}
	}
}
