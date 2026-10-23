package dev.halloween.test;

import dev.halloween.HwBlocks;
import dev.halloween.HwConfig;
import dev.halloween.client.ClientDarkness;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.DebrisBlock;
import dev.halloween.game.Obstacles;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** What gets pulled down in a chase (game.Obstacles): the four kinds lying every way, and one pulled down for real and put back. */
public class ChaseClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-chase");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("chase")) {
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
			dev.halloween.game.Weather.forced = dev.halloween.game.Weather.CLEAR;
			server.runCommand("hw start");
			ctx.waitTicks(80);
			server.runCommand("time set noon");
			ctx.runOnClient(mc -> {
				ClientDarkness.enabled = false;
				mc.options.setCameraType(CameraType.FIRST_PERSON);
			});
			int[] at = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				int x = (int) Math.floor(p.getX());
				int z = (int) Math.floor(p.getZ());
				ServerLevel level = s.overworld();
				s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(),
					"fill " + (x - 8) + " 150 " + (z - 8) + " " + (x + 8) + " 150 " + (z + 8) + " minecraft:smooth_stone");
				s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(),
					"fill " + (x - 8) + " 151 " + (z - 8) + " " + (x + 8) + " 154 " + (z + 8) + " minecraft:air");
				// Each kind in a row, turned each way; what they were beside them.
				DebrisBlock.Kind[] kinds = DebrisBlock.Kind.values();
				Direction[] dirs = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
				for (int k = 0; k < kinds.length; k++) {
					for (int d = 0; d < dirs.length; d++) {
						level.setBlock(new BlockPos(x - 3 + d * 2, 151, z + 3 + k * 2), HwBlocks.DEBRIS.defaultBlockState()
							.setValue(DebrisBlock.KIND, kinds[k]).setValue(DebrisBlock.FACING, dirs[d]), 3);
					}
				}
				level.setBlock(new BlockPos(x + 5, 151, z + 3), Blocks.CAULDRON.defaultBlockState(), 3);
				level.setBlock(new BlockPos(x + 5, 151, z + 5), Blocks.BOOKSHELF.defaultBlockState(), 3);
				level.setBlock(new BlockPos(x + 5, 152, z + 5), Blocks.BOOKSHELF.defaultBlockState(), 3);
				level.setBlock(new BlockPos(x + 5, 151, z + 7), Blocks.BARREL.defaultBlockState(), 3);
				level.setBlock(new BlockPos(x + 5, 151, z + 9), Blocks.HAY_BLOCK.defaultBlockState(), 3);
				p.teleportTo(level, x + 0.5, 152.6, z + 0.5, java.util.Set.of(), 0f, 38f, false);
				return new int[]{x, z};
			});
			ctx.waitTicks(20);
			ctx.takeScreenshot("chase_debris");
			server.runOnServer(s -> player(s).teleportTo(s.overworld(), at[0] + 0.5, 152.4, at[1] + 4.5, java.util.Set.of(), 0f, 40f, false));
			ctx.waitTicks(10);
			ctx.takeScreenshot("chase_debris_near");
			// Pulled down for real: running past the bookcase and using it.
			boolean knocked = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				ServerLevel level = s.overworld();
				BlockPos shelf = new BlockPos(at[0] + 5, 151, at[1] + 5);
				p.teleportTo(level, at[0] + 6.5, 151, at[1] + 5.5, java.util.Set.of(), 90f, 0f, false);
				p.setSprinting(true);
				Obstacles.onUse(p, level, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(shelf), Direction.EAST, shelf, false));
				boolean gone = level.getBlockState(shelf).isAir() && level.getBlockState(shelf.above()).isAir();
				boolean lying = false;
				for (BlockPos q : BlockPos.betweenClosed(shelf.offset(-2, 0, -2), shelf.offset(3, 0, 2))) {
					lying |= level.getBlockState(q).is(HwBlocks.DEBRIS);
				}
				return gone && lying;
			});
			if (!knocked) {
				throw new AssertionError("The bookcase was not pulled down");
			}
			server.runOnServer(s -> player(s).teleportTo(s.overworld(), at[0] + 7.5, 153.2, at[1] + 5.5, java.util.Set.of(), 90f, 40f, false));
			ctx.waitTicks(15);
			ctx.takeScreenshot("chase_knocked");
			boolean back = server.computeOnServer(s -> {
				Obstacles.clear(s);
				BlockPos shelf = new BlockPos(at[0] + 5, 151, at[1] + 5);
				return s.overworld().getBlockState(shelf).is(Blocks.BOOKSHELF) && s.overworld().getBlockState(shelf.above()).is(Blocks.BOOKSHELF);
			});
			if (!back) {
				throw new AssertionError("The bookcase was not put back");
			}
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> ClientDarkness.enabled = true);
			dev.halloween.game.Weather.forced = -1;
		}
		LOG.info("Chase client test finished OK");
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
