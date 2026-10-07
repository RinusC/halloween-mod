package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientState;
import dev.halloween.game.AiStats;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Stamina;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Survivors run at 6 m/s; running burns stamina, run dry and you pant and can't run until a third is back; bots too. */
public class StaminaClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-stamina-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("stamina")) {
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
			double run = 0.1 * 1.3 * 6.0 / 5.612;

			// Speed: 6 m/s on both sides.
			server.runOnServer(s -> player(s).setSprinting(true));
			ctx.runOnClient(mc -> mc.player.setSprinting(true));
			double serverSpeed = server.computeOnServer(s -> player(s).getAttributeValue(Attributes.MOVEMENT_SPEED));
			double clientSpeed = ctx.computeOnClient(mc -> mc.player.getAttributeValue(Attributes.MOVEMENT_SPEED));
			check(Math.abs(serverSpeed - run) < 1e-4 && Math.abs(clientSpeed - run) < 1e-4,
				String.format("a survivor runs at %.2f m/s (server) / %.2f m/s (client)", serverSpeed * 20 / 0.1 * 0.2159, clientSpeed * 20 / 0.1 * 0.2159));
			server.runOnServer(s -> player(s).setSprinting(false));
			ctx.runOnClient(mc -> mc.player.setSprinting(false));
			check(server.computeOnServer(s -> Math.abs(player(s).getAttributeValue(Attributes.MOVEMENT_SPEED) - 0.1) < 1e-4), "walking is as before");

			// Running burns it.
			ctx.getInput().holdKey(o -> o.keyUp);
			ctx.getInput().holdKey(o -> o.keySprint);
			ctx.waitTicks(30);
			float left = server.computeOnServer(s -> Stamina.value(player(s)));
			check(left < 0.99f, "running burns stamina (" + left + " left)");
			check(ctx.computeOnClient(mc -> ClientState.stamina < 0.99f), "and the HUD knows");

			// Run dry: out of breath, no running, panting.
			server.runOnServer(s -> Stamina.set(player(s), 0.02f));
			ctx.waitTicks(15);
			check(server.computeOnServer(s -> Stamina.exhausted(player(s)) && !player(s).isSprinting()), "run dry: out of breath, running stops");
			check(ctx.computeOnClient(mc -> ClientState.staminaExhausted && !mc.player.isSprinting()), "the client can't run either");
			check(server.computeOnServer(s -> AiStats.snapshot().getOrDefault("*total", java.util.Map.of()).getOrDefault("stamina.pant", 0) > 0), "and pants");
			check(ctx.computeOnClient(mc -> dev.halloween.client.ClientPanting.panting(mc.player.getId())), "the panting plays (one long take)");
			ctx.takeScreenshot("stamina_out");
			ctx.getInput().releaseKey(o -> o.keySprint);
			ctx.getInput().releaseKey(o -> o.keyUp);

			// Standing still, a third is back in about 2.7 s (1 s pause, then 5 s to full).
			ctx.waitTicks(20);
			check(server.computeOnServer(s -> Stamina.exhausted(player(s))), "still out of breath a second later");
			ctx.waitTicks(50);
			check(server.computeOnServer(s -> !Stamina.exhausted(player(s))), "caught their breath after ~3.5 s standing");
			check(ctx.computeOnClient(mc -> !dev.halloween.client.ClientPanting.panting(mc.player.getId())), "and fades out once the breath is caught");
			ctx.takeScreenshot("stamina_back");

			// Bots too.
			boolean botBlocked = server.computeOnServer(s -> {
				for (ServerPlayer p : s.getPlayerList().getPlayers()) {
					if (p instanceof BotPlayer bot) {
						Stamina.set(bot, 0f);
						bot.setSprinting(true);
						return !bot.isSprinting();
					}
				}
				return false;
			});
			check(botBlocked, "an out-of-breath bot can't run");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds);
		}
		LOG.info("Stamina client test finished OK");
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
