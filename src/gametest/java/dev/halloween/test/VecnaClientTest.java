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

			// The Eyes: through the bot's, then back.
			server.runOnServer(s -> MatchManager.onShapeJump(player(s)));
			ctx.waitTicks(10);
			check(ctx.computeOnClient(mc -> mc.getCameraEntity() != mc.player && dev.halloween.client.VecnaClient.eyesActive()), "the Eyes look through the bot's");
			ctx.takeScreenshot("vecna_eyes");
			int reserve = ctx.computeOnClient(mc -> ClientState.vecna.eyesReserve());
			LOG.info("Eyes reserve while looking: {}", reserve);
			check(reserve < HwConfig.get().vecnaEyesMaxTicks, "the reserve drains while looking");
			server.runOnServer(s -> MatchManager.onShapeJump(player(s)));
			ctx.waitTicks(6);
			check(ctx.computeOnClient(mc -> mc.getCameraEntity() == mc.player), "back to his own eyes");

			// The booths are up; a call from one lifts the curse.
			int booths = server.computeOnServer(s -> dev.halloween.game.Booths.all().size());
			LOG.info("booths: {}", booths);
			check(booths > 0, "telephone booths are up");
			server.runOnServer(s -> {
				dev.halloween.game.VecnaCurse.testSet(bot(s), 2);
				dev.halloween.game.Booths.Booth b = dev.halloween.game.Booths.all().get(0);
				ServerPlayer bot = bot(s);
				bot.teleportTo(s.overworld(), b.inside.getX() + 0.5, b.inside.getY(), b.inside.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
				// (Held still: its own errands would walk it out of the booth.)
				bot.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).addOrUpdateTransientModifier(
					new net.minecraft.world.entity.ai.attributes.AttributeModifier(dev.halloween.Halloween.id("test_still"), -1, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
				// He looks at the booth from a few steps off.
				ServerPlayer p = player(s);
				// In front of its doorway and a little to one side, where he can stand under the open sky.
				net.minecraft.core.BlockPos door = b.inside.subtract(b.phone.below());
				Vec3 front = null;
				for (int k = 7; k >= 3 && front == null; k--) {
					for (int side : new int[]{2, -2, 0}) {
						net.minecraft.core.BlockPos at = b.inside.offset(door.getX() * k + door.getZ() * side, 0, door.getZ() * k + door.getX() * side);
						if (dev.halloween.game.Nav.standable(s.overworld(), at) && s.overworld().canSeeSky(at.above())) {
							front = Vec3.atBottomCenterOf(at);
							break;
						}
					}
				}
				if (front == null) {
					front = Vec3.atBottomCenterOf(b.inside.offset(door.getX() * 3, 0, door.getZ() * 3));
				}
				LOG.info("booth at {}, doorway {}, he stands at {}", b.inside, door, front);
				Vec3 d = Vec3.atCenterOf(b.inside).subtract(front.add(0, 1.6, 0));
				p.teleportTo(s.overworld(), front.x, front.y, front.z, java.util.Set.of(), (float) Math.toDegrees(Math.atan2(-d.x, d.z)) + 18f, 10f, false);
			});
			check(server.computeOnServer(s -> dev.halloween.game.VecnaCurse.stage(bot(s)) == 2), "the bot is cursed (stage 2)");
			Vec3 boothAt = server.computeOnServer(s -> Vec3.atCenterOf(dev.halloween.game.Booths.all().get(0).inside));
			ctx.waitTicks(3);
			ctx.runOnClient(mc -> {
				Vec3 d = boothAt.subtract(mc.player.getEyePosition());
				mc.player.setYRot((float) (Math.atan2(-d.x, d.z) * 180 / Math.PI) - 22f);
				mc.player.setXRot(5f);
			});
			ctx.waitTicks(10);
			ctx.takeScreenshot("vecna_booth");
			for (int i = 0; i < 40; i++) {
				server.runOnServer(s -> dev.halloween.game.Holds.ping(bot(s), dev.halloween.game.Booths.all().get(0).phone));
				ctx.waitTicks(4);
				if (i == 10) {
					server.runOnServer(s -> LOG.info("mid-call: bot {} from the phone, holding {}", bot(s).getEyePosition().distanceTo(Vec3.atCenterOf(dev.halloween.game.Booths.all().get(0).phone)), dev.halloween.game.Holds.working(bot(s))));
				}
			}
			server.runOnServer(s -> LOG.info("bot {} from the phone, holding {}, stage {}", bot(s).getEyePosition().distanceTo(Vec3.atCenterOf(dev.halloween.game.Booths.all().get(0).phone)),
				dev.halloween.game.Holds.working(bot(s)), dev.halloween.game.VecnaCurse.stage(bot(s))));
			check(server.computeOnServer(s -> dev.halloween.game.VecnaCurse.stage(bot(s)) == 0), "the booth's call lifts the curse");
			server.runOnServer(s -> bot(s).getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).removeModifier(dev.halloween.Halloween.id("test_still")));
			server.runOnServer(s -> dev.halloween.game.VecnaCurse.testSet(bot(s), 1));
			check(server.computeOnServer(s -> dev.halloween.game.Holds.working(bot(s)) == false), "the bot holds nothing now");

			// The Anchor: the bot, cursed, thirty blocks off; he chooses it and lets go, and three seconds later is beside it.
			Vec3 far = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				for (int yaw = 0; yaw < 360; yaw += 30) {
					Vec3 at = p.position().add(Vec3.directionFromRotation(0, yaw).scale(30));
					if (dev.halloween.game.Nav.standable(s.overworld(), net.minecraft.core.BlockPos.containing(at))) {
						bot(s).teleportTo(s.overworld(), at.x, Math.floor(at.y), at.z, java.util.Set.of(), 0f, 0f, false);
						return at;
					}
				}
				return null;
			});
			check(far != null, "a spot thirty blocks off for the bot");
			ctx.runOnClient(mc -> {
				Vec3 d = far.add(0, 1, 0).subtract(mc.player.getEyePosition());
				mc.player.setYRot((float) (Math.atan2(-d.x, d.z) * 180 / Math.PI));
			});
			ctx.waitTicks(5);
			server.runOnServer(s -> Vecna.anchorKey(player(s), true));
			ctx.waitTicks(6);
			ctx.takeScreenshot("vecna_anchor_choose");
			check(ctx.computeOnClient(mc -> ClientState.vecna.marks().size() == 1), "holding the key shows the cursed bot");
			server.runOnServer(s -> Vecna.anchorKey(player(s), false));
			ctx.waitTicks(HwConfig.get().vecnaAnchorDelayTicks + 10);
			double near = server.computeOnServer(s -> player(s).distanceTo(bot(s)));
			LOG.info("after the Anchor: {} blocks from the bot", near);
			check(near < 12, "the Anchor brought him beside the bot");
			ctx.takeScreenshot("vecna_anchor_there");

			// A phantom (as a cursed survivor sees one).
			check(ctx.computeOnClient(mc -> dev.halloween.client.VecnaClient.testPhantom()), "a phantom stands");
			ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 120f));
			ctx.waitTicks(4);
			ctx.takeScreenshot("vecna_phantom");

			// The last stage: twenty seconds (two here), then he takes the bot.
			java.util.UUID doomed = server.computeOnServer(s -> bot(s).getUUID());
			server.runOnServer(s -> {
				HwConfig.get().vecnaCurseLastTicks = 40;
				dev.halloween.game.VecnaCurse.testSet(bot(s), 4);
			});
			ctx.waitTicks(100);
			// (A dead bot leaves the match: gone, or watching.)
			check(server.computeOnServer(s -> s.getPlayerList().getPlayer(doomed) == null || s.getPlayerList().getPlayer(doomed).isSpectator()),
				"the curse killed the bot at the last stage");
			server.runOnServer(s -> HwConfig.get().vecnaCurseLastTicks = 400);
			ctx.takeScreenshot("vecna_after_curse_death");
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
