package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.CurseVisions;
import dev.halloween.client.MichaelRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** What the cursed one sees (HW_ONLY=visions): Vecna glimpsed off to the side, behind them, beside them; the lights failing. */
public class VisionsClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-visions");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("visions")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runOnServer(s -> {
				HwConfig.get().botsFillTo = 0;
				HwConfig.get().countdownSeconds = 1;
			});
			server.runCommand("hw start");
			ctx.waitTicks(60);
			ctx.runOnClient(mc -> MichaelRenderer.forceVecna = true);
			String[] names = {"glimpse", "behind", "beside"};
			for (int k = 0; k < 3; k++) {
				int kind = k;
				boolean stands = ctx.computeOnClient(mc -> CurseVisions.testVision(kind));
				check(stands, "a vision stands: " + names[k]);
				// Turned to it: a glimpse at the edge of the view, the others full on.
				ctx.runOnClient(mc -> CurseVisions.testFace(kind == 0 ? 22f : 8f));
				ctx.waitTicks(3);
				ctx.takeScreenshot("vision_" + names[k]);
			}
			ctx.runOnClient(mc -> CurseVisions.testFlicker());
			ctx.waitTicks(10);
			check(ctx.computeOnClient(mc -> CurseVisions.lights() < 0.5f), "the lights failing");
			ctx.takeScreenshot("vision_lights");
			ctx.runOnClient(mc -> MichaelRenderer.forceVecna = false);
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds;
				HwConfig.get().botsFillTo = new HwConfig().botsFillTo;
			});
		}
		LOG.info("Visions client test finished OK");
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
