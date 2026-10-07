package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.MatchManager;
import dev.halloween.game.ShapeState;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Coming out of Shape Jump, Michael can't speed up for two seconds; after that he can. */
public class DashLockClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-dash-lock-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("dashlock")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runOnServer(s -> HwConfig.get().countdownSeconds = 2);
			server.runCommand("hw start @p");
			ctx.waitTicks(60);
			server.runOnServer(s -> {
				ShapeState st = MatchManager.shapeState(player(s));
				st.dashCooldown = 0;
				st.shadowTicks = 20;
				try {
					var exit = MatchManager.class.getDeclaredMethod("exitShadow", ServerPlayer.class, ShapeState.class);
					exit.setAccessible(true);
					exit.invoke(null, player(s), st);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
				MatchManager.onDash(player(s));
			});
			check(server.computeOnServer(s -> MatchManager.shapeState(player(s)).dashTicks == 0), "right out of Shape Jump he can't speed up");
			ctx.waitTicks(20);
			server.runOnServer(s -> MatchManager.onDash(player(s)));
			check(server.computeOnServer(s -> MatchManager.shapeState(player(s)).dashTicks == 0), "nor a second later");
			ctx.waitTicks(22);
			server.runOnServer(s -> MatchManager.onDash(player(s)));
			check(server.computeOnServer(s -> MatchManager.shapeState(player(s)).dashTicks > 0), "two seconds after, he can");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds);
		}
		LOG.info("Dash lock client test finished OK");
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
