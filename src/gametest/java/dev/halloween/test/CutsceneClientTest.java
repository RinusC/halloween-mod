package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.Cutscene;
import dev.halloween.game.MapJob;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The opening scenes in Haddonfield: a survivor's flight over the town, then Michael through the mask. Pictures along the way. */
public class CutsceneClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-cutscene");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("cutscene")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw map haddonfield");
			ctx.waitTicks(2);
			boolean busy = true;
			for (int i = 0; i < 3000 && busy; i++) {
				ctx.waitTicks(10);
				busy = server.computeOnServer(s -> MapJob.busy());
			}
			server.runOnServer(s -> HwConfig.get().botsFillTo = 2);

			// A survivor (no Michael in this match).
			server.runCommand("hw start");
			ctx.waitTicks(10);
			check(ctx.computeOnClient(mc -> Cutscene.active()), "the survivor's scene plays");
			int[] at = {20, 70, 100, 70, 40};
			String[] names = {"s_title", "s_hero", "s_ghost", "s_drop", "s_eyes"};
			for (int i = 0; i < at.length; i++) {
				ctx.waitTicks(at[i]);
				if (i == 1) {
					double far = ctx.computeOnClient(mc -> mc.gameRenderer.mainCamera().position().distanceTo(mc.player.getEyePosition()));
					check(far > 1.5 && far < 6, "the camera stands a few steps off them, watching them at what they do (" + Math.round(far) + " blocks off)");
				}
				ctx.takeScreenshot("cut_" + names[i]);
			}
			ctx.waitTicks(30);
			check(ctx.computeOnClient(mc -> !Cutscene.active() && mc.gameRenderer.mainCamera().position().distanceTo(mc.player.getEyePosition()) < 0.5),
				"then the match starts in their eyes");
			ctx.takeScreenshot("cut_s_after");
			server.runCommand("hw stop");
			ctx.waitTicks(10);

			// Michael.
			server.runCommand("hw start @p");
			ctx.waitTicks(10);
			check(ctx.computeOnClient(mc -> Cutscene.active()), "Michael's scene plays");
			int[] mat = {50, 80, 90, 55, 40};
			String[] mnames = {"m_walk", "m_title", "m_window", "m_black", "m_after"};
			for (int i = 0; i < mat.length; i++) {
				ctx.waitTicks(mat[i]);
				ctx.takeScreenshot("cut_" + mnames[i]);
			}
			check(ctx.computeOnClient(mc -> !Cutscene.active()), "and ends with the match");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> HwConfig.get().botsFillTo = new HwConfig().botsFillTo);
		}
		LOG.info("Cutscene client test finished OK");
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
