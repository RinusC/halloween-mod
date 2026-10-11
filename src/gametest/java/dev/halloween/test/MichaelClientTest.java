package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import dev.halloween.Role;
import dev.halloween.game.AiStats;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Exits;
import dev.halloween.game.Lockers;
import dev.halloween.game.MapPoints;
import dev.halloween.game.Match;
import dev.halloween.game.MatchManager;
import dev.halloween.game.Nav;
import dev.halloween.game.ShapeBrain;
import dev.halloween.game.Townsfolk;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Michael played by the server (/hw shape bot): he goes about on his own, goes to a scream, cuts a phone, hears
 * breathing in a wardrobe and pulls them out, chases and grabs someone he sees and wins the struggle, stalks
 * someone far off with his Sense, kills townsfolk.
 */
public class MichaelClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-michael");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("michael")) {
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
				cfg.botsFillTo = 0;
				cfg.eventsEnabled = false;
				cfg.npcMax = 0;
				AiStats.reset();
			});
			dev.halloween.game.Weather.forced = dev.halloween.game.Weather.CLEAR;
			server.runCommand("hw shape bot");

			// ---------------------------------------------------------------- he plays, and goes about
			this.start(ctx, server);
			check(server.computeOnServer(s -> ShapeBrain.body() != null && MatchManager.role(ShapeBrain.body()) == Role.SHAPE
				&& ShapeBrain.body().getScoreboardName().equals(ShapeBrain.NAME)), "a bot plays Michael");
			check(server.computeOnServer(s -> MatchManager.role(player(s)) == Role.CIVILIAN), "the player is a survivor");
			this.stage(server);
			Vec3 from = server.computeOnServer(s -> ShapeBrain.body().position());
			ctx.waitTicks(300);
			double moved = server.computeOnServer(s -> ShapeBrain.body().position().distanceTo(from));
			LOG.info("moved {} blocks, now {}", moved, server.computeOnServer(s -> ShapeBrain.mode()));
			check(moved > 4, "he goes about on his own");

			// ---------------------------------------------------------------- a scream: he goes there
			BlockPos scream = server.computeOnServer(s -> at(s, 18, 1, -5));
			server.runOnServer(s -> {
				ShapeBrain.forget();
				ServerPlayer m = ShapeBrain.body();
				BlockPos lair = at(s, 30, 0, -30);
				m.teleportTo(s.overworld(), lair.getX() + 0.5, lair.getY(), lair.getZ() + 0.5, java.util.Set.of(), 180f, 0f, false);
			});
			ctx.waitTicks(5);
			double before = server.computeOnServer(s -> ShapeBrain.body().position().distanceTo(Vec3.atCenterOf(scream)));
			server.runOnServer(s -> s.overworld().playSound(null, scream, HwSounds.VOICE_MALE_SCREAM, SoundSource.PLAYERS, 3f, 1f));
			ctx.waitTicks(10);
			check(stat("lead.voice") > 0, "he hears the scream");
			check(server.computeOnServer(s -> "listen".equals(ShapeBrain.mode())), "and goes to see");
			ctx.waitTicks(200);
			double after = server.computeOnServer(s -> ShapeBrain.body().position().distanceTo(Vec3.atCenterOf(scream)));
			LOG.info("scream: {} -> {}", before, after);
			check(after < before - 8, "he gets there");

			// ---------------------------------------------------------------- a phone by him: he cuts it
			BlockPos phone = server.computeOnServer(s -> {
				ShapeBrain.forget();
				ServerLevel level = s.overworld();
				BlockPos p = MapPoints.get(level).list(MapPoints.Type.PHONE).getFirst();
				BlockPos stand = standNear(level, p);
				ShapeBrain.body().teleportTo(level, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
				return p;
			});
			check(server.computeOnServer(s -> Exits.phoneWorks(phone) && Exits.policePoint() != null), "a working phone, and police to call");
			this.until(ctx, server, 300, s -> !Exits.phoneWorks(phone));
			check(server.computeOnServer(s -> !Exits.phoneWorks(phone)), "he cuts the phone");

			// ---------------------------------------------------------------- breathing in a wardrobe
			this.start(ctx, server);
			BlockPos locker = server.computeOnServer(s -> Lockers.nearestFree(s.overworld(), at(s, 2, 0, 15), 60));
			check(server.computeOnServer(s -> {
				BlockPos front = Lockers.front(s.overworld(), locker);
				player(s).teleportTo(s.overworld(), front.getX() + 0.5, front.getY(), front.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
				return Lockers.enter(player(s), locker, false);
			}), "the player hides in a wardrobe");
			server.runOnServer(s -> {
				ShapeBrain.forget();
				BlockPos front = Lockers.front(s.overworld(), locker);
				ShapeBrain.body().teleportTo(s.overworld(), front.getX() + 0.5, front.getY(), front.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
			});
			this.until(ctx, server, 120, s -> !Lockers.isInside(player(s)));
			check(stat("heard.breathing") > 0, "he hears breathing in it");
			check(stat("locker.found") > 0 && server.computeOnServer(s -> !Lockers.isInside(player(s))), "and pulls them out");
			this.until(ctx, server, 400, s -> dead(s));
			check(server.computeOnServer(MichaelClientTest::dead), "he wins the struggle");

			// ---------------------------------------------------------------- seen: chased, grabbed, killed
			this.start(ctx, server);
			server.runOnServer(s -> {
				ShapeBrain.forget();
				BlockPos c = at(s, 0, 0, 0);
				player(s).teleportTo(s.overworld(), c.getX() + 0.5, c.getY(), c.getZ() + 0.5, java.util.Set.of(), 180f, 0f, false);
				ShapeBrain.body().teleportTo(s.overworld(), c.getX() + 0.5, c.getY(), c.getZ() - 5.5, java.util.Set.of(), 0f, 0f, false);
			});
			int grabs = stat("grab");
			this.until(ctx, server, 60, s -> ShapeBrain.body().distanceTo(player(s)) < 3.2);
			ctx.takeScreenshot("michael_close");
			this.until(ctx, server, 200, s -> stat("grab") > grabs);
			LOG.info("chase: {}", (Object) server.computeOnServer(s -> ShapeBrain.debug()));
			check(stat("grab") > grabs, "he sees them, comes and grabs them");
			ctx.waitTicks(4);
			ctx.takeScreenshot("michael_grab");
			this.until(ctx, server, 400, s -> dead(s));
			check(server.computeOnServer(MichaelClientTest::dead), "and kills them");

			// ---------------------------------------------------------------- far off, unaware: stalked
			this.start(ctx, server);
			server.runOnServer(s -> {
				ShapeBrain.forget();
				BlockPos c = at(s, 0, 0, 0);
				// The player looks away (south); he is behind, to the north, looking at them.
				player(s).teleportTo(s.overworld(), c.getX() + 0.5, c.getY(), c.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
				ShapeBrain.body().teleportTo(s.overworld(), c.getX() + 0.5, c.getY(), c.getZ() - 17.5, java.util.Set.of(), 0f, 0f, false);
			});
			this.until(ctx, server, 300, s -> MatchManager.stalkOf(player(s)) > 0.3f);
			LOG.info("stalk {} ({} ticks stalking)", server.computeOnServer(s -> MatchManager.stalkOf(player(s))), stat("stalk"));
			check(server.computeOnServer(s -> MatchManager.stalkOf(player(s)) > 0.3f), "he stalks someone far off who hasn't seen him");

			// ---------------------------------------------------------------- nobody else: the townsfolk
			server.runOnServer(s -> HwConfig.get().npcMax = 8);
			this.start(ctx, server);
			this.stage(server);
			int people = server.computeOnServer(s -> Townsfolk.bodies().size());
			server.runOnServer(s -> {
				ShapeBrain.forget();
				BlockPos near = at(s, 0, 0, 4);
				ShapeBrain.body().teleportTo(s.overworld(), near.getX() + 0.5, near.getY(), near.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
			});
			this.until(ctx, server, 1200, s -> Townsfolk.bodies().size() < people);
			LOG.info("townsfolk {} -> {}", people, server.computeOnServer(s -> Townsfolk.bodies().size()));
			check(stat("spotted.npc") > 0, "he spots townsfolk");
			check(server.computeOnServer(s -> Townsfolk.bodies().size() < people), "and kills one");

			LOG.info("michael stats: {}", AiStats.snapshot().get("~michael"));
			server.runCommand("hw stop");
			server.runCommand("hw shape off");
			ctx.waitTicks(5);
			dev.halloween.game.Weather.forced = -1;
		}
		LOG.info("Michael client test finished OK");
	}

	private void start(ClientGameTestContext ctx, TestServerContext server) {
		server.runCommand("hw start");
		ctx.waitTicks(80);
		server.runCommand("time set midnight");
		check(server.computeOnServer(s -> Match.running() && ShapeBrain.body() != null), "a match with him");
	}

	/** The player up on a stage high in the air: out of his sight and hearing. */
	private void stage(TestServerContext server) {
		server.runOnServer(s -> {
			ServerPlayer p = player(s);
			BlockPos c = at(s, 0, 0, 0);
			s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(),
				"fill " + (c.getX() - 3) + " 200 " + (c.getZ() - 3) + " " + (c.getX() + 3) + " 200 " + (c.getZ() + 3) + " minecraft:smooth_stone");
			p.teleportTo(s.overworld(), c.getX() + 0.5, 201, c.getZ() + 0.5, java.util.Set.of(), 0f, 30f, false);
		});
	}

	private void until(ClientGameTestContext ctx, TestServerContext server, int ticks, Function<MinecraftServer, Boolean> done) {
		for (int t = 0; t < ticks; t += 5) {
			if (server.computeOnServer(done::apply)) {
				return;
			}
			ctx.waitTicks(5);
		}
	}

	private static boolean dead(MinecraftServer s) {
		ServerPlayer p = player(s);
		return p.isSpectator() || p.isDeadOrDying() || Match.state() == Match.State.ENDED || MatchManager.role(p) != Role.CIVILIAN;
	}

	private static int stat(String event) {
		return AiStats.snapshot().getOrDefault("~michael", Map.of()).getOrDefault(event, 0);
	}

	private static BlockPos at(MinecraftServer s, int x, int y, int z) {
		return MapPoints.get(s.overworld()).first(MapPoints.Type.CENTER).offset(x, y, z);
	}

	/** A spot to stand at by this block (the nearest one with room). */
	private static BlockPos standNear(ServerLevel level, BlockPos p) {
		BlockPos best = null;
		for (BlockPos q : BlockPos.betweenClosed(p.offset(-3, -2, -3), p.offset(3, 1, 3))) {
			if (Nav.standable(level, q) && (best == null || q.distSqr(p) < best.distSqr(p))) {
				best = q.immutable();
			}
		}
		return best == null ? p : best;
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
