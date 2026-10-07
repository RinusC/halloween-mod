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

/** Every wardrobe in Haddonfield opens onto the room (its door isn't against a wall or a piece of furniture). */
public class LockerFacingClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-locker-facing");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("lockers")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw map haddonfield");
			ctx.waitTicks(2);
			boolean busy = true;
			for (int i = 0; i < 3000 && busy; i++) {
				ctx.waitTicks(10);
				busy = server.computeOnServer(s -> MapJob.busy());
			}
			List<String> bad = server.computeOnServer(s -> {
				ServerLevel level = s.overworld();
				int minX = Integer.MAX_VALUE;
				int minZ = Integer.MAX_VALUE;
				int maxX = Integer.MIN_VALUE;
				int maxZ = Integer.MIN_VALUE;
				int y0 = 0;
				for (List<BlockPos> list : MapPoints.get(level).all().values()) {
					for (BlockPos p : list) {
						minX = Math.min(minX, p.getX());
						minZ = Math.min(minZ, p.getZ());
						maxX = Math.max(maxX, p.getX());
						maxZ = Math.max(maxZ, p.getZ());
						y0 = p.getY();
					}
				}
				List<String> out = new ArrayList<>();
				int total = 0;
				for (BlockPos p : BlockPos.betweenClosed(minX - 10, y0 - 6, minZ - 10, maxX + 10, y0 + 12, maxZ + 10)) {
					BlockState st = level.getBlockState(p);
					if (!LockerBlock.is(st) || st.getValue(LockerBlock.HALF) != DoubleBlockHalf.LOWER) {
						continue;
					}
					total++;
					Direction facing = st.getValue(LockerBlock.FACING);
					boolean frontBlocked = blocked(level, p.relative(facing));
					boolean backBlocked = blocked(level, p.relative(facing.getOpposite()));
					if (frontBlocked) {
						out.add(p.toShortString() + " facing " + facing + (backBlocked ? " (walled in)" : " (back is free: turned the wrong way)")
							+ " front=" + name(level, p.relative(facing)) + "/" + name(level, p.relative(facing).above()) + " back=" + name(level, p.relative(facing.getOpposite()))
							+ " left=" + name(level, p.relative(facing.getCounterClockWise())) + " right=" + name(level, p.relative(facing.getClockWise())));
					}
				}
				LOG.info("Lockers checked: {}", total);
				return out;
			});
			bad.forEach(b -> LOG.info("BAD locker {}", b));
			if (!bad.isEmpty()) {
				throw new AssertionError("FAILED: " + bad.size() + " lockers face a wall");
			}
			LOG.info("PASS: every locker opens onto the room");
			// Every car spot has room for the car; one blocked is moved to free ground next to it.
			List<String> cars = server.computeOnServer(s -> {
				List<String> out = new ArrayList<>();
				for (BlockPos p : MapPoints.get(s.overworld()).list(MapPoints.Type.CAR)) {
					if (!dev.halloween.game.Exits.carFits(s.overworld(), p)) {
						StringBuilder why = new StringBuilder();
						for (int u = -3; u <= 3; u++) {
							for (int w = -1; w <= 1; w++) {
								BlockPos q = p.offset(u, 0, w);
								if (!s.overworld().getBlockState(q.below()).isSolidRender()) {
									why.append(" ground(").append(u).append(",").append(w).append(")=").append(name(s.overworld(), q.below()));
								}
								for (int y = 0; y < 3; y++) {
									if (!s.overworld().getBlockState(q.above(y)).canBeReplaced()) {
										why.append(" (").append(u).append(",").append(y).append(",").append(w).append(")=").append(name(s.overworld(), q.above(y)));
									}
								}
							}
						}
						out.add(p.toShortString() + why);
					}
				}
				return out;
			});
			LOG.info("Car spots blocked: {}", cars);
			if (!cars.isEmpty()) {
				throw new AssertionError("FAILED: the car doesn't fit at " + cars);
			}
			LOG.info("PASS: the car fits at every spot");
			boolean moved = server.computeOnServer(s -> {
				BlockPos p = MapPoints.get(s.overworld()).list(MapPoints.Type.CAR).get(0);
				s.overworld().setBlock(p.offset(2, 1, 1), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
				BlockPos spot = dev.halloween.game.Exits.clearSpot(s.overworld(), p);
				s.overworld().setBlock(p.offset(2, 1, 1), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
				return !spot.equals(p) && spot.distManhattan(p) <= 8;
			});
			if (!moved) {
				throw new AssertionError("FAILED: a blocked car spot moves the car next to it");
			}
			LOG.info("PASS: a blocked car spot moves the car to free ground next to it");
			// A town built before the fix: one wardrobe turned to face a wall gets turned back when a match starts.
			BlockPos turned = server.computeOnServer(s -> {
				ServerLevel level = s.overworld();
				BlockPos c = MapPoints.get(level).first(MapPoints.Type.CENTER);
				for (BlockPos p : BlockPos.betweenClosed(c.offset(-150, -6, -150), c.offset(150, 6, 150))) {
					BlockState st = level.getBlockState(p);
					if (LockerBlock.is(st) && st.getValue(LockerBlock.HALF) == DoubleBlockHalf.LOWER) {
						for (Direction d : Direction.Plane.HORIZONTAL) {
							if (blocked(level, p.relative(d))) {
								level.setBlock(p, st.setValue(LockerBlock.FACING, d), 2);
								level.setBlock(p.above(), level.getBlockState(p.above()).setValue(LockerBlock.FACING, d), 2);
								return p.immutable();
							}
						}
					}
				}
				return null;
			});
			LOG.info("Turned the locker at {} to face a wall", turned);
			server.runOnServer(s -> dev.halloween.HwConfig.get().countdownSeconds = 1);
			server.runCommand("hw start");
			ctx.waitTicks(40);
			boolean fixed = server.computeOnServer(s -> {
				BlockState st = s.overworld().getBlockState(turned);
				return !blocked(s.overworld(), turned.relative(st.getValue(LockerBlock.FACING)))
					&& s.overworld().getBlockState(turned.above()).getValue(LockerBlock.FACING) == st.getValue(LockerBlock.FACING);
			});
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> dev.halloween.HwConfig.get().countdownSeconds = new dev.halloween.HwConfig().countdownSeconds);
			if (!fixed) {
				throw new AssertionError("FAILED: a wardrobe facing a wall is turned at match start");
			}
			LOG.info("PASS: a wardrobe facing a wall is turned to the room at match start");
		}
	}

	private static String name(ServerLevel level, BlockPos pos) {
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();
	}

	private static boolean blocked(ServerLevel level, BlockPos pos) {
		return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
			|| !level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
	}
}
