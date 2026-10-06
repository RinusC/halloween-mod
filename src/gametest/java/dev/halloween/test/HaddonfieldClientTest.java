package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Bots;
import dev.halloween.game.Exits;
import dev.halloween.game.HwItems;
import dev.halloween.game.LightSystem;
import dev.halloween.game.Loot;
import dev.halloween.game.MapJob;
import dev.halloween.game.MapPoints;
import dev.halloween.game.Match;
import dev.halloween.game.MatchManager;
import dev.halloween.game.Nav;
import dev.halloween.game.Townsfolk;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Haddonfield map: it builds, every map point is where it should be and can be walked to, the
 * houses have what a match needs, and a match with bots runs on it. Screenshots show the town.
 */
public class HaddonfieldClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-map-test");

	private BlockPos origin;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("map")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
			this.origin = server.computeOnServer(s -> player(s).blockPosition());
			long started = System.currentTimeMillis();
			server.runCommand("execute as @p at @p run hw map haddonfield");
			ctx.waitTicks(2);
			boolean busy = true;
			for (int i = 0; i < 3000 && busy; i++) {
				ctx.waitTicks(10);
				busy = server.computeOnServer(s -> MapJob.busy());
			}
			LOG.info("Haddonfield built in {} s", (System.currentTimeMillis() - started) / 1000);
			check(!busy, "the map gets built");

			// Every kind of point, enough of each.
			Map<MapPoints.Type, Integer> counts = server.computeOnServer(s -> {
				Map<MapPoints.Type, Integer> m = new EnumMap<>(MapPoints.Type.class);
				for (MapPoints.Type t : MapPoints.Type.values()) {
					m.put(t, MapPoints.get(s.overworld()).list(t).size());
				}
				return m;
			});
			LOG.info("Map points: {}", counts);
			int[][] need = {
				{MapPoints.Type.CENTER.ordinal(), 1}, {MapPoints.Type.SPAWN_SHAPE.ordinal(), 1}, {MapPoints.Type.SPAWN_CIVILIAN.ordinal(), 4},
				{MapPoints.Type.SPAWN_DEPUTY.ordinal(), 1}, {MapPoints.Type.CELLAR.ordinal(), 3}, {MapPoints.Type.CAR.ordinal(), 3},
				{MapPoints.Type.POLICE.ordinal(), 3}, {MapPoints.Type.GATE.ordinal(), 4}, {MapPoints.Type.PHONE.ordinal(), 25},
				{MapPoints.Type.NPC.ordinal(), 25}, {MapPoints.Type.GUN_CASE.ordinal(), 3}};
			for (int[] n : need) {
				MapPoints.Type t = MapPoints.Type.values()[n[0]];
				check(counts.get(t) >= n[1], "the map has " + n[1] + "+ " + t.key() + " points (" + counts.get(t) + ")");
			}

			// Spawns and townsfolk stand on open ground; phones sit on something.
			String badSpots = server.computeOnServer(s -> {
				ServerLevel level = s.overworld();
				MapPoints points = MapPoints.get(level);
				List<String> bad = new ArrayList<>();
				for (MapPoints.Type t : new MapPoints.Type[]{MapPoints.Type.SPAWN_SHAPE, MapPoints.Type.SPAWN_CIVILIAN, MapPoints.Type.SPAWN_DEPUTY,
					MapPoints.Type.NPC, MapPoints.Type.GUN_CASE, MapPoints.Type.CELLAR, MapPoints.Type.POLICE}) {
					for (BlockPos p : points.list(t)) {
						if (!Nav.standable(level, p)) {
							bad.add(t.key() + "@" + p.subtract(this.origin).toShortString());
						}
					}
				}
				for (BlockPos p : points.list(MapPoints.Type.PHONE)) {
					if (!Exits.isPhone(level, p)) {
						bad.add("phone@" + p.subtract(this.origin).toShortString());
					}
				}
				return String.join(", ", bad);
			});
			check(badSpots.isEmpty(), "every spawn, townsperson, cellar, van and gun case spot is open ground, every phone is a phone (" + badSpots + ")");

			// Bots and townsfolk can walk everywhere that matters: exits, the Myers house, an upstairs bedroom.
			String unreachable = server.computeOnServer(s -> {
				ServerLevel level = s.overworld();
				MapPoints points = MapPoints.get(level);
				BlockPos from = points.list(MapPoints.Type.SPAWN_CIVILIAN).getFirst();
				List<BlockPos> targets = new ArrayList<>();
				for (MapPoints.Type t : new MapPoints.Type[]{MapPoints.Type.CELLAR, MapPoints.Type.CAR, MapPoints.Type.POLICE,
					MapPoints.Type.SPAWN_SHAPE, MapPoints.Type.SPAWN_CIVILIAN, MapPoints.Type.GUN_CASE, MapPoints.Type.SPAWN_DEPUTY}) {
					targets.addAll(points.list(t));
				}
				List<BlockPos> npcs = points.list(MapPoints.Type.NPC);
				for (int i = 0; i < npcs.size(); i += 3) {
					targets.add(npcs.get(i));
				}
				// The Doyle house's back bedroom, upstairs.
				targets.add(this.origin.offset(82, 5, -43));
				List<String> bad = new ArrayList<>();
				for (BlockPos target : targets) {
					List<Nav.Node> path = Nav.to(level, from, target, 2.5, Nav.Options.walker().nodes(80000));
					BlockPos end = path.isEmpty() ? from : path.getLast().pos();
					if (end.distSqr(target) > 3.5 * 3.5) {
						bad.add(target.subtract(this.origin).toShortString() + " (got to " + end.subtract(this.origin).toShortString() + ")");
					}
				}
				return String.join("; ", bad);
			});
			check(unreachable.isEmpty(), "every exit spot, spawn, gun case, a sample of townsfolk and an upstairs bedroom can be walked to (" + unreachable + ")");

			// The edge of town is woods nobody walks through: something solid two high all along the edge
			// (hedge, trunks, or the barricade across a gate lane), and nothing in the woods can be walked to.
			String edge = server.computeOnServer(s -> {
				ServerLevel level = s.overworld();
				int f = 148;
				List<String> bad = new ArrayList<>();
				for (int t = -147; t <= 147; t += 3) {
					for (int[] p : new int[][]{{t, -f}, {t, f}, {-f, t}, {f, t}}) {
						for (int y = 0; y <= 1; y++) {
							BlockPos pos = this.origin.offset(p[0], y, p[1]);
							if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
								bad.add(p[0] + "," + y + "," + p[1]);
							}
						}
					}
				}
				BlockPos from = MapPoints.get(level).list(MapPoints.Type.SPAWN_CIVILIAN).getFirst();
				for (int[] p : new int[][]{{-153, 30}, {153, -30}, {30, 153}, {-30, -153}, {-153, 0}, {153, -64}, {-64, 153}, {64, -153}}) {
					BlockPos target = this.origin.offset(p[0], 0, p[1]);
					List<Nav.Node> path = Nav.to(level, from, target, 1.5, Nav.Options.walker().nodes(80000));
					if (!path.isEmpty() && path.getLast().pos().distSqr(target) <= 4) {
						bad.add("walked to " + p[0] + "," + p[1]);
					}
				}
				return String.join("; ", bad);
			});
			check(edge.isEmpty(), "the town ends in woods nobody gets through (" + edge + ")");

			// A house has its doors, stairs up, a breaker that works the lights.
			String house = server.computeOnServer(s -> {
				ServerLevel level = s.overworld();
				BlockPos front = this.origin.offset(82, 0, -51);
				if (!(level.getBlockState(front).getBlock() instanceof DoorBlock)) {
					return "no front door at the Doyle house";
				}
				if (!(level.getBlockState(this.origin.offset(77, 0, -49)).getBlock() instanceof StairBlock)) {
					return "no stairs up";
				}
				List<BlockPos> breakers = LightSystem.breakers(level, this.origin.offset(82, 0, -45), 10);
				if (breakers.isEmpty()) {
					return "no breaker";
				}
				return "ok";
			});
			check("ok".equals(house), "the Doyle house has a front door, stairs up and a breaker box (" + house + ")");

			// Wiring: every light in town hangs on some breaker; the Doyles' breaker puts out their whole house and
			// nobody else's.
			String wiring = server.computeOnServer(s -> {
				ServerLevel level = s.overworld();
				int r = HwConfig.get().breakerRadius;
				List<BlockPos> breakers = LightSystem.breakers(level, this.origin, 156);
				int bulbs = 0;
				List<String> orphans = new ArrayList<>();
				for (BlockPos p : BlockPos.betweenClosed(this.origin.offset(-156, 0, -156), this.origin.offset(156, 12, 156))) {
					if (level.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.CopperBulbBlock) {
						bulbs++;
						BlockPos at = p.immutable();
						if (breakers.stream().noneMatch(b -> Math.abs(b.getX() - at.getX()) <= r && Math.abs(b.getY() - at.getY()) <= r
							&& Math.abs(b.getZ() - at.getZ()) <= r)) {
							orphans.add(at.subtract(this.origin).toShortString());
						}
					}
				}
				java.util.function.BiFunction<BlockPos, BlockPos, Integer> lit = (a, b) -> {
					int n = 0;
					for (BlockPos p : BlockPos.betweenClosed(a, b)) {
						var st = level.getBlockState(p);
						n += st.getBlock() instanceof net.minecraft.world.level.block.CopperBulbBlock && st.getValue(net.minecraft.world.level.block.CopperBulbBlock.LIT) ? 1 : 0;
					}
					return n;
				};
				BlockPos doyleA = this.origin.offset(75, 0, -52);
				BlockPos doyleB = this.origin.offset(89, 10, -39);
				BlockPos nextA = this.origin.offset(97, 0, -52);
				BlockPos nextB = this.origin.offset(121, 10, -39);
				int doyleOn = lit.apply(doyleA, doyleB);
				int nextOn = lit.apply(nextA, nextB);
				LightSystem.flip(level, this.origin.offset(89, 1, -45), false);
				int doyleOff = lit.apply(doyleA, doyleB);
				int nextAfter = lit.apply(nextA, nextB);
				LightSystem.flip(level, this.origin.offset(89, 1, -45), true);
				LOG.info("Bulbs in town: {}, orphans: {}; Doyle lit {} -> {}, next door {} -> {}", bulbs, orphans, doyleOn, doyleOff, nextOn, nextAfter);
				if (!orphans.isEmpty()) {
					return "lights on no breaker: " + orphans;
				}
				return doyleOn >= 8 && doyleOff == 0 && nextAfter == nextOn && nextOn > 0 ? "ok"
					: "Doyle " + doyleOn + " -> " + doyleOff + ", next door " + nextOn + " -> " + nextAfter;
			});
			check("ok".equals(wiring), "every light hangs on a breaker; a house's breaker puts out just that house (" + wiring + ")");

			// Pictures: the town from above by day, Lampkin Lane and the Myers house at night, inside a house.
			server.runCommand("gamemode spectator @p");
			server.runCommand("time set noon");
			String biome = server.computeOnServer(s -> s.overworld().getBiome(this.origin.offset(10, 0, 10)).unwrapKey().map(k -> k.identifier().getPath()).orElse("?"));
			check("badlands".equals(biome), "the town is in autumn colours (" + biome + ")");
			this.view(ctx, server, new Vec3(0, 120, 60), 0, 60);
			ctx.waitTicks(200);
			ctx.takeScreenshot("h01_town_from_above");
			this.view(ctx, server, new Vec3(-160, 60, 160), -135, 25);
			ctx.waitTicks(120);
			ctx.takeScreenshot("h02_town_from_southwest");
			this.view(ctx, server, new Vec3(-108, 3, 4), 180, 10);
			ctx.takeScreenshot("h03_myers_house_day");
			this.view(ctx, server, new Vec3(0, 6, -60), 180, 15);
			ctx.takeScreenshot("h04_school");
			this.view(ctx, server, new Vec3(-20, 4, 70), 180, 5);
			ctx.takeScreenshot("h05_main_street");
			this.view(ctx, server, new Vec3(-125, 4, -80), 180, 10);
			ctx.takeScreenshot("h06_cemetery");
			server.runCommand("time set midnight");
			this.view(ctx, server, new Vec3(-95, 3, 4), 140, 8);
			ctx.takeScreenshot("h07_lampkin_lane_night");
			server.runCommand("time set noon");
			this.view(ctx, server, new Vec3(86.5, 0.2, -46.5), 135, 12);
			ctx.takeScreenshot("h08_doyle_living_room");
			this.view(ctx, server, new Vec3(86.5, 0.2, -43.5), 50, 15);
			ctx.takeScreenshot("h09_doyle_kitchen");
			this.view(ctx, server, new Vec3(80.5, 5.2, -46.5), -130, 15);
			ctx.takeScreenshot("h10_doyle_bedroom");
			this.view(ctx, server, new Vec3(14.5, 0.2, 49.5), 180, 10);
			ctx.takeScreenshot("h11_diner");
			this.view(ctx, server, new Vec3(-48.5, 0.2, 50.5), -150, 10);
			ctx.takeScreenshot("h12_hardware");
			this.view(ctx, server, new Vec3(-21.5, 0.2, -108.5), 180, 10);
			ctx.takeScreenshot("h13_classroom");
			this.view(ctx, server, new Vec3(0.5, 0.2, 111.5), 0, 5);
			ctx.takeScreenshot("h14_church");
			this.view(ctx, server, new Vec3(-90, 4, 84), 20, 20);
			ctx.takeScreenshot("h15_park_fair");
			this.view(ctx, server, new Vec3(40, 4, -20), 30, 20);
			ctx.takeScreenshot("h16_backyards");
			this.view(ctx, server, new Vec3(-122, 2, 64), 90, 4);
			ctx.takeScreenshot("h17_forest_edge");
			server.runCommand("gamemode survival @p");

			// A match on the map: the player is the Shape, four bots, townsfolk, the exits come up.
			server.runOnServer(s -> HwConfig.get().countdownSeconds = 2);
			server.runCommand("hw start @p");
			ctx.waitTicks(60);
			check(server.computeOnServer(s -> Match.running()), "a match starts on Haddonfield");
			check(server.computeOnServer(s -> MatchManager.role(player(s))) == Role.SHAPE, "the player is the Shape at the Myers house");
			int containers = server.computeOnServer(s -> Loot.containers().size());
			check(containers >= 50, "the town's chests and barrels are searchable (" + containers + ")");
			check(server.computeOnServer(s -> Exits.infos().size()) == 4, "all four kinds of exit are set up");
			check(server.computeOnServer(s -> {
				Map<HwItems.Kind, Long> hidden = Loot.hidden().values().stream()
					.collect(java.util.stream.Collectors.groupingBy(k -> k, java.util.stream.Collectors.counting()));
				LOG.info("Hidden in Haddonfield: {}", hidden);
				for (HwItems.Kind kind : HwItems.Kind.values()) {
					if (kind.escape() && hidden.getOrDefault(kind, 0L) < (kind == HwItems.Kind.REPAIR_KIT ? 2 : 1)) {
						return false;
					}
				}
				return hidden.values().stream().mapToLong(Long::longValue).sum() > 30;
			}), "every escape item is hidden in town, and plenty besides");
			// The match's gate is a big one, out on the edge, facing town.
			BlockPos gate = server.computeOnServer(s -> Exits.infos().stream().filter(e -> e.type() == Exits.GATE).findFirst().orElseThrow().pos());
			BlockPos inward = new BlockPos(-Integer.signum(Math.abs(gate.getX() - this.origin.getX()) >= 148 ? gate.getX() - this.origin.getX() : 0), 0,
				-Integer.signum(Math.abs(gate.getZ() - this.origin.getZ()) >= 148 ? gate.getZ() - this.origin.getZ() : 0));
			check(server.computeOnServer(s -> s.overworld().getBlockState(gate.above(4)).is(net.minecraft.world.level.block.Blocks.DARK_OAK_LOG)
				&& s.overworld().getBlockState(gate.above(6).offset(inward.getZ() != 0 ? 3 : 0, 0, inward.getX() != 0 ? 3 : 0)).is(net.minecraft.world.level.block.Blocks.LANTERN)),
				"the gate out of town is a big lit one (" + gate.subtract(this.origin).toShortString() + ")");
			Vec3 gateView = Vec3.atBottomCenterOf(gate.offset(inward.multiply(9))).add(0, 1, 0);
			float gateYaw = inward.getX() > 0 ? 90f : inward.getX() < 0 ? -90f : inward.getZ() > 0 ? 180f : 0f;
			server.runOnServer(s -> player(s).teleportTo(s.overworld(), gateView.x, gateView.y, gateView.z, java.util.Set.of(), gateYaw, -8f, false));
			ctx.waitTicks(40);
			ctx.takeScreenshot("h18_big_gate");
			check(server.computeOnServer(s -> Townsfolk.count()) >= 6, "townsfolk are at home");
			Map<UUID, Vec3> start = server.computeOnServer(s -> {
				Map<UUID, Vec3> m = new HashMap<>();
				Bots.all().forEach(b -> m.put(b.getUUID(), b.position()));
				return m;
			});
			check(start.size() == 4, "four bots play the civilians");
			// The furniture is scenery in a match: no sleeping in the Doyles' bed, no cooking on their stove.
			String scenery = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				ServerLevel level = s.overworld();
				List<String> bad = new ArrayList<>();
				for (BlockPos at : new BlockPos[]{this.origin.offset(87, 5, -48), this.origin.offset(80, 0, -41)}) {
					var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(at), net.minecraft.core.Direction.UP, at, false);
					p.teleportTo(level, at.getX() + 0.5, at.getY() + 1, at.getZ() + 1.5, java.util.Set.of(), 0f, 0f, false);
					var result = p.gameMode.useItemOn(p, level, p.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND, hit);
					String what = level.getBlockState(at).getBlock().getDescriptionId();
					if (result.consumesAction() || p.isSleeping() || p.containerMenu != p.inventoryMenu) {
						bad.add(what + " -> " + result);
					}
					if (!what.contains("bed") && !what.contains("smoker")) {
						bad.add("expected a bed and a stove, found " + what);
					}
				}
				return String.join(", ", bad);
			});
			check(scenery.isEmpty(), "beds and stoves are scenery during a match (" + scenery + ")");
			// The Shape keeps out of the way while the bots go about their business.
			server.runOnServer(s -> player(s).teleportTo(s.overworld(), this.origin.getX() + 140.5, this.origin.getY(), this.origin.getZ() + 140.5,
				java.util.Set.of(), 0f, 0f, false));
			ctx.waitTicks(600);
			double moved = server.computeOnServer(s -> Bots.all().stream()
				.mapToDouble(b -> b.position().distanceTo(start.getOrDefault(b.getUUID(), b.position()))).max().orElse(0));
			int searched = server.computeOnServer(s -> Loot.searched().size());
			LOG.info("Bots walked up to {} blocks, searched {} containers; stats {}", moved, searched,
				server.computeOnServer(s -> dev.halloween.game.AiStats.snapshot().get("*total")));
			check(moved > 15, "bots get about the town (" + moved + ")");
			check(searched > 0, "bots search the houses (" + searched + ")");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
		}
		LOG.info("Haddonfield map test finished OK");
	}

	/** Puts the (spectating) player at {@code at} (relative to the map centre) looking along yaw/pitch. */
	private void view(ClientGameTestContext ctx, TestServerContext server, Vec3 at, float yaw, float pitch) {
		server.runOnServer(s -> player(s).teleportTo(s.overworld(), this.origin.getX() + at.x, this.origin.getY() + at.y, this.origin.getZ() + at.z,
			java.util.Set.of(), yaw, pitch, false));
		ctx.waitTicks(60);
	}

	private static ServerPlayer player(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!(p instanceof BotPlayer)) {
				return p;
			}
		}
		throw new IllegalStateException("no player");
	}

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
