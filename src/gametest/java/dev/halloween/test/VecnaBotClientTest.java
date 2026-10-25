package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.game.AiStats;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.MapPoints;
import dev.halloween.game.Match;
import dev.halloween.game.MatchManager;
import dev.halloween.game.ShapeBrain;
import dev.halloween.game.Vecna;
import dev.halloween.game.VecnaCurse;
import dev.halloween.game.VecnaRitual;
import java.util.Map;
import java.util.function.Function;
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
 * Vecna played by the server (/hw killer vecna, /hw shape bot): with nobody to go by he looks through the Eyes of the
 * one nearest him; someone in sight out of his reach gets the Grip (thrown where they will be), is drawn in and
 * killed; with the ritual's kills made he goes by the Anchor to the cursed one far off, takes them and the rift opens.
 */
public class VecnaBotClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-vecnabot");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("vecnabot")) {
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
			server.runCommand("hw killer vecna");
			server.runCommand("hw shape bot");

			// ---------------------------------------------------------------- he plays; nobody to go by: the Eyes
			this.start(ctx, server);
			check(server.computeOnServer(s -> Vecna.active() && ShapeBrain.body() != null && MatchManager.role(ShapeBrain.body()) == Role.SHAPE
				&& ShapeBrain.body().getScoreboardName().equals(ShapeBrain.VECNA_NAME)), "a bot plays Vecna");
			this.stage(server);
			this.until(ctx, server, 1100, s -> stat("eyes.survivor") > 0);
			LOG.info("eyes: {}", (Object) server.computeOnServer(s -> ShapeBrain.debug()));
			check(stat("eyes.survivor") > 0, "with nobody to go by he looks through the Eyes of the one nearest him");
			check(stat("lead.eyes") > 0 || server.computeOnServer(s -> ShapeBrain.debug()).startsWith("LISTEN"), "and goes where they are");
			check(server.computeOnServer(s -> !Vecna.rooted()), "and opens his own eyes again");

			// ---------------------------------------------------------------- in sight, out of reach: the Grip
			server.runOnServer(s -> {
				ShapeBrain.forget();
				BlockPos c = at(s, 0, 0, 0);
				lit(s, c);
				player(s).teleportTo(s.overworld(), c.getX() + 0.5, c.getY(), c.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
				ShapeBrain.body().teleportTo(s.overworld(), c.getX() + 0.5, c.getY(), c.getZ() - 13.5, java.util.Set.of(), 0f, 0f, false);
			});
			this.until(ctx, server, 200, s -> MatchManager.isHeld(player(s)) || Vecna.held(player(s)));
			LOG.info("grip: {}", (Object) server.computeOnServer(s -> ShapeBrain.debug()));
			check(stat("grip.aim") > 0 && stat("grip.throw") > 0, "someone in sight out of his reach: he aims the Grip and throws it");
			check(stat("grip.caught.survivor") > 0, "it catches them");
			ctx.takeScreenshot("vecnabot_grip");
			this.until(ctx, server, 700, VecnaBotClientTest::dead);
			check(server.computeOnServer(VecnaBotClientTest::dead), "drawn in, grabbed and killed");

			// ---------------------------------------------------------------- the ritual's kills made: the Anchor, the rift
			this.start(ctx, server);
			server.runOnServer(s -> {
				ShapeBrain.forget();
				ServerPlayer p = player(s);
				VecnaCurse.testSet(p, 1);
				for (int i = 0; i < HwConfig.get().vecnaRitualKills; i++) {
					BlockPos at = at(s, -30 + i * 2, 0, 30);
					net.minecraft.world.entity.decoration.Mannequin m = net.minecraft.world.entity.decoration.Mannequin.create(net.minecraft.world.entity.EntityTypes.MANNEQUIN, s.overworld());
					m.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
					s.overworld().addFreshEntity(m);
					VecnaRitual.testKill(ShapeBrain.body(), m);
				}
				BlockPos far = at(s, 25, 0, 25);
				lit(s, far);
				p.teleportTo(s.overworld(), far.getX() + 0.5, far.getY(), far.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
				BlockPos m = at(s, -25, 0, -25);
				ShapeBrain.body().teleportTo(s.overworld(), m.getX() + 0.5, m.getY(), m.getZ() + 0.5, java.util.Set.of(), 180f, 0f, false);
			});
			check(server.computeOnServer(s -> VecnaRitual.kills() >= HwConfig.get().vecnaRitualKills && VecnaCurse.cursed(player(s))),
				"three kills made, the player cursed and far off");
			this.until(ctx, server, 200, s -> stat("anchor") > 0);
			check(stat("anchor") > 0, "he goes by the Anchor to the cursed one");
			ctx.waitTicks(HwConfig.get().vecnaAnchorDelayTicks + 5);
			double near = server.computeOnServer(s -> ShapeBrain.body().distanceTo(player(s)));
			LOG.info("anchor: {} blocks off ({})", near, server.computeOnServer(s -> ShapeBrain.debug()));
			check(near < 13, "and is there beside them");
			this.until(ctx, server, 900, s -> VecnaRitual.rift());
			LOG.info("rift: {}", (Object) server.computeOnServer(s -> ShapeBrain.debug()));
			check(server.computeOnServer(s -> VecnaRitual.rift()), "takes them, and the rift opens");

			LOG.info("vecna stats: {}", AiStats.snapshot().get("~vecna"));
			server.runCommand("hw stop");
			server.runCommand("hw shape off");
			server.runCommand("hw killer michael");
			ctx.waitTicks(5);
			dev.halloween.game.Weather.forced = -1;
		}
		LOG.info("Vecna bot client test finished OK");
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
			BlockPos c = at(s, 0, 0, 0);
			s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(),
				"fill " + (c.getX() - 3) + " 200 " + (c.getZ() - 3) + " " + (c.getX() + 3) + " 200 " + (c.getZ() + 3) + " minecraft:smooth_stone");
			player(s).teleportTo(s.overworld(), c.getX() + 0.5, 201, c.getZ() + 0.5, java.util.Set.of(), 0f, 30f, false);
		});
	}

	/** A light over this spot (seen from far in the light). */
	private static void lit(MinecraftServer s, BlockPos p) {
		s.overworld().setBlockAndUpdate(p.above(3), net.minecraft.world.level.block.Blocks.LIGHT.defaultBlockState()
			.setValue(net.minecraft.world.level.block.LightBlock.LEVEL, 15));
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
		return AiStats.snapshot().getOrDefault("~vecna", Map.of()).getOrDefault(event, 0);
	}

	private static BlockPos at(MinecraftServer s, int x, int y, int z) {
		return MapPoints.get(s.overworld()).first(MapPoints.Type.CENTER).offset(x, y, z);
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
