package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientDarkness;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.GrabSession;
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
 * Michael's model (art/michael.bbmodel): screenshots of him standing (front, side, back, by a person for height),
 * walking, striking, holding someone, and the player's own view as him.
 */
public class MichaelModelClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-michael-model");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("michaelmodel")) {
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
				mc.options.setCameraType(CameraType.FIRST_PERSON);
			});
			// A stage in the air: the player looking north at him four and a half blocks off, a person beside him for height.
			int[] at = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				int x = (int) Math.floor(p.getX());
				int z = (int) Math.floor(p.getZ());
				command(s, "fill " + (x - 8) + " 150 " + (z - 8) + " " + (x + 8) + " 150 " + (z + 8) + " minecraft:smooth_stone");
				command(s, "summon minecraft:mannequin " + (x + 2.0) + " 151 " + (z + 0.5) + " {Rotation:[0f,0f],immovable:1b,Tags:[\"hw_test\"]}");
				p.teleportTo(s.overworld(), x + 0.5, 151, z + 5, java.util.Set.of(), 180f, 8f, false);
				return new int[]{x, z};
			});
			for (float yaw : new float[]{0f, 90f, 180f, 270f}) {
				this.michael(server, at[0] + 0.5, at[1] + 0.5, yaw);
				ctx.waitTicks(12);
				ctx.takeScreenshot("michael_model_" + (int) yaw);
			}
			// Closer, the head.
			server.runOnServer(s -> player(s).teleportTo(s.overworld(), at[0] + 0.5, 152.2, at[1] + 2.2, java.util.Set.of(), 180f, 10f, false));
			this.michael(server, at[0] + 0.5, at[1] + 0.5, 0f);
			ctx.waitTicks(12);
			ctx.takeScreenshot("michael_model_face");
			server.runOnServer(s -> player(s).teleportTo(s.overworld(), at[0] + 0.5, 151, at[1] + 5, java.util.Set.of(), 180f, 8f, false));
			// Walking past, side on.
			this.michael(server, at[0] - 3.5, at[1] + 0.5, 270f);
			ctx.waitTicks(5);
			for (int i = 0; i < 40; i++) {
				server.runOnServer(s -> {
					ServerPlayer m = ShapeBrain.body();
					m.teleportTo(s.overworld(), m.getX() + 0.13, m.getY(), m.getZ(), java.util.Set.of(), 270f, 0f, false);
					m.setYHeadRot(270f);
					m.setYBodyRot(270f);
				});
				ctx.waitTicks(1);
				if (i == 24 || i == 30) {
					ctx.takeScreenshot("michael_model_walk_" + i);
				}
			}
			// A strike: the knife from overhead.
			this.michael(server, at[0] + 0.5, at[1] + 0.5, 270f);
			ctx.waitTicks(10);
			server.runOnServer(s -> ShapeBrain.body().swing(InteractionHand.MAIN_HAND, true));
			for (int i = 1; i <= 3; i++) {
				ctx.waitTicks(2);
				ctx.takeScreenshot("michael_model_strike_" + i);
			}
			ctx.waitTicks(10);
			// Holding someone by the throat, the knife raised.
			server.runOnServer(s -> GrabSession.anim(ShapeBrain.body(), HwNet.Anim.HOLD));
			ctx.waitTicks(8);
			ctx.takeScreenshot("michael_model_hold");
			server.runOnServer(s -> GrabSession.anim(ShapeBrain.body(), HwNet.Anim.NONE));
			server.runCommand("kill @e[tag=hw_test]");
			server.runCommand("hw stop");
			ctx.waitTicks(10);

			// The player as Michael (survivors to play against): his own view from behind.
			ShapeBrain.paused = false;
			server.runOnServer(s -> HwConfig.get().botsFillTo = 2);
			server.runCommand("hw shape off");
			server.runCommand("hw start @p");
			ctx.waitTicks(80);
			server.runCommand("time set noon");
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			ctx.waitTicks(10);
			ctx.takeScreenshot("michael_model_own_view");
			ctx.getInput().holdKey(o -> o.keyUp);
			ctx.waitTicks(12);
			ctx.takeScreenshot("michael_model_own_walk");
			ctx.getInput().releaseKey(o -> o.keyUp);
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> {
				ClientDarkness.enabled = true;
				mc.options.setCameraType(CameraType.FIRST_PERSON);
			});
			dev.halloween.game.Weather.forced = -1;
		}
		LOG.info("Michael model client test finished OK");
	}

	/** Puts him (his mind stopped) here, facing {@code yaw}. */
	private void michael(TestServerContext server, double x, double z, float yaw) {
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
