package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientDarkness;
import dev.halloween.game.Birds;
import dev.halloween.game.BotPlayer;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Crows (short, on the arena): flocks settle up on roofs and posts; seen up close; walking up to them puts them up and they fly off. */
public class BirdsClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-birds");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("birds")) {
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
			dev.halloween.game.ShapeBrain.paused = true;
			server.runCommand("hw shape bot");
			server.runCommand("hw start");
			ctx.waitTicks(60);
			server.runCommand("time set noon");
			ctx.runOnClient(mc -> ClientDarkness.enabled = false);
			ctx.waitTicks(260);
			int settled = server.computeOnServer(s -> Birds.grounded());
			LOG.info("crows perched: {}", settled);
			check(settled > 0, "crows settle");
			// Stand 5 blocks off, crouched (they stay), and look.
			int before = server.computeOnServer(s -> Birds.flying());
			List<Vec3> all = server.computeOnServer(s -> Birds.positions());
			LOG.info("perches: {}", all.stream().map(v -> String.format("%.0f/%.1f/%.0f", v.x, v.y, v.z)).toList());
			Vec3 bird = all.getFirst();
			server.runOnServer(s -> {
				ServerPlayer p = player(s);
				p.setShiftKeyDown(true);
				Vec3 at = bird.add(1.3, -0.9, 1.3);
				float yaw = (float) Math.toDegrees(Math.atan2(-(bird.x - at.x), bird.z - at.z));
				p.teleportTo(s.overworld(), at.x, at.y, at.z, java.util.Set.of(), yaw, 30f, false);
				p.setNoGravity(true);
			});
			ctx.waitTicks(10);
			ctx.takeScreenshot("birds_ground");
			ctx.waitTicks(17);
			ctx.takeScreenshot("birds_ground2");
			// Up to them on foot.
			server.runOnServer(s -> {
				ServerPlayer p = player(s);
				p.setShiftKeyDown(false);
				Vec3 at = bird.add(1.5, 0, 1.5);
				p.teleportTo(s.overworld(), at.x, at.y, at.z, java.util.Set.of(), p.getYRot(), -20f, false);
			});
			ctx.waitTicks(4);
			ctx.takeScreenshot("birds_takeoff");
			int after = server.computeOnServer(s -> Birds.flying());
			LOG.info("flying before {}, after {}", before, after);
			check(after > before, "walking up to them puts them up");
			ctx.waitTicks(12);
			ctx.takeScreenshot("birds_flying");
			// At night, as in a match: a crow seen from about ten blocks off.
			server.runCommand("time set midnight");
			ctx.runOnClient(mc -> ClientDarkness.enabled = true);
			ctx.waitTicks(200);
			List<Vec3> night = server.computeOnServer(s -> Birds.positions());
			if (!night.isEmpty()) {
				Vec3 crow = night.getLast();
				server.runOnServer(s -> {
					ServerPlayer p = player(s);
					p.setShiftKeyDown(true);
					Vec3 at = crow.add(7, -1.5, 7);
					float yaw = (float) Math.toDegrees(Math.atan2(-(crow.x - at.x), crow.z - at.z));
					p.teleportTo(s.overworld(), at.x, at.y, at.z, java.util.Set.of(), yaw, 0f, false);
				});
				ctx.waitTicks(10);
				ctx.takeScreenshot("birds_night");
			}
			server.runCommand("hw stop");
			ctx.waitTicks(10);
			dev.halloween.game.ShapeBrain.paused = false;
			ctx.runOnClient(mc -> ClientDarkness.enabled = true);
		}
		LOG.info("Birds client test finished OK");
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
