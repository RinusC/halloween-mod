package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.client.CameraAim;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Bots;
import dev.halloween.game.DoorLocks;
import dev.halloween.game.Exits;
import dev.halloween.game.Lockers;
import dev.halloween.game.Loot;
import dev.halloween.game.Match;
import dev.halloween.game.MatchManager;
import dev.halloween.game.Nav;
import dev.halloween.game.Sight;
import dev.halloween.game.Townsfolk;
import dev.halloween.game.Talks;
import dev.halloween.client.ClientState;
import dev.halloween.client.NpcMenuScreen;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bots and townsfolk. First the lone player plays the Shape: four bot civilians join, go about their
 * business, run when they see him; townsfolk scream and he can kill them. Then the player is a lone
 * civilian: no bots, and townsfolk can be led to an exit or told to hide.
 */
public class BotsClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-bots-test");

	private BlockPos origin;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			this.origin = server.computeOnServer(s -> player(s).blockPosition());
			server.runOnServer(s -> {
				HwConfig cfg = HwConfig.get();
				cfg.countdownSeconds = 2;
				// The match test speeds these up; here bots must not get away too early.
				cfg.policeArriveSeconds = new HwConfig().policeArriveSeconds;
				cfg.deputyRespawnSeconds = new HwConfig().deputyRespawnSeconds;
			});

			// ---------------------------------------------------------------- the player is the Shape
			server.runCommand("hw start @p");
			ctx.waitTicks(60);
			check(server.computeOnServer(s -> Match.running()), "the match runs");
			check(server.computeOnServer(s -> MatchManager.role(player(s))) == Role.SHAPE, "the player is the Shape");
			int bots = server.computeOnServer(s -> (int) s.getPlayerList().getPlayers().stream()
				.filter(p -> p instanceof BotPlayer && MatchManager.role(p) == Role.CIVILIAN).count());
			check(bots == 4, "four bot civilians join (" + bots + ")");
			int npcs = server.computeOnServer(s -> Townsfolk.count());
			check(npcs == 6, "townsfolk are at home in the houses (" + npcs + ")");

			// Keep the Shape out of everyone's way while the bots get on with it.
			this.tp(server, 30.5, 0, -30.5);
			// Michael has cut the power in the first house; bots should notice and switch it back on.
			BlockPos breaker = this.origin.offset(-17, 1, 15);
			server.runOnServer(s -> dev.halloween.game.LightSystem.flip(s.overworld(), breaker, false));
			Map<UUID, Vec3> start = server.computeOnServer(s -> {
				Map<UUID, Vec3> map = new HashMap<>();
				Bots.all().forEach(b -> map.put(b.getUUID(), b.position()));
				return map;
			});
			ctx.waitTicks(400);
			double moved = server.computeOnServer(s -> Bots.all().stream()
				.mapToDouble(b -> b.position().distanceTo(start.getOrDefault(b.getUUID(), b.position()))).max().orElse(0));
			LOG.info("Furthest bot walk: {}", moved);
			check(moved > 5, "bots walk around the map");
			int searched = server.computeOnServer(s -> Loot.searched().size());
			check(searched > 0, "bots search containers (" + searched + ")");
			check(server.computeOnServer(s -> dev.halloween.game.LightSystem.isOn(s.overworld(), breaker)), "a bot switches the house lights back on");
			// Talking a neighbour round takes 15 s for bots too.
			int told = 0;
			for (int i = 0; i < 30 && told == 0; i++) {
				ctx.waitTicks(20);
				told = server.computeOnServer(s -> npcs(s).stream().mapToInt(m -> "hidden".equals(Townsfolk.stateOf(m)) ? 1 : 0).sum());
			}
			check(told > 0, "bots tell townsfolk at home to hide (" + told + ")");
			ctx.takeScreenshot("b01_bots_at_work");

			// A bot that sees the Shape runs away.
			String fled = server.computeOnServer(s -> {
				BotPlayer bot = Bots.all().stream().filter(b -> !b.isSpectator() && !Lockers.isInside(b)).findFirst().orElse(null);
				if (bot == null) {
					return "no bot";
				}
				Vec3 front = near(s, bot, 3);
				player(s).teleportTo(s.overworld(), front.x, front.y, front.z, java.util.Set.of(), 0f, 0f, false);
				return bot.getUUID().toString();
			});
			check(!"no bot".equals(fled), "a bot is still around");
			UUID fleeing = UUID.fromString(fled);
			double before = server.computeOnServer(s -> distance(s, fleeing));
			for (int i = 0; i < 12 && server.computeOnServer(s -> distance(s, fleeing)) < before + 3; i++) {
				ctx.waitTicks(10);
				LOG.info("fleeing bot: {} at {}", server.computeOnServer(s -> Bots.modeOf(s.getPlayerList().getPlayer(fleeing))),
					server.computeOnServer(s -> distance(s, fleeing)));
			}
			double after = server.computeOnServer(s -> distance(s, fleeing));
			LOG.info("Bot distance to the Shape: {} -> {}", before, after);
			check(after > before + 3, "a bot that sees the Shape runs away");
			check(server.computeOnServer(s -> dev.halloween.game.AiStats.snapshot().getOrDefault("*total", Map.of()).getOrDefault("callout", 0)) > 0,
				"a bot that spots him calls it out to the others");

			// A civilian (here a bot) shoves the Shape: he is thrown back and slowed.
			String shoved = server.computeOnServer(s -> {
				String why = "no bot";
				// Any bot that can shove right now will do (one in a tight corner may have no room in front).
				for (BotPlayer bot : Bots.all().stream().filter(b -> !b.isSpectator() && !Lockers.isInside(b) && dev.halloween.game.Fight.pushCooldown(b) == 0).toList()) {
					Vec3 front = near(s, bot, 1.6);
					ServerPlayer shape = player(s);
					shape.teleportTo(s.overworld(), front.x, front.y, front.z, java.util.Set.of(), 0f, 0f, false);
					Vec3 d = shape.position().add(0, 1.2, 0).subtract(bot.getEyePosition());
					bot.setYRot((float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f);
					bot.setXRot((float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG));
					Vec3 body = dev.halloween.game.FocusPoints.bodyCenter(shape);
					why = String.format(Locale.ROOT, "dist %.2f angle %.1f sees %s cooldown %d mode %s", bot.getEyePosition().distanceTo(body),
						Sight.angleTo(bot.getEyePosition(), bot.getViewVector(1f), body), Sight.canSee(s.overworld(), bot.getEyePosition(), body, bot),
						dev.halloween.game.Fight.pushCooldown(bot), Bots.modeOf(bot));
					boolean ok = dev.halloween.game.Fight.survivorPush(bot);
					if (ok && MatchManager.shapeState(shape).pushedTicks > 0) {
						return "ok";
					}
				}
				return "not shoved: " + why;
			});
			check("ok".equals(shoved) || "no bot".equals(shoved), "a civilian shoves the Shape back and slows him (" + shoved + ")");
			ctx.takeScreenshot("b02_bot_flees");

			// The Shape right behind a bot standing by a window: over the sill it goes.
			UUID climber = server.computeOnServer(s -> {
				BotPlayer bot = Bots.all().stream().filter(b -> !b.isSpectator()).toList().getFirst();
				Lockers.leave(bot, false);
				dev.halloween.game.Talks.cancel(bot, false);
				bot.teleportTo(s.overworld(), this.origin.getX() + 2.5, this.origin.getY(), this.origin.getZ() + 7.5, java.util.Set.of(), 0f, 0f, false);
				// Right behind it (close enough to hear, just out of shoving reach).
				player(s).teleportTo(s.overworld(), this.origin.getX() + 2.5, this.origin.getY(), this.origin.getZ() + 4.0, java.util.Set.of(), 0f, 0f, false);
				return bot.getUUID();
			});
			// Any window will do: with two at hand it may take the other one.
			int vaultsBefore = server.computeOnServer(s -> windowVaults(s, climber));
			boolean inHouse = false;
			for (int i = 0; i < 12 && !inHouse; i++) {
				ctx.waitTicks(10);
				String where = server.computeOnServer(s -> {
					ServerPlayer bot = s.getPlayerList().getPlayer(climber);
					return bot == null ? "gone" : Bots.modeOf(bot) + " at " + bot.position();
				});
				LOG.info("climbing bot: {}", where);
				inHouse = server.computeOnServer(s -> {
					ServerPlayer bot = s.getPlayerList().getPlayer(climber);
					return bot != null && (bot.getZ() > this.origin.getZ() + 8.6 && Math.abs(bot.getX() - this.origin.getX()) < 4
						|| windowVaults(s, climber) > vaultsBefore && !dev.halloween.game.Windows.isVaulting(bot));
				});
			}
			check(inHouse, "a bot with the Shape close behind climbs in through a window");
			ctx.takeScreenshot("b02a_bot_window");

			// With a few steps' head start next to a house, a bot gets behind the door and locks it.
			BlockPos door = this.origin.offset(-22, 0, 8);
			UUID locker = server.computeOnServer(s -> {
				BotPlayer bot = Bots.all().stream().filter(b -> !b.isSpectator()).toList().getLast();
				Lockers.leave(bot, false);
				bot.teleportTo(s.overworld(), this.origin.getX() - 21.5, this.origin.getY(), this.origin.getZ() + 5.5, java.util.Set.of(), 180f, 0f, false);
				player(s).teleportTo(s.overworld(), this.origin.getX() - 21.5, this.origin.getY(), this.origin.getZ() - 2.5, java.util.Set.of(), 0f, 0f, false);
				// A scream nearby gives the Shape away to every bot.
				Bots.alert(player(s).position(), s.getTickCount());
				return bot.getUUID();
			});
			boolean locked = false;
			for (int i = 0; i < 20 && !locked; i++) {
				ctx.waitTicks(10);
				locked = server.computeOnServer(s -> DoorLocks.isLocked(s.overworld(), door));
				LOG.info("door bot: {} at {}", server.computeOnServer(s -> Bots.modeOf(s.getPlayerList().getPlayer(locker))),
					server.computeOnServer(s -> s.getPlayerList().getPlayer(locker).position()));
			}
			check(locked, "a fleeing bot locks the door behind it");
			ctx.takeScreenshot("b02b_bot_locked_door");
			// He wanders off (standing at the house he would be in sight through a window and keep it running).
			this.tp(server, 30.5, 0, -30.5);

			// Locked in, it hides in the house (in the locker unless a neighbour it warned earlier already sits there);
			// the Shape throws the locker open and has whoever is inside.
			BlockPos cupboard = this.origin.offset(-19, 0, 15);
			boolean hiding = false;
			for (int i = 0; i < 20 && !hiding; i++) {
				ctx.waitTicks(10);
				hiding = server.computeOnServer(s -> Lockers.occupied(cupboard));
			}
			check(hiding, "someone hides in the locker");
			LOG.info("In the locker: {}, the bot that locked: {} ({})", server.computeOnServer(s -> Lockers.occupantName(cupboard)),
				server.computeOnServer(s -> s.getPlayerList().getPlayer(locker).getPlainTextName()),
				server.computeOnServer(s -> Bots.modeOf(s.getPlayerList().getPlayer(locker))));
			String lockerMode = server.computeOnServer(s -> Bots.modeOf(s.getPlayerList().getPlayer(locker)));
			check(lockerMode.equals("hide") || lockerMode.equals("locker"), "the bot that locked the door hides in the house (" + lockerMode + ")");
			check(server.computeOnServer(s -> !MatchManager.exposed(Lockers.occupant(cupboard))), "the Shape learns nothing about someone in a locker");
			String pulled = server.computeOnServer(s -> {
				ServerPlayer shape = player(s);
				shape.teleportTo(s.overworld(), cupboard.getX() + 0.5, cupboard.getY(), cupboard.getZ() - 0.8, java.util.Set.of(), 0f, 0f, false);
				MatchManager.shapeState(shape).attackCooldown = 0;
				net.minecraft.world.entity.LivingEntity bot = Lockers.occupant(cupboard);
				Lockers.shapeOpen(shape, cupboard);
				var grab = MatchManager.shapeState(shape).grab;
				if (bot == null || !MatchManager.isHeld(bot) || grab == null) {
					return "not grabbed";
				}
				int stalk = (int) MatchManager.stalkOf(bot);
				int expected = HwConfig.at(HwConfig.get().shapePointsToKillByStalk, stalk);
				return grab.shapeNeeded() == expected && HwConfig.get().victimPointsToEscape == 4 ? "ok"
					: "needs " + grab.shapeNeeded() + " at stalk " + stalk;
			});
			check("ok".equals(pulled), "the Shape pulls whoever hides there out into a grab; the kill needs 5/5/4/3 hits by stalk, escape 4 (" + pulled + ")");
			for (int stalk = 0; stalk <= 3; stalk++) {
				LOG.info("Kill needs at stalk {}: {}", stalk, HwConfig.at(HwConfig.get().shapePointsToKillByStalk, stalk));
			}
			ctx.takeScreenshot("b02c_pulled_out");
			server.runOnServer(s -> {
				var grab = MatchManager.shapeState(player(s)).grab;
				if (grab != null) {
					MatchManager.reset(s);
				}
			});

			// A townsperson that sees the Shape screams.
			int screamsBefore = server.computeOnServer(s -> Townsfolk.screams);
			int npcId = server.computeOnServer(s -> {
				// One quietly at home: not hiding in a locker, not still hoarse from an earlier scream.
				Mannequin npc = npcs(s).stream().filter(m -> !Lockers.isInside(m) && Townsfolk.canScream(m) && "home".equals(Townsfolk.stateOf(m))).findFirst()
					.orElse(npcs(s).stream().filter(m -> !Lockers.isInside(m) && Townsfolk.canScream(m)).findFirst().orElseThrow());
				Vec3 front = near(s, npc, 2);
				player(s).teleportTo(s.overworld(), front.x, front.y, front.z, java.util.Set.of(), 0f, 0f, false);
				LOG.info("Scream test: {} state {} at {}, Shape at {}, can see {}", npc.getPlainTextName(), Townsfolk.stateOf(npc), npc.position(), front,
					dev.halloween.game.Sight.canSee(s.overworld(), npc.getEyePosition(), front.add(0, 1.2, 0), npc));
				return npc.getId();
			});
			for (int i = 0; i < 8 && server.computeOnServer(s -> Townsfolk.screams) <= screamsBefore; i++) {
				ctx.waitTicks(5);
			}
			LOG.info("Scream test after: state {}, player at {}", server.computeOnServer(s -> Townsfolk.stateOf(s.overworld().getEntity(npcId))),
				server.computeOnServer(s -> player(s).position()));
			check(server.computeOnServer(s -> Townsfolk.screams) > screamsBefore, "a townsperson that sees the Shape screams");
			String state = server.computeOnServer(s -> Townsfolk.stateOf(s.overworld().getEntity(npcId)));
			LOG.info("Townsperson state: {}", state);
			check(!state.equals("home"), "and stops minding the house (" + state + ")");

			// The Shape kills a townsperson: a body is left and Bloodthirst grows.
			float bloodBefore = server.computeOnServer(s -> MatchManager.shapeState(player(s)).bloodthirst);
			String kill = "alive";
			for (int i = 0; i < 12 && "alive".equals(kill); i++) {
				kill = server.computeOnServer(s -> {
					Entity npc = s.overworld().getEntity(npcId);
					if (npc == null || npc.isRemoved()) {
						return "dead";
					}
					ServerPlayer shape = player(s);
					shape.teleportTo(s.overworld(), npc.getX() + 1.2, npc.getY(), npc.getZ(), java.util.Set.of(), 90f, 10f, false);
					MatchManager.onShapeAttack(shape, npcId, true);
					return "alive";
				});
				ctx.waitTicks(32);
			}
			check("dead".equals(kill), "the Shape can kill a townsperson");
			check(server.computeOnServer(s -> MatchManager.shapeState(player(s)).bloodthirst) > bloodBefore, "the kill feeds Bloodthirst");
			check(server.computeOnServer(s -> !s.overworld().getEntities(EntityTypes.MANNEQUIN,
				e -> e.entityTags().contains(MatchManager.CORPSE_TAG)).isEmpty()), "a body is left behind");
			ctx.takeScreenshot("b03_townsperson_killed");

			server.runCommand("hw stop");
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> s.getPlayerList().getPlayers().size()) == 1, "bots leave when the match stops");
			check(server.computeOnServer(s -> Townsfolk.count()) == 0, "townsfolk go home when the match stops");

			// ---------------------------------------------------------------- the player is a civilian
			server.runCommand("hw start");
			ctx.waitTicks(60);
			check(server.computeOnServer(s -> s.getPlayerList().getPlayers().size()) == 1, "no bots without a Shape");
			check(server.computeOnServer(s -> MatchManager.role(player(s))) == Role.CIVILIAN, "the player is a civilian");

			// Breaking a talk off: open the menu, start asking, use again.
			int guest = server.computeOnServer(s -> npcs(s).stream().filter(m -> !Lockers.isInside(m)).findFirst().orElseThrow().getId());
			this.openMenu(ctx, server, guest);
			ctx.takeScreenshot("b04_talk_menu");
			ctx.getInput().pressKey(GLFW.GLFW_KEY_1);
			ctx.waitTicks(20);
			check(server.computeOnServer(s -> Talks.isTalking(player(s))), "picking from the menu starts the talk");
			check("talk".equals(server.computeOnServer(s -> Townsfolk.stateOf(s.overworld().getEntity(guest)))), "the townsperson stops to listen");
			ctx.getInput().holdKey(o -> o.keyUse);
			ctx.waitTicks(3);
			ctx.getInput().releaseKey(o -> o.keyUse);
			ctx.waitTicks(5);
			check(!server.computeOnServer(s -> Talks.isTalking(player(s))), "using it again breaks the talk off");
			check(!"talk".equals(server.computeOnServer(s -> Townsfolk.stateOf(s.overworld().getEntity(guest)))), "and it goes back to what it was doing");

			// "Follow me", talked through: about 15 s, reaction checks cut it short.
			int took = this.talk(ctx, server, guest, 0, "follow");
			check("follow".equals(server.computeOnServer(s -> Townsfolk.stateOf(s.overworld().getEntity(guest)))), "a townsperson follows when talked round");
			check(took < HwConfig.get().talkTicks, "hit reaction checks make the talk shorter (" + took + " ticks)");
			ctx.takeScreenshot("b04_follow_me");

			// Lead it out: walk a few steps, it keeps up.
			this.place(server, server.computeOnServer(s -> near(s, s.overworld().getEntity(guest), 4)));
			double gap = 99;
			for (int i = 0; i < 12 && gap >= 3.5; i++) {
				ctx.waitTicks(10);
				gap = server.computeOnServer(s -> s.overworld().getEntity(guest).distanceTo(player(s)));
			}
			LOG.info("Follow gap: {}", gap);
			check(gap < 3.5, "it keeps up");

			// At an open exit it gets out (using the exit up) and the civilian scores.
			int scoreBefore = server.computeOnServer(s -> Match.scores().values().stream().mapToInt(Integer::intValue).sum());
			server.runOnServer(s -> {
				Exits.forceOpen(s.overworld(), Exits.CELLAR);
				BlockPos exit = Exits.infos().stream().filter(e -> e.type() == Exits.CELLAR).findFirst().orElseThrow().pos();
				ServerPlayer p = player(s);
				p.teleportTo(s.overworld(), exit.getX() + 0.5, exit.getY(), exit.getZ() - 1.5, java.util.Set.of(), 0f, 0f, false);
				s.overworld().getEntity(guest).teleportTo(exit.getX() + 0.5, exit.getY(), exit.getZ() - 4.5);
			});
			ctx.waitTicks(80);
			check(server.computeOnServer(s -> s.overworld().getEntity(guest) == null), "a townsperson led to an exit is rescued");
			int scoreAfter = server.computeOnServer(s -> Match.scores().values().stream().mapToInt(Integer::intValue).sum());
			check(scoreAfter >= scoreBefore + HwConfig.get().scoreRescueNpc, "the rescue scores (" + scoreBefore + " -> " + scoreAfter + ")");
			check(server.computeOnServer(s -> Exits.infos().stream().filter(e -> e.type() == Exits.CELLAR).findFirst().orElseThrow().state()) == 2,
				"the townsperson used the exit up, like a civilian would");

			// A civilian hides in a locker and climbs out again.
			BlockPos cupboard2 = server.computeOnServer(s -> Lockers.nearestFree(s.overworld(), this.origin, 40));
			check(cupboard2 != null, "the arena has a free locker");
			Vec3 front = server.computeOnServer(s -> Vec3.atBottomCenterOf(Lockers.front(s.overworld(), cupboard2)));
			this.place(server, front);
			this.lookAt(ctx, Vec3.atCenterOf(cupboard2).add(0, 0.3, 0));
			ctx.waitTicks(60);
			ctx.takeScreenshot("b04_locker_front");
			ctx.getInput().pressKey(o -> o.keyUse);
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> Lockers.isInside(player(s))), "a civilian climbs into a locker");
			check(server.computeOnServer(s -> !MatchManager.exposed(player(s))), "and is hidden from the Shape's senses");
			ctx.waitTicks(20);
			ctx.takeScreenshot("b04a_inside_locker");
			ctx.waitTicks(15);
			ctx.getInput().holdKey(o -> o.keyShift);
			ctx.waitTicks(4);
			ctx.getInput().releaseKey(o -> o.keyShift);
			ctx.waitTicks(4);
			check(server.computeOnServer(s -> !Lockers.isInside(player(s)) && player(s).position().distanceTo(front) < 1.0), "sneak climbs back out");

			// "Hide here", from the menu; it pays out if it lives to the end.
			int hider = server.computeOnServer(s -> npcs(s).stream().filter(m -> !Lockers.isInside(m)).findFirst().orElseThrow().getId());
			this.talk(ctx, server, hider, 1, "hidden");
			check("hidden".equals(server.computeOnServer(s -> Townsfolk.stateOf(s.overworld().getEntity(hider)))), "a townsperson hides when told");
			int beforeEnd = server.computeOnServer(s -> Match.scores().values().stream().mapToInt(Integer::intValue).sum());
			server.runCommand("hw timer 1");
			ctx.waitTicks(40);
			check(server.computeOnServer(s -> Match.state()) == Match.State.IDLE, "the match ends");
			int expected = beforeEnd + HwConfig.get().scoreHiddenNpc;
			String board = ctx.computeOnClient(mc -> mc.player == null || dev.halloween.client.ClientState.matchEnd == null ? ""
				: dev.halloween.client.ClientState.matchEnd.lines().stream().map(l -> l.getString()).reduce("", (a, b) -> a + " | " + b));
			LOG.info("Final board: {}", board);
			check(board.contains(String.valueOf(expected)), "a hidden townsperson that lives pays out at the end (" + expected + ")");
		}
		LOG.info("Bots client test finished OK");
	}

	/** Walks up to a townsperson and uses it until the radial menu opens (furniture can be in the way). */
	private void openMenu(ClientGameTestContext ctx, TestServerContext server, int npcId) {
		for (int attempt = 0; attempt < 4; attempt++) {
			int skip = attempt * 4;
			this.place(server, server.computeOnServer(s -> near(s, s.overworld().getEntity(npcId), 1.8, skip)));
			Vec3 at = server.computeOnServer(s -> s.overworld().getEntity(npcId).position());
			this.lookAt(ctx, at.add(0, 1.2, 0));
			ctx.getInput().pressKey(o -> o.keyUse);
			ctx.waitTicks(4);
			if (ctx.computeOnClient(mc -> mc.gui.screen() instanceof NpcMenuScreen)) {
				break;
			}
		}
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof NpcMenuScreen), "using a townsperson opens the radial menu");
	}

	/**
	 * Talks a townsperson into {@code option} through the menu, hitting the reaction checks as they come.
	 * Returns how many ticks the talk took.
	 */
	private int talk(ClientGameTestContext ctx, TestServerContext server, int npcId, int option, String state) {
		this.openMenu(ctx, server, npcId);
		ctx.getInput().pressKey(GLFW.GLFW_KEY_1 + option);
		int ticks = 0;
		int hits = 0;
		while (ticks < HwConfig.get().talkTicks + 40 && !state.equals(server.computeOnServer(s -> Townsfolk.stateOf(s.overworld().getEntity(npcId))))) {
			ctx.waitTicks(1);
			ticks++;
			boolean inZone = ctx.computeOnClient(mc -> {
				var check = ClientState.skill;
				if (!check.active()) {
					return false;
				}
				float needle = dev.halloween.net.HwNet.needle(mc.level.getGameTime() - check.start(), check.period());
				return needle > check.zoneStart() + 0.02f && needle < check.zoneStart() + check.zoneWidth() - 0.02f;
			});
			if (inZone) {
				ctx.getInput().pressKey(o -> o.keyJump);
				hits++;
				ctx.waitTicks(3);
				ticks += 3;
			}
		}
		LOG.info("Talk ({}) took {} ticks, {} checks pressed", option, ticks, hits);
		return ticks;
	}

	private static List<Mannequin> npcs(MinecraftServer server) {
		List<Mannequin> list = new ArrayList<>();
		for (Mannequin m : server.overworld().getEntities(EntityTypes.MANNEQUIN, Townsfolk::isNpc)) {
			list.add(m);
		}
		return list;
	}

	/** A free spot about {@code dist} from the entity that it can see. */
	private static Vec3 near(MinecraftServer server, Entity entity, double dist) {
		return near(server, entity, dist, 0);
	}

	private static Vec3 near(MinecraftServer server, Entity entity, double dist, int skip) {
		var level = server.overworld();
		for (int i = 0; i < 16; i++) {
			double a = Math.toRadians(entity.getYRot() + 90 + (i + skip) * 22.5);
			Vec3 p = entity.position().add(Math.cos(a) * dist, 0, Math.sin(a) * dist);
			BlockPos feet = BlockPos.containing(p);
			if (Nav.standable(level, feet) && Sight.canSee(level, entity.getEyePosition(), Vec3.atBottomCenterOf(feet).add(0, 1.5, 0), entity)) {
				return Vec3.atBottomCenterOf(feet);
			}
		}
		return entity.position().add(1, 0, 0);
	}

	private void place(TestServerContext server, Vec3 pos) {
		server.runOnServer(s -> player(s).teleportTo(s.overworld(), pos.x, pos.y, pos.z, java.util.Set.of(), 0f, 0f, false));
	}

	private static double distance(MinecraftServer server, UUID botId) {
		ServerPlayer bot = server.getPlayerList().getPlayer(botId);
		return bot == null ? 999 : bot.distanceTo(player(server));
	}

	private static int windowVaults(MinecraftServer server, UUID botId) {
		ServerPlayer bot = server.getPlayerList().getPlayer(botId);
		return bot == null ? 0 : dev.halloween.game.AiStats.snapshot().getOrDefault(bot.getPlainTextName(), Map.of()).getOrDefault("window", 0);
	}

	private static ServerPlayer player(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!(p instanceof BotPlayer)) {
				return p;
			}
		}
		throw new IllegalStateException("no player");
	}

	private void tp(TestServerContext server, double dx, double dy, double dz) {
		this.place(server, new Vec3(this.origin.getX() + dx, this.origin.getY() + dy, this.origin.getZ() + dz));
	}

	private void lookAt(ClientGameTestContext ctx, Vec3 target) {
		for (int i = 0; i < 10; i++) {
			ctx.waitTicks(2);
			double error = ctx.computeOnClient(mc -> {
				Vec3 want = target.subtract(CameraAim.origin()).normalize();
				return Math.toDegrees(Math.acos(Mth.clamp(want.dot(CameraAim.direction()), -1, 1)));
			});
			if (error < 0.5) {
				break;
			}
			float[] rot = ctx.computeOnClient(mc -> {
				Vec3 d = target.subtract(CameraAim.origin());
				float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
				float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
				return new float[]{yaw, pitch};
			});
			ctx.getInput().lookAt(rot[0], rot[1]);
		}
		ctx.waitTicks(2);
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
