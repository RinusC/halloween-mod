package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.game.AiStats;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.DoorLocks;
import dev.halloween.game.Fight;
import dev.halloween.game.HwItems;
import dev.halloween.game.MatchManager;
import dev.halloween.game.Panic;
import dev.halloween.game.Runt;
import dev.halloween.game.Stamina;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The Runt: frightens, levels up and picks gifts, clings, hexes, plays tricks, swaps with Michael; shoved, beaten, chased off; a bot plays him on its own. */
public class RuntClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-runt");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("runt")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			// ---------------------------------------------------------------- the player plays him
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = 1;
				HwConfig.get().botsFillTo = 2;
			});
			server.runCommand("hw runt @p");
			server.runCommand("hw start");
			ctx.waitTicks(40);
			check(server.computeOnServer(s -> MatchManager.role(player(s)) == Role.RUNT), "the player picked plays the Runt");
			check(server.computeOnServer(s -> Math.abs(player(s).getAttributeValue(Attributes.SCALE) - HwConfig.get().runtScale) < 1e-3), "small");
			check(server.computeOnServer(s -> s.getPlayerList().getPlayers().stream().filter(p -> MatchManager.role(p) == Role.CIVILIAN).count()) == 2,
				"two bot civilians to frighten");
			ctx.takeScreenshot("runt_hud");
			// A fright: the survivor panics, he gains fear and has to wait.
			String victim = server.computeOnServer(s -> {
				ServerPlayer bot = civilian(s);
				ServerPlayer runt = player(s);
				runt.teleportTo(s.overworld(), bot.getX() + 1.2, bot.getY(), bot.getZ(), java.util.Set.of(), 90f, 0f, false);
				return bot.getScoreboardName();
			});
			ctx.waitTicks(2);
			boolean scared = server.computeOnServer(s -> {
				ServerPlayer bot = s.getPlayerList().getPlayerByName(victim);
				float before = Panic.value(bot);
				return Runt.scare(player(s), bot) && Panic.value(bot) > before;
			});
			check(scared, "a fright panics a survivor");
			check(server.computeOnServer(s -> Runt.state(player(s)).fear >= HwConfig.get().runtFearScare && Runt.state(player(s)).scareCooldown > 0),
				"and feeds him, then he has to wait");
			check(server.computeOnServer(s -> !Runt.scare(player(s), s.getPlayerList().getPlayerByName(victim))), "no second fright straight away");
			// Level one: two gifts to pick from.
			server.runOnServer(s -> {
				Runt.State st = Runt.state(player(s));
				st.fear = HwConfig.get().runtLevelFear[0] - 1;
				st.scareCooldown = 0;
				Runt.scare(player(s), s.getPlayerList().getPlayerByName(victim));
			});
			check(server.computeOnServer(s -> Runt.state(player(s)).level == 1 && Runt.state(player(s)).pending() == 0), "level one: a gift to pick");
			ctx.waitTicks(10);
			ctx.takeScreenshot("runt_pick");
			server.runOnServer(s -> Runt.pick(player(s), 0, 0));
			check(server.computeOnServer(s -> Runt.state(player(s)).has("clinger") && Runt.state(player(s)).pending() < 0), "picked: the clinger");
			// The pounce: wound up from a crouch, a leap the way he looks. A miss floors him (the clinger gets a
			// second go first).
			server.runOnServer(s -> {
				ServerPlayer runt = player(s);
				BlockPos c = dev.halloween.game.MapPoints.get(s.overworld()).first(dev.halloween.game.MapPoints.Type.CENTER);
				runt.teleportTo(s.overworld(), c.getX() + 0.5, c.getY(), c.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
				Runt.State st = Runt.state(runt);
				st.scareCooldown = 0;
				st.crouch = HwConfig.get().runtPounceChargeTicks / 2;
			});
			ctx.waitTicks(3);
			check(server.computeOnServer(s -> Runt.state(player(s)).leapTicks > 0), "let go of the crouch: he leaps");
			for (int i = 0; i < 20 && server.computeOnServer(s -> Runt.state(player(s)).leapTicks > 0); i++) {
				ctx.waitTicks(3);
			}
			check(server.computeOnServer(s -> Runt.state(player(s)).stunTicks == 0), "a miss: the clinger gets another go");
			server.runOnServer(s -> Runt.state(player(s)).crouch = HwConfig.get().runtPounceChargeTicks / 2);
			ctx.waitTicks(3);
			for (int i = 0; i < 20 && server.computeOnServer(s -> Runt.state(player(s)).leapTicks > 0); i++) {
				ctx.waitTicks(3);
			}
			check(server.computeOnServer(s -> Runt.state(player(s)).stunTicks > 0), "a second miss floors him");
			// Right on them: on their back, hands at their throat, for as long as it takes them to tear the hands off.
			server.runOnServer(s -> {
				Runt.State st = Runt.state(player(s));
				st.stunTicks = 0;
				pounceOnto(s, player(s), s.getPlayerList().getPlayerByName(victim));
			});
			ctx.waitTicks(3);
			check(server.computeOnServer(s -> Runt.state(player(s)).holding != null), "a pounce that lands on someone puts him on their back");
			ctx.waitTicks(10);
			check(ctx.computeOnClient(mc -> mc.options.getCameraType() == net.minecraft.client.CameraType.THIRD_PERSON_BACK), "riding them, he sees himself from behind");
			ctx.takeScreenshot("runt_riding");
			check(server.computeOnServer(s -> {
				ServerPlayer bot = s.getPlayerList().getPlayerByName(victim);
				Stamina.set(bot, 1f);
				Fight.survivorPush(bot);
				Runt.hit(player(s), bot, 1f);
				return Runt.state(player(s)).holding != null;
			}), "the one held can't shove or strike him off");
			check(server.computeOnServer(s -> {
				ServerPlayer friend = s.getPlayerList().getPlayers().stream().filter(p -> p instanceof BotPlayer && MatchManager.role(p) == Role.CIVILIAN
					&& !p.getScoreboardName().equals(victim)).findFirst().orElseThrow();
				Runt.chaseOff(player(s), friend);
				Runt.State st = Runt.state(player(s));
				return st.outTicks == 0 && st.holding == null && st.stunTicks > 0;
			}), "a shot or a blow from a friend knocks him off their back but doesn't chase him off");
			ctx.waitTicks(10);
			check(ctx.computeOnClient(mc -> mc.options.getCameraType() == net.minecraft.client.CameraType.FIRST_PERSON), "off their back: his own eyes again");
			server.runOnServer(s -> {
				Runt.state(player(s)).stunTicks = 0;
				pounceOnto(s, player(s), s.getPlayerList().getPlayerByName(victim));
			});
			ctx.waitTicks(3);
			check(server.computeOnServer(s -> Runt.state(player(s)).holding != null), "on their back again");
			boolean over = false;
			for (int i = 0; i < 60 && !over; i++) {
				ctx.waitTicks(5);
				over = server.computeOnServer(s -> Runt.state(player(s)).holding == null);
			}
			check(over && server.computeOnServer(s -> Runt.state(player(s)).stunTicks > 0), "no time limit: the bot tears his hands off in its own time and throws him");
			// Fear for frightening people for real: a giggle everybody near hears, knocking on a door.
			check(server.computeOnServer(s -> {
				ServerPlayer runt = player(s);
				Runt.State st = Runt.state(runt);
				st.stunTicks = 0;
				ServerPlayer bot = s.getPlayerList().getPlayerByName(victim);
				runt.teleportTo(s.overworld(), bot.getX() + 6, bot.getY(), bot.getZ(), java.util.Set.of(), 0f, 0f, false);
				float fear = st.fear;
				float panic = Panic.value(bot);
				return Runt.laugh(runt) && st.fear > fear && Panic.value(bot) > panic && !Runt.laugh(runt);
			}), "a giggle: those near panic, he feeds on it (then a pause)");
			// Ambush: unseen for a while, then right in front of them.
			server.runOnServer(s -> HwConfig.get().runtAmbushHiddenTicks = 20);
			int ambushes = server.computeOnServer(s -> AiStats.snapshot().getOrDefault("*total", Map.of()).getOrDefault("runt.fear.ambush", 0));
			server.runOnServer(s -> {
				ServerPlayer bot = s.getPlayerList().getPlayerByName(victim);
				ServerPlayer runt = player(s);
				Vec3 back = bot.position().subtract(bot.getViewVector(1f).multiply(1, 0, 1).normalize().scale(40));
				runt.teleportTo(s.overworld(), back.x, bot.getY(), back.z, java.util.Set.of(), 0f, 0f, false);
			});
			ctx.waitTicks(30);
			boolean ambushed = false;
			for (int i = 0; i < 10 && !ambushed; i++) {
				server.runOnServer(s -> {
					ServerPlayer bot = s.getPlayerList().getPlayerByName(victim);
					Vec3 front = bot.getEyePosition().add(bot.getViewVector(1f).multiply(1, 0, 1).normalize().scale(2.5));
					player(s).teleportTo(s.overworld(), front.x, bot.getY(), front.z, java.util.Set.of(), 0f, 0f, false);
				});
				ctx.waitTicks(5);
				ambushed = server.computeOnServer(s -> AiStats.snapshot().getOrDefault("*total", Map.of()).getOrDefault("runt.fear.ambush", 0)) > ambushes;
			}
			check(ambushed, "an ambush: unseen a while, then right in front of them");
			server.runOnServer(s -> HwConfig.get().runtAmbushHiddenTicks = new HwConfig().runtAmbushHiddenTicks);
			// A locker thrown open: whoever hides in it tumbles out, frightened.
			BlockPos locker = server.computeOnServer(s -> dev.halloween.game.Lockers.nearestFree(s.overworld(), player(s).blockPosition(), 60));
			check(locker != null, "a locker to try");
			boolean pulled = server.computeOnServer(s -> {
				ServerPlayer bot = s.getPlayerList().getPlayerByName(victim);
				BlockPos front = dev.halloween.game.Lockers.front(s.overworld(), locker);
				Runt.State st = Runt.state(player(s));
				st.stunTicks = 0;
				st.scareCooldown = 0;
				Runt.hexed(bot);
				bot.teleportTo(s.overworld(), front.getX() + 0.5, front.getY(), front.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
				boolean in = dev.halloween.game.Lockers.enter(bot, locker, false);
				player(s).teleportTo(s.overworld(), front.getX() + 0.5, front.getY(), front.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
				float before = Panic.value(bot);
				return in && Runt.openLocker(player(s), locker) && !dev.halloween.game.Lockers.isInside(bot) && Panic.value(bot) > before;
			});
			check(pulled, "he throws a locker open: the one hiding tumbles out, frightened");
			// (With the clinger gift he is at their throat again: wait until that is over.)
			for (int i = 0; i < 40 && server.computeOnServer(s -> Runt.state(player(s)).holding != null); i++) {
				ctx.waitTicks(5);
			}
			// A shove floors him; two blows with something chase him off.
			server.runOnServer(s -> {
				Runt.State st = Runt.state(player(s));
				st.stunTicks = 0;
			});
			boolean shoved = server.computeOnServer(s -> {
				ServerPlayer bot = s.getPlayerList().getPlayerByName(victim);
				ServerPlayer runt = player(s);
				runt.teleportTo(s.overworld(), bot.getX() + 1.2, bot.getY(), bot.getZ(), java.util.Set.of(), 90f, 0f, false);
				bot.teleportTo(s.overworld(), bot.getX(), bot.getY(), bot.getZ(), java.util.Set.of(), -90f, 0f, false);
				// (The bot may have shoved him just now by itself: straight to the shove, not through its cooldown.)
				Runt.shove(runt, bot);
				return Runt.state(runt).stunTicks > 0;
			});
			check(shoved, "a shove floors him");
			boolean chased = server.computeOnServer(s -> {
				ServerPlayer bot = s.getPlayerList().getPlayerByName(victim);
				ServerPlayer runt = player(s);
				Runt.hit(runt, bot, HwItems.Kind.KITCHEN_KNIFE.stun);
				Runt.hit(runt, bot, HwItems.Kind.KITCHEN_KNIFE.stun);
				return Runt.state(runt).outTicks > 0 && runt.isInvisible();
			});
			check(chased, "two blows chase him off for a while");
			server.runOnServer(s -> {
				Runt.State st = Runt.state(player(s));
				st.outTicks = 2;
			});
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> Runt.state(player(s)).outTicks == 0 && !player(s).isInvisible()), "and he comes back");
			// Hex: no breath, no healing.
			check(server.computeOnServer(s -> {
				ServerPlayer bot = s.getPlayerList().getPlayerByName(victim);
				Runt.hex(bot);
				return Runt.hexed(bot) && Stamina.value(bot) <= 0.15f + 1e-3;
			}), "a hex takes the breath away");
			// Level two, mischief: a locked door opens for him.
			server.runOnServer(s -> {
				Runt.State st = Runt.state(player(s));
				st.fear = HwConfig.get().runtLevelFear[1] + 1;
				st.level = 2;
				Runt.pick(player(s), 1, 1);
			});
			BlockPos door = server.computeOnServer(s -> {
				ServerPlayer runt = player(s);
				for (BlockPos p : BlockPos.betweenClosed(runt.blockPosition().offset(-60, -3, -60), runt.blockPosition().offset(60, 4, 60))) {
					if (DoorLocks.isDoor(s.overworld().getBlockState(p))) {
						BlockPos d = DoorLocks.base(s.overworld(), p.immutable());
						DoorLocks.forceLock(s.overworld(), d);
						runt.teleportTo(s.overworld(), d.getX() + 0.5, d.getY(), d.getZ() + 1.5, java.util.Set.of(), 180f, 0f, false);
						return d;
					}
				}
				return null;
			});
			check(door != null, "a door to try");
			ctx.waitTicks(2);
			check(server.computeOnServer(s -> Runt.mischief(player(s), door) && !DoorLocks.isLocked(s.overworld(), door)), "mischief: the locked door opens");
			// Level three, swap: a bot made Michael, trading places with him.
			String michael = server.computeOnServer(s -> s.getPlayerList().getPlayers().stream().filter(p -> p instanceof BotPlayer && MatchManager.role(p) == Role.CIVILIAN
				&& !p.getScoreboardName().equals(victim)).findFirst().orElseThrow().getScoreboardName());
			server.runCommand("hw role " + michael + " shape");
			ctx.waitTicks(5);
			server.runOnServer(s -> {
				Runt.State st = Runt.state(player(s));
				st.fear = HwConfig.get().runtLevelFear[2] + 1;
				st.level = 3;
				Runt.pick(player(s), 2, 1);
			});
			boolean swapped = server.computeOnServer(s -> {
				ServerPlayer runt = player(s);
				ServerPlayer m = s.getPlayerList().getPlayerByName(michael);
				m.teleportTo(s.overworld(), runt.getX() + 20, runt.getY(), runt.getZ(), java.util.Set.of(), 0f, 0f, false);
				var a = runt.position();
				var b = m.position();
				return Runt.special(runt) && runt.position().distanceTo(b) < 0.5 && m.position().distanceTo(a) < 0.5;
			});
			check(swapped, "swap: he and Michael trade places");
			// Michael knocked down: over on his back, and up again at the end.
			int michaelId = server.computeOnServer(s -> {
				ServerPlayer m = s.getPlayerList().getPlayerByName(michael);
				ServerPlayer p = player(s);
				BlockPos c = dev.halloween.game.MapPoints.get(s.overworld()).first(dev.halloween.game.MapPoints.Type.CENTER);
				m.teleportTo(s.overworld(), c.getX() + 0.5, c.getY(), c.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
				p.teleportTo(s.overworld(), c.getX() + 4.5, c.getY(), c.getZ() + 0.5, java.util.Set.of(), 90f, 20f, false);
				Fight.stun(m, 1f, null);
				return m.getId();
			});
			ctx.waitTicks(12);
			check(ctx.computeOnClient(mc -> dev.halloween.client.ClientAnims.fallAngle(michaelId) > 60f), "a stun puts Michael on his back");
			server.runCommand("time set noon");
			ctx.waitTicks(3);
			ctx.takeScreenshot("michael_down");
			server.runCommand("time set midnight");
			ctx.waitTicks(HwConfig.get().stunnedTicks + 10);
			check(ctx.computeOnClient(mc -> dev.halloween.client.ClientAnims.fallAngle(michaelId) == 0f), "and he gets back up when it ends");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> MatchManager.role(player(s)) == Role.NONE
				&& Math.abs(player(s).getAttributeValue(Attributes.SCALE) - 1.0) < 1e-3), "after the match he is himself again");

			// ---------------------------------------------------------------- a bot plays him
			server.runCommand("hw runt bot");
			server.runOnServer(s -> HwConfig.get().botsFillTo = 3);
			server.runCommand("hw start");
			ctx.waitTicks(40);
			check(server.computeOnServer(s -> Runt.all(s).size() == 1 && Runt.all(s).getFirst() instanceof BotPlayer), "nobody picked: a bot plays the Runt");
			// A look at him, close up, by day (held still for it).
			server.runCommand("time set noon");
			server.runOnServer(s -> {
				ServerPlayer runt = Runt.all(s).getFirst();
				Runt.state(runt).stunTicks = 60;
				runt.teleportTo(s.overworld(), runt.getX(), runt.getY(), runt.getZ(), java.util.Set.of(), 180f, 0f, false);
				runt.setYHeadRot(180f);
				runt.setYBodyRot(180f);
				ServerPlayer p = player(s);
				p.teleportTo(s.overworld(), runt.getX(), runt.getY(), runt.getZ() - 2.0, java.util.Set.of(), 0f, 25f, false);
			});
			ctx.waitTicks(10);
			ctx.takeScreenshot("runt_look");
			server.runCommand("time set midnight");
			int before = server.computeOnServer(s -> frights(s));
			int scares = 0;
			for (int i = 0; i < 60 && scares == 0; i++) {
				ctx.waitTicks(20);
				scares = server.computeOnServer(s -> frights(s)) - before;
			}
			LOG.info("Bot Runt frights: {}", scares);
			check(scares > 0, "the bot Runt finds survivors and frightens them by itself");
			// Its hands at your throat: the hands game opens; get them all off and it is thrown off.
			server.runOnServer(s -> {
				ServerPlayer runt = Runt.all(s).getFirst();
				Runt.State st = Runt.state(runt);
				st.level = Math.max(st.level, 1);
				if (st.picks[0] < 0) {
					st.picks[0] = 0;
				}
				st.picks[0] = 0;
				st.scareCooldown = 0;
				st.stunTicks = 0;
				st.holding = null;
				ServerPlayer p = player(s);
				runt.teleportTo(s.overworld(), p.getX() + 1.2, p.getY(), p.getZ(), java.util.Set.of(), 90f, 0f, false);
			});
			ctx.waitTicks(2);
			server.runOnServer(s -> pounceOnto(s, Runt.all(s).getFirst(), player(s)));
			ctx.waitTicks(3);
			check(server.computeOnServer(s -> Runt.heldBy(player(s)) != null), "the bot Runt pounces onto the player");
			boolean screen = false;
			for (int i = 0; i < 20 && !screen; i++) {
				ctx.waitTicks(2);
				screen = ctx.computeOnClient(mc -> mc.gui.screen() instanceof dev.halloween.client.HandsScreen);
			}
			check(screen, "the hands game opens");
			ctx.takeScreenshot("runt_hands");
			ctx.runOnClient(mc -> {
				if (mc.gui.screen() instanceof dev.halloween.client.HandsScreen hands) {
					for (int i = 0; i < dev.halloween.client.HandsScreen.HANDS; i++) {
						hands.throwOff(i);
					}
				}
			});
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> Runt.heldBy(player(s)) == null && Runt.state(Runt.all(s).getFirst()).stunTicks > 0),
				"all the hands off: it is thrown off and floored");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> Runt.all(s).isEmpty() && s.getPlayerList().getPlayers().stream().noneMatch(p -> p.getScoreboardName().equals(Runt.BOT_NAME))),
				"and goes when the match stops");

			// ---------------------------------------------------------------- Michael's creature: orders
			server.runOnServer(s -> HwConfig.get().botsFillTo = 2);
			server.runCommand("hw start @p");
			ctx.waitTicks(60);
			check(server.computeOnServer(s -> MatchManager.role(player(s)) == Role.SHAPE && Runt.all(s).size() == 1), "the player is Michael, a bot his Runt");
			check(server.computeOnServer(s -> Runt.statusOf(Runt.all(s).getFirst())) == 0, "no orders: at his heels");
			server.runOnServer(s -> {
				ServerPlayer m = player(s);
				BlockPos c = dev.halloween.game.MapPoints.get(s.overworld()).first(dev.halloween.game.MapPoints.Type.CENTER);
				m.teleportTo(s.overworld(), c.getX() + 0.5, c.getY(), c.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
			});
			boolean heeled = false;
			for (int i = 0; i < 30 && !heeled; i++) {
				ctx.waitTicks(10);
				heeled = server.computeOnServer(s -> Runt.all(s).getFirst().distanceTo(player(s)) < 7);
			}
			check(heeled, "it follows him about");
			// The order wheel: go for that one.
			int prey = server.computeOnServer(s -> s.getPlayerList().getPlayers().stream().filter(p -> MatchManager.role(p) == Role.CIVILIAN).findFirst().orElseThrow().getId());
			ctx.runOnClient(mc -> mc.gui.setScreen(new dev.halloween.client.RuntOrderScreen(prey, mc.player.blockPosition())));
			ctx.waitTicks(5);
			ctx.takeScreenshot("runt_orders");
			ctx.runOnClient(mc -> {
				if (mc.gui.screen() instanceof dev.halloween.client.RuntOrderScreen wheel) {
					wheel.choose(1);
				}
			});
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> {
				int st = Runt.statusOf(Runt.all(s).getFirst());
				return st == 2 || st == 5;
			}), "\"attack\": it goes for that one");
			ctx.runOnClient(mc -> mc.gui.setScreen(new dev.halloween.client.RuntOrderScreen(-1, mc.player.blockPosition())));
			ctx.waitTicks(3);
			ctx.runOnClient(mc -> {
				if (mc.gui.screen() instanceof dev.halloween.client.RuntOrderScreen wheel) {
					wheel.choose(3);
				}
			});
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> {
				int st = Runt.statusOf(Runt.all(s).getFirst());
				return st == 0 || st >= 5;
			}), "\"heel\": back to him");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds;
				HwConfig.get().botsFillTo = new HwConfig().botsFillTo;
			});
		}
		LOG.info("Runt client test finished OK");
	}

	/** Frights and pounces so far. */
	private static int frights(MinecraftServer s) {
		Map<String, Integer> all = AiStats.snapshot().getOrDefault("*total", Map.of());
		return all.getOrDefault("runt.scare", 0) + all.getOrDefault("runt.pounce", 0);
	}

	/** Puts the Runt right on the survivor in the middle of a leap (the hit is worked out on the next tick). */
	private static void pounceOnto(MinecraftServer s, ServerPlayer runt, ServerPlayer bot) {
		runt.teleportTo(s.overworld(), bot.getX() + 0.3, bot.getY() + 0.3, bot.getZ(), java.util.Set.of(), 90f, 0f, false);
		Runt.state(runt).leapTicks = 1;
	}

	private static ServerPlayer player(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!(p instanceof BotPlayer)) {
				return p;
			}
		}
		throw new IllegalStateException("no player");
	}

	private static ServerPlayer civilian(MinecraftServer server) {
		return server.getPlayerList().getPlayers().stream().filter(p -> p instanceof BotPlayer && MatchManager.role(p) == Role.CIVILIAN).findFirst().orElseThrow();
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
