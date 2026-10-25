package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientMusic;
import dev.halloween.client.ClientWeather;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Music;
import dev.halloween.game.Weather;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pictures and frame times of the match night's atmosphere (fog, later the screen's pass): each view with the
 * atmosphere off, on a clear night, a foggy one and in a chase; then how long a frame takes with it off and on.
 * With -Phw_sodium the terrain goes through Sodium as in the game.
 */
public class AtmosClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-atmos-test");
	private static final int FRAMES = 300;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("atmos")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("weather clear 1000000");
			ctx.runOnClient(mc -> mc.options.renderDistance().set(12));
			server.runCommand("execute as @p at @p run hw map town");
			ctx.waitTicks(2);
			boolean busy = true;
			for (int i = 0; i < 3000 && busy; i++) {
				ctx.waitTicks(10);
				busy = server.computeOnServer(s -> dev.halloween.game.MapJob.busy());
			}
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = 1;
				HwConfig.get().botsFillTo = 0;
				// A clear night, so the vanilla rain's own fog does not darken the "off" pictures.
				HwConfig.get().weatherWeights = new int[] {1, 0, 0};
			});
			server.runCommand("hw start");
			ctx.waitTicks(60);
			ctx.runOnClient(mc -> {
				mc.options.gamma().set(0.5);
				mc.options.framerateLimit().set(260);
				mc.options.enableVsync().set(false);
				if (!mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
			});
			BlockPos spawn = server.computeOnServer(s -> player(s).blockPosition());
			// Out in the open, where the view goes furthest.
			double[] spot = server.computeOnServer(s -> openSpot(s, spawn));
			BlockPos at = BlockPos.containing(spot[0], spot[1], spot[2]);
			float yaw = (float) spot[3];
			LOG.info("Atmos view from {} at yaw {}", at, yaw);
			views(ctx, server, "street", at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, 2f);
			views(ctx, server, "back", at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw + 180f, 2f);
			views(ctx, server, "roof", at.getX() + 0.5, at.getY() + 14, at.getZ() + 0.5, yaw, 14f);

			// Frame times down the street, off and on twice over (the first pair warms things up).
			server.runOnServer(s -> player(s).teleportTo(s.overworld(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, java.util.Set.of(), yaw, 2f, false));
			ctx.runOnClient(mc -> ClientWeather.kind = Weather.CLEAR);
			ctx.waitTicks(60);
			for (int round = 0; round < 2; round++) {
				for (boolean on : new boolean[] {false, true}) {
					ctx.runOnClient(mc -> ClientWeather.enabled = on);
					ctx.waitTicks(20);
					double ms = ctx.computeOnClient(AtmosClientTest::frameMs);
					LOG.info("Atmos frame time round {} atmosphere {}: {} ms", round, on ? "on" : "off", String.format(java.util.Locale.ROOT, "%.3f", ms));
				}
			}
			ctx.runOnClient(mc -> {
				ClientWeather.enabled = true;
				if (mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
			});
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds;
				HwConfig.get().botsFillTo = new HwConfig().botsFillTo;
				HwConfig.get().weatherWeights = new HwConfig().weatherWeights;
			});
		}
		LOG.info("Atmos shots done");
	}

	private static void views(ClientGameTestContext ctx, TestServerContext server, String name, double x, double y, double z, float yaw, float pitch) {
		server.runOnServer(s -> {
			ServerPlayer p = player(s);
			// Held up in the air for the view from above.
			p.getAbilities().mayfly = true;
			p.getAbilities().flying = true;
			p.onUpdateAbilities();
			p.teleportTo(s.overworld(), x, y, z, java.util.Set.of(), yaw, pitch, false);
		});
		ctx.waitTicks(40);
		ctx.runOnClient(mc -> {
			ClientWeather.enabled = false;
			ClientWeather.kind = Weather.CLEAR;
		});
		ctx.waitTicks(5);
		ctx.takeScreenshot("atmos_" + name + "_0off");
		ctx.runOnClient(mc -> ClientWeather.enabled = true);
		ctx.waitTicks(60);
		ctx.takeScreenshot("atmos_" + name + "_1clear");
		ctx.runOnClient(mc -> ClientWeather.kind = Weather.FOG);
		ctx.waitTicks(80);
		ctx.takeScreenshot("atmos_" + name + "_2fog");
		ctx.runOnClient(mc -> {
			ClientWeather.kind = Weather.CLEAR;
			ClientMusic.set(Music.PROXIMITY, 1);
		});
		ctx.waitTicks(80);
		ctx.takeScreenshot("atmos_" + name + "_3chase");
		ctx.runOnClient(mc -> ClientMusic.set(Music.AMBIENCE, 1));
		ctx.waitTicks(40);
	}

	/** How long a frame takes on the GPU and CPU together (ms): FRAMES frames drawn one after another, each finished. */
	private static double frameMs(net.minecraft.client.Minecraft mc) {
		for (int i = 0; i < 20; i++) {
			frame(mc);
		}
		long t0 = System.nanoTime();
		for (int i = 0; i < FRAMES; i++) {
			frame(mc);
		}
		return (System.nanoTime() - t0) / 1e6 / FRAMES;
	}

	/** One frame as the game draws it (see Minecraft.runTick), not shown, waited for. */
	private static void frame(net.minecraft.client.Minecraft mc) {
		try (var gizmos = mc.levelExtractor.collectPerFrameMainThreadGizmos()) {
			mc.gameRenderer.update(net.minecraft.client.DeltaTracker.ONE);
			mc.gameRenderer.extract(net.minecraft.client.DeltaTracker.ONE, true);
		}
		try (var gizmos = mc.levelRenderer.collectPerFrameRenderThreadGizmos()) {
			com.mojang.blaze3d.systems.RenderSystem.executePendingTasks();
			mc.gameRenderer.render(net.minecraft.client.DeltaTracker.ONE, true);
		}
		com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().submit();
		com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms().reset();
		mc.levelRenderer.endFrame();
		org.lwjgl.opengl.GL11.glFinish();
	}

	/** A spot under the open sky near `from` with the longest free line of sight at eye height: x, y, z, yaw. */
	private static double[] openSpot(MinecraftServer s, BlockPos from) {
		var level = s.overworld();
		double[] best = {from.getX() + 0.5, from.getY(), from.getZ() + 0.5, 0};
		double bestLen = -1;
		for (int dx = -48; dx <= 48; dx += 6) {
			for (int dz = -48; dz <= 48; dz += 6) {
				BlockPos top = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, from.offset(dx, 0, dz));
				if (Math.abs(top.getY() - from.getY()) > 3) {
					continue;
				}
				net.minecraft.world.phys.Vec3 eye = net.minecraft.world.phys.Vec3.atBottomCenterOf(top).add(0, 1.62, 0);
				for (int i = 0; i < 16; i++) {
					double r = Math.toRadians(i * 22.5);
					net.minecraft.world.phys.Vec3 ahead = new net.minecraft.world.phys.Vec3(-Math.sin(r), 0, Math.cos(r));
					net.minecraft.world.phys.Vec3 side = new net.minecraft.world.phys.Vec3(ahead.z, 0, -ahead.x);
					double len = sight(level, eye, ahead, 90);
					// A street: a long way down it, houses close on both sides along the way.
					int walls = 0;
					for (int k = 1; k <= 4; k++) {
						net.minecraft.world.phys.Vec3 along = eye.add(ahead.scale(Math.min(len, 60) * k / 5));
						walls += sight(level, along, side, 16) < 16 ? 1 : 0;
						walls += sight(level, along, side.reverse(), 16) < 16 ? 1 : 0;
					}
					double score = Math.min(len, 70) + 8 * walls;
					if (score > bestLen) {
						bestLen = score;
						best = new double[] {top.getX() + 0.5, top.getY(), top.getZ() + 0.5, i * 22.5};
					}
				}
			}
		}
		LOG.info("Atmos open spot {} scores {}", java.util.Arrays.toString(best), bestLen);
		return best;
	}

	/** How far one sees from `from` along `dir` before a block (at most `max`). */
	private static double sight(net.minecraft.server.level.ServerLevel level, net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 dir, double max) {
		var hit = level.clip(new net.minecraft.world.level.ClipContext(from, from.add(dir.scale(max)), net.minecraft.world.level.ClipContext.Block.COLLIDER,
			net.minecraft.world.level.ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
		return hit.getLocation().distanceTo(from);
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
