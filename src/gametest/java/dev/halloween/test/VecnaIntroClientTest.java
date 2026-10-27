package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientDarkness;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Vecna's opening scene (client.VecnaIntro): screenshots along its length, a 10 s countdown. */
public class VecnaIntroClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("vecnaintro")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runOnServer(s -> {
				HwConfig cfg = HwConfig.get();
				cfg.countdownSeconds = 10;
				cfg.botsFillTo = 2;
				cfg.eventsEnabled = false;
			});
			server.runCommand("hw killer vecna");
			ctx.runOnClient(mc -> ClientDarkness.enabled = false);
			server.runCommand("hw start @p");
			// f = 0.05, 0.2, 0.3 (a flash), 0.5, 0.6, 0.7, 0.77 (the eyes), 0.85.
			int[] at = {10, 30, 60, 100, 120, 140, 154, 170};
			int now = 0;
			for (int i = 0; i < at.length; i++) {
				ctx.waitTicks(at[i] - now);
				now = at[i];
				ctx.takeScreenshot("vecnaintro_" + i);
			}
			server.runOnServer(s -> HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds);
		}
	}
}
