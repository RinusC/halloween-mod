package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.game.LightSystem;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A breaker switched off and a door left open in one match are back to normal in the next. */
public class RestoreClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-restore-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("restore")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			BlockPos origin = server.computeOnServer(s -> s.getPlayerList().getPlayers().get(0).blockPosition());
			server.runCommand("hw point clear npc");
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = 2;
				HwConfig.get().botsFillTo = 0;
			});
			server.runCommand("hw start");
			ctx.waitTicks(60);
			BlockPos[] spots = server.computeOnServer(s -> {
				var level = s.overworld();
				BlockPos breaker = LightSystem.breakers(level, origin, 40).getFirst();
				BlockPos door = null;
				for (BlockPos p : BlockPos.betweenClosed(origin.offset(-40, -2, -40), origin.offset(40, 3, 40))) {
					var st = level.getBlockState(p);
					if (st.getBlock() instanceof DoorBlock && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
						door = p.immutable();
						break;
					}
				}
				LightSystem.flip(level, breaker, false);
				for (BlockPos d : new BlockPos[]{door, door.above()}) {
					level.setBlock(d, level.getBlockState(d).setValue(DoorBlock.OPEN, true), Block.UPDATE_CLIENTS);
				}
				return new BlockPos[]{breaker, door};
			});
			check(server.computeOnServer(s -> !LightSystem.isOn(s.overworld(), spots[0]) && s.overworld().getBlockState(spots[1]).getValue(DoorBlock.OPEN)),
				"in the first match a breaker is off and a door open");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runCommand("hw start");
			ctx.waitTicks(60);
			check(server.computeOnServer(s -> LightSystem.isOn(s.overworld(), spots[0])), "the next match starts with the breaker on");
			check(server.computeOnServer(s -> !s.overworld().getBlockState(spots[1]).getValue(DoorBlock.OPEN)
				&& !s.overworld().getBlockState(spots[1].above()).getValue(DoorBlock.OPEN)), "and the door shut");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds;
				HwConfig.get().botsFillTo = new HwConfig().botsFillTo;
			});
		}
		LOG.info("Restore client test finished OK");
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
