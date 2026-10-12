package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Lockers;
import dev.halloween.game.MapPoints;
import dev.halloween.game.Nav;
import dev.halloween.game.Windows;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The midnight earthquake (short, on the arena): midnight from the start of a 90-second match, so a minute of
 * tremors. Every window and door goes, the wardrobes are jammed (whoever is in one tumbles out), the doors lie on
 * the floor; after the match all of it is back.
 */
public class QuakeClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-quake");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("quake")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			int[] before = server.computeOnServer(s -> count(s, MapPoints.get(s.overworld()).first(MapPoints.Type.CENTER)));
			LOG.info("before: {} windows, {} doors", before[0], before[1]);
			check(before[0] > 0 && before[1] > 0, "the arena has windows and doors");
			server.runOnServer(s -> {
				HwConfig cfg = HwConfig.get();
				cfg.countdownSeconds = 1;
				cfg.botsFillTo = 2;
				cfg.eventsEnabled = false;
				cfg.matchSeconds = 90;
				cfg.midnightSeconds = 90;
				cfg.lootMinSpacing = 0;
			});
			// A bot in a wardrobe before it starts? It starts with the match: put the player in one at once.
			server.runCommand("hw start");
			ctx.waitTicks(30);
			BlockPos locker = server.computeOnServer(s -> Lockers.nearestFree(s.overworld(), player(s).blockPosition(), 80));
			check(server.computeOnServer(s -> {
				BlockPos front = Lockers.front(s.overworld(), locker);
				player(s).teleportTo(s.overworld(), front.getX() + 0.5, front.getY(), front.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
				return !Lockers.enter(player(s), locker, false) && !Lockers.isInside(player(s));
			}), "the wardrobes are jammed");
			ctx.waitTicks(400);
			ctx.takeScreenshot("quake_tremor");
			int[] mid = server.computeOnServer(s -> count(s, null));
			LOG.info("after 20 s: {} windows, {} doors", mid[0], mid[1]);
			check(mid[0] + mid[1] < before[0] + before[1], "the tremors are breaking things");
			ctx.waitTicks(20 * 45);
			int[] after = server.computeOnServer(s -> count(s, null));
			int[] afterAll = server.computeOnServer(s -> count(s, MapPoints.get(s.overworld()).first(MapPoints.Type.CENTER)));
			LOG.info("round the centre: {} windows, {} doors (outside the match area the quake leaves them)", afterAll[0], afterAll[1]);
			int lying = server.computeOnServer(s -> s.overworld().getEntitiesOfClass(net.minecraft.world.entity.Display.BlockDisplay.class,
				new net.minecraft.world.phys.AABB(player(s).blockPosition()).inflate(80), e -> e.entityTags().contains("hw_quake")).size());
			LOG.info("near the end: {} windows, {} doors, {} door halves lying about", after[0], after[1], lying);
			check(after[0] == 0 && after[1] == 0, "by the end every window is broken and every door knocked out");
			check(lying >= 2 * before[1], "the doors lie on the floor");
			server.runOnServer(s -> {
				// A look at a doorway.
				BlockPos c = MapPoints.get(s.overworld()).first(MapPoints.Type.CENTER);
				player(s).teleportTo(s.overworld(), c.getX() - 4 + 0.5, c.getY(), c.getZ() + 4.5, java.util.Set.of(), 0f, 30f, false);
			});
			ctx.waitTicks(10);
			ctx.takeScreenshot("quake_after");
			server.runCommand("hw stop");
			ctx.waitTicks(10);
			int[] back = server.computeOnServer(s -> count(s, MapPoints.get(s.overworld()).first(MapPoints.Type.CENTER)));
			LOG.info("after the match: {} windows, {} doors", back[0], back[1]);
			check(back[0] == before[0] && back[1] == before[1], "after the match the windows and doors are back");
			server.runOnServer(s -> {
				HwConfig fresh = new HwConfig();
				HwConfig.get().matchSeconds = fresh.matchSeconds;
				HwConfig.get().midnightSeconds = fresh.midnightSeconds;
				HwConfig.get().lootMinSpacing = fresh.lootMinSpacing;
			});
		}
		LOG.info("Quake client test finished OK");
	}

	/** Whole windows (columns) and doors in the match area (or round the centre before there is one). */
	private static int[] count(MinecraftServer s, BlockPos center) {
		ServerLevel level = s.overworld();
		BoundingBox area = Nav.area();
		if (center != null) {
			area = new BoundingBox(center.getX() - 40, -64, center.getZ() - 40, center.getX() + 40, 320, center.getZ() + 40);
		}
		int windows = 0;
		int doors = 0;
		int y0 = (center != null ? center : player(s).blockPosition()).getY();
		for (BlockPos p : BlockPos.betweenClosed(area.minX(), y0 - 3, area.minZ(), area.maxX(), y0 + 12, area.maxZ())) {
			var state = level.getBlockState(p);
			if (state.getBlock() instanceof DoorBlock && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
				== net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
				doors++;
			} else if (Windows.isWindow(state) && !Windows.isBrokenPane(state) && !Windows.isWindow(level.getBlockState(p.below()))) {
				windows++;
			}
		}
		return new int[]{windows, doors};
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
