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
			// Clinging on: the survivor can't go anywhere until they shake him off (a bot shoves and shoves).
			server.runOnServer(s -> {
				Runt.State st = Runt.state(player(s));
				st.scareCooldown = 0;
				Runt.scare(player(s), s.getPlayerList().getPlayerByName(victim));
			});
			check(server.computeOnServer(s -> Runt.state(player(s)).holding != null), "with that gift the fright ends on their back");
			boolean shaken = false;
			for (int i = 0; i < 20 && !shaken; i++) {
				ctx.waitTicks(5);
				shaken = server.computeOnServer(s -> Runt.state(player(s)).holding == null);
			}
			check(shaken && server.computeOnServer(s -> Runt.state(player(s)).stunTicks > 0 || Runt.state(player(s)).holding == null),
				"the survivor shakes him off");
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
				Stamina.set(bot, 1f);
				return Fight.survivorPush(bot) && Runt.state(runt).stunTicks > 0;
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
			int before = server.computeOnServer(s -> AiStats.snapshot().getOrDefault("*total", Map.of()).getOrDefault("runt.scare", 0));
			int scares = 0;
			for (int i = 0; i < 60 && scares == 0; i++) {
				ctx.waitTicks(20);
				scares = server.computeOnServer(s -> AiStats.snapshot().getOrDefault("*total", Map.of()).getOrDefault("runt.scare", 0)) - before;
			}
			LOG.info("Bot Runt frights: {}", scares);
			check(scares > 0, "the bot Runt finds survivors and frightens them by itself");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> Runt.all(s).isEmpty() && s.getPlayerList().getPlayers().stream().noneMatch(p -> p.getScoreboardName().equals(Runt.BOT_NAME))),
				"and goes when the match stops");
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds;
				HwConfig.get().botsFillTo = new HwConfig().botsFillTo;
			});
		}
		LOG.info("Runt client test finished OK");
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
