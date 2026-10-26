package dev.halloween.test;

import dev.halloween.HwBlocks;
import dev.halloween.HwConfig;
import dev.halloween.client.ClientDarkness;
import dev.halloween.client.KillCam;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.LockerBlock;
import dev.halloween.game.Lockers;
import dev.halloween.game.MatchManager;
import dev.halloween.game.VecnaCurse;
import dev.halloween.game.VecnaKill;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vecna's kills (game.VecnaKill): lifted and run through by the vines, dragged into the ground, pulled out of a
 * wardrobe by the vines and killed against it. Each in a match of its own on a platform up in the air; screenshots
 * through the kill's own camera at the moments that matter, and the body left at the end.
 *   HW_ONLY=vecnakill (HW_SCENE=ascend|drag|locker for one of them)
 */
public class VecnaKillClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-vecna-kill");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("vecnakill")) {
			return;
		}
		String only = System.getenv("HW_SCENE");
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runOnServer(s -> {
				HwConfig cfg = HwConfig.get();
				cfg.countdownSeconds = 1;
				cfg.botsFillTo = 3;
				cfg.npcMax = 0;
				cfg.eventsEnabled = false;
				cfg.botQteSuccessChance = 0f;
			});
			for (String scene : new String[]{"ascend", "drag", "locker"}) {
				if (only != null && !only.equals(scene)) {
					continue;
				}
				server.runCommand("hw killer vecna");
				server.runCommand("hw start @p");
				ctx.waitTicks(60);
				server.runCommand("time set " + (scene.equals("drag") ? "18000" : "noon"));
				ctx.runOnClient(mc -> ClientDarkness.enabled = false);
				BlockPos at = server.computeOnServer(s -> platform(s, scene.equals("locker")));
				ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
				if (scene.equals("locker")) {
					locker(ctx, server, at);
				} else {
					finisher(ctx, server, at, scene);
				}
				server.runCommand("hw stop");
				ctx.waitTicks(20);
			}
			server.runCommand("hw killer michael");
		}
		LOG.info("Vecna kill client test finished OK");
	}

	/** A floor up in the air (three blocks of earth, grass on top), room above; the two of them on it, he facing south. */
	private static BlockPos platform(MinecraftServer s, boolean locker) {
		ServerPlayer p = player(s);
		BlockPos at = p.blockPosition().offset(0, 40, 0);
		for (int x = -7; x <= 7; x++) {
			for (int z = -7; z <= 7; z++) {
				s.overworld().setBlock(at.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
				s.overworld().setBlock(at.offset(x, -2, z), Blocks.DIRT.defaultBlockState(), 2);
				s.overworld().setBlock(at.offset(x, -3, z), Blocks.DIRT.defaultBlockState(), 2);
				for (int y = 0; y <= 6; y++) {
					s.overworld().setBlock(at.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
				}
			}
		}
		if (locker) {
			LockerBlock.place(s.overworld(), at.offset(0, 0, 2), Direction.NORTH, HwBlocks.LOCKER);
		}
		return at;
	}

	private static void finisher(ClientGameTestContext ctx, TestServerContext server, BlockPos at, String scene) {
		server.runOnServer(s -> {
			ServerPlayer p = player(s);
			ServerPlayer bot = victim(s);
			p.teleportTo(s.overworld(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, java.util.Set.of(), 0f, 10f, false);
			bot.teleportTo(s.overworld(), at.getX() + 0.5, at.getY(), at.getZ() + 1.4, java.util.Set.of(), 180f, 0f, false);
			VecnaKill.forceKind = scene.equals("drag") ? VecnaKill.DRAG : VecnaKill.ASCEND;
			check(MatchManager.grabOut(p, bot), scene + ": he has them by the throat");
		});
		ctx.waitTicks(20);
		ctx.takeScreenshot("vecnakill_" + scene + "_held");
		server.runOnServer(s -> MatchManager.grabHolding(victim(s)).testWin());
		int[] shots = scene.equals("drag") ? new int[]{6, 14, 22, 32, 46, 58, 70, 78, 86, 95, 104} : new int[]{8, 18, 28, 36, 41, 48, 58, 66, 70, 78, 88};
		int waited = 0;
		for (int t : shots) {
			ctx.waitTicks(t - waited);
			waited = t;
			if (t == shots[2]) {
				check(ctx.computeOnClient(mc -> KillCam.active()), scene + ": he sees it through the kill's camera");
			}
			ctx.takeScreenshot("vecnakill_" + scene + "_" + t);
		}
		if (scene.equals("drag")) {
			Vec3 hole = server.computeOnServer(s -> player(s).position());
			LOG.info("drag: Vecna at {}", hole);
		}
		ctx.waitTicks(20);
		check(server.computeOnServer(s -> s.overworld().getEntitiesOfClass(net.minecraft.world.entity.decoration.Mannequin.class,
			new net.minecraft.world.phys.AABB(at).inflate(4), e -> e.entityTags().contains(MatchManager.CORPSE_TAG)).size() == 1), scene + ": a body is left");
		ctx.takeScreenshot("vecnakill_" + scene + "_after");
		server.runOnServer(s -> VecnaKill.forceKind = 0);
	}

	private static void locker(ClientGameTestContext ctx, TestServerContext server, BlockPos at) {
		BlockPos base = at.offset(0, 0, 2);
		server.runOnServer(s -> {
			ServerPlayer p = player(s);
			ServerPlayer bot = victim(s);
			bot.teleportTo(s.overworld(), at.getX() + 0.5, at.getY(), at.getZ() + 1.5, java.util.Set.of(), 0f, 0f, false);
			p.teleportTo(s.overworld(), at.getX() + 0.5, at.getY(), at.getZ() - 0.2, java.util.Set.of(), 0f, 10f, false);
			// No amulet to break his hold, nothing left over that keeps him busy.
			while (dev.halloween.game.Inventory.has(bot, dev.halloween.game.HwItems.Kind.AMULET)) {
				dev.halloween.game.Inventory.consume(bot, dev.halloween.game.HwItems.Kind.AMULET);
			}
			var st = MatchManager.shapeState(p);
			st.attackCooldown = 0;
			st.recoverTicks = 0;
			st.blindTicks = 0;
			// In and caught in the same tick (a bot not told to hide gets out again at once).
			check(Lockers.enter(bot, base, false), "locker: the bot hides");
			Lockers.shapeOpen(p, base);
			LOG.info("locker: busy {} shadow {} distance {}", st.busy(), st.shadowTicks, p.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(base)));
			check(MatchManager.grabHolding(bot) != null, "locker: he has found them");
		});
		int[] shots = {4, 12, 18, 23, 27, 32, 38};
		int waited = 0;
		for (int t : shots) {
			ctx.waitTicks(t - waited);
			waited = t;
			ctx.takeScreenshot("vecnakill_locker_open_" + t);
		}
		ctx.waitTicks(12);
		check(server.computeOnServer(s -> MatchManager.grabHolding(victim(s)).sceneTick()) == -1, "locker: the struggle is on after the scene");
		ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
		ctx.waitTicks(10);
		ctx.takeScreenshot("vecnakill_locker_struggle");
		ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
		server.runOnServer(s -> MatchManager.grabHolding(victim(s)).testWin());
		int[] kill = {8, 16, 23, 29, 36, 46, 58, 64, 72, 80, 88};
		waited = 0;
		for (int t : kill) {
			ctx.waitTicks(t - waited);
			waited = t;
			ctx.takeScreenshot("vecnakill_locker_kill_" + t);
		}
		ctx.waitTicks(20);
		check(server.computeOnServer(s -> s.overworld().getEntitiesOfClass(net.minecraft.world.entity.decoration.Mannequin.class,
			new net.minecraft.world.phys.AABB(at).inflate(5), e -> e.entityTags().contains(MatchManager.CORPSE_TAG)).size() == 1), "locker: a body is left");
		ctx.takeScreenshot("vecnakill_locker_after");
	}

	/** A survivor bot without the curse (the cursed one dies a scene of its own). */
	private static ServerPlayer victim(MinecraftServer s) {
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (p instanceof BotPlayer && MatchManager.role(p).survivor() && p.isAlive() && !p.isSpectator() && !VecnaCurse.cursed(p)) {
				return p;
			}
		}
		throw new IllegalStateException("no uncursed survivor bot");
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
