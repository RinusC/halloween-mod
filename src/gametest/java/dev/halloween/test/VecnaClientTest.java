package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientDarkness;
import dev.halloween.client.ClientState;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.FocusPoints;
import dev.halloween.game.MatchManager;
import dev.halloween.game.ShapeState;
import dev.halloween.game.Sight;
import dev.halloween.game.Vecna;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vecna (short, on the arena): the player plays him with one bot survivor. Holding the Sense key with the bot under
 * his aim catches it in the Grip: it stands rooted and is drawn towards him; a stun breaks the Grip and starts its
 * rest. Screenshots of the Grip from behind him and from the side.
 */
public class VecnaClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-vecna");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("vecna")) {
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
			server.runCommand("hw killer vecna");
			server.runCommand("hw start @p");
			ctx.waitTicks(80);
			check(server.computeOnServer(s -> Vecna.active()), "Vecna is this match's killer");
			check(ctx.computeOnClient(mc -> ClientState.killer == 2), "the client knows the killer is Vecna");
			ctx.runOnClient(mc -> ClientDarkness.enabled = false);
			double baseSpeed = server.computeOnServer(s -> player(s).getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED));
			LOG.info("Vecna's speed: {}", baseSpeed);

			// The bot twelve blocks off in the open, in his sight; he looks at it.
			Vec3 botAt = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				ServerPlayer bot = bot(s);
				s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), "time set noon");
				for (int yaw = 0; yaw < 360; yaw += 30) {
					Vec3 dir = Vec3.directionFromRotation(0, yaw);
					Vec3 at = p.position().add(dir.scale(12));
					bot.teleportTo(s.overworld(), at.x, at.y, at.z, java.util.Set.of(), yaw + 180f, 0f, false);
					if (s.overworld().noCollision(bot) && Sight.canSee(s.overworld(), p.getEyePosition(), FocusPoints.bodyCenter(bot), p)) {
						return at;
					}
				}
				return null;
			});
			check(botAt != null, "found an open spot for the bot");
			ctx.runOnClient(mc -> {
				Vec3 d = botAt.add(0, 1, 0).subtract(mc.player.getEyePosition());
				mc.player.setYRot((float) (Math.atan2(-d.x, d.z) * 180 / Math.PI));
				mc.player.setXRot((float) (-Math.atan2(d.y, Math.hypot(d.x, d.z)) * 180 / Math.PI));
			});
			ctx.waitTicks(5);
			double before = server.computeOnServer(s -> bot(s).distanceTo(player(s)));
			server.runOnServer(s -> MatchManager.shapeState(player(s)).senseHeld = true);
			ctx.waitTicks(10);
			check(server.computeOnServer(s -> Vecna.gripping()), "the Grip caught the bot");
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			ctx.waitTicks(30);
			ctx.takeScreenshot("vecna_grip_behind");
			ctx.waitTicks(30);
			double after = server.computeOnServer(s -> bot(s).distanceTo(player(s)));
			LOG.info("bot drawn from {} to {}", before, after);
			check(after < before - 3, "the bot is drawn towards him");
			check(ctx.computeOnClient(mc -> ClientState.vecna.gripTarget() >= 0), "his screen shows the Grip");
			// Once caught, his aim no longer matters: turned aside, the Grip runs across the view.
			ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() - 45f));
			ctx.waitTicks(10);
			ctx.takeScreenshot("vecna_grip_side");
			check(server.computeOnServer(s -> Vecna.gripping()), "still held with him looking aside");

			// A stun breaks it, and the rest begins.
			server.runOnServer(s -> MatchManager.shapeState(player(s)).stunnedTicks = 40);
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> !Vecna.gripping()), "a stun breaks the Grip");
			double freed = server.computeOnServer(s -> bot(s).getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED));
			check(freed > 0, "the bot can walk again");
			server.runOnServer(s -> MatchManager.shapeState(player(s)).stunnedTicks = 0);
			ctx.waitTicks(10);
			check(server.computeOnServer(s -> !Vecna.gripping()), "no Grip again during its rest");
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
			ctx.waitTicks(5);
			ctx.takeScreenshot("vecna_hud_rest");

			server.runOnServer(s -> {
				ShapeState st = MatchManager.shapeState(player(s));
				st.senseHeld = false;
			});
			ctx.runOnClient(mc -> ClientDarkness.enabled = true);
			server.runCommand("hw stop");
			server.runCommand("hw killer michael");
			ctx.waitTicks(10);
		}
		LOG.info("Vecna client test finished OK");
	}

	private static ServerPlayer player(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!(p instanceof BotPlayer)) {
				return p;
			}
		}
		throw new IllegalStateException("no player");
	}

	private static ServerPlayer bot(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p instanceof BotPlayer && MatchManager.role(p).survivor()) {
				return p;
			}
		}
		throw new IllegalStateException("no bot survivor");
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
