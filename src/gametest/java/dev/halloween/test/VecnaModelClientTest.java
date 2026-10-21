package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientDarkness;
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
 * Vecna's model (art/vecna.bbmodel, client.VecnaModel): screenshots of him floating (front, side, back, from below,
 * the head close up, a person beside him for height), gliding past, casting the Grip, striking. The bot killer drawn
 * as him.
 */
public class VecnaModelClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-vecna-model");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("vecnamodel")) {
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
				MichaelRenderer.forceVecna = true;
				mc.options.setCameraType(CameraType.FIRST_PERSON);
			});
			int[] at = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				int x = (int) Math.floor(p.getX());
				int z = (int) Math.floor(p.getZ());
				command(s, "fill " + (x - 9) + " 150 " + (z - 9) + " " + (x + 9) + " 150 " + (z + 9) + " minecraft:smooth_stone");
				command(s, "fill " + (x - 9) + " 151 " + (z - 9) + " " + (x + 9) + " 160 " + (z + 9) + " minecraft:air");
				command(s, "summon minecraft:mannequin " + (x + 2.0) + " 151 " + (z + 0.5) + " {Rotation:[0f,0f],immovable:1b,Tags:[\"hw_test\"]}");
				p.teleportTo(s.overworld(), x + 0.5, 151, z + 5.5, java.util.Set.of(), 180f, 8f, false);
				return new int[]{x, z};
			});
			this.vecna(server, at[0] + 0.5, at[1] + 0.5, 0f);
			ctx.waitTicks(20);
			ctx.takeScreenshot("vecna_float_front");
			this.vecna(server, at[0] + 0.5, at[1] + 0.5, 270f);
			ctx.waitTicks(5);
			ctx.takeScreenshot("vecna_float_side");
			this.vecna(server, at[0] + 0.5, at[1] + 0.5, 180f);
			ctx.waitTicks(5);
			ctx.takeScreenshot("vecna_float_back");
			this.vecna(server, at[0] + 0.5, at[1] + 0.5, 35f);
			ctx.waitTicks(5);
			ctx.takeScreenshot("vecna_float_three_quarter");
			// Close up: the face, then the chest.
			this.vecna(server, at[0] + 0.5, at[1] + 0.5, 0f);
			server.runOnServer(s -> player(s).teleportTo(s.overworld(), at[0] + 0.5, 152.4, at[1] + 2.1, java.util.Set.of(), 180f, 4f, false));
			ctx.waitTicks(5);
			ctx.takeScreenshot("vecna_face");
			server.runOnServer(s -> player(s).teleportTo(s.overworld(), at[0] + 1.6, 152.2, at[1] + 1.8, java.util.Set.of(), 150f, 10f, false));
			ctx.waitTicks(5);
			ctx.takeScreenshot("vecna_face_side");
			server.runOnServer(s -> player(s).teleportTo(s.overworld(), at[0] + 0.5, 151.3, at[1] + 2.6, java.util.Set.of(), 180f, 0f, false));
			ctx.waitTicks(5);
			ctx.takeScreenshot("vecna_chest");
			server.runOnServer(s -> player(s).teleportTo(s.overworld(), at[0] + 0.5, 151, at[1] + 5.5, java.util.Set.of(), 180f, 8f, false));

			// The Grip: the arm thrust out.
			server.runOnServer(s -> dev.halloween.game.GrabSession.anim(ShapeBrain.body(), HwNet.Anim.VECNA_CAST));
			this.vecna(server, at[0] + 0.5, at[1] + 0.5, 0f);
			ctx.waitTicks(12);
			ctx.takeScreenshot("vecna_cast_front");
			this.vecna(server, at[0] + 0.5, at[1] + 0.5, 270f);
			ctx.waitTicks(4);
			ctx.takeScreenshot("vecna_cast_side");
			server.runOnServer(s -> dev.halloween.game.GrabSession.anim(ShapeBrain.body(), HwNet.Anim.NONE));
			ctx.waitTicks(10);

			// Gliding past side on.
			this.vecna(server, at[0] - 3.5, at[1] + 0.5, 270f);
			ctx.waitTicks(3);
			for (int i = 0; i < 34; i++) {
				server.runOnServer(s -> {
					ServerPlayer m = ShapeBrain.body();
					m.teleportTo(s.overworld(), m.getX() + 0.15, m.getY(), m.getZ(), java.util.Set.of(), 270f, 0f, false);
					m.setYHeadRot(270f);
					m.setYBodyRot(270f);
				});
				ctx.waitTicks(1);
				if (i == 24) {
					ctx.takeScreenshot("vecna_glide");
				}
			}
			// A blow, seen from the side.
			this.vecna(server, at[0] + 0.5, at[1] + 0.5, 90f);
			ctx.waitTicks(10);
			server.runOnServer(s -> ShapeBrain.body().swing(InteractionHand.MAIN_HAND, true));
			int bodyId = server.computeOnServer(s -> ShapeBrain.body().getId());
			ctx.waitTicks(1);
			// (A mannequin's swing does not play on the client: the blow started here as a player's would.)
			ctx.runOnClient(mc -> dev.halloween.client.VecnaModel.testStrike(bodyId));
			ctx.waitTicks(2);
			ctx.takeScreenshot("vecna_strike_1");
			ctx.waitTicks(3);
			ctx.takeScreenshot("vecna_strike_2");
			// At night, as he will be met.
			server.runCommand("time set midnight");
			ctx.runOnClient(mc -> ClientDarkness.enabled = true);
			this.vecna(server, at[0] + 0.5, at[1] + 0.5, 20f);
			server.runOnServer(s -> player(s).teleportTo(s.overworld(), at[0] + 0.5, 151, at[1] + 4.0, java.util.Set.of(), 180f, 5f, false));
			ctx.waitTicks(10);
			ctx.takeScreenshot("vecna_night");

			server.runCommand("kill @e[tag=hw_test]");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> MichaelRenderer.forceVecna = false);
			ShapeBrain.paused = false;
			dev.halloween.game.Weather.forced = -1;
		}
		LOG.info("Vecna model client test finished OK");
	}

	private void vecna(TestServerContext server, double x, double z, float yaw) {
		server.runOnServer(s -> {
			ServerPlayer m = ShapeBrain.body();
			m.teleportTo(s.overworld(), x, 151, z, java.util.Set.of(), yaw, 0f, false);
			m.setYHeadRot(yaw);
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
