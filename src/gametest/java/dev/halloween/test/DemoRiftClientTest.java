package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientDarkness;
import dev.halloween.client.ClientState;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.MatchManager;
import dev.halloween.game.UpsideDown;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Demogorgon's Upside Down (short, on the arena): the player plays him; he sinks (a second and a half), is
 * below and five times as fast, the houses with survivors come to him, he climbs out at the same spot (five
 * seconds), senses who is near, and has to wait before going down again. Screenshots below and climbing out.
 */
public class DemoRiftClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-demo-rift");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("demorift")) {
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
				cfg.botsFillTo = 2;
				cfg.eventsEnabled = false;
			});
			server.runCommand("hw killer demogorgon");
			server.runCommand("hw start @p");
			ctx.waitTicks(60);
			ctx.runOnClient(mc -> ClientDarkness.enabled = false);
			for (int i = 0; i < 60 && !server.computeOnServer(s -> UpsideDown.ready()); i++) {
				ctx.waitTicks(20);
			}
			check(server.computeOnServer(s -> UpsideDown.ready()), "the Upside Down is built");
			Vec3 start = server.computeOnServer(s -> player(s).position());
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
			server.runOnServer(s -> MatchManager.onShapeJump(player(s)));
			ctx.waitTicks(16);
			int serverRifts = server.computeOnServer(s -> s.overworld().getEntitiesOfClass(net.minecraft.world.entity.Display.ItemDisplay.class,
				player(s).getBoundingBox().inflate(6), e -> e.entityTags().contains("hw_rift")).size());
			int clientRifts = ctx.computeOnClient(mc -> mc.level.getEntitiesOfClass(net.minecraft.world.entity.Display.ItemDisplay.class,
				mc.player.getBoundingBox().inflate(6), e -> true).size());
			LOG.info("rifts: server {}, client {}", serverRifts, clientRifts);
			ctx.takeScreenshot("demo_rift_sinking");
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
			ctx.waitTicks(24);
			check(server.computeOnServer(s -> UpsideDown.isBelow(player(s))), "down in the Upside Down after sinking");
			double speed = server.computeOnServer(s -> player(s).getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED));
			LOG.info("speed below: {}", speed);
			ctx.waitTicks(20);
			int houses = ctx.computeOnClient(mc -> ClientState.demoHouses.size() / 2);
			LOG.info("houses shown: {}", houses);
			ctx.takeScreenshot("demo_rift_below");
			ctx.getInput().holdKey(o -> o.keyUp);
			ctx.waitTicks(10);
			ctx.getInput().releaseKey(o -> o.keyUp);
			Vec3 below = server.computeOnServer(s -> UpsideDown.above(player(s).position()));
			LOG.info("moved below: {}", below.distanceTo(start));
			server.runOnServer(s -> MatchManager.onShapeJump(player(s)));
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
			ctx.waitTicks(40);
			check(server.computeOnServer(s -> !UpsideDown.isBelow(player(s)) && player(s).position().distanceTo(below) < 4), "up again at the same spot");
			ctx.takeScreenshot("demo_rift_emerging");
			ctx.waitTicks(70);
			check(ctx.computeOnClient(mc -> ClientState.demo.phase() == 0), "out after five seconds");
			server.runOnServer(s -> MatchManager.onShapeJump(player(s)));
			ctx.waitTicks(10);
			check(server.computeOnServer(s -> !UpsideDown.isBelow(player(s)) && ctx != null), "not down again before the rest is over");
			ctx.runOnClient(mc -> {
				mc.options.setCameraType(CameraType.FIRST_PERSON);
				ClientDarkness.enabled = true;
			});
			server.runCommand("hw stop");
			server.runCommand("hw killer michael");
			ctx.waitTicks(10);
		}
		LOG.info("Demogorgon rift client test finished OK");
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
