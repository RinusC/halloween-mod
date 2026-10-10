package dev.halloween.test;

import dev.halloween.HwBlocks;
import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.client.ClientEvents;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Events;
import dev.halloween.game.HwItems;
import dev.halloween.game.LightSystem;
import dev.halloween.game.MapJob;
import dev.halloween.game.MapPoints;
import dev.halloween.game.Match;
import dev.halloween.game.MatchManager;
import dev.halloween.game.Police;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Match events in the second town: each one happens when called and does what it should (Loomis's call, the
 * bag, the patrol, the blackout, Samhain, the party, the fire), and Midnight comes at the end.
 */
public class EventsClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-events");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("events")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			ctx.runOnClient(mc -> mc.options.renderDistance().set(12));
			server.runCommand("execute as @p at @p run hw map town");
			ctx.waitTicks(2);
			boolean busy = true;
			for (int i = 0; i < 3000 && busy; i++) {
				ctx.waitTicks(10);
				busy = server.computeOnServer(s -> MapJob.busy());
			}
			check(!busy, "the town gets built");
			server.runOnServer(s -> {
				HwConfig cfg = HwConfig.get();
				cfg.countdownSeconds = 1;
				cfg.botsFillTo = 3;
				cfg.eventsEnabled = false;
				cfg.midnightEnabled = false;
				cfg.eventAnnounceSeconds = 1;
				// Everyone at home goes to the party, wherever they live (so the test doesn't depend on the dice).
				cfg.partyGatherShare = 1f;
				cfg.partyGatherRange = 1000;
			});
			server.runCommand("hw start @p");
			boolean running = false;
			for (int i = 0; i < 40 && !running; i++) {
				ctx.waitTicks(10);
				running = server.computeOnServer(s -> Match.running());
			}
			check(running && server.computeOnServer(s -> MatchManager.role(player(s)) == Role.SHAPE), "a match with the player as Michael");
			check(server.computeOnServer(s -> Events.lead()) == 0, "nobody is ahead at the start");

			// ---------------------------------------------------------------- Loomis's call
			check(this.start(ctx, server, "loomis"), "Loomis calls");
			BlockPos phone = server.computeOnServer(s -> Events.objects().getFirst());
			check(server.computeOnServer(s -> player(s).position().distanceTo(Vec3.atCenterOf(phone)) >= HwConfig.get().loomisMinDistance),
				"on a phone far from Michael");
			String bot = server.computeOnServer(s -> civilian(s).getScoreboardName());
			check(server.computeOnServer(s -> Events.finishFor(s.getPlayerList().getPlayerByName(bot), phone)), "a survivor answers");
			ctx.waitTicks(2);
			check(server.computeOnServer(s -> Events.glows(true).contains(player(s).getId()) && Events.running() == null),
				"the survivors see Michael for a while, and the call is over");

			// ---------------------------------------------------------------- the forgotten bag
			check(this.start(ctx, server, "bag"), "a bag turns up in the street");
			BlockPos bag = server.computeOnServer(s -> Events.objects().getFirst());
			check(server.computeOnServer(s -> s.overworld().getBlockState(bag).is(HwBlocks.CARDBOARD_BOX)), "there it lies");
			check(ctx.computeOnClient(mc -> ClientEvents.state.marks().stream().anyMatch(m -> m.kind() == dev.halloween.net.HwNet.EventMark.BAG)),
				"Michael sees where too");
			int before = server.computeOnServer(s -> items(s.getPlayerList().getPlayerByName(bot)));
			check(server.computeOnServer(s -> Events.finishFor(s.getPlayerList().getPlayerByName(bot), bag)), "a survivor empties it");
			ctx.waitTicks(2);
			check(server.computeOnServer(s -> items(s.getPlayerList().getPlayerByName(bot))) > before, "and has more things");
			check(server.computeOnServer(s -> !s.overworld().getBlockState(bag).is(HwBlocks.CARDBOARD_BOX) && Events.running() == null), "the bag is gone");

			// ---------------------------------------------------------------- Brackett's patrol
			check(this.start(ctx, server, "patrol"), "the patrol sets off");
			Vec3 from = server.computeOnServer(s -> Events.patrolPosition());
			check(from != null && server.computeOnServer(s -> s.overworld().getEntities(EntityTypes.BLOCK_DISPLAY, e -> e.entityTags().contains("hw_patrol")).size() >= 10),
				"a car of a dozen pieces");
			ctx.waitTicks(80);
			Vec3 later = server.computeOnServer(s -> Events.patrolPosition());
			LOG.info("Patrol from {} to {}", from, later);
			check(later != null && later.distanceTo(from) > 6, "it drives");
			// Michael stands in its headlights.
			server.runOnServer(s -> {
				Vec3 at = Events.patrolPosition();
				Vec3 h = Events.patrolHeading();
				Vec3 ahead = at.add(h.scale(9));
				// Facing the car coming.
				float yaw = (float) Math.toDegrees(Math.atan2(h.x, -h.z));
				player(s).teleportTo(s.overworld(), ahead.x, at.y, ahead.z, java.util.Set.of(), yaw, 15f, false);
			});
			boolean seen = false;
			for (int i = 0; i < 20 && !seen; i++) {
				ctx.waitTicks(5);
				seen = server.computeOnServer(s -> Police.wanted() > 0);
			}
			check(seen, "Michael in its headlights: the wanted level goes up");
			ctx.takeScreenshot("event_patrol");
			int officers = server.computeOnServer(s -> Police.officers());
			check(server.computeOnServer(s -> {
				ServerPlayer b = s.getPlayerList().getPlayerByName(bot);
				Vec3 at = Events.patrolPosition();
				b.teleportTo(s.overworld(), at.x + 1, at.y, at.z + 1, java.util.Set.of(), 0f, 0f, false);
				var hitbox = s.overworld().getEntities(EntityTypes.INTERACTION, e -> e.entityTags().contains("hw_patrol")).getFirst();
				Events.onUseEntity(b, hitbox);
				return Police.officers() == officers + 1;
			}), "waved down, it leaves an officer");
			check(server.computeOnServer(s -> Police.wards(s.overworld()).stream().anyMatch(w -> w.endsWith(bot))), "who looks after them");

			// ---------------------------------------------------------------- blackout
			int on = server.computeOnServer(s -> (int) breakers(s).stream().filter(b -> LightSystem.isOn(s.overworld(), b)).count());
			check(on > 0, "lights on in town");
			check(this.start(ctx, server, "blackout"), "a blackout");
			check(server.computeOnServer(s -> breakers(s).stream().noneMatch(b -> LightSystem.isOn(s.overworld(), b))), "every breaker is off");
			int dead = server.computeOnServer(s -> deadLanterns(s));
			LOG.info("Dead lanterns round the middle: {}", dead);
			check(dead > 0, "the street lamps are out");
			server.runOnServer(s -> {
				BlockPos box = Events.objects().getFirst();
				player(s).teleportTo(s.overworld(), box.getX() + 3.5, box.getY(), box.getZ() + 0.5, java.util.Set.of(), 90f, 10f, false);
			});
			ctx.waitTicks(20);
			ctx.takeScreenshot("event_blackout");
			check(server.computeOnServer(s -> Events.finishFor(s.getPlayerList().getPlayerByName(bot), Events.objects().getFirst())), "a survivor mends the transformer");
			ctx.waitTicks(2);
			check(server.computeOnServer(s -> Events.running() == null && breakers(s).stream().filter(b -> LightSystem.isOn(s.overworld(), b)).count() >= on
				&& deadLanterns(s) == 0), "the lights come back");

			// ---------------------------------------------------------------- Samhain
			check(this.start(ctx, server, "samhain"), "Samhain night");
			check(server.computeOnServer(s -> Events.stalkFactor() > 1f && Events.noFatigue()), "stalking goes faster, the Sense doesn't tire");
			ctx.waitTicks(50);
			check(ctx.computeOnClient(mc -> ClientEvents.samhain() && ClientEvents.redness() > 0.5f), "the night turns red");
			ctx.takeScreenshot("event_samhain");

			// ---------------------------------------------------------------- the party
			check(this.start(ctx, server, "party"), "a party");
			check(server.computeOnServer(s -> Events.muffled(Vec3.atCenterOf(Events.partyHouse())) && !Events.muffled(Vec3.atCenterOf(Events.partyHouse()).add(60, 0, 0))),
				"its music drowns everything near it, and only near it");
			check(server.computeOnServer(s -> dev.halloween.game.AiStats.snapshot().getOrDefault("*total", java.util.Map.of()).getOrDefault("npc.party", 0) > 0),
				"townsfolk go over");

			// ---------------------------------------------------------------- the fire
			server.runOnServer(s -> {
				// Everyone out of the way, so a house can burn.
				BlockPos lair = MapPoints.get(s.overworld()).first(MapPoints.Type.SPAWN_SHAPE);
				for (ServerPlayer p : s.getPlayerList().getPlayers()) {
					p.teleportTo(s.overworld(), lair.getX() + 0.5, lair.getY(), lair.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
				}
			});
			ctx.waitTicks(5);
			check(this.start(ctx, server, "fire"), "a house catches fire");
			check(server.computeOnServer(s -> fires(s, Events.fireHouse())) >= 5, "flames on it and in it");
			check(server.computeOnServer(s -> {
				Vec3 c = Vec3.atCenterOf(Events.fireHouse()).add(0, 2, 0);
				return Events.smokeBetween(c.add(-10, 0, 0), c.add(10, 0, 0)) && !Events.smokeBetween(c.add(-10, 0, 30), c.add(10, 0, 30));
			}), "the smoke hides what is behind it");
			BlockPos crate = server.computeOnServer(s -> Events.objects().isEmpty() ? null : Events.objects().getFirst());
			check(crate != null, "something lies in there");
			server.runOnServer(s -> {
				// Out in the open some way off, looking at the house.
				BlockPos h = Events.fireHouse();
				for (int a = 0; a < 360; a += 20) {
					double r = Math.toRadians(a);
					BlockPos p = dev.halloween.game.Nav.snap(s.overworld(), h.offset((int) Math.round(Math.cos(r) * 15), 0, (int) Math.round(Math.sin(r) * 15)));
					if (dev.halloween.game.Nav.standable(s.overworld(), p) && s.overworld().canSeeSky(p)) {
						double dx = h.getX() - p.getX();
						double dz = h.getZ() - p.getZ();
						float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
						player(s).teleportTo(s.overworld(), p.getX() + 0.5, p.getY(), p.getZ() + 0.5, java.util.Set.of(), yaw, -15f, false);
						break;
					}
				}
			});
			ctx.waitTicks(30);
			ctx.takeScreenshot("event_fire");
			check(server.computeOnServer(s -> {
				ServerPlayer b = s.getPlayerList().getPlayerByName(bot);
				Events.finishFor(b, crate);
				for (int i = 0; i < b.getInventory().getContainerSize(); i++) {
					HwItems.Kind k = HwItems.kindOf(b.getInventory().getItem(i));
					if (k != null && k.escape()) {
						return true;
					}
				}
				return false;
			}), "whoever goes in comes out with something for getting out");
			check(server.computeOnServer(s -> {
				ServerPlayer b = s.getPlayerList().getPlayerByName(bot);
				b.setHealth(2f);
				b.hurtServer(s.overworld(), s.overworld().damageSources().inFire(), 6f);
				return b.isAlive() && !b.isSpectator() && b.getHealth() >= 1f;
			}), "the fire burns but doesn't kill");
			BlockPos burnt = server.computeOnServer(s -> Events.fireHouse());
			server.runCommand("hw event samhain");
			ctx.waitTicks(3);
			check(server.computeOnServer(s -> fires(s, burnt)) == 0, "when it ends, the flames go");

			// ---------------------------------------------------------------- midnight
			server.runOnServer(s -> {
				HwConfig.get().midnightEnabled = true;
				Match.forceTimeLeft(HwConfig.get().midnightSeconds);
			});
			boolean shown = false;
			for (int i = 0; i < 30 && !shown; i++) {
				ctx.waitTicks(5);
				shown = ctx.computeOnClient(mc -> (ClientEvents.state.flags() & dev.halloween.net.HwNet.EventSync.MIDNIGHT) != 0 && !ClientEvents.state.glows().isEmpty());
			}
			check(shown, "midnight: Michael sees every survivor");

			server.runCommand("hw stop");
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> Events.running() == null && s.overworld().getEntities(EntityTypes.BLOCK_DISPLAY, e -> e.entityTags().contains("hw_patrol")).isEmpty()),
				"after the match nothing is left of them");
			server.runOnServer(s -> {
				HwConfig fresh = new HwConfig();
				HwConfig cfg = HwConfig.get();
				cfg.countdownSeconds = fresh.countdownSeconds;
				cfg.botsFillTo = fresh.botsFillTo;
				cfg.eventsEnabled = fresh.eventsEnabled;
				cfg.midnightEnabled = fresh.midnightEnabled;
				cfg.eventAnnounceSeconds = fresh.eventAnnounceSeconds;
				cfg.partyGatherShare = fresh.partyGatherShare;
				cfg.partyGatherRange = fresh.partyGatherRange;
			});
		}
		LOG.info("Events client test finished OK");
	}

	/** Calls an event and waits for it to begin. */
	private boolean start(ClientGameTestContext ctx, TestServerContext server, String id) {
		boolean ok = server.computeOnServer(s -> Events.trigger(s.overworld(), id));
		if (!ok) {
			LOG.warn("Event {} could not start", id);
			return false;
		}
		for (int i = 0; i < 20; i++) {
			ctx.waitTicks(5);
			if (id.equals(server.computeOnServer(s -> Events.running()))) {
				ctx.waitTicks(11);
				return true;
			}
		}
		return false;
	}

	private static List<BlockPos> breakers(MinecraftServer s) {
		BlockPos center = MapPoints.get(s.overworld()).first(MapPoints.Type.CENTER);
		return LightSystem.breakers(s.overworld(), center, dev.halloween.game.Loot.radius());
	}

	private static int deadLanterns(MinecraftServer s) {
		ServerLevel level = s.overworld();
		BlockPos center = MapPoints.get(level).first(MapPoints.Type.CENTER);
		int n = 0;
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-60, -2, -60), center.offset(60, 6, 60))) {
			n += level.getBlockState(p).is(HwBlocks.DEAD_LANTERN) ? 1 : 0;
		}
		return n;
	}

	private static int fires(MinecraftServer s, BlockPos house) {
		int n = 0;
		for (BlockPos p : BlockPos.betweenClosed(house.offset(-8, -4, -8), house.offset(8, 17, 8))) {
			n += s.overworld().getBlockState(p).getBlock() instanceof BaseFireBlock ? 1 : 0;
		}
		return n;
	}

	private static int items(ServerPlayer p) {
		int n = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			n += p.getInventory().getItem(i).isEmpty() ? 0 : 1;
		}
		return n;
	}

	private static ServerPlayer civilian(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p instanceof BotPlayer && MatchManager.role(p) == Role.CIVILIAN) {
				return p;
			}
		}
		throw new IllegalStateException("no civilian bot");
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
