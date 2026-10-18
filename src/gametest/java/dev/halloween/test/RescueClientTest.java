package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.game.Exits;
import dev.halloween.game.ShapeBrain;
import dev.halloween.game.Townsfolk;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bots rescue townsfolk: with the ways out open and Michael standing idle, they fetch neighbours and lead them out. */
public class RescueClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-rescue");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("rescue")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runOnServer(s -> {
				HwConfig cfg = HwConfig.get();
				cfg.countdownSeconds = 1;
				cfg.botsFillTo = 3;
				cfg.npcMax = 6;
				cfg.eventsEnabled = false;
			});
			ShapeBrain.paused = true;
			server.runCommand("hw shape bot");
			server.runCommand("gamemode spectator @p");
			server.runCommand("hw start");
			ctx.waitTicks(60);
			int npcs = server.computeOnServer(s -> Townsfolk.count());
			LOG.info("townsfolk: {}", npcs);
			check(npcs > 0, "there are townsfolk");
			server.runOnServer(s -> {
				Exits.forceOpen(s.overworld(), Exits.CELLAR);
				Exits.forceOpen(s.overworld(), Exits.GATE);
			});
			int rescued = 0;
			for (int i = 0; i < 90 && rescued == 0; i++) {
				ctx.waitTicks(20);
				rescued = server.computeOnServer(s -> Townsfolk.rescued());
			}
			LOG.info("rescued after the wait: {}", rescued);
			check(rescued > 0, "bots lead a neighbour out");
			server.runCommand("hw stop");
			ShapeBrain.paused = false;
			ctx.waitTicks(10);
		}
		LOG.info("Rescue client test finished OK");
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
