package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.game.AiStats;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.DoorLocks;
import dev.halloween.game.ShapeBrain;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI Michael getting about (short, on a stage in the air over the arena: inside the match area): a place on top of a pillar he gives up, a long wall he
 * goes round, a locked hut he gets into; and how many path searches it all took.
 */
public class MichaelNavClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-michael-nav");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("michaelnav")) {
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
				AiStats.reset();
			});
			server.runCommand("hw shape bot");
			server.runCommand("hw start");
			ctx.waitTicks(80);
			// The stage, well away; the player high up out of everything.
			int[] c = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				int sx = (int) Math.floor(p.getX());
				int sz = (int) Math.floor(p.getZ());
				cmd(s, "fill " + (sx - 16) + " 150 " + (sz - 18) + " " + (sx + 16) + " 150 " + (sz + 12) + " minecraft:smooth_stone");
				cmd(s, "fill " + (sx - 16) + " 151 " + (sz - 18) + " " + (sx + 16) + " 158 " + (sz + 12) + " minecraft:air");
				// A pillar with a place on top.
				cmd(s, "fill " + (sx + 5) + " 151 " + sz + " " + (sx + 5) + " 154 " + sz + " minecraft:stone");
				// A long wall.
				cmd(s, "fill " + (sx - 10) + " 151 " + (sz + 3) + " " + (sx + 10) + " 153 " + (sz + 3) + " minecraft:stone");
				// A hut with a locked door.
				cmd(s, "fill " + (sx - 12) + " 150 " + (sz - 12) + " " + (sx - 8) + " 154 " + (sz - 8) + " minecraft:stone_bricks hollow");
				cmd(s, "setblock " + (sx - 10) + " 151 " + (sz - 12) + " minecraft:oak_door[facing=north,half=lower]");
				cmd(s, "setblock " + (sx - 10) + " 152 " + (sz - 12) + " minecraft:oak_door[facing=north,half=upper]");
				DoorLocks.forceLock(s.overworld(), new BlockPos(sx - 10, 151, sz - 12));
				cmd(s, "fill " + (p.getBlockX() - 2) + " 250 " + (p.getBlockZ() - 2) + " " + (p.getBlockX() + 2) + " 250 " + (p.getBlockZ() + 2) + " minecraft:stone");
				p.teleportTo(s.overworld(), p.getBlockX() + 0.5, 251, p.getBlockZ() + 0.5, java.util.Set.of(), 180f, 0f, false);
				return new int[]{sx, sz};
			});
			int sx = c[0];
			int sz = c[1];
			check(server.computeOnServer(s -> DoorLocks.isLocked(s.overworld(), new BlockPos(sx - 10, 151, sz - 12))), "the hut is locked");

			// ---------------------------------------------------------------- on top of a pillar: given up
			BlockPos top = new BlockPos(sx + 5, 155, sz);
			this.place(server, sx + 0.5, sz + 0.5, top);
			this.until(ctx, server, 300, s -> ShapeBrain.unreachable(top));
			LOG.info("pillar: {}", (Object) server.computeOnServer(s -> ShapeBrain.debug()));
			check(server.computeOnServer(s -> ShapeBrain.unreachable(top)), "a place he can't get to, given up");

			// ---------------------------------------------------------------- round a long wall
			BlockPos behind = new BlockPos(sx, 151, sz + 6);
			this.place(server, sx + 0.5, sz + 0.5, behind);
			this.until(ctx, server, 400, s -> near(behind, 1.8));
			LOG.info("wall: {}", (Object) server.computeOnServer(s -> ShapeBrain.debug()));
			check(server.computeOnServer(s -> near(behind, 1.8)), "round a long wall");

			// ---------------------------------------------------------------- into a locked hut
			BlockPos inside = new BlockPos(sx - 10, 151, sz - 10);
			this.place(server, sx - 9.5, sz - 15.5, inside);
			for (int t = 0; t < 600 && !server.computeOnServer(s -> near(inside, 1.8)); t += 20) {
				if (t >= 200) {
					LOG.info("hut t{}: {} busy {}", t, server.computeOnServer(s -> ShapeBrain.debug()), server.computeOnServer(s -> dev.halloween.game.MatchManager.shapeState(ShapeBrain.body()).busy()));
				}
				ctx.waitTicks(20);
			}
			LOG.info("hut: {}", (Object) server.computeOnServer(s -> ShapeBrain.debug() + " unreachable " + ShapeBrain.unreachable(inside)
				+ " door " + s.overworld().getBlockState(new BlockPos(sx - 10, 151, sz - 12)) + " locked " + DoorLocks.isLocked(s.overworld(), new BlockPos(sx - 10, 151, sz - 12))
				+ " shadow " + dev.halloween.game.MatchManager.shapeState(ShapeBrain.body()).shadowTicks + " stats " + AiStats.snapshot().get("~michael")));
			check(server.computeOnServer(s -> near(inside, 1.8)), "into a locked hut");

			Map<String, Integer> stats = AiStats.snapshot().getOrDefault("~michael", Map.of());
			LOG.info("michael stats: {}", stats);
			ShapeBrain.testGoal = null;
			server.runCommand("hw stop");
			server.runCommand("hw shape off");
			ctx.waitTicks(5);
		}
		LOG.info("Michael nav client test finished OK");
	}

	/** Him here (his mind fresh), making for {@code goal} only. */
	private void place(TestServerContext server, double x, double z, BlockPos goal) {
		server.runOnServer(s -> {
			ShapeBrain.forget();
			ShapeBrain.testGoal = goal;
			ShapeBrain.body().teleportTo(s.overworld(), x, 151, z, java.util.Set.of(), 0f, 0f, false);
		});
	}

	private static boolean near(BlockPos goal, double d) {
		return ShapeBrain.body().position().distanceTo(Vec3.atBottomCenterOf(goal)) < d;
	}

	private void until(ClientGameTestContext ctx, TestServerContext server, int ticks, Function<MinecraftServer, Boolean> done) {
		for (int t = 0; t < ticks; t += 10) {
			if (server.computeOnServer(done::apply)) {
				return;
			}
			ctx.waitTicks(10);
		}
	}

	private static void cmd(MinecraftServer s, String command) {
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

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
