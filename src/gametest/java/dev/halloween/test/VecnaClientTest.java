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
import net.minecraft.util.Mth;
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
					if (s.overworld().noCollision(bot) && Sight.canSee(s.overworld(), p.getEyePosition(), FocusPoints.bodyCenter(bot), p) && clearCorridor(s, p, bot)) {
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
			// (The bot held still: its own errands would walk it off.)
			server.runOnServer(s -> still(bot(s), true));
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));

			// Too short an aim throws nothing.
			server.runOnServer(s -> MatchManager.shapeState(player(s)).senseHeld = true);
			ctx.waitTicks(8);
			check(server.computeOnServer(s -> Vecna.creeping() && !Vecna.rooted()), "aiming: he can only creep");
			double creep = server.computeOnServer(s -> player(s).getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED));
			LOG.info("speed while aiming: {}", creep);
			check(creep > 0 && creep < baseSpeed * 0.3, "creeping speed while aiming");
			server.runOnServer(s -> MatchManager.shapeState(player(s)).senseHeld = false);
			ctx.waitTicks(3);
			check(server.computeOnServer(s -> !Vecna.flying() && !Vecna.gripping()), "a short aim throws nothing");

			// Thrown at the bot, which steps aside as it comes: it goes by.
			server.runOnServer(s -> MatchManager.shapeState(player(s)).senseHeld = true);
			ctx.waitTicks(HwConfig.get().vecnaGripAimTicks + 4);
			ctx.takeScreenshot("vecna_grip_aim");
			server.runOnServer(s -> MatchManager.shapeState(player(s)).senseHeld = false);
			ctx.waitTicks(2);
			check(server.computeOnServer(s -> Vecna.flying()), "let go after a second's aim: the Grip flies");
			server.runOnServer(s -> {
				ServerPlayer bot = bot(s);
				Vec3 side = Vec3.directionFromRotation(0, player(s).getYRot() - 90).scale(3);
				bot.teleportTo(s.overworld(), bot.getX() + side.x, bot.getY(), bot.getZ() + side.z, java.util.Set.of(), bot.getYRot(), 0f, false);
			});
			// (Thirty blocks at twelve a second: it flies two and a half seconds.)
			ctx.waitTicks(60);
			check(server.computeOnServer(s -> !Vecna.gripping() && !Vecna.flying()), "a step aside: the Grip went by");
			server.runOnServer(s -> {
				ServerPlayer bot = bot(s);
				bot.teleportTo(s.overworld(), botAt.x, botAt.y, botAt.z, java.util.Set.of(), bot.getYRot(), 0f, false);
				Vecna.testReady();
			});
			ctx.waitTicks(3);

			// Thrown again, the bot standing: caught, drawn in, and straight into a stranglehold.
			double before = server.computeOnServer(s -> bot(s).distanceTo(player(s)));
			server.runOnServer(s -> MatchManager.shapeState(player(s)).senseHeld = true);
			ctx.waitTicks(HwConfig.get().vecnaGripAimTicks + 2);
			server.runOnServer(s -> MatchManager.shapeState(player(s)).senseHeld = false);
			ctx.waitTicks(6);
			ctx.takeScreenshot("vecna_grip_flying");
			// And as the bot sees it coming.
			int botEye = server.computeOnServer(s -> {
				ServerPlayer bot = bot(s);
				Vec3 d = player(s).getEyePosition().subtract(bot.getEyePosition());
				bot.teleportTo(s.overworld(), bot.getX(), bot.getY(), bot.getZ(), java.util.Set.of(), (float) Math.toDegrees(Math.atan2(-d.x, d.z)), 0f, false);
				return bot.getId();
			});
			ctx.runOnClient(mc -> {
				net.minecraft.world.entity.Entity b = mc.level.getEntity(botEye);
				Vec3 d = mc.player.getEyePosition().subtract(b.getEyePosition());
				float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
				b.setYRot(yaw);
				b.setYHeadRot(yaw);
				b.setXRot(0f);
				b.yRotO = yaw;
				b.xRotO = 0f;
				mc.setCameraEntity(b);
				mc.options.setCameraType(CameraType.FIRST_PERSON);
			});
			ctx.takeScreenshot("vecna_grip_incoming");
			ctx.runOnClient(mc -> mc.setCameraEntity(mc.player));
			int caughtBy = -1;
			for (int i = 0; i < 30 && caughtBy < 0; i++) {
				if (server.computeOnServer(s -> Vecna.gripping())) {
					caughtBy = i;
				}
				ctx.waitTicks(2);
			}
			LOG.info("caught {} ticks after the screenshot", caughtBy * 2);
			check(caughtBy >= 0, "the Grip caught the bot");
			server.runOnServer(s -> still(bot(s), false));
			ctx.waitTicks(30);
			ctx.takeScreenshot("vecna_grip_pull");
			double after = server.computeOnServer(s -> bot(s).distanceTo(player(s)));
			LOG.info("bot drawn from {} to {}", before, after);
			check(after < before - 2, "the bot is drawn towards him");
			boolean grabbed = false;
			for (int i = 0; i < 60 && !grabbed; i++) {
				ctx.waitTicks(4);
				grabbed = server.computeOnServer(s -> MatchManager.shapeState(player(s)).grab != null);
			}
			check(grabbed, "drawn to his hand: he strangles them");
			ctx.waitTicks(10);
			ctx.takeScreenshot("vecna_grip_strangle");
			server.runOnServer(s -> MatchManager.testEndGrab(player(s)));
			ctx.waitTicks(10);
			check(server.computeOnServer(s -> !Vecna.gripping()), "no Grip again during its rest");

			// He lets go himself: the key pressed again while drawing them in.
			server.runOnServer(s -> {
				ServerPlayer bot = bot(s);
				bot.teleportTo(s.overworld(), botAt.x, botAt.y, botAt.z, java.util.Set.of(), bot.getYRot(), 0f, false);
				still(bot, true);
				Vecna.testReady();
			});
			ctx.waitTicks(3);
			throwGrip(ctx, server);
			check(waitCaught(ctx, server), "caught again");
			check(server.computeOnServer(s -> Vecna.held(bot(s)) && !bot(s).isSpectator()), "the held one is the bot");
			check(server.computeOnServer(s -> {
				// Held: no window, door or shove for them.
				dev.halloween.game.MatchManager.onPush(bot(s));
				return Vecna.held(bot(s));
			}), "a shove does nothing while held");
			server.runOnServer(s -> MatchManager.shapeState(player(s)).senseHeld = true);
			ctx.waitTicks(3);
			server.runOnServer(s -> MatchManager.shapeState(player(s)).senseHeld = false);
			check(server.computeOnServer(s -> !Vecna.gripping() && MatchManager.shapeState(player(s)).stunnedTicks == 0), "R again: he let go, no stun");

			// Not drawn in within the time (ten seconds; one here): the bond snaps and throws him down.
			server.runOnServer(s -> {
				ServerPlayer bot = bot(s);
				bot.teleportTo(s.overworld(), botAt.x, botAt.y, botAt.z, java.util.Set.of(), bot.getYRot(), 0f, false);
				Vecna.testReady();
				HwConfig.get().vecnaGripPullTicks = 20;
			});
			ctx.waitTicks(3);
			throwGrip(ctx, server);
			check(waitCaught(ctx, server), "caught once more");
			ctx.waitTicks(28);
			check(server.computeOnServer(s -> !Vecna.gripping() && MatchManager.shapeState(player(s)).stunnedTicks > 0), "too long: the bond snapped, he is stunned");
			server.runOnServer(s -> {
				HwConfig.get().vecnaGripPullTicks = 200;
				MatchManager.shapeState(player(s)).stunnedTicks = 0;
				dev.halloween.game.GrabSession.anim(player(s), dev.halloween.net.HwNet.Anim.RISE);
				still(bot(s), false);
			});
			ctx.waitTicks(10);
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
			ctx.waitTicks(5);
			ctx.takeScreenshot("vecna_hud_rest");

			server.runOnServer(s -> {
				ShapeState st = MatchManager.shapeState(player(s));
				st.senseHeld = false;
			});

			// The Eyes: through the bot's, then back.
			server.runOnServer(s -> {
				// The bot facing him, so he is in its view.
				ServerPlayer bot = bot(s);
				Vec3 d = player(s).position().subtract(bot.position());
				bot.teleportTo(s.overworld(), bot.getX(), bot.getY(), bot.getZ(), java.util.Set.of(), (float) Math.toDegrees(Math.atan2(-d.x, d.z)), 5f, false);
			});
			server.runOnServer(s -> MatchManager.onShapeJump(player(s)));
			ctx.waitTicks(10);
			check(ctx.computeOnClient(mc -> mc.getCameraEntity() != mc.player && dev.halloween.client.VecnaClient.eyesActive()), "the Eyes look through the bot's");
			ctx.runOnClient(mc -> {
				// Whoever he looks through, turned to face him.
				net.minecraft.world.entity.Entity b = mc.getCameraEntity();
				Vec3 d = mc.player.getEyePosition().subtract(b.getEyePosition());
				float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
				b.setYRot(yaw);
				b.setYHeadRot(yaw);
				b.yRotO = yaw;
				b.setXRot(5f);
				b.xRotO = 5f;
				dev.halloween.client.VecnaClient.selfDrawn = 0;
			});
			ctx.waitTicks(1);
			ctx.takeScreenshot("vecna_eyes");
			check(ctx.computeOnClient(mc -> dev.halloween.client.VecnaClient.selfDrawn > 0), "through the Eyes he is drawn himself");
			check(ctx.computeOnClient(mc -> ClientState.outlineColor(mc.player) != -1), "through the Eyes he sees himself through walls");
			int reserve = ctx.computeOnClient(mc -> ClientState.vecna.eyesReserve());
			LOG.info("Eyes reserve while looking: {}", reserve);
			check(reserve < HwConfig.get().vecnaEyesMaxTicks, "the reserve drains while looking");
			server.runOnServer(s -> MatchManager.onShapeJump(player(s)));
			ctx.waitTicks(6);
			check(ctx.computeOnClient(mc -> mc.getCameraEntity() == mc.player), "back to his own eyes");

			// The curse: on one survivor from the start (chosen at random), Vecna told who it is. Then on our bot.
			check(server.computeOnServer(s -> dev.halloween.game.VecnaCurse.cursedPlayer(s) != null), "one survivor bears the curse from the start");
			server.runOnServer(s -> dev.halloween.game.VecnaCurse.testSet(bot(s), 1));
			ctx.waitTicks(3);
			check(server.computeOnServer(s -> dev.halloween.game.VecnaCurse.cursed(bot(s)) && dev.halloween.game.VecnaCurse.stage(bot(s)) == 1), "the curse on the bot, stage 1");
			check(ctx.computeOnClient(mc -> ClientState.vecna.marks().stream().anyMatch(m -> m.stage() > 0)), "he knows who is cursed");
			// Broken at the last stage: narrower checks, and he sees them wherever they are.
			server.runOnServer(s -> dev.halloween.game.VecnaCurse.testSet(bot(s), 4));
			ctx.waitTicks(3);
			check(server.computeOnServer(s -> dev.halloween.game.VecnaCurse.broken(bot(s)) && dev.halloween.game.VecnaCurse.checkFactor(bot(s)) < 1f),
				"stage 4: broken, the checks narrower");
			server.runOnServer(s -> dev.halloween.game.VecnaCurse.testSet(bot(s), 1));

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
			ctx.waitTicks(3);
			check(server.computeOnServer(s -> player(s).distanceTo(bot(s)) < 12 && Vecna.rooted() && player(s).isInvisible()), "the Anchor: there at once, unseen, rooted while he takes shape");
			ctx.takeScreenshot("vecna_anchor_arriving");
			ctx.waitTicks(HwConfig.get().vecnaAnchorDelayTicks + 5);
			check(server.computeOnServer(s -> !Vecna.rooted() && !player(s).isInvisible()), "taken shape: free again");
			double near = server.computeOnServer(s -> player(s).distanceTo(bot(s)));
			LOG.info("after the Anchor: {} blocks from the bot", near);
			// (The bot may well have run off while he took shape: that is the point.)
			ctx.takeScreenshot("vecna_anchor_there");

			// A phantom (as a cursed survivor sees one).
			check(ctx.computeOnClient(mc -> dev.halloween.client.VecnaClient.testPhantom()), "a phantom stands");
			ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 120f));
			ctx.waitTicks(4);
			ctx.takeScreenshot("vecna_phantom");

			// The ritual: three kills of anyone (townsfolk here), then the cursed bot: the rift opens where it falls.
			server.runOnServer(s -> HwConfig.get().vecnaCrackSpreadTicks = 40);
			double speedBefore = server.computeOnServer(s -> player(s).getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED));
			for (int i = 0; i < HwConfig.get().vecnaRitualKills; i++) {
				int k = i;
				server.runOnServer(s -> {
					ServerPlayer p = player(s);
					Vec3 at = p.position().add(Vec3.directionFromRotation(0, p.getYRot() + 40 * k - 40).scale(2.5));
					net.minecraft.world.entity.decoration.Mannequin m = net.minecraft.world.entity.decoration.Mannequin.create(net.minecraft.world.entity.EntityTypes.MANNEQUIN, s.overworld());
					m.snapTo(at.x, Math.floor(at.y), at.z, 0f, 0f);
					s.overworld().addFreshEntity(m);
					dev.halloween.game.VecnaRitual.testKill(p, m);
				});
				ctx.waitTicks(3);
			}
			check(server.computeOnServer(s -> dev.halloween.game.VecnaRitual.kills() == HwConfig.get().vecnaRitualKills && !dev.halloween.game.VecnaRitual.rift()),
				"three kills: the ritual is ready, no rift yet");
			ctx.waitTicks(22);
			ctx.takeScreenshot("vecna_ritual_ready");
			// The bot broken (so he sees it through walls), across the way.
			server.runOnServer(s -> {
				ServerPlayer p = player(s);
				dev.halloween.game.VecnaCurse.testSet(bot(s), 4);
				Vec3 at = p.position().add(Vec3.directionFromRotation(0, p.getYRot()).scale(6));
				bot(s).teleportTo(s.overworld(), at.x, Math.floor(at.y), at.z, java.util.Set.of(), p.getYRot() + 180, 0f, false);
			});
			ctx.waitTicks(5);
			ctx.takeScreenshot("vecna_cursed_broken");
			// He takes them: the scene (lifted, the bones, the eyes), seen as he sees it; the rift opens under them.
			// (By his claw: a blow that would kill it, close in front of him.)
			server.runOnServer(s -> {
				ServerPlayer p = player(s);
				Vec3 at = p.position().add(Vec3.directionFromRotation(0, p.getYRot()).scale(2.0));
				bot(s).teleportTo(s.overworld(), at.x, p.getY(), at.z, java.util.Set.of(), p.getYRot() + 180, 0f, false);
				bot(s).setHealth(1f);
				MatchManager.shapeState(p).attackCooldown = 0;
			});
			ctx.waitTicks(2);
			server.runOnServer(s -> MatchManager.onShapeAttack(player(s), bot(s).getId(), true));
			check(server.computeOnServer(s -> dev.halloween.game.VecnaDeath.active()), "a killing blow on the cursed one starts the scene");
			int shot = 0;
			for (int at : new int[]{30, 66, 104, 140}) {
				ctx.waitTicks(at - shot);
				shot = at;
				ctx.takeScreenshot("vecna_death_" + at);
				if (at == 66) {
					check(ctx.computeOnClient(mc -> dev.halloween.client.VecnaDeathScene.cinematic()), "the scene's shot is his");
					check(server.computeOnServer(s -> bot(s).getY() > dev.halloween.game.VecnaDeath.HEIGHT * 0.8 + player(s).getY() - 0.5), "the cursed one is lifted");
				}
			}
			ctx.waitTicks(15);
			check(server.computeOnServer(s -> dev.halloween.game.VecnaRitual.rift() && dev.halloween.game.VecnaRitual.pit() != null), "the cursed one killed last: the rift is open");
			check(ctx.computeOnClient(mc -> dev.halloween.client.VecnaClient.riftOpen()), "the client lives in the rift");
			ctx.waitTicks(70);
			server.runOnServer(s -> {
				ServerPlayer p = player(s);
				Vec3 a = dev.halloween.game.VecnaRitual.pit();
				for (int k = 9; k >= 4; k--) {
					net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(a.x, a.y, a.z - k);
					for (int dy = -1; dy <= 2; dy++) {
						if (dev.halloween.game.Nav.standable(s.overworld(), at.above(dy))) {
							p.teleportTo(s.overworld(), a.x, at.getY() + dy, a.z - k, java.util.Set.of(), 0f, -8f, false);
							LOG.info("rift view from {} blocks off", k);
							return;
						}
					}
				}
			});
			ctx.runOnClient(mc -> {
				mc.player.setYRot(0f);
				mc.player.setXRot(-8f);
			});
			ctx.waitTicks(10);
			ctx.takeScreenshot("vecna_rift");
			// Orange lightning across the sky.
			server.runCommand("time set midnight");
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				mc.player.setXRot(-35f);
				for (int i = 0; i < 3; i++) {
					dev.halloween.client.VecnaLightning.strike(mc);
				}
			});
			ctx.waitTicks(1);
			ctx.takeScreenshot("vecna_rift_lightning");
			LOG.info("lightning: {} bolts, extracted {}, drawn {}", ctx.computeOnClient(mc -> mc.level.getEntitiesOfClass(net.minecraft.world.entity.Display.ItemDisplay.class, mc.player.getBoundingBox().inflate(60), e -> e.getId() < -7_000_000).size()), ctx.computeOnClient(mc -> dev.halloween.client.VecnaLightning.extracted), ctx.computeOnClient(mc -> dev.halloween.client.VecnaLightning.drawn));
			// In the rift he steers the thrown Grip with his eyes.
			server.runOnServer(s -> Vecna.testReady());
			ctx.runOnClient(mc -> mc.player.setXRot(0f));
			throwGrip(ctx, server);
			ctx.waitTicks(2);
			double yaw0 = server.computeOnServer(s -> Math.toDegrees(Math.atan2(-Vecna.testBoltDir().x, Vecna.testBoltDir().z)));
			ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 70f));
			ctx.waitTicks(10);
			double yaw1 = server.computeOnServer(s -> Math.toDegrees(Math.atan2(-Vecna.testBoltDir().x, Vecna.testBoltDir().z)));
			LOG.info("steered from {} to {}", yaw0, yaw1);
			check(Math.abs(Mth.wrapDegrees(yaw1 - yaw0)) > 25, "in the rift the Grip turns where he looks");
			ctx.waitTicks(60);
			// From above, as in the show: the cracks out from the altar.
			server.runOnServer(s -> {
				Vec3 a = dev.halloween.game.VecnaRitual.pit();
				player(s).teleportTo(s.overworld(), a.x, a.y + 30, a.z - 22, java.util.Set.of(), 0f, 52f, false);
			});
			ctx.runOnClient(mc -> {
				mc.player.setYRot(0f);
				mc.player.setXRot(52f);
			});
			ctx.waitTicks(3);
			ctx.takeScreenshot("vecna_rift_above");
			server.runOnServer(s -> HwConfig.get().vecnaCrackSpreadTicks = 300);
			double speedAfter = server.computeOnServer(s -> player(s).getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED));
			LOG.info("speed {} -> {}", speedBefore, speedAfter);
			check(speedAfter > speedBefore * 1.05, "the rift makes him faster");
			// The curse as a survivor sees it on screen (this client made one for a moment): the veins at the second
			// stage, and at the last.
			for (int stage : new int[]{2, 4}) {
				ctx.runOnClient(mc -> {
					dev.halloween.client.ClientState.ROLES.put(mc.player.getUUID(), dev.halloween.Role.CIVILIAN);
					dev.halloween.client.ClientState.curse = new dev.halloween.net.HwNet.CurseHud(stage, 0.4f);
				});
				ctx.waitTicks(1);
				ctx.takeScreenshot("vecna_curse_screen_" + stage);
			}
			ctx.runOnClient(mc -> {
				dev.halloween.client.ClientState.ROLES.put(mc.player.getUUID(), dev.halloween.Role.SHAPE);
				dev.halloween.client.ClientState.curse = dev.halloween.net.HwNet.CurseHud.NONE;
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

	/** Nothing solid in a lane a couple of blocks wide between him and the bot (his camera is off to one side). */
	private static boolean clearCorridor(MinecraftServer s, ServerPlayer p, ServerPlayer bot) {
		Vec3 to = FocusPoints.bodyCenter(bot);
		Vec3 d = to.subtract(p.position());
		Vec3 side = new Vec3(-d.z, 0, d.x).normalize();
		for (double off : new double[]{-1.3, -0.6, 0, 0.6, 1.3}) {
			for (double h : new double[]{1.2, 2.0}) {
				Vec3 a = p.position().add(side.scale(off)).add(0, h, 0);
				Vec3 b = to.add(side.scale(off * 0.3));
				if (s.overworld().clip(new net.minecraft.world.level.ClipContext(a, b, net.minecraft.world.level.ClipContext.Block.COLLIDER,
					net.minecraft.world.level.ClipContext.Fluid.NONE, p)).getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
					return false;
				}
			}
		}
		return true;
	}

	/** Aim a second, let go. */
	private static void throwGrip(ClientGameTestContext ctx, TestServerContext server) {
		server.runOnServer(s -> MatchManager.shapeState(player(s)).senseHeld = true);
		ctx.waitTicks(HwConfig.get().vecnaGripAimTicks + 2);
		server.runOnServer(s -> MatchManager.shapeState(player(s)).senseHeld = false);
	}

	private static boolean waitCaught(ClientGameTestContext ctx, TestServerContext server) {
		for (int i = 0; i < 40; i++) {
			if (server.computeOnServer(s -> Vecna.gripping())) {
				return true;
			}
			ctx.waitTicks(2);
		}
		return false;
	}

	private static void still(ServerPlayer p, boolean on) {
		var speed = p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
		if (on) {
			speed.addOrUpdateTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(dev.halloween.Halloween.id("test_still"), -1,
				net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		} else {
			speed.removeModifier(dev.halloween.Halloween.id("test_still"));
		}
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
