package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientDarkness;
import dev.halloween.game.BotPlayer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Pictures only: a match night as vanilla draws it (brightness at max) and with Halloween's darkness. */
public class DarkClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-dark-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("dark")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = 1;
				HwConfig.get().botsFillTo = 0;
			});
			server.runCommand("hw start");
			ctx.waitTicks(60);
			ctx.runOnClient(mc -> {
				mc.options.gamma().set(1.0);
			});
			BlockPos at = server.computeOnServer(s -> player(s).blockPosition());
			// Outside, looking down the street; then by a house, facing its lit window.
			shots(ctx, server, "street", at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 5f);
			BlockPos lamp = server.computeOnServer(s -> {
				for (BlockPos p : BlockPos.betweenClosed(at.offset(-40, -3, -40), at.offset(40, 6, 40))) {
					if (s.overworld().getBlockState(p).getLightEmission() >= 12 && s.overworld().getBlockState(p.below()).isAir()) {
						return p.immutable();
					}
				}
				return at;
			});
			LOG.info("Light at {}", lamp);
			shots(ctx, server, "lamp", lamp.getX() + 0.5, lamp.getY() - 3, lamp.getZ() + 4.5, 180f, 20f);
			ctx.runOnClient(mc -> {
				mc.options.gamma().set(0.5);
			});
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds;
				HwConfig.get().botsFillTo = new HwConfig().botsFillTo;
			});
		}
		LOG.info("Dark shots done");
	}

	private static void shots(ClientGameTestContext ctx, TestServerContext server, String name, double x, double y, double z, float yaw, float pitch) {
		server.runOnServer(s -> player(s).teleportTo(s.overworld(), x, y, z, java.util.Set.of(), yaw, pitch, false));
		ctx.waitTicks(40);
		ctx.runOnClient(mc -> ClientDarkness.enabled = false);
		ctx.waitTicks(5);
		ctx.takeScreenshot("dark_" + name + "_vanilla");
		ctx.runOnClient(mc -> ClientDarkness.enabled = true);
		ctx.waitTicks(5);
		ctx.takeScreenshot("dark_" + name + "_halloween");
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
