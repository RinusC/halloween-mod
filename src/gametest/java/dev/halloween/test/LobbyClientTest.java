package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientDarkness;
import dev.halloween.client.ClientState;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.HwItems;
import dev.halloween.game.Lobby;
import dev.halloween.game.ShootingRange;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The lobby (the school): built, players sent into the yard, a stand explains, the Ready button; the parkour (sound
 * by its own checks, a checkpoint taken, a fall back onto it, a finished run kept); the range (a gun off the wall, a
 * round, a target shot down for points, the gun left behind at the door); out to a match and back. Screenshots of
 * the yard, the hallway, the gym, the course up high and the range.
 */
public class LobbyClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-lobby");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("lobby")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runCommand("hw lobby");
			ctx.waitTicks(60);
			check(server.computeOnServer(s -> Lobby.exists(s) && Lobby.inside(player(s))), "the school is built and we're in its yard");
			ctx.runOnClient(mc -> ClientDarkness.enabled = false);
			ctx.waitTicks(20);
			check(ctx.computeOnClient(mc -> ClientState.lobby.inLobby() && !ClientState.lobby.status().getString().isEmpty()), "the lobby line shows");
			ctx.takeScreenshot("lobby_yard");

			// The course is sound: every jump within reach, nothing over a pad or in the way.
			List<String> problems = server.computeOnServer(s -> Lobby.courseProblems());
			for (String p : problems) {
				LOG.info("course: {}", p);
			}
			if (!problems.isEmpty()) {
				List<BlockPos> pads = server.computeOnServer(s -> Lobby.path(BlockPos.ZERO));
				for (int i = 0; i < pads.size(); i++) {
					LOG.info("course: pad {} {}", i, pads.get(i).toShortString());
				}
			}
			LOG.info("course: {} jumps, {} checkpoints, top at {}", Lobby.jumps(), server.computeOnServer(s -> Lobby.checkpoints(Lobby.origin(s)).size()),
				Lobby.courseTop());
			check(problems.isEmpty(), "the parkour course checks out");
			check(Lobby.jumps() >= 55, "the course is long (55 jumps at least)");

			// The hallway, a stand.
			server.runOnServer(s -> {
				BlockPos at = Lobby.station(Lobby.origin(s), "goal");
				player(s).teleportTo(s.overworld(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, java.util.Set.of(), 180f, 10f, false);
			});
			ctx.waitTicks(20);
			check(ctx.computeOnClient(mc -> ClientState.lobby.panel().size() >= 3), "a stand tells what it's about");
			server.runOnServer(s -> {
				BlockPos o = Lobby.origin(s);
				player(s).teleportTo(s.overworld(), o.getX() + 8.5, o.getY(), o.getZ() + 27.5, java.util.Set.of(), -90f, 5f, false);
			});
			ctx.waitTicks(15);
			ctx.takeScreenshot("lobby_hallway");

			// Ready on your own: noted, but a match needs two.
			server.runOnServer(s -> Lobby.onUse(player(s), Lobby.readyButton(Lobby.origin(s))));
			ctx.waitTicks(30);
			check(server.computeOnServer(s -> Lobby.ready(player(s)) && !dev.halloween.game.Match.frozen() && !dev.halloween.game.Match.running()),
				"ready alone: noted, no match");

			// The gym from its corner.
			server.runOnServer(s -> {
				BlockPos o = Lobby.origin(s);
				player(s).teleportTo(s.overworld(), o.getX() + 10.5, o.getY(), o.getZ() + 36.5, java.util.Set.of(), -40f, -12f, false);
			});
			ctx.waitTicks(15);
			ctx.takeScreenshot("lobby_gym");

			// Parkour: start, the first checkpoint, a fall back onto it, the rest, the finish.
			List<BlockPos> path = server.computeOnServer(s -> Lobby.path(Lobby.origin(s)));
			List<BlockPos> cps = server.computeOnServer(s -> Lobby.checkpoints(Lobby.origin(s)));
			this.stand(server, path.getFirst());
			ctx.waitTicks(10);
			this.hop(ctx, server, path.get(1));
			this.stand(server, cps.getFirst());
			ctx.waitTicks(10);
			check(ctx.computeOnClient(mc -> ClientState.lobby.status().getString().contains("1/")), "the first checkpoint is taken");
			// Down to the floor: back up on it.
			server.runOnServer(s -> {
				BlockPos o = Lobby.origin(s);
				player(s).teleportTo(s.overworld(), o.getX() + 20.5, o.getY(), o.getZ() + 50.5, java.util.Set.of(), 0f, 0f, false);
			});
			ctx.waitTicks(10);
			check(server.computeOnServer(s -> player(s).blockPosition().distManhattan(cps.getFirst().above()) <= 2), "a fall puts you back on the checkpoint");
			for (BlockPos cp : cps.subList(1, cps.size())) {
				this.stand(server, cp);
				ctx.waitTicks(6);
			}
			// Up high, looking back over the gym.
			ctx.takeScreenshot("lobby_parkour_high");
			this.stand(server, path.getLast());
			ctx.waitTicks(10);
			check(server.computeOnServer(s -> Lobby.record(s, player(s).getScoreboardName()) != null), "a parkour run is timed and kept");

			// The range: a gun off the wall, a round, a target shot down.
			server.runOnServer(s -> {
				BlockPos b = Lobby.rangeGunButton(Lobby.origin(s), HwItems.Kind.REVOLVER);
				player(s).teleportTo(s.overworld(), b.getX() + 0.5, b.getY() - 1, b.getZ() + 2.5, java.util.Set.of(), 180f, 0f, false);
			});
			ctx.waitTicks(10);
			server.runOnServer(s -> Lobby.onUse(player(s), Lobby.rangeGunButton(Lobby.origin(s), HwItems.Kind.REVOLVER)));
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> HwItems.is(player(s).getMainHandItem(), HwItems.Kind.REVOLVER)), "a revolver off the wall");
			server.runOnServer(s -> Lobby.onUse(player(s), Lobby.rangeStartButton(Lobby.origin(s))));
			server.runOnServer(s -> {
				BlockPos o = Lobby.origin(s);
				player(s).teleportTo(s.overworld(), o.getX() + 75.5, o.getY(), o.getZ() + 35.5, java.util.Set.of(), 0f, 0f, false);
			});
			ctx.waitTicks(90);
			check(server.computeOnServer(s -> ShootingRange.score() == 0 && !ShootingRange.targets().isEmpty()), "a round is on, targets up");
			ctx.waitTicks(4);
			ctx.takeScreenshot("lobby_range");
			// Aim at a target's mask and fire.
			server.runOnServer(s -> {
				ServerPlayer p = player(s);
				BlockPos t = ShootingRange.targets().getFirst();
				Vec3 head = Vec3.atCenterOf(t.above(2));
				Vec3 d = head.subtract(p.getEyePosition()).normalize();
				float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
				float pitch = (float) Math.toDegrees(-Math.asin(d.y));
				p.setYRot(yaw);
				p.setXRot(pitch);
				p.connection.teleport(p.getX(), p.getY(), p.getZ(), yaw, pitch);
				HwConfig.get().revolverAimSpread = 0f;
				dev.halloween.game.Guns.setAiming(p, true);
				dev.halloween.game.Guns.fire(p, yaw, pitch);
			});
			ctx.waitTicks(3);
			int score = server.computeOnServer(s -> ShootingRange.score());
			LOG.info("range score after a shot: {}", score);
			check(score > 0, "a target shot down scores");
			ctx.takeScreenshot("lobby_range_aim");
			server.runOnServer(s -> HwConfig.get().revolverAimSpread = new HwConfig().revolverAimSpread);
			// Out of the door: the round is off, the gun stays behind.
			server.runOnServer(s -> {
				BlockPos o = Lobby.origin(s);
				player(s).teleportTo(s.overworld(), o.getX() + 76.5, o.getY(), o.getZ() + 27.5, java.util.Set.of(), 0f, 0f, false);
			});
			ctx.waitTicks(10);
			check(server.computeOnServer(s -> ShootingRange.score() < 0 && player(s).getMainHandItem().isEmpty()), "out of the range: no round, no gun");

			// A match takes us out, its end brings us back.
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = 1;
				HwConfig.get().botsFillTo = 0;
			});
			server.runCommand("hw start");
			ctx.waitTicks(40);
			check(server.computeOnServer(s -> !Lobby.inside(player(s))), "the match takes us to town");
			server.runCommand("hw stop");
			ctx.waitTicks(20);
			check(server.computeOnServer(s -> Lobby.inside(player(s))), "and we're back at school after");
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds;
				HwConfig.get().botsFillTo = new HwConfig().botsFillTo;
			});
			ctx.runOnClient(mc -> ClientDarkness.enabled = true);
		}
		LOG.info("Lobby client test finished OK");
	}

	/** On a pad (on its block, facing on along the course). */
	private void stand(TestServerContext server, BlockPos pad) {
		server.runOnServer(s -> player(s).teleportTo(s.overworld(), pad.getX() + 0.5, pad.getY() + 1, pad.getZ() + 0.5, java.util.Set.of(), 0f, 15f, false));
	}

	/** In the air over a pad (off the start: the clock runs), then down on it. */
	private void hop(ClientGameTestContext ctx, TestServerContext server, BlockPos pad) {
		server.runOnServer(s -> player(s).teleportTo(s.overworld(), pad.getX() + 0.5, pad.getY() + 2.5, pad.getZ() + 0.5, java.util.Set.of(), 0f, 15f, false));
		ctx.waitTicks(10);
	}

	private static ServerPlayer player(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!(p instanceof BotPlayer)) {
				return p;
			}
		}
		throw new IllegalStateException("no player");
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
