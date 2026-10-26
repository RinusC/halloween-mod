package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientDarkness;
import dev.halloween.client.KillCam;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.MatchManager;
import dev.halloween.game.MichaelKill;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Michael's kills after a struggle (game.MichaelKill): pinned to a wall by his knife, lifted on it. Each in a match of
 * its own on a platform up in the air (a wall of bricks behind the one he holds for the first); screenshots through
 * the kill's own camera, the knife left in the wall and back in his hand, the body left at the end.
 *   HW_ONLY=michaelkill (HW_SCENE=pin|lift for one of them)
 */
public class MichaelKillClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-michael-kill");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("michaelkill")) {
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
			server.runCommand("hw killer michael");
			for (String scene : new String[]{"pin", "lift"}) {
				if (only != null && !only.equals(scene)) {
					continue;
				}
				server.runCommand("hw start @p");
				ctx.waitTicks(60);
				server.runCommand("time set " + (scene.equals("pin") ? "noon" : "13500"));
				ctx.runOnClient(mc -> ClientDarkness.enabled = false);
				BlockPos at = server.computeOnServer(s -> platform(s, scene.equals("pin")));
				ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
				server.runOnServer(s -> {
					ServerPlayer p = player(s);
					ServerPlayer bot = victim(s);
					p.teleportTo(s.overworld(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, java.util.Set.of(), 0f, 10f, false);
					bot.teleportTo(s.overworld(), at.getX() + 0.5, at.getY(), at.getZ() + 1.4, java.util.Set.of(), 180f, 0f, false);
					while (dev.halloween.game.Inventory.has(bot, dev.halloween.game.HwItems.Kind.AMULET)) {
						dev.halloween.game.Inventory.consume(bot, dev.halloween.game.HwItems.Kind.AMULET);
					}
					MichaelKill.forceKind = scene.equals("pin") ? MichaelKill.PIN : MichaelKill.LIFT;
					check(MatchManager.grabOut(p, bot), scene + ": he has them by the throat");
				});
				ctx.waitTicks(15);
				server.runOnServer(s -> MatchManager.grabHolding(victim(s)).testWin());
				int[] shots = scene.equals("pin") ? new int[]{8, 16, 24, 28, 34, 42, 54, 64, 80, 86, 94} : new int[]{6, 12, 20, 30, 40, 48, 56, 62, 66, 72, 80};
				int waited = 0;
				for (int t : shots) {
					ctx.waitTicks(t - waited);
					waited = t;
					if (t == shots[2]) {
						check(ctx.computeOnClient(mc -> KillCam.active()), scene + ": he sees it through the kill's camera");
					}
					if (scene.equals("pin") && t == 54) {
						check(server.computeOnServer(s -> player(s).getMainHandItem().isEmpty()), "pin: the knife is left in them");
					}
					ctx.takeScreenshot("michaelkill_" + scene + "_" + t);
				}
				ctx.waitTicks(20);
				check(server.computeOnServer(s -> !player(s).getMainHandItem().isEmpty()), scene + ": the knife is back in his hand");
				check(server.computeOnServer(s -> s.overworld().getEntitiesOfClass(net.minecraft.world.entity.decoration.Mannequin.class,
					new net.minecraft.world.phys.AABB(at).inflate(4), e -> e.entityTags().contains(MatchManager.CORPSE_TAG)).size() == 1), scene + ": a body is left");
				ctx.takeScreenshot("michaelkill_" + scene + "_after");
				server.runOnServer(s -> MichaelKill.forceKind = 0);
				server.runCommand("hw stop");
				ctx.waitTicks(20);
			}
		}
		LOG.info("Michael kill client test finished OK");
	}

	/** A floor up in the air with room above; for the wall, a wall of bricks three blocks behind where they will stand. */
	private static BlockPos platform(MinecraftServer s, boolean wall) {
		ServerPlayer p = player(s);
		BlockPos at = p.blockPosition().offset(0, 40, 0);
		for (int x = -7; x <= 7; x++) {
			for (int z = -7; z <= 7; z++) {
				s.overworld().setBlock(at.offset(x, -1, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
				for (int y = 0; y <= 6; y++) {
					boolean brick = wall && z == 3 && y <= 3;
					s.overworld().setBlock(at.offset(x, y, z), brick ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
				}
			}
		}
		return at;
	}

	private static ServerPlayer victim(MinecraftServer s) {
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (p instanceof BotPlayer && MatchManager.role(p).survivor() && p.isAlive() && !p.isSpectator()) {
				return p;
			}
		}
		throw new IllegalStateException("no survivor bot");
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
