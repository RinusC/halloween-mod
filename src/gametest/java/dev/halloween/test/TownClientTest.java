package dev.halloween.test;

import dev.halloween.game.LockerBlock;
import dev.halloween.game.MapJob;
import dev.halloween.game.MapPoints;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The second town (/hw map town): it builds, has all the points a match needs, wardrobes open onto rooms, people stand in free space. */
public class TownClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-town");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("town")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw map town");
			ctx.waitTicks(2);
			boolean busy = true;
			for (int i = 0; i < 3000 && busy; i++) {
				ctx.waitTicks(10);
				busy = server.computeOnServer(s -> MapJob.busy());
			}
			check(!busy, "the town is built");
			// Everything a match needs.
			for (MapPoints.Type t : MapPoints.Type.values()) {
				int n = server.computeOnServer(s -> MapPoints.get(s.overworld()).list(t).size());
				LOG.info("Points {}: {}", t, n);
			}
			for (MapPoints.Type t : new MapPoints.Type[]{MapPoints.Type.SPAWN_SHAPE, MapPoints.Type.NPC, MapPoints.Type.PHONE, MapPoints.Type.GUN_CASE,
				MapPoints.Type.CELLAR, MapPoints.Type.CAR}) {
				check(server.computeOnServer(s -> !MapPoints.get(s.overworld()).list(t).isEmpty()), "the town has " + t);
			}
			check(server.computeOnServer(s -> MapPoints.get(s.overworld()).list(MapPoints.Type.GUN_CASE).size()) >= 2, "a gun case at the Strodes' and at the police station");
			// People stand in free space, phones are where the points say.
			List<String> stuck = server.computeOnServer(s -> {
				ServerLevel level = s.overworld();
				List<String> out = new ArrayList<>();
				for (BlockPos p : MapPoints.get(level).list(MapPoints.Type.NPC)) {
					if (blocked(level, p)) {
						out.add(p.toShortString() + "=" + name(level, p) + "/" + name(level, p.above()));
					}
				}
				for (BlockPos p : MapPoints.get(level).list(MapPoints.Type.PHONE)) {
					if (!name(level, p).equals("phone")) {
						out.add("phone " + p.toShortString() + "=" + name(level, p));
					}
				}
				return out;
			});
			stuck.forEach(b -> LOG.info("BAD point {}", b));
			check(stuck.isEmpty(), "townsfolk stand in free space and every phone is there (" + stuck.size() + " bad)");
			List<String> bad = server.computeOnServer(s -> {
				ServerLevel level = s.overworld();
				BlockPos c = MapPoints.get(level).first(MapPoints.Type.CENTER);
				List<String> out = new ArrayList<>();
				int total = 0;
				for (BlockPos p : BlockPos.betweenClosed(c.offset(-150, -6, -150), c.offset(150, 12, 150))) {
					BlockState st = level.getBlockState(p);
					if (!LockerBlock.is(st) || st.getValue(LockerBlock.HALF) != DoubleBlockHalf.LOWER) {
						continue;
					}
					total++;
					Direction facing = st.getValue(LockerBlock.FACING);
					if (blocked(level, p.relative(facing))) {
						out.add(p.toShortString() + " facing " + facing + " front=" + name(level, p.relative(facing)));
					}
				}
				LOG.info("Lockers in town: {}", total);
				if (total < 40) {
					out.add("only " + total + " lockers");
				}
				return out;
			});
			bad.forEach(b -> LOG.info("BAD locker {}", b));
			check(bad.isEmpty(), "every wardrobe opens onto a room");
			List<String> cars = server.computeOnServer(s -> {
				List<String> out = new ArrayList<>();
				for (BlockPos p : MapPoints.get(s.overworld()).list(MapPoints.Type.CAR)) {
					if (!dev.halloween.game.Exits.carFits(s.overworld(), p)) {
						out.add(p.toShortString());
					}
				}
				return out;
			});
			check(cars.isEmpty(), "the car fits at every spot " + cars);
			// Everything can be walked to from the street: townsfolk, wardrobes, phones, things to search, upstairs too.
			List<String> cut = server.computeOnServer(s -> unreachable(s.overworld(), MapPoints.get(s.overworld()).first(MapPoints.Type.CENTER)));
			cut.forEach(b -> LOG.info("UNREACHABLE {}", b));
			check(cut.isEmpty(), "everything inside can be walked to from the street (" + cut.size() + " cut off)");
			// A look round.
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			server.runCommand("gamemode spectator @p");
			BlockPos c = server.computeOnServer(s -> MapPoints.get(s.overworld()).first(MapPoints.Type.CENTER));
			shot(ctx, server, c, 0, 70, 130, 180, 40, "town_overview");
			shot(ctx, server, c, -40, 12, 90, 180, 25, "town_main_street");
			shot(ctx, server, c, 30, 14, 30, 200, 30, "town_services");
			shot(ctx, server, c, 110, 30, -20, 180, 35, "town_industrial");
			shot(ctx, server, c, -110, 25, -25, 160, 40, "town_houses_north");
			shot(ctx, server, c, -130, 25, 30, 200, 40, "town_houses_west");
			shot(ctx, server, c, 40, 25, -100, 0, 40, "town_orange_grove");
			shot(ctx, server, c, -100, 5, 50, 0, 10, "town_shops_street");
			shot(ctx, server, c, 30, 4, 63, 0, 5, "town_services_street");
			shot(ctx, server, c, 43, 2, -2, 180, 0, "town_front_mansion");
			shot(ctx, server, c, -15, 2, -2, 180, 0, "town_front_townhouses");
			shot(ctx, server, c, 108, 2, -2, 180, 0, "town_front_bungalow");
			shot(ctx, server, c, 14, 2, 2, 0, 0, "town_front_fire");
			shot(ctx, server, c, 82, 2, 2, 0, 0, "town_front_clinic");
			shot(ctx, server, c, -121, 2, 61, 180, 0, "town_front_shops");
			shot(ctx, server, c, 95, 2, 61, 180, 0, "town_front_cinema");
			shot(ctx, server, c, -44, 2, 62, 0, 0, "town_front_police");
			if (System.getenv("HW_PLANS") != null) {
				// Floor plans: the roofs and the upstairs walls taken off, then the upstairs too, seen from above.
				int[][] spots = {{-134, -19}, {-44, -19}, {-15, -19}, {43, -19}, {108, -19}, {134, -19}, {-134, 19}, {14, 19}, {43, 19}, {82, 19}, {-134, 50}, {-108, 50}, {82, 50}, {108, 50}, {-44, 73}, {43, 73}, {82, 73}, {108, 83}, {-44, -45}, {43, -45}, {82, -45}, {108, -45}};
				cut(server, c, 7);
				for (int[] p : spots) {
					shot(ctx, server, c, p[0], 24, p[1], 180, 90, "plan_up_" + p[0] + "_" + p[1]);
				}
				cut(server, c, 2);
				for (int[] p : spots) {
					shot(ctx, server, c, p[0], 20, p[1], 180, 90, "plan_down_" + p[0] + "_" + p[1]);
				}
			}
		}
		LOG.info("Town client test finished OK");
	}

	private static void shot(ClientGameTestContext ctx, TestServerContext server, BlockPos c, int dx, int dy, int dz, float yaw, float pitch, String name) {
		server.runCommand("tp @p " + (c.getX() + dx) + " " + (c.getY() + dy) + " " + (c.getZ() + dz) + " " + yaw + " " + pitch);
		ctx.waitTicks(60);
		ctx.takeScreenshot(name);
	}

	/** How high the block's collision reaches (0: nothing to bump into); doors count as open. */
	private static double top(ServerLevel level, BlockPos p) {
		BlockState st = level.getBlockState(p);
		if (st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
			return 0;
		}
		var shape = st.getCollisionShape(level, p);
		return shape.isEmpty() ? 0 : shape.max(net.minecraft.core.Direction.Axis.Y);
	}

	/** Feet at p: room for a body, something to stand on that isn't a fence or a wall. */
	private static boolean standable(ServerLevel level, BlockPos p) {
		double under = top(level, p.below());
		return top(level, p) == 0 && top(level, p.above()) == 0 && under > 0 && under <= 1.0;
	}

	/**
	 * Walks the town from the middle of the street (a step up onto stairs, slabs and beds, no jumping: a
	 * staircase must be climbable as it is; a drop of up to three) and lists what can't be reached.
	 */
	private static List<String> unreachable(ServerLevel level, BlockPos c) {
		java.util.Set<Long> seen = new java.util.HashSet<>();
		java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
		BlockPos start = c;
		for (int dy = -2; dy <= 3 && !standable(level, start); dy++) {
			start = c.offset(0, dy, 0);
		}
		seen.add(start.asLong());
		queue.add(start);
		while (!queue.isEmpty()) {
			BlockPos p = queue.poll();
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos side = p.relative(d);
				if (Math.abs(side.getX() - c.getX()) > 152 || Math.abs(side.getZ() - c.getZ()) > 152) {
					continue;
				}
				for (int dy = 1; dy >= -3; dy--) {
					BlockPos q = side.offset(0, dy, 0);
					if (seen.contains(q.asLong()) || !standable(level, q)) {
						continue;
					}
					if (dy == 1) {
						BlockState step = level.getBlockState(q.below());
						boolean low = step.getBlock() instanceof net.minecraft.world.level.block.StairBlock || top(level, q.below()) <= 0.6;
						if (!low) {
							continue;
						}
					}
					if (dy < 0) {
						// Walking off an edge: the way over it must be open.
						boolean open = true;
						for (int y = dy + 2; y <= 1 && open; y++) {
							open = top(level, side.offset(0, y, 0)) == 0;
						}
						if (!open) {
							continue;
						}
					}
					seen.add(q.asLong());
					queue.add(q);
					break;
				}
			}
		}
		List<String> out = new ArrayList<>();
		for (BlockPos p : MapPoints.get(level).list(MapPoints.Type.NPC)) {
			if (!seen.contains(p.asLong())) {
				out.add("townsperson at " + p.toShortString());
			}
		}
		for (BlockPos p : MapPoints.get(level).list(MapPoints.Type.PHONE)) {
			if (!near(seen, p, 2)) {
				out.add("phone at " + p.toShortString());
			}
		}
		for (BlockPos p : BlockPos.betweenClosed(c.offset(-150, -2, -150), c.offset(150, 12, 150))) {
			BlockState st = level.getBlockState(p);
			if (LockerBlock.is(st) && st.getValue(LockerBlock.HALF) == DoubleBlockHalf.LOWER) {
				BlockPos front = p.relative(st.getValue(LockerBlock.FACING));
				if (!seen.contains(front.asLong())) {
					out.add("wardrobe at " + p.toShortString());
				}
			} else if (dev.halloween.game.SearchBlock.is(st) && !near(seen, p, 1)) {
				out.add(name(level, p) + " at " + p.toShortString() + " beside: " + name(level, p.north()) + "/" + name(level, p.south()) + "/" + name(level, p.east()) + "/" + name(level, p.west()) + " standable S/W: " + standable(level, p.south()) + "/" + standable(level, p.west()) + " walked nearby: " + nearby(seen, p, 6) + " under S: " + name(level, p.south().below()));
			}
		}
		return out;
	}

	private static int nearby(java.util.Set<Long> seen, BlockPos p, int r) {
		int n = 0;
		for (BlockPos q : BlockPos.betweenClosed(p.offset(-r, 0, -r), p.offset(r, 0, r))) {
			n += seen.contains(q.asLong()) ? 1 : 0;
		}
		return n;
	}

	/** Somewhere to stand within reach: next to it, level with it or a block up or down. */
	private static boolean near(java.util.Set<Long> seen, BlockPos p, int reach) {
		for (BlockPos q : BlockPos.betweenClosed(p.offset(-reach, -2, -reach), p.offset(reach, 1, reach))) {
			if (seen.contains(q.asLong()) && Math.abs(q.getX() - p.getX()) + Math.abs(q.getZ() - p.getZ()) <= reach + 1) {
				return true;
			}
		}
		return false;
	}

	/** Takes off everything from {@code dy} above the ground up (bulbs in the ceilings stay out of it: they are higher). */
	private static void cut(TestServerContext server, BlockPos c, int dy) {
		server.runOnServer(s -> {
			ServerLevel level = s.overworld();
			for (BlockPos p : BlockPos.betweenClosed(c.offset(-150, dy, -70), c.offset(150, 30, 95))) {
				if (!level.getBlockState(p).isAir()) {
					level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2 | 16);
				}
			}
		});
	}

	private static String name(ServerLevel level, BlockPos pos) {
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();
	}

	private static boolean blocked(ServerLevel level, BlockPos pos) {
		return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
			|| !level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
