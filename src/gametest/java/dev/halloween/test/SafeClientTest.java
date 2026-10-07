package dev.halloween.test;

import dev.halloween.HwBlocks;
import dev.halloween.HwConfig;
import dev.halloween.game.GunCase;
import dev.halloween.game.SafeBlock;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Screenshots only: the gun safe shut and open, and in its spot in a match before and after the code. */
public class SafeClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-safe-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("safe")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			BlockPos origin = server.computeOnServer(s -> player(s).blockPosition());
			server.runCommand("time set noon");
			BlockPos room = origin.offset(130, 0, 0);
			server.runOnServer(s -> {
				var level = s.overworld();
				for (int x = -6; x <= 10; x++) {
					for (int z = -8; z <= 4; z++) {
						level.setBlock(room.offset(x, -1, z), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
						for (int y = 0; y <= 5; y++) {
							level.setBlock(room.offset(x, y, z), z == 1 ? Blocks.QUARTZ_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
						}
					}
				}
				level.setBlock(room, HwBlocks.GUN_SAFE.defaultBlockState().setValue(SafeBlock.FACING, Direction.NORTH), Block.UPDATE_CLIENTS);
				level.setBlock(room.offset(3, 0, 0), HwBlocks.GUN_SAFE.defaultBlockState().setValue(SafeBlock.FACING, Direction.NORTH).setValue(SafeBlock.OPEN, true),
					Block.UPDATE_CLIENTS);
			});
			server.runCommand(String.format(Locale.ROOT, "tp @p %.2f %.2f %.2f %.1f %.1f", room.getX() + 0.0, room.getY() + 0.6, room.getZ() - 3.2, -35f, 18f));
			ctx.waitTicks(30);
			ctx.takeScreenshot("s01_safe_shut_and_open");

			// In a match: the safe in one of the gun case spots, then opened with the code.
			server.runCommand("hw point clear npc");
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = 2;
				HwConfig.get().botsFillTo = 0;
			});
			server.runCommand("hw start");
			ctx.waitTicks(60);
			BlockPos safe = server.computeOnServer(s -> GunCase.closed().getFirst());
			Direction facing = server.computeOnServer(s -> s.overworld().getBlockState(safe).getValue(SafeBlock.FACING));
			LOG.info("Safe at {} facing {}", safe, facing);
			BlockPos stand = safe.relative(facing, 2).relative(facing.getClockWise());
			double dx = safe.getX() - stand.getX();
			double dz = safe.getZ() - stand.getZ();
			float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			server.runCommand(String.format(Locale.ROOT, "tp @p %.2f %d %.2f %.1f %.1f", stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, yaw, 32f));
			ctx.waitTicks(20);
			ctx.takeScreenshot("s02_safe_in_match");
			server.runOnServer(s -> GunCase.tryCode(player(s), Integer.parseInt(GunCase.code())));
			ctx.waitTicks(10);
			ctx.takeScreenshot("s03_safe_opened");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds;
				HwConfig.get().botsFillTo = new HwConfig().botsFillTo;
			});
		}
		LOG.info("Safe client test finished OK");
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().get(0);
	}
}
