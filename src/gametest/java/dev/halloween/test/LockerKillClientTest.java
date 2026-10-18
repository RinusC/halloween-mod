package dev.halloween.test;

import dev.halloween.HwBlocks;
import dev.halloween.HwConfig;
import dev.halloween.client.ClientDarkness;
import dev.halloween.client.KillCam;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Bots;
import dev.halloween.game.LockerBlock;
import dev.halloween.game.Lockers;
import dev.halloween.game.MatchManager;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pulled out of a wardrobe: Michael's knife into the door, the struggle against it, their head smashed against it
 * (seen from the side), the knife back in his hand; the Demogorgon roaring at it, his claw through it, them flung
 * down and devoured. Screenshots along the way.
 */
public class LockerKillClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-locker-kill");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("lockerkill")) {
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
				cfg.npcMax = 0;
				cfg.eventsEnabled = false;
				cfg.botQteSuccessChance = 0f;
			});
			for (String killer : new String[]{"michael", "demogorgon"}) {
				server.runCommand("hw killer " + killer);
				server.runCommand("hw start @p");
				ctx.waitTicks(60);
				server.runCommand("time set noon");
				ctx.runOnClient(mc -> ClientDarkness.enabled = false);
				BlockPos base = server.computeOnServer(s -> {
					ServerPlayer p = player(s);
					BlockPos at = p.blockPosition().offset(0, 40, 0);
					for (int x = -5; x <= 5; x++) {
						for (int z = -5; z <= 5; z++) {
							s.overworld().setBlock(at.offset(x, -1, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
							for (int y = 0; y <= 3; y++) {
								s.overworld().setBlock(at.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
							}
						}
					}
					BlockPos locker = at.offset(0, 0, -2);
					LockerBlock.place(s.overworld(), locker, Direction.SOUTH, HwBlocks.LOCKER);
					p.teleportTo(s.overworld(), at.getX() + 0.5, at.getY(), at.getZ() - 0.4, java.util.Set.of(), 180f, 10f, false);
					ServerPlayer bot = survivorBot(s);
					bot.teleportTo(s.overworld(), at.getX() + 0.5, at.getY(), at.getZ() - 0.5, java.util.Set.of(), 0f, 0f, false);
					return locker;
				});
				ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
				server.runOnServer(s -> {
					// In and caught in the same tick (a bot not told to hide gets out again at once).
					Lockers.enter(survivorBot(s), base, false);
					Lockers.shapeOpen(player(s), base);
				});
				boolean demo = killer.equals("demogorgon");
				int[] shots = demo ? new int[]{12, 32, 44, 58} : new int[]{4, 10, 22};
				int waited = 0;
				for (int at : shots) {
					ctx.waitTicks(at - waited);
					waited = at;
					ctx.takeScreenshot("locker_" + killer + "_" + at);
				}
				check(server.computeOnServer(s -> MatchManager.grabHolding(survivorBot(s)) != null), killer + ": they are in his hands");
				if (!demo) {
					check(server.computeOnServer(s -> player(s).getMainHandItem().isEmpty()), "michael: the knife is left in the door");
				}
				ctx.waitTicks(demo ? 10 : 8);
				check(server.computeOnServer(s -> MatchManager.grabHolding(survivorBot(s)).sceneTick()) == -1, killer + ": the struggle is on after the scene");
				ctx.takeScreenshot("locker_" + killer + "_struggle");
				if (demo) {
					// From the side: is the one on the floor under his mouth?
					net.minecraft.world.phys.Vec3[] ends = server.computeOnServer(s -> new net.minecraft.world.phys.Vec3[]{player(s).position(), survivorBot(s).position()});
					net.minecraft.world.phys.Vec3 mid = ends[0].lerp(ends[1], 0.5);
					net.minecraft.world.phys.Vec3 along = ends[1].subtract(ends[0]).normalize();
					net.minecraft.world.phys.Vec3 cam = mid.add(-along.z * 6, 2.2, along.x * 6);
					LOG.info("demo at {}, victim at {}", ends[0], ends[1]);
					ctx.runOnClient(mc -> KillCam.begin(new dev.halloween.net.HwNet.Cutscene(dev.halloween.game.Cutscenes.KILLCAM, 30, cam, mid.add(0, 0.6, 0), net.minecraft.world.phys.Vec3.ZERO)));
					ctx.waitTicks(3);
					ctx.takeScreenshot("locker_demogorgon_side");
					ctx.waitTicks(10);
					ctx.takeScreenshot("locker_demogorgon_side2");
				}
				server.runOnServer(s -> MatchManager.grabHolding(survivorBot(s)).testWin());
				ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
				ctx.waitTicks(9);
				if (!demo) {
					check(ctx.computeOnClient(mc -> KillCam.active()), "michael: the kill is seen from the side");
				}
				ctx.takeScreenshot("locker_" + killer + "_kill1");
				ctx.waitTicks(14);
				ctx.takeScreenshot("locker_" + killer + "_kill2");
				ctx.waitTicks(40);
				if (!demo) {
					check(server.computeOnServer(s -> !player(s).getMainHandItem().isEmpty()), "michael: the knife is back in his hand");
				}
				if (!demo) {
					// An empty wardrobe: two seconds of searching it.
					server.runOnServer(s -> {
						MatchManager.shapeState(player(s)).attackCooldown = 0;
						Lockers.shapeOpen(player(s), base);
					});
					ctx.waitTicks(14);
					ctx.takeScreenshot("locker_search");
					check(server.computeOnServer(s -> MatchManager.shapeState(player(s)).busy()), "michael: searching an empty wardrobe holds him");
					ctx.waitTicks(30);
					check(server.computeOnServer(s -> !MatchManager.shapeState(player(s)).busy()), "michael: free again after two seconds");
					// Someone climbing through a window: caught on the sill and pulled back.
					BlockPos pane = base.offset(3, 0, 2);
					server.runOnServer(s -> {
						s.overworld().setBlock(pane, Blocks.GLASS_PANE.defaultBlockState(), 3);
						s.overworld().setBlock(pane.above(), Blocks.GLASS_PANE.defaultBlockState(), 3);
						s.overworld().setBlock(pane.north(), Blocks.STONE.defaultBlockState(), 3);
						s.overworld().setBlock(pane.south(), Blocks.STONE.defaultBlockState(), 3);
						ServerPlayer bot = survivorBot(s);
						bot.teleportTo(s.overworld(), pane.getX() - 0.6, pane.getY(), pane.getZ() + 0.5, java.util.Set.of(), -90f, 0f, false);
						player(s).teleportTo(s.overworld(), pane.getX() - 2.2, pane.getY(), pane.getZ() + 0.5, java.util.Set.of(), -90f, 10f, false);
					});
					ctx.waitTicks(3);
					server.runOnServer(s -> dev.halloween.game.Windows.request(survivorBot(s), pane, false));
					ctx.waitTicks(6);
					check(server.computeOnServer(s -> dev.halloween.game.Windows.isVaulting(survivorBot(s))), "a survivor climbs through the window");
					server.runOnServer(s -> {
						MatchManager.shapeState(player(s)).grabCooldown = 0;
						MatchManager.onGrab(player(s), survivorBot(s).getId());
					});
					ctx.waitTicks(4);
					ctx.takeScreenshot("window_grab");
					check(server.computeOnServer(s -> MatchManager.grabHolding(survivorBot(s)) != null), "michael: caught on the window sill");
					ctx.waitTicks(8);
					check(server.computeOnServer(s -> survivorBot(s).getX() < pane.getX()), "michael: pulled back to his side");
				}
				server.runCommand("hw stop");
				ctx.waitTicks(20);
			}
			server.runCommand("hw killer michael");
		}
		LOG.info("Locker kill client test finished OK");
	}

	private static ServerPlayer survivorBot(MinecraftServer s) {
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (p instanceof BotPlayer && MatchManager.role(p).survivor() && p.isAlive()) {
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
