package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.game.AiStats;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Bots;
import dev.halloween.game.DoorLocks;
import dev.halloween.game.Exits;
import dev.halloween.game.Gear;
import dev.halloween.game.Guns;
import dev.halloween.game.HwItems;
import dev.halloween.game.Inventory;
import dev.halloween.game.Lockers;
import dev.halloween.game.Match;
import dev.halloween.game.MatchManager;
import dev.halloween.game.Police;
import dev.halloween.game.ShapeState;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The things survivors find, and the police, with the player as the Shape and bots as civilians:
 * officers run to pull someone out of his hands and get through locked doors on the way; a knife breaks
 * a grab and the amulet stops one; a brick, firecrackers, the hourglass, a road flare and the shotgun
 * work on him.
 */
public class GearClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-gear-test");

	private BlockPos origin;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("gear")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			this.origin = server.computeOnServer(s -> player(s).blockPosition());
			HwConfig defaults = new HwConfig();
			server.runOnServer(s -> {
				HwConfig cfg = HwConfig.get();
				cfg.countdownSeconds = 2;
				// The bots are the props here: they don't win grabs by themselves.
				cfg.botQteSuccessChance = 0f;
			});
			server.runCommand("hw point clear npc");
			server.runCommand("hw start @p");
			ctx.waitTicks(60);
			check(server.computeOnServer(s -> Match.running() && MatchManager.role(player(s)) == Role.SHAPE), "the player is the Shape");
			// Nobody leaves early: no exits this time.
			server.runOnServer(s -> Exits.clear(s.overworld()));
			this.tp(server, 30.5, 0, -30.5);

			this.lookOfGuns(ctx, server);
			this.policeHelp(ctx, server);
			this.policeLockedDoor(ctx, server);
			this.stabAndAmulet(ctx, server);
			this.throwing(ctx, server);
			this.shotgun(ctx, server);

			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> {
				HwConfig cfg = HwConfig.get();
				cfg.botQteSuccessChance = defaults.botQteSuccessChance;
				cfg.victimPointsToEscape = defaults.victimPointsToEscape;
			});
		}
		LOG.info("Gear client test finished OK");
	}

	// ---------------------------------------------------------------- police

	/** An officer and a bot side on in front of the Shape's camera, guns in hand (third-person models). */
	private void lookOfGuns(ClientGameTestContext ctx, TestServerContext server) {
		int officerId = server.computeOnServer(s -> {
			ServerPlayer shape = player(s);
			Vec3 at = this.at(10.5, 0, -10.5);
			shape.teleportTo(s.overworld(), at.x, at.y, at.z, java.util.Set.of(), 180f, 10f, false);
			var officer = Police.spawnOfficer(s.overworld(), at.add(0.8, 0, -4));
			officer.setYRot(90f);
			officer.setYHeadRot(90f);
			officer.setYBodyRot(90f);
			BotPlayer bot = bot(s, 0);
			bot.getInventory().clearContent();
			Inventory.give(bot, HwItems.create(HwItems.Kind.SHOTGUN));
			bot.getInventory().setSelectedSlot(0);
			bot.teleportTo(s.overworld(), at.x - 1.2, at.y, at.z - 4, java.util.Set.of(), -90f, 0f, false);
			return officer.getId();
		});
		ctx.waitTicks(4);
		ctx.takeScreenshot("g00_guns_in_hand");
		server.runOnServer(s -> {
			Police.clear(s);
			bot(s, 0).getInventory().clearContent();
		});
		this.tp(server, 30.5, 0, -30.5);
	}

	/** The Shape holds a bot; an officer some way off (looking elsewhere) runs over and gets him off. */
	private void policeHelp(ClientGameTestContext ctx, TestServerContext server) {
		server.runOnServer(s -> HwConfig.get().victimPointsToEscape = 99);
		int officerId = server.computeOnServer(s -> {
			BotPlayer bot = bot(s, 0);
			ServerPlayer shape = player(s);
			Vec3 at = this.at(10.5, 0, -10.5);
			shape.teleportTo(s.overworld(), at.x, at.y, at.z, java.util.Set.of(), 180f, 0f, false);
			bot.getInventory().clearContent();
			bot.teleportTo(s.overworld(), at.x, at.y, at.z - 1.2, java.util.Set.of(), 0f, 0f, false);
			ShapeState st = MatchManager.shapeState(shape);
			st.stun = 0;
			st.stunnedTicks = 0;
			if (!MatchManager.grabOut(shape, bot)) {
				return -1;
			}
			var officer = Police.spawnOfficer(s.overworld(), this.at(10.5, 0, 4.5));
			// Looking away: it hears the struggle rather than sees it.
			officer.setYRot(0f);
			officer.setYHeadRot(0f);
			officer.setYBodyRot(0f);
			return officer.getId();
		});
		check(officerId >= 0, "the Shape gets hold of a bot");
		boolean stunned = false;
		for (int i = 0; i < 80 && !stunned; i++) {
			ctx.waitTicks(5);
			stunned = server.computeOnServer(s -> MatchManager.shapeState(player(s)).stunnedTicks > 0);
		}
		Map<String, Integer> stats = server.computeOnServer(s -> AiStats.snapshot().getOrDefault("*total", Map.of()));
		LOG.info("Police help stats: {}", stats);
		check(stats.getOrDefault("police.help", 0) > 0, "an officer within 20 blocks runs to help the one he holds");
		check(stunned, "and knocks him down, which frees them");
		ctx.takeScreenshot("g01_police_help");
		server.runOnServer(s -> {
			Police.clear(s);
			ShapeState st = MatchManager.shapeState(player(s));
			st.stunnedTicks = 0;
			st.stun = 0;
			HwConfig.get().victimPointsToEscape = new HwConfig().victimPointsToEscape;
		});
		this.tp(server, 30.5, 0, -30.5);
	}

	/** An officer inside a house with the front door locked gets out to the one it guards. */
	private void policeLockedDoor(ClientGameTestContext ctx, TestServerContext server) {
		BlockPos door = this.origin.offset(0, 0, 8);
		int officerId = server.computeOnServer(s -> {
			DoorLocks.forceLock(s.overworld(), door);
			var officer = Police.spawnOfficer(s.overworld(), this.at(0.5, 0, 12.5));
			BotPlayer bot = bot(s, 0);
			bot.teleportTo(s.overworld(), this.origin.getX() + 0.5, this.origin.getY(), this.origin.getZ() + 3.5, java.util.Set.of(), 0f, 0f, false);
			Police.guard(officer, bot);
			return officer.getId();
		});
		check(server.computeOnServer(s -> DoorLocks.isLocked(s.overworld(), door)), "the house door is locked");
		boolean out = false;
		for (int i = 0; i < 40 && !out; i++) {
			ctx.waitTicks(10);
			out = server.computeOnServer(s -> {
				var officer = s.overworld().getEntity(officerId);
				return officer != null && officer.getZ() < this.origin.getZ() + 7.5;
			});
		}
		LOG.info("Officer at {}", (Object) server.computeOnServer(s -> String.valueOf(s.overworld().getEntity(officerId).position())));
		check(!server.computeOnServer(s -> DoorLocks.isLocked(s.overworld(), door)), "the officer unlocks the door");
		check(out, "and walks out of the house through it");
		ctx.takeScreenshot("g02_police_door");
		server.runOnServer(Police::clear);
	}

	// ---------------------------------------------------------------- grab: knife and amulet

	private void stabAndAmulet(ClientGameTestContext ctx, TestServerContext server) {
		String grabbed = server.computeOnServer(s -> {
			BotPlayer bot = bot(s, 0);
			ServerPlayer shape = player(s);
			Vec3 at = this.at(10.5, 0, -10.5);
			shape.teleportTo(s.overworld(), at.x, at.y, at.z, java.util.Set.of(), 180f, 0f, false);
			bot.getInventory().clearContent();
			Inventory.give(bot, HwItems.create(HwItems.Kind.POCKET_KNIFE));
			bot.teleportTo(s.overworld(), at.x, at.y, at.z - 1.2, java.util.Set.of(), 0f, 0f, false);
			MatchManager.shapeState(shape).grabCooldown = 0;
			return MatchManager.grabOut(shape, bot) ? bot.getUUID().toString() : "no grab";
		});
		check(!"no grab".equals(grabbed), "the Shape grabs a bot with a pocket knife");
		java.util.UUID stabber = java.util.UUID.fromString(grabbed);
		ctx.waitTicks(HwConfig.get().botStabTicks + 15);
		check(server.computeOnServer(s -> MatchManager.shapeState(player(s)).grab == null), "a knife in the pockets breaks the grab");
		check(!server.computeOnServer(s -> Inventory.has(s.getPlayerList().getPlayer(stabber), HwItems.Kind.POCKET_KNIFE)), "and the knife is gone");

		String amulet = server.computeOnServer(s -> {
			BotPlayer bot = bot(s, 0);
			ServerPlayer shape = player(s);
			ShapeState st = MatchManager.shapeState(shape);
			st.recoverTicks = 0;
			st.grabCooldown = 0;
			st.blindTicks = 0;
			bot.getInventory().clearContent();
			Inventory.give(bot, HwItems.create(HwItems.Kind.AMULET));
			bot.teleportTo(s.overworld(), shape.getX(), shape.getY(), shape.getZ() - 1.2, java.util.Set.of(), 0f, 0f, false);
			boolean caught = MatchManager.grabOut(shape, bot);
			return !caught && !Inventory.has(bot, HwItems.Kind.AMULET) && st.blindTicks > 0 ? "ok"
				: String.format(Locale.ROOT, "caught %s, amulet %s, blind %d", caught, Inventory.has(bot, HwItems.Kind.AMULET), st.blindTicks);
		});
		check("ok".equals(amulet), "the amulet stops a grab, blinds him and crumbles (" + amulet + ")");
		server.runOnServer(s -> {
			ShapeState st = MatchManager.shapeState(player(s));
			st.recoverTicks = 0;
			st.blindTicks = 0;
		});
	}

	// ---------------------------------------------------------------- throwing

	private void throwing(ClientGameTestContext ctx, TestServerContext server) {
		// A brick at him: some stun and a stagger.
		this.throwAt(server, HwItems.Kind.BRICK, 5, 1.2);
		ctx.waitTicks(15);
		check(server.computeOnServer(s -> AiStats.snapshot().getOrDefault("*total", Map.of()).getOrDefault("throw.hit", 0)) > 0
			&& server.computeOnServer(s -> MatchManager.shapeState(player(s)).stun > 0.1f), "a thrown brick hits the Shape and stuns him a bit");

		// The hourglass at him while he is down keeps him down.
		server.runOnServer(s -> {
			ShapeState st = MatchManager.shapeState(player(s));
			st.stun = 0;
			st.stunnedTicks = 60;
		});
		this.throwAt(server, HwItems.Kind.HOURGLASS, 4, 1.0);
		ctx.waitTicks(10);
		int down = server.computeOnServer(s -> MatchManager.shapeState(player(s)).stunnedTicks);
		check(down > 60, "the hourglass thrown at him while he is down keeps him down longer (" + down + ")");
		server.runOnServer(s -> MatchManager.shapeState(player(s)).stunnedTicks = 0);

		// Firecrackers at his feet go off after a moment.
		server.runOnServer(s -> MatchManager.shapeState(player(s)).stun = 0);
		this.throwAt(server, HwItems.Kind.FIRECRACKERS, 4, 0.1);
		ctx.waitTicks(HwConfig.get().firecrackerFuseTicks + 15);
		check(server.computeOnServer(s -> AiStats.snapshot().getOrDefault("*total", Map.of()).getOrDefault("firecracker.hit", 0)) > 0,
			"firecrackers going off by him stun him");
		server.runOnServer(s -> {
			ShapeState st = MatchManager.shapeState(player(s));
			st.stun = 0;
			st.stunnedTicks = 0;
			st.blindTicks = 0;
		});

		// A road flare burns where it lands: real light.
		Vec3 from = server.computeOnServer(s -> {
			BotPlayer bot = bot(s, 0);
			Vec3 at = this.at(-12.5, 0, -12.5);
			bot.teleportTo(s.overworld(), at.x, at.y, at.z, java.util.Set.of(), 0f, 30f, false);
			bot.getInventory().clearContent();
			Inventory.give(bot, HwItems.create(HwItems.Kind.ROAD_FLARE));
			Gear.throwItem(bot, bot.getInventory().getItem(0), HwItems.Kind.ROAD_FLARE);
			return at;
		});
		ctx.waitTicks(30);
		long lights = server.computeOnServer(s -> BlockPos.betweenClosedStream(BlockPos.containing(from).offset(-8, -1, -8), BlockPos.containing(from).offset(8, 3, 8))
			.filter(p -> s.overworld().getBlockState(p).is(Blocks.LIGHT)).count());
		check(lights > 0, "a thrown road flare lights up where it lands");
		this.tp(server, -12.5, 0, -16.5);
		ctx.waitTicks(5);
		ctx.takeScreenshot("g03_road_flare");
		this.tp(server, 30.5, 0, -30.5);
	}

	/** A bot throws {@code kind} at the Shape from {@code dist} blocks, aiming {@code height} above his feet. */
	private void throwAt(TestServerContext server, HwItems.Kind kind, double dist, double height) {
		server.runOnServer(s -> {
			BotPlayer bot = bot(s, 0);
			ServerPlayer shape = player(s);
			Vec3 at = this.at(10.5, 0, -10.5);
			shape.teleportTo(s.overworld(), at.x, at.y, at.z, java.util.Set.of(), 180f, 0f, false);
			bot.teleportTo(s.overworld(), at.x, at.y, at.z + dist, java.util.Set.of(), 180f, 0f, false);
			bot.getInventory().clearContent();
			Inventory.give(bot, HwItems.create(kind));
			face(bot, at.add(0, height + dist * 0.04, 0));
			Gear.throwItem(bot, bot.getInventory().getItem(0), kind);
		});
	}

	private void shotgun(ClientGameTestContext ctx, TestServerContext server) {
		String shot = server.computeOnServer(s -> {
			BotPlayer bot = bot(s, 0);
			ServerPlayer shape = player(s);
			Vec3 at = this.at(10.5, 0, -10.5);
			shape.teleportTo(s.overworld(), at.x, at.y, at.z, java.util.Set.of(), 180f, 0f, false);
			bot.teleportTo(s.overworld(), at.x, at.y, at.z + 4, java.util.Set.of(), 180f, 0f, false);
			ShapeState st = MatchManager.shapeState(shape);
			st.stun = 0;
			st.stunnedTicks = 0;
			Vec3 eye = bot.getEyePosition();
			Vec3 target = dev.halloween.game.FocusPoints.bodyCenter(shape);
			Guns.shoot(bot, HwItems.Kind.SHOTGUN, eye, target.subtract(eye), 3f, bot);
			return st.stun >= 0.6f || st.stunnedTicks > 0 ? "ok" : String.format(Locale.ROOT, "stun %.2f", st.stun);
		});
		check("ok".equals(shot), "a shotgun blast up close nearly knocks him down (" + shot + ")");
		check(HwItems.create(HwItems.Kind.SHOTGUN) != null && Guns.capacity(HwItems.Kind.SHOTGUN) == HwConfig.get().shotgunCapacity,
			"the shotgun holds " + HwConfig.get().shotgunCapacity + " shells");
	}

	// ---------------------------------------------------------------- helpers

	/** The {@code n}-th bot still in play. */
	private static BotPlayer bot(MinecraftServer server, int n) {
		List<BotPlayer> bots = Bots.all().stream().filter(b -> !b.isSpectator() && !Lockers.isInside(b) && b.isAlive()
			&& MatchManager.role(b) == Role.CIVILIAN && !MatchManager.isGrabbed(b)).toList();
		if (bots.isEmpty()) {
			throw new AssertionError("FAILED: no bot left in play");
		}
		return bots.get(n % bots.size());
	}

	private static void face(LivingEntity e, Vec3 target) {
		Vec3 d = target.subtract(e.getEyePosition());
		float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
		float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
		e.setYRot(yaw);
		e.setYHeadRot(yaw);
		e.setYBodyRot(yaw);
		e.setXRot(pitch);
	}

	private Vec3 at(double dx, double dy, double dz) {
		return new Vec3(this.origin.getX() + dx, this.origin.getY() + dy, this.origin.getZ() + dz);
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
		Vec3 pos = this.at(dx, dy, dz);
		server.runOnServer(s -> player(s).teleportTo(s.overworld(), pos.x, pos.y, pos.z, java.util.Set.of(), 0f, 0f, false));
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
