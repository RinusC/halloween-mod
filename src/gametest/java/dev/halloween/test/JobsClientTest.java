package dev.halloween.test;

import dev.halloween.HwBlocks;
import dev.halloween.HwConfig;
import dev.halloween.client.ClientDarkness;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Heroes;
import dev.halloween.game.SideJobs;
import dev.halloween.game.Skins;
import dev.halloween.net.HwNet;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Heroes' skills (Rachel's pumpkin), a mark for the team, and the side jobs (the radio tuned, the dog untied). */
public class JobsClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-jobs");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("jobs")) {
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
			Skins.forced = "rachel";
			dev.halloween.game.Weather.forced = dev.halloween.game.Weather.CLEAR;
			server.runCommand("hw start");
			ctx.waitTicks(100);
			server.runCommand("time set noon");
			ctx.runOnClient(mc -> {
				ClientDarkness.enabled = false;
				mc.options.setCameraType(CameraType.FIRST_PERSON);
			});
			// Rachel's pumpkin, set down before her.
			boolean pumpkin = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				if (!"rachel".equals(Heroes.hero(p))) {
					throw new AssertionError("Not playing Rachel but " + Heroes.hero(p));
				}
				Heroes.use(p);
				for (BlockPos q : BlockPos.betweenClosed(p.blockPosition().offset(-2, -1, -2), p.blockPosition().offset(2, 1, 2))) {
					if (s.overworld().getBlockState(q).is(Blocks.JACK_O_LANTERN)) {
						return true;
					}
				}
				return false;
			});
			if (!pumpkin) {
				throw new AssertionError("Rachel's pumpkin was not set down");
			}
			ctx.waitTicks(10);
			ctx.takeScreenshot("heroes_pumpkin");
			// The side jobs are out.
			BlockPos radio = server.computeOnServer(s -> SideJobs.radioPos());
			boolean dog = server.computeOnServer(s -> SideJobs.dogEntity() != null);
			if (radio == null || !dog) {
				throw new AssertionError("Side jobs not set out: radio " + radio + ", dog " + dog);
			}
			server.runOnServer(s -> player(s).teleportTo(s.overworld(), radio.getX() + 0.5, radio.getY() + 0.6, radio.getZ() - 1.4, java.util.Set.of(), 0f, 35f, false));
			ctx.waitTicks(20);
			ctx.takeScreenshot("heroes_radio");
			// A mark for the team on the ground before her: it comes back to her own screen.
			ctx.runOnClient(mc -> ClientPlayNetworking.send(new HwNet.Ping(Vec3.atBottomCenterOf(radio).add(0, 0.5, 0), dev.halloween.game.Pings.PLACE, -1)));
			ctx.waitTicks(10);
			ctx.takeScreenshot("heroes_ping");
			// Tuning the radio: held use for its time.
			for (int i = 0; i < 30; i++) {
				server.runOnServer(s -> dev.halloween.game.Holds.ping(player(s), radio));
				ctx.waitTicks(4);
			}
			if (!server.computeOnServer(s -> SideJobs.tuned())) {
				throw new AssertionError("The radio was not tuned");
			}
			// The dog: untied by a use, it then goes with her.
			server.runOnServer(s -> {
				ServerPlayer p = player(s);
				var wolf = SideJobs.dogEntity();
				p.teleportTo(s.overworld(), wolf.getX() + 1.5, wolf.getY(), wolf.getZ(), java.util.Set.of(), 90f, 20f, false);
				SideJobs.onUseEntity(p, wolf);
			});
			ctx.waitTicks(20);
			ctx.takeScreenshot("heroes_dog");
			if (!server.computeOnServer(s -> SideJobs.dogEntity().isTame())) {
				throw new AssertionError("The dog was not untied");
			}
			server.runCommand("hw stop");
			ctx.waitTicks(10);
			if (server.computeOnServer(s -> SideJobs.dogEntity() != null || SideJobs.radioPos() != null)) {
				throw new AssertionError("Side jobs not cleared after the match");
			}
			ctx.runOnClient(mc -> ClientDarkness.enabled = true);
			dev.halloween.game.Weather.forced = -1;
			Skins.forced = null;
		}
		LOG.info("Jobs client test finished OK");
	}

	private static ServerPlayer player(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!(p instanceof BotPlayer)) {
				return p;
			}
		}
		throw new IllegalStateException("no player");
	}
}
