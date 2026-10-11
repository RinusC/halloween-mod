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

			// ---------------------------------------------------------------- unaware: stalked to 2, then grabbed
			this.start(ctx, server);
			this.place(server, 0f, 6.5);
			float first = this.stalkAtGrab(ctx, server, 500);
			LOG.info("grabbed at stalk {} ({})", first, server.computeOnServer(s -> ShapeBrain.debug()));
			check(first >= 2f, "he stalks someone unaware to level 2 before he grabs them");
			ctx.takeScreenshot("michael_grab");
			// Knocked down in the struggle: they get away. Next time he stalks them to the full.
			server.runOnServer(s -> dev.halloween.game.Fight.stun(ShapeBrain.body(), 1f, null));
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> !MatchManager.isHeld(player(s)) && ShapeBrain.stalkGoal(player(s)) == 3), "they get away: level 3 for them from now on");
			ShapeBrain.forceReaction = "FOLLOW";
			server.runOnServer(s -> {
				BlockPos c = at(s, 0, 0, 0);
				player(s).teleportTo(s.overworld(), c.getX() + 0.5, c.getY(), c.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
			});
			float second = this.stalkAtGrab(ctx, server, 900);
			LOG.info("grabbed again at stalk {}", second);
			check(second >= 3f, "and he stalks them to level 3 before the next grab");
			this.until(ctx, server, 400, s -> dead(s));
			check(server.computeOnServer(MichaelClientTest::dead), "and kills them");

			// ---------------------------------------------------------------- noticed: he backs off out of sight...
			this.start(ctx, server);
			ShapeBrain.forceReaction = "RETREAT";
			this.place(server, 180f, 9.5);
			this.until(ctx, server, 200, s -> stat("retreat") > 0
				&& (ShapeBrain.body().distanceTo(player(s)) > 14 || MatchManager.shapeState(ShapeBrain.body()).shadowTicks > 0));
			LOG.info("retreat: {} d {}", server.computeOnServer(s -> ShapeBrain.debug()), server.computeOnServer(s -> ShapeBrain.body().distanceTo(player(s))));
			check(stat("noticed.retreat") > 0 && stat("retreat") > 0, "seen by them, he can back off");
			check(server.computeOnServer(s -> ShapeBrain.body().distanceTo(player(s)) > 14 || MatchManager.shapeState(ShapeBrain.body()).shadowTicks > 0),
				"out of their sight");
			// ...or goes for them there and then.
			ShapeBrain.forceReaction = "ATTACK";
			this.place(server, 180f, 6.5);
			float rushed = this.stalkAtGrab(ctx, server, 300);
			LOG.info("rushed at stalk {}", rushed);
			check(stat("noticed.attack") > 0 && rushed >= 0 && rushed < 2f, "or goes straight for them");
			this.until(ctx, server, 400, s -> dead(s));

			// ---------------------------------------------------------------- ...or follows, stalking them all the same
			this.start(ctx, server);
			ShapeBrain.forceReaction = "FOLLOW";
			this.place(server, 180f, 9.5);
			ctx.waitTicks(30);
			ctx.takeScreenshot("michael_stalking");
			float followed = this.stalkAtGrab(ctx, server, 600);
			LOG.info("followed: grabbed at stalk {}", followed);
			check(stat("noticed.follow") > 0 && followed >= 2f, "or keeps after them, stalking, and grabs them at level 2");
			ShapeBrain.forceReaction = null;
			this.until(ctx, server, 400, s -> dead(s));

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
			check(server.computeOnServer(s -> Townsfolk.bodies().size() < people), "and kills one (no stalking for them)");
			// Off the body, a look at it, and on.
			ctx.waitTicks(60);
			double off = server.computeOnServer(MichaelClientTest::fromBody);
			LOG.info("off the body: {} ({})", off, server.computeOnServer(s -> ShapeBrain.debug()));
			check(off > 2.5, "he steps off the body");
			ctx.waitTicks(140);
			check(server.computeOnServer(s -> !"leave".equals(ShapeBrain.mode())), "and goes on");

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

	/** The player in the middle looking south (0, away from him) or north (180, at him); he {@code back} blocks north, looking at them. */
	private void place(TestServerContext server, float playerYaw, double back) {
		server.runOnServer(s -> {
			ShapeBrain.forget();
			BlockPos c = at(s, 0, 0, 0);
			player(s).teleportTo(s.overworld(), c.getX() + 0.5, c.getY(), c.getZ() + 0.5, java.util.Set.of(), playerYaw, 0f, false);
			ShapeBrain.body().teleportTo(s.overworld(), c.getX() + 0.5, c.getY(), c.getZ() + 0.5 - back, java.util.Set.of(), 0f, 0f, false);
		});
	}

	/** Waits for him to grab the player: their stalk level at that moment, or -1 if he doesn't. */
	private float stalkAtGrab(ClientGameTestContext ctx, TestServerContext server, int ticks) {
		for (int t = 0; t < ticks; t += 2) {
			float at = server.computeOnServer(s -> MatchManager.isHeld(player(s)) ? MatchManager.stalkOf(player(s)) : -1f);
			if (at >= 0) {
				return at;
			}
			ctx.waitTicks(2);
		}
		LOG.info("no grab: {} stalk {}", server.computeOnServer(s -> ShapeBrain.debug()), server.computeOnServer(s -> MatchManager.stalkOf(player(s))));
		return -1f;
	}

	/** How far he is from the nearest body. */
	private static double fromBody(MinecraftServer s) {
		ServerPlayer m = ShapeBrain.body();
		return s.overworld().getEntities((net.minecraft.world.entity.Entity) null, m.getBoundingBox().inflate(20), e -> e.entityTags().contains(MatchManager.CORPSE_TAG))
			.stream().mapToDouble(e -> e.distanceTo(m)).min().orElse(99);
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
