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
