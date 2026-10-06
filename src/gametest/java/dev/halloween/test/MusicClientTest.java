package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.client.ClientMusic;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Bots;
import dev.halloween.game.MatchManager;
import dev.halloween.game.ShapeState;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Match music: Michael hears Stalking by Bloodthirst level and Proximity by stun level when he chases
 * someone who saw him; survivors hear Ambience, Being Stalked, Proximity; a kill silences it all.
 */
public class MusicClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-music-test");

	private BlockPos origin;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("music")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			this.origin = server.computeOnServer(s -> player(s).blockPosition());
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = 2;
				HwConfig.get().botQteSuccessChance = 0f;
			});
			server.runCommand("hw point clear npc");

			// ---------------------------------------------------------------- Michael
			server.runCommand("hw start @p");
			ctx.waitTicks(60);
			check(server.computeOnServer(s -> MatchManager.role(player(s))) == Role.SHAPE, "the player is the Shape");
			this.tp(server, 30.5, 0, -30.5);
			this.waitFor(ctx, "stalking_1", "watching at Bloodthirst level 1: Stalking, first composition");
			server.runOnServer(s -> MatchManager.shapeState(player(s)).bloodthirst = 250f);
			this.waitFor(ctx, "stalking_3", "Bloodthirst level 3: the third");

			// A bot that sees him coming: a chase.
			int botId = server.computeOnServer(s -> {
				BotPlayer bot = Bots.all().getFirst();
				ServerPlayer shape = player(s);
				Vec3 at = this.at(10.5, 0, -10.5);
				bot.teleportTo(s.overworld(), at.x, at.y, at.z - 9, java.util.Set.of(), 0f, 0f, false);
				shape.teleportTo(s.overworld(), at.x, at.y, at.z, java.util.Set.of(), 180f, 0f, false);
				return bot.getId();
			});
			for (int i = 0; i < 12; i++) {
				// He comes on, the bot keeps looking at him.
				double step = i * 0.6;
				server.runOnServer(s -> {
					ServerPlayer shape = player(s);
					Vec3 at = this.at(10.5, 0, -10.5 - step);
					shape.teleportTo(s.overworld(), at.x, at.y, at.z, java.util.Set.of(), 180f, 0f, false);
					LivingEntity bot = (LivingEntity) s.overworld().getEntity(botId);
					bot.teleportTo(at.x, at.y, this.origin.getZ() - 19.5);
					face(bot, shape.getEyePosition());
				});
				ctx.waitTicks(5);
			}
			this.waitFor(ctx, "proximity_1", "chasing someone who saw him: Proximity, first composition");
			server.runOnServer(s -> MatchManager.shapeState(player(s)).stun = 0.5f);
			this.waitFor(ctx, "proximity_2", "half stunned: the second");
			server.runOnServer(s -> MatchManager.shapeState(player(s)).stunnedTicks = 200);
			this.waitFor(ctx, "proximity_4", "knocked down: the last");
			server.runOnServer(s -> {
				ShapeState st = MatchManager.shapeState(player(s));
				st.stunnedTicks = 0;
				st.stun = 0;
			});

			// A kill: the music falls silent.
			server.runOnServer(s -> {
				ServerPlayer shape = player(s);
				LivingEntity bot = (LivingEntity) s.overworld().getEntity(botId);
				shape.teleportTo(s.overworld(), bot.getX(), bot.getY(), bot.getZ() + 1.4, java.util.Set.of(), 180f, 10f, false);
				bot.setHealth(1f);
				MatchManager.shapeState(shape).attackCooldown = 0;
				MatchManager.onShapeAttack(shape, botId, true);
			});
			this.waitFor(ctx, "none", "a kill silences the music");
			server.runCommand("hw stop");
			ctx.waitTicks(10);
			this.waitFor(ctx, "none", "no match, no match music");

			// ---------------------------------------------------------------- a survivor
			server.runOnServer(s -> HwConfig.get().botsFillTo = 2);
			server.runCommand("hw start");
			ctx.waitTicks(60);
			check(server.computeOnServer(s -> MatchManager.role(player(s))) == Role.CIVILIAN, "the player is a survivor");
			// A bot plays Michael for this.
			int shapeId = server.computeOnServer(s -> {
				BotPlayer bot = Bots.all().getFirst();
				MatchManager.applyRole(bot, Role.SHAPE);
				Vec3 at = this.at(40.5, 0, 30.5);
				bot.teleportTo(s.overworld(), at.x, at.y, at.z, java.util.Set.of(), 0f, 0f, false);
				return bot.getId();
			});
			this.tp(server, 0.5, 0, 0.5);
			this.waitFor(ctx, "ambience", "all quiet: Ambience");
			server.runOnServer(s -> {
				ServerPlayer me = player(s);
				s.overworld().getEntity(shapeId).teleportTo(me.getX() + 14, me.getY(), me.getZ() + 14);
			});
			this.waitFor(ctx, "being_stalked", "he is close but not after me: Being Stalked");
			for (int i = 0; i < 12; i++) {
				double d = 12 - i * 0.7;
				server.runOnServer(s -> {
					ServerPlayer me = player(s);
					var shape = s.overworld().getEntity(shapeId);
					shape.teleportTo(me.getX(), me.getY(), me.getZ() - d);
					face(me, shape.getEyePosition());
				});
				ctx.waitTicks(5);
			}
			this.waitFor(ctx, "proximity_1", "he comes for me and I saw him: Proximity");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> {
				HwConfig.get().botsFillTo = new HwConfig().botsFillTo;
				HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds;
				HwConfig.get().botQteSuccessChance = new HwConfig().botQteSuccessChance;
			});
		}
		LOG.info("Music client test finished OK");
	}

	private void waitFor(ClientGameTestContext ctx, String part, String what) {
		String playing = "";
		for (int i = 0; i < 40; i++) {
			ctx.waitTicks(5);
			playing = ctx.computeOnClient(mc -> ClientMusic.playing());
			if (playing.contains(part)) {
				break;
			}
		}
		check(playing.contains(part), what + " (" + playing + ")");
	}

	private static void face(LivingEntity e, Vec3 target) {
		Vec3 d = target.subtract(e.getEyePosition());
		float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
		float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
		e.setYRot(yaw);
		e.setYHeadRot(yaw);
		e.setXRot(pitch);
		if (e instanceof ServerPlayer p) {
			p.connection.teleport(p.getX(), p.getY(), p.getZ(), yaw, pitch);
		}
	}

	private Vec3 at(double dx, double dy, double dz) {
		return new Vec3(this.origin.getX() + dx, this.origin.getY() + dy, this.origin.getZ() + dz);
	}

	private void tp(TestServerContext server, double dx, double dy, double dz) {
		Vec3 pos = this.at(dx, dy, dz);
		server.runOnServer(s -> player(s).teleportTo(s.overworld(), pos.x, pos.y, pos.z, java.util.Set.of(), 0f, 0f, false));
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
