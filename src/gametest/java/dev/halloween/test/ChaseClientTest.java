package dev.halloween.test;

import dev.halloween.HwBlocks;
import dev.halloween.HwConfig;
import dev.halloween.client.ClientDarkness;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.MatchManager;
import dev.halloween.game.Obstacles;
import dev.halloween.game.PropBlock;
import dev.halloween.game.Senses;
import dev.halloween.game.ShapeBrain;
import dev.halloween.game.ShapeState;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What gets pulled down in a chase (game.Obstacles): the town's bookshelves and bin made bookcases and cans, a bookcase
 * and a can pulled down (mid-fall and lying, the lid and the spill), a wall to Michael and a step to the survivor,
 * kicked apart by him, coming down on him, and all put back.
 */
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
			ShapeBrain.paused = true;
			Obstacles.testAllowed = true;
			server.runCommand("hw start");
			ctx.waitTicks(80);
			// The player runs; a bot plays Michael (held still: ShapeBrain.paused).
			String michael = server.computeOnServer(s -> s.getPlayerList().getPlayers().stream().filter(p -> p instanceof BotPlayer).findFirst().orElseThrow().getScoreboardName());
			server.runCommand("hw role @p civilian");
			server.runCommand("hw role " + michael + " shape");
			ctx.waitTicks(10);
			server.runCommand("time set noon");
			ctx.runOnClient(mc -> {
				ClientDarkness.enabled = false;
				mc.options.setCameraType(CameraType.FIRST_PERSON);
			});
			// A room's corner up in the air: a wall to the south, two bookshelves two high against it, a cauldron bin.
			int[] at = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				int x = (int) Math.floor(p.getX());
				int z = (int) Math.floor(p.getZ());
				ServerLevel level = s.overworld();
				cmd(s, "fill " + (x - 8) + " 150 " + (z - 8) + " " + (x + 8) + " 150 " + (z + 8) + " minecraft:oak_planks");
				cmd(s, "fill " + (x - 8) + " 151 " + (z - 8) + " " + (x + 8) + " 155 " + (z + 8) + " minecraft:air");
				cmd(s, "fill " + (x - 7) + " 151 " + (z + 6) + " " + (x + 7) + " 153 " + (z + 6) + " minecraft:stripped_spruce_wood");
				for (int dx : new int[]{-3, 0}) {
					level.setBlock(new BlockPos(x + dx, 151, z + 5), Blocks.BOOKSHELF.defaultBlockState(), 3);
					level.setBlock(new BlockPos(x + dx, 152, z + 5), Blocks.BOOKSHELF.defaultBlockState(), 3);
				}
				level.setBlock(new BlockPos(x + 3, 151, z + 4), Blocks.CAULDRON.defaultBlockState(), 3);
				Obstacles.setup(level, new BlockPos(x, 151, z), 16);
				look(p, new Vec3(x + 0.5, 152.0, z + 1.2), new Vec3(x + 0.5, 151.8, z + 5));
				// Michael out of the way for now.
				ServerPlayer shape = Senses.shape(level);
				shape.teleportTo(level, x - 6.5, 151, z - 6.5, java.util.Set.of(), 0f, 0f, false);
				return new int[]{x, z};
			});
			int x = at[0];
			int z = at[1];
			boolean made = server.computeOnServer(s -> {
				ServerLevel level = s.overworld();
				BlockState a = level.getBlockState(new BlockPos(x - 3, 151, z + 5));
				BlockState b = level.getBlockState(new BlockPos(x + 3, 151, z + 4));
				return PropBlock.is(a) && a.getValue(PropBlock.FACING) == Direction.NORTH && PropBlock.is(level.getBlockState(new BlockPos(x - 3, 152, z + 5)))
					&& PropBlock.is(b) && b.getValue(PropBlock.KIND) == PropBlock.Kind.BIN;
			});
			if (!made) {
				throw new AssertionError("The bookshelves and the cauldron did not become a bookcase and a can");
			}
			ctx.waitTicks(10);
			ctx.takeScreenshot("chase_props");
			server.runOnServer(s -> look(player(s), new Vec3(x - 1.2, 152.2, z + 3.6), new Vec3(x - 2.5, 151.7, z + 5.4)));
			ctx.waitTicks(5);
			ctx.takeScreenshot("chase_props_close");

			// The bookcase pulled down by someone running past it, watched from the side.
			knock(server, x - 1.4, z + 3.5, new BlockPos(x - 3, 151, z + 5), new Vec3(0.3, 0, 0));
			server.runOnServer(s -> look(player(s), new Vec3(x + 1.6, 152.0, z + 2.6), new Vec3(x - 2.5, 151.6, z + 3.8)));
			ctx.waitTicks(4);
			ctx.takeScreenshot("chase_case_falling");
			ctx.waitTicks(3);
			ctx.takeScreenshot("chase_case_falling_2");
			ctx.waitTicks(18);
			ctx.takeScreenshot("chase_case_down");
			boolean lying = server.computeOnServer(s -> Obstacles.isCell(s.overworld(), new BlockPos(x - 3, 151, z + 4))
				&& Obstacles.isCell(s.overworld(), new BlockPos(x - 3, 151, z + 3)) && s.overworld().getBlockState(new BlockPos(x - 3, 151, z + 5)).isAir());
			if (!lying) {
				throw new AssertionError("The bookcase is not lying across the two blocks in front of it");
			}

			// The can: towards the one who pulled it, the lid off, the rubbish out.
			knock(server, x + 4.6, z + 3.0, new BlockPos(x + 3, 151, z + 4), new Vec3(-0.3, 0, 0));
			server.runOnServer(s -> look(player(s), new Vec3(x + 4.5, 152.4, z + 0.6), new Vec3(x + 4.0, 151.2, z + 4.2)));
			ctx.waitTicks(4);
			ctx.takeScreenshot("chase_bin_falling");
			ctx.waitTicks(20);
			ctx.takeScreenshot("chase_bin_down");
			server.runOnServer(s -> look(player(s), new Vec3(x + 0.5, 155.2, z - 0.5), new Vec3(x + 0.5, 151, z + 3.6)));
			ctx.waitTicks(5);
			ctx.takeScreenshot("chase_down_above");

			// A wall to Michael, a step to the survivor.
			String walls = server.computeOnServer(s -> {
				ServerLevel level = s.overworld();
				BlockPos cell = new BlockPos(x - 3, 151, z + 4);
				BlockState state = level.getBlockState(cell);
				double him = state.getCollisionShape(level, cell, CollisionContext.of(Senses.shape(level))).max(Direction.Axis.Y);
				double them = state.getCollisionShape(level, cell, CollisionContext.of(player(s))).max(Direction.Axis.Y);
				return him > 1.2 && them < 0.6 ? null : "him " + him + ", them " + them;
			});
			if (walls != null) {
				throw new AssertionError("The bookcase lying down is not a wall to him and a step to them: " + walls);
			}

			// Kicked apart: Michael beside it, facing it, strikes.
			server.runOnServer(s -> {
				ServerLevel level = s.overworld();
				ServerPlayer shape = Senses.shape(level);
				shape.teleportTo(level, x - 1.3, 151, z + 3.6, java.util.Set.of(), 90f, 30f, false);
				look(player(s), new Vec3(x - 1.0, 152.3, z + 0.3), new Vec3(x - 2.0, 151.6, z + 3.8));
			});
			ctx.waitTicks(5);
			server.runOnServer(s -> {
				ServerPlayer shape = Senses.shape(s.overworld());
				Vec3 eye = shape.getEyePosition();
				MatchManager.onAim(shape, eye, Vec3.atCenterOf(new BlockPos(x - 3, 151, z + 4)).subtract(0, 0.3, 0).subtract(eye).normalize());
				MatchManager.onShapeAttack(shape, -1, true);
			});
			ctx.waitTicks(7);
			ctx.takeScreenshot("chase_kick");
			ctx.waitTicks(5);
			ctx.takeScreenshot("chase_kick_hit");
			ctx.waitTicks(20);
			ctx.takeScreenshot("chase_kicked");
			boolean gone = server.computeOnServer(s -> !Obstacles.isCell(s.overworld(), new BlockPos(x - 3, 151, z + 4))
				&& !Obstacles.isCell(s.overworld(), new BlockPos(x - 3, 151, z + 3)));
			if (!gone) {
				throw new AssertionError("Michael's kick did not clear the bookcase away");
			}

			// Coming down on him: stunned, thrown out from under it.
			server.runOnServer(s -> {
				ServerLevel level = s.overworld();
				Senses.shape(level).teleportTo(level, x + 0.5, 151, z + 3.5, java.util.Set.of(), 180f, 0f, false);
			});
			ctx.waitTicks(30);
			knock(server, x + 1.7, z + 3.4, new BlockPos(x, 151, z + 5), new Vec3(0.3, 0, 0));
			server.runOnServer(s -> look(player(s), new Vec3(x + 3.2, 152.1, z + 1.8), new Vec3(x + 0.3, 151.6, z + 3.8)));
			ctx.waitTicks(14);
			ctx.takeScreenshot("chase_on_him");
			String stunned = server.computeOnServer(s -> {
				ServerPlayer shape = Senses.shape(s.overworld());
				ShapeState st = MatchManager.shapeState(shape);
				boolean out = shape.getX() < x - 0.05 || shape.getX() > x + 1.05;
				return st != null && st.recoverTicks > 0 && out ? null : "recover " + (st == null ? -1 : st.recoverTicks) + " at " + shape.position();
			});
			if (stunned != null) {
				throw new AssertionError("Coming down on him did not stun him and throw him clear: " + stunned);
			}

			// All put back.
			boolean back = server.computeOnServer(s -> {
				Obstacles.clear(s);
				ServerLevel level = s.overworld();
				return PropBlock.is(level.getBlockState(new BlockPos(x - 3, 151, z + 5))) && PropBlock.is(level.getBlockState(new BlockPos(x - 3, 152, z + 5)))
					&& PropBlock.is(level.getBlockState(new BlockPos(x + 3, 151, z + 4))) && PropBlock.is(level.getBlockState(new BlockPos(x, 151, z + 5)))
					&& !level.getBlockState(new BlockPos(x - 3, 151, z + 4)).is(HwBlocks.DEBRIS) && !level.getBlockState(new BlockPos(x, 151, z + 4)).is(HwBlocks.DEBRIS);
			});
			if (!back) {
				throw new AssertionError("What was pulled down was not put back");
			}
			ctx.waitTicks(5);
			ctx.takeScreenshot("chase_put_back");
			server.runOnServer(s -> player(s).setNoGravity(false));
			server.runCommand("hw stop");
			ctx.waitTicks(5);
		} finally {
			ShapeBrain.paused = false;
			Obstacles.testAllowed = false;
			ctx.runOnClient(mc -> ClientDarkness.enabled = true);
			dev.halloween.game.Weather.forced = -1;
		}
		LOG.info("Chase client test finished OK");
	}

	/** The player running past {@code prop} at (px, pz) along {@code motion} pulls it down. */
	private static void knock(TestServerContext server, double px, double pz, BlockPos prop, Vec3 motion) {
		boolean ok = server.computeOnServer(s -> {
			ServerPlayer p = player(s);
			p.setNoGravity(false);
			p.teleportTo(s.overworld(), px, 151, pz, java.util.Set.of(), 0f, 0f, false);
			p.setSprinting(true);
			return Obstacles.request(p, prop, motion);
		});
		if (!ok) {
			throw new AssertionError("Could not pull down the one at " + prop);
		}
	}

	private static void look(ServerPlayer p, Vec3 from, Vec3 to) {
		Vec3 d = to.subtract(from);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
		// (Floating where the camera should be.)
		p.setNoGravity(true);
		p.teleportTo(p.level(), from.x, from.y - 1.62, from.z, java.util.Set.of(), yaw, pitch, false);
		p.setSprinting(false);
	}

	private static void cmd(MinecraftServer s, String c) {
		s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), c);
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
