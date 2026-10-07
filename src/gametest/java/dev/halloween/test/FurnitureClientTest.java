package dev.halloween.test;

import dev.halloween.HwBlocks;
import dev.halloween.HwConfig;
import dev.halloween.client.CameraAim;
import dev.halloween.game.HwItems;
import dev.halloween.game.Inventory;
import dev.halloween.game.Loot;
import dev.halloween.game.Match;
import dev.halloween.game.SearchBlock;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Furniture to search: every piece shows closed and open (screenshots), searching one opens it, a new match closes it. */
public class FurnitureClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-furniture-test");
	private static final List<Block> PIECES = List.of(HwBlocks.DRESSER, HwBlocks.NIGHTSTAND, HwBlocks.KITCHEN_CABINET, HwBlocks.CARDBOARD_BOX,
		HwBlocks.CRATE, HwBlocks.TOOL_CABINET, HwBlocks.FILING_CABINET);

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("furniture")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			BlockPos origin = server.computeOnServer(s -> player(s).blockPosition());
			server.runCommand("time set noon");

			// A showroom past the arena: every piece in a row, fronts to the camera.
			BlockPos room = origin.offset(130, 0, 0);
			server.runOnServer(s -> {
				var level = s.overworld();
				for (int x = -6; x <= 20; x++) {
					for (int z = -12; z <= 6; z++) {
						level.setBlock(room.offset(x, -1, z), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
						for (int y = 0; y <= 6; y++) {
							level.setBlock(room.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
						}
					}
				}
				for (int i = 0; i < PIECES.size(); i++) {
					level.setBlock(room.offset(i * 2, 0, 0), PIECES.get(i).defaultBlockState().setValue(SearchBlock.FACING, Direction.NORTH), Block.UPDATE_CLIENTS);
				}
			});
			this.view(server, room, 6.5, 1.0, -6.0, 0f, 12f);
			ctx.waitTicks(30);
			ctx.takeScreenshot("f01_furniture_closed");
			server.runOnServer(s -> {
				for (int i = 0; i < PIECES.size(); i++) {
					BlockPos p = room.offset(i * 2, 0, 0);
					s.overworld().setBlock(p, s.overworld().getBlockState(p).setValue(SearchBlock.OPEN, true), Block.UPDATE_CLIENTS);
				}
			});
			ctx.waitTicks(10);
			ctx.takeScreenshot("f02_furniture_open");
			this.view(server, room, -3.5, 2.0, -4.5, -50f, 25f);
			ctx.waitTicks(10);
			ctx.takeScreenshot("f03_furniture_open_side");
			this.view(server, room, 1.0, 1.5, -2.2, 0f, 35f);
			ctx.waitTicks(10);
			ctx.takeScreenshot("f04_furniture_open_close");
			this.view(server, room, 7.0, 3.2, -2.0, 0f, 55f);
			ctx.waitTicks(10);
			ctx.takeScreenshot("f04b_furniture_open_above");

			// In a match: the arena's furniture is searchable; searching a dresser opens it for good.
			server.runCommand("hw point clear npc");
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = 2;
				HwConfig.get().botsFillTo = 0;
			});
			server.runCommand("hw start");
			ctx.waitTicks(60);
			check(server.computeOnServer(s -> Match.running()), "the match runs");
			int furniture = server.computeOnServer(s -> (int) Loot.containers().stream().filter(p -> SearchBlock.is(s.overworld().getBlockState(p))).count());
			check(furniture >= 21, "the arena's dressers, crates and boxes are containers (" + furniture + ")");
			check(server.computeOnServer(s -> Loot.containers().stream().noneMatch(p -> p.closerThan(room, 20))), "the showroom outside the map isn't");
			BlockPos dresser = origin.offset(1, 0, 15);
			check(server.computeOnServer(s -> s.overworld().getBlockState(dresser).is(HwBlocks.DRESSER) && Loot.isContainer(dresser)), "a dresser stands where the chest was");
			server.runOnServer(s -> Loot.hide(dresser, HwItems.Kind.BOLT_CUTTERS));
			server.runCommand(String.format(Locale.ROOT, "tp @p %.2f %d %.2f", origin.getX() + 1.5, origin.getY(), origin.getZ() + 13.4));
			this.lookAt(ctx, Vec3.atCenterOf(dresser));
			ctx.getInput().holdKey(o -> o.keyUse);
			ctx.waitTicks(HwConfig.get().searchTicks + 12);
			ctx.getInput().releaseKey(o -> o.keyUse);
			ctx.waitTicks(4);
			check(server.computeOnServer(s -> Inventory.has(player(s), HwItems.Kind.BOLT_CUTTERS)), "searching the dresser finds the bolt cutters");
			check(server.computeOnServer(s -> s.overworld().getBlockState(dresser).getValue(SearchBlock.OPEN)), "and its drawer stays pulled out");
			ctx.takeScreenshot("f05_searched_dresser");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runCommand("hw start");
			ctx.waitTicks(60);
			check(server.computeOnServer(s -> !s.overworld().getBlockState(dresser).getValue(SearchBlock.OPEN)), "a new match closes it again");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds;
				HwConfig.get().botsFillTo = new HwConfig().botsFillTo;
			});
		}
		LOG.info("Furniture client test finished OK");
	}

	private void view(TestServerContext server, BlockPos room, double dx, double dy, double dz, float yaw, float pitch) {
		server.runCommand(String.format(Locale.ROOT, "tp @p %.2f %.2f %.2f %.1f %.1f", room.getX() + dx, room.getY() + dy, room.getZ() + dz, yaw, pitch));
	}

	private void lookAt(ClientGameTestContext ctx, Vec3 target) {
		for (int i = 0; i < 10; i++) {
			ctx.waitTicks(2);
			double error = ctx.computeOnClient(mc -> {
				Vec3 want = target.subtract(CameraAim.origin()).normalize();
				return Math.toDegrees(Math.acos(Mth.clamp(want.dot(CameraAim.direction()), -1, 1)));
			});
			if (error < 0.5) {
				break;
			}
			float[] rot = ctx.computeOnClient(mc -> {
				Vec3 d = target.subtract(CameraAim.origin());
				return new float[]{(float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f, (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG)};
			});
			ctx.getInput().lookAt(rot[0], rot[1]);
		}
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().get(0);
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
