package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Exits;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Pictures only: the police van waits, a small blue arrow under the wanted badges points to it. */
public class VanArrowClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-van-arrow");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("van")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runOnServer(s -> HwConfig.get().countdownSeconds = 1);
			server.runCommand("hw start");
			ctx.waitTicks(40);
			server.runOnServer(s -> Exits.forceOpen(s.overworld(), Exits.POLICE));
			ctx.waitTicks(20);
			for (float yaw : new float[]{0f, 90f, 180f}) {
				server.runOnServer(s -> {
					ServerPlayer p = player(s);
					p.teleportTo(s.overworld(), p.getX(), p.getY(), p.getZ(), java.util.Set.of(), yaw, 0f, false);
				});
				ctx.waitTicks(10);
				ctx.takeScreenshot("van_arrow_" + (int) yaw);
			}
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds);
		}
		LOG.info("Van arrow shots done");
	}

	private static ServerPlayer player(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!(p instanceof BotPlayer)) {
				return p;
			}
		}
		throw new IllegalStateException("no player");
	}
}
