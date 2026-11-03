package dev.halloween.test;

import dev.halloween.game.BotPlayer;
import dev.halloween.game.Bots;
import dev.halloween.game.Nav;
import dev.halloween.game.Walker;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bots climb ladders: a loft that only a ladder leads up to (a hole in its floor, the ladder on the wall under
 * it). The path up goes by the ladder, a bot walks it up onto the loft, and back down to the ground.
 */
public class LadderClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-ladder");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("ladder")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			// The ladder's foot: 8 east of the player, on the ground. The loft floor is 4 up, the hole over the ladder.
			BlockPos foot = server.computeOnServer(s -> {
				ServerLevel level = s.overworld();
				BlockPos p = s.getPlayerList().getPlayers().getFirst().blockPosition().east(8);
				for (BlockPos q : BlockPos.betweenClosed(p.offset(-2, 0, -3), p.offset(6, 8, 3))) {
					level.setBlock(q, Blocks.AIR.defaultBlockState(), 2);
				}
				for (int y = 0; y <= 6; y++) {
					level.setBlock(p.offset(-1, y, 0), Blocks.STONE.defaultBlockState(), 2);
				}
				for (BlockPos q : BlockPos.betweenClosed(p.offset(0, 4, -2), p.offset(5, 4, 2))) {
					level.setBlock(q, Blocks.STONE.defaultBlockState(), 2);
				}
				for (int y = 0; y <= 4; y++) {
					level.setBlock(p.above(y), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.EAST), 2);
				}
				return p.immutable();
			});
			BlockPos loft = foot.offset(3, 5, 0);
			BlockPos ground = foot.offset(3, 0, 0);
			BotPlayer bot = server.computeOnServer(s -> Bots.spawnLoose(s, "Climber", ground));
			ctx.waitTicks(20);

			List<Nav.Node> up = server.computeOnServer(s -> Nav.to(s.overworld(), bot.blockPosition(), loft, 0.5, Nav.Options.walker()));
			LOG.info("Path up: {}", up);
			check(!up.isEmpty() && up.getLast().pos().equals(loft), "a path up onto the loft");
			check(up.stream().anyMatch(n -> n.step() == Nav.Step.LADDER_UP), "it goes up the ladder");
			check(walk(ctx, server, bot, up), "the bot climbs up onto the loft (at " + bot.blockPosition().toShortString() + ")");

			List<Nav.Node> down = server.computeOnServer(s -> Nav.to(s.overworld(), bot.blockPosition(), ground, 0.5, Nav.Options.walker()));
			LOG.info("Path down: {}", down);
			check(down.stream().anyMatch(n -> n.step() == Nav.Step.LADDER_DOWN), "the way down is the ladder");
			check(walk(ctx, server, bot, down), "and climbs back down (at " + bot.blockPosition().toShortString() + ")");
			server.runOnServer(s -> s.getPlayerList().remove(bot));
		}
		LOG.info("Ladder client test finished OK");
	}

	/** Walks the bot along the path, a tick at a time; true when it gets to the end. */
	private static boolean walk(ClientGameTestContext ctx, TestServerContext server, BotPlayer bot, List<Nav.Node> path) {
		Walker walker = new Walker(bot);
		walker.follow(path);
		for (int i = 0; i < 400; i++) {
			Walker.Status status = server.computeOnServer(s -> walker.tick());
			if (status == Walker.Status.ARRIVED) {
				walker.halt();
				ctx.waitTicks(10);
				return server.computeOnServer(s -> bot.blockPosition().equals(path.getLast().pos()));
			}
			if (status == Walker.Status.STUCK) {
				LOG.info("Stuck at {}", bot.position());
				return false;
			}
			ctx.waitTick();
		}
		return false;
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
