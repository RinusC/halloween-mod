package dev.halloween.test;

import dev.halloween.game.MapJob;
import dev.halloween.game.MapPoints;
import dev.halloween.game.MapSaveJob;
import dev.halloween.game.SavedMap;
import java.io.IOException;
import java.nio.file.Files;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /hw map save and /hw map load: a hand-made change (a stone block, a chest with a diamond in it) is read into a
 * file, wiped from the world, and comes back from the file with the map points as they were.
 */
public class MapSaveClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-map-save");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("mapsave")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			BlockPos[] spot = server.computeOnServer(s -> {
				BlockPos center = MapPoints.get(s.overworld()).first(MapPoints.Type.CENTER);
				BlockPos stone = center.offset(3, 5, 4);
				BlockPos chest = center.offset(5, 5, 4);
				s.overworld().setBlock(stone, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
				s.overworld().setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
				if (s.overworld().getBlockEntity(chest) instanceof ChestBlockEntity c) {
					c.setItem(2, new ItemStack(Items.DIAMOND, 7));
				}
				return new BlockPos[]{center, stone, chest};
			});
			int pointsBefore = server.computeOnServer(s -> MapPoints.get(s.overworld()).all().values().stream().mapToInt(java.util.List::size).sum());
			check(pointsBefore > 0, "the arena has map points to save");

			server.runCommand("hw map save maptest");
			server.runOnServer(s -> MapSaveJob.finishNow());
			check(Files.isRegularFile(SavedMap.file("maptest")), "the map file was written");

			// Wipe what was built by hand and the points, then bring it back from the file.
			BlockPos center = spot[0];
			server.runOnServer(s -> {
				cmd(s, "fill " + (center.getX() + 2) + " " + (center.getY() + 5) + " " + (center.getZ() + 3) + " "
					+ (center.getX() + 6) + " " + (center.getY() + 5) + " " + (center.getZ() + 5) + " minecraft:air");
				MapPoints.get(s.overworld()).clearAll();
			});
			check(server.computeOnServer(s -> s.overworld().getBlockState(spot[1]).isAir()), "the block is gone before loading");

			server.runCommand("execute positioned " + center.getX() + " " + center.getY() + " " + center.getZ() + " run hw map load maptest");
			server.runOnServer(s -> MapJob.finishNow(s));

			check(server.computeOnServer(s -> s.overworld().getBlockState(spot[1]).is(Blocks.GOLD_BLOCK)), "the hand-placed block came back");
			boolean diamonds = server.computeOnServer(s -> {
				BlockEntity be = s.overworld().getBlockEntity(spot[2]);
				return be instanceof ChestBlockEntity c && c.getItem(2).is(Items.DIAMOND) && c.getItem(2).getCount() == 7;
			});
			check(diamonds, "the chest came back with its diamonds");
			int pointsAfter = server.computeOnServer(s -> MapPoints.get(s.overworld()).all().values().stream().mapToInt(java.util.List::size).sum());
			check(pointsAfter == pointsBefore, "the map points came back (" + pointsAfter + " of " + pointsBefore + ")");
			try {
				Files.deleteIfExists(SavedMap.file("maptest"));
			} catch (IOException e) {
				LOG.warn("could not remove the test file", e);
			}
		}
	}

	private static void cmd(MinecraftServer s, String command) {
		s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), command);
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
