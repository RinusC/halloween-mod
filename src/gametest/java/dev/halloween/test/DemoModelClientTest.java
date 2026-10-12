package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientDarkness;
import dev.halloween.client.DemogorgonModel;
import dev.halloween.client.MichaelRenderer;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.ShapeBrain;
import dev.halloween.net.HwNet;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Demogorgon's model (art/demogorgon.bbmodel, client.DemogorgonModel): screenshots of him upright (front, side,
 * by a person for height), walking, on all fours, crawling, coiled for a Shred with the petals parting, in the
 * lunge, striking upright and on all fours, the head turned. The bot killer drawn as him, his stance forced.
 */
public class DemoModelClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-demo-model");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("demomodel")) {
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
				cfg.botsFillTo = 0;
				cfg.npcMax = 0;
				cfg.eventsEnabled = false;
			});
			dev.halloween.game.Weather.forced = dev.halloween.game.Weather.CLEAR;
			ShapeBrain.paused = true;
			server.runCommand("hw shape bot");
			server.runCommand("hw start");
			ctx.waitTicks(80);
			server.runCommand("time set noon");
			ctx.runOnClient(mc -> {
				ClientDarkness.enabled = false;
				MichaelRenderer.forceDemo = true;
				DemogorgonModel.forcedPose = HwNet.DemoPose.UPRIGHT;
				mc.options.setCameraType(CameraType.FIRST_PERSON);
			});
			int[] at = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				int x = (int) Math.floor(p.getX());
				int z = (int) Math.floor(p.getZ());
				command(s, "fill " + (x - 9) + " 150 " + (z - 9) + " " + (x + 9) + " 150 " + (z + 9) + " minecraft:smooth_stone");
				command(s, "summon minecraft:mannequin " + (x + 2.0) + " 151 " + (z + 0.5) + " {Rotation:[0f,0f],immovable:1b,Tags:[\"hw_test\"]}");
				p.teleportTo(s.overworld(), x + 0.5, 151, z + 5.5, java.util.Set.of(), 180f, 10f, false);
				return new int[]{x, z};
			});
			this.demo(server, at[0] + 0.5, at[1] + 0.5, 0f);
			ctx.waitTicks(15);
			ctx.takeScreenshot("demo_upright_front");
			this.demo(server, at[0] + 0.5, at[1] + 0.5, 270f);
			ctx.waitTicks(5);
			ctx.takeScreenshot("demo_upright_side");
			this.walk(ctx, server, at, "demo_walk");

			ctx.runOnClient(mc -> DemogorgonModel.forcedPose = HwNet.DemoPose.HUNTING);
			this.demo(server, at[0] + 0.5, at[1] + 0.5, 270f);
			ctx.waitTicks(20);
			ctx.takeScreenshot("demo_fours_side");
			this.demo(server, at[0] + 0.5, at[1] + 0.5, 330f);
			ctx.waitTicks(5);
			ctx.takeScreenshot("demo_fours_front");
			// The head turned towards the camera, the body side on.
			server.runOnServer(s -> ShapeBrain.body().setYHeadRot(330f));
			this.demo(server, at[0] + 0.5, at[1] + 0.5, 270f, 330f);
			ctx.waitTicks(5);
			ctx.takeScreenshot("demo_fours_look");
			this.walk(ctx, server, at, "demo_crawl");

			ctx.runOnClient(mc -> DemogorgonModel.forcedPose = HwNet.DemoPose.CHARGING);
			this.demo(server, at[0] + 0.5, at[1] + 0.5, 270f);
			ctx.waitTicks(16);
			ctx.takeScreenshot("demo_charge_side");
			this.demo(server, at[0] + 0.5, at[1] + 0.5, 0f);
			server.runOnServer(s -> player(s).teleportTo(s.overworld(), at[0] + 0.5, 151, at[1] + 3.2, java.util.Set.of(), 180f, 20f, false));
			ctx.waitTicks(8);
			ctx.takeScreenshot("demo_charge_face");
			server.runOnServer(s -> player(s).teleportTo(s.overworld(), at[0] + 0.5, 151, at[1] + 5.5, java.util.Set.of(), 180f, 10f, false));
			ctx.runOnClient(mc -> DemogorgonModel.forcedPose = HwNet.DemoPose.LUNGING);
			this.demo(server, at[0] + 0.5, at[1] + 0.5, 270f);
			ctx.waitTicks(8);
			ctx.takeScreenshot("demo_lunge_side");

			ctx.runOnClient(mc -> DemogorgonModel.forcedPose = HwNet.DemoPose.UPRIGHT);
			ctx.waitTicks(25);
			server.runOnServer(s -> ShapeBrain.body().swing(InteractionHand.MAIN_HAND, true));
			ctx.waitTicks(2);
			ctx.takeScreenshot("demo_strike_1");
			ctx.waitTicks(2);
			ctx.takeScreenshot("demo_strike_2");
			ctx.runOnClient(mc -> DemogorgonModel.forcedPose = HwNet.DemoPose.HUNTING);
			ctx.waitTicks(20);
			server.runOnServer(s -> ShapeBrain.body().swing(InteractionHand.MAIN_HAND, true));
			ctx.waitTicks(2);
			ctx.takeScreenshot("demo_strike_fours");

			server.runCommand("kill @e[tag=hw_test]");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> {
				ClientDarkness.enabled = true;
				MichaelRenderer.forceDemo = false;
				DemogorgonModel.forcedPose = -1;
			});
			ShapeBrain.paused = false;
			dev.halloween.game.Weather.forced = -1;
		}
		LOG.info("Demogorgon model client test finished OK");
	}

	/** Walking past side on, two screenshots in stride. */
	private void walk(ClientGameTestContext ctx, TestServerContext server, int[] at, String name) {
		this.demo(server, at[0] - 3.5, at[1] + 0.5, 270f);
		ctx.waitTicks(3);
		for (int i = 0; i < 34; i++) {
			server.runOnServer(s -> {
				ServerPlayer m = ShapeBrain.body();
				m.teleportTo(s.overworld(), m.getX() + 0.15, m.getY(), m.getZ(), java.util.Set.of(), 270f, 0f, false);
				m.setYHeadRot(270f);
				m.setYBodyRot(270f);
			});
			ctx.waitTicks(1);
			if (i == 22 || i == 27) {
				ctx.takeScreenshot(name + "_" + i);
			}
		}
	}

	private void demo(TestServerContext server, double x, double z, float yaw) {
		this.demo(server, x, z, yaw, yaw);
	}

	private void demo(TestServerContext server, double x, double z, float yaw, float head) {
		server.runOnServer(s -> {
			ServerPlayer m = ShapeBrain.body();
			m.teleportTo(s.overworld(), x, 151, z, java.util.Set.of(), head, 0f, false);
			m.setYHeadRot(head);
			m.setYBodyRot(yaw);
		});
	}

	private static void command(MinecraftServer s, String command) {
		s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), command);
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
