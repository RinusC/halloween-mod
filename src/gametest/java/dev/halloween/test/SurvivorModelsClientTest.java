package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientDarkness;
import dev.halloween.client.KillCam;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.ShapeBrain;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The survivors' own models: those built, side by side in daylight, from the front, the side and the back. */
public class SurvivorModelsClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-survivors");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("survivors")) {
			return;
		}
		List<String> show = List.of(System.getenv().getOrDefault("HW_SURVIVORS", "laurie,bob,annie").split(","));
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runOnServer(s -> {
				HwConfig cfg = HwConfig.get();
				cfg.countdownSeconds = 1;
				cfg.botsFillTo = 11;
				cfg.npcMax = 0;
				cfg.eventsEnabled = false;
			});
			ShapeBrain.paused = true;
			dev.halloween.game.Skins.forced = "richard";
			server.runCommand("hw shape bot");
			server.runCommand("hw start");
			ctx.waitTicks(60);
			server.runCommand("time set noon");
			ctx.runOnClient(mc -> ClientDarkness.enabled = false);
			Vec3 base = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				int x = (int) Math.floor(p.getX());
				int z = (int) Math.floor(p.getZ());
				s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(),
					"fill " + (x - 8) + " 150 " + (z - 8) + " " + (x + 8) + " 150 " + (z + 8) + " minecraft:smooth_stone");
				s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(),
					"fill " + (x - 8) + " 151 " + (z - 8) + " " + (x + 8) + " 156 " + (z + 8) + " minecraft:air");
				p.teleportTo(s.overworld(), x + 0.5, 151, z + 8.5, java.util.Set.of(), 180f, 0f, false);
				int i = 0;
				for (String name : show) {
					for (ServerPlayer b : s.getPlayerList().getPlayers()) {
						if (b instanceof BotPlayer && b.getScoreboardName().equalsIgnoreCase(name)) {
							b.teleportTo(s.overworld(), x + 0.5 + (i - (show.size() - 1) / 2.0) * 1.6, 151, z + 0.5, java.util.Set.of(), 0f, 0f, false);
							i++;
						}
					}
				}
				LOG.info("placed {} of {}", i, show);
				return new Vec3(x + 0.5, 151, z + 0.5);
			});
			ctx.waitTicks(10);
			String[][] views = {{"front", "0", "3.2"}, {"side", "4.2", "0"}, {"back", "0", "-4.2"}, {"close", "0", "2.0"}};
			for (String[] v : views) {
				Vec3 cam = base.add(Double.parseDouble(v[1]), v[0].equals("close") ? 1.5 : 1.2, Double.parseDouble(v[2]));
				Vec3 look = base.add(0, v[0].equals("close") ? 1.5 : 0.95, 0);
				ctx.runOnClient(mc -> KillCam.begin(new dev.halloween.net.HwNet.Cutscene(dev.halloween.game.Cutscenes.KILLCAM, 40, cam, look, Vec3.ZERO)));
				for (int k = 0; k < 8; k++) {
					hold(server, base, show);
					ctx.waitTicks(1);
				}
				ctx.takeScreenshot("survivors_" + v[0]);
				ctx.waitTicks(30);
			}
			server.runCommand("hw stop");
			ShapeBrain.paused = false;
			ctx.waitTicks(10);
		}
		LOG.info("Survivor models client test finished OK");
	}

	/** Them back in their places, standing, facing the front. */
	private static void hold(TestServerContext server, Vec3 base, List<String> show) {
		server.runOnServer(s -> {
			int i = 0;
			for (String name : show) {
				for (ServerPlayer b : s.getPlayerList().getPlayers()) {
					if (b instanceof BotPlayer && b.getScoreboardName().equalsIgnoreCase(name)) {
						b.teleportTo(s.overworld(), base.x + (i - (show.size() - 1) / 2.0) * 1.6, base.y, base.z, java.util.Set.of(), 0f, 0f, false);
						b.setYHeadRot(0f);
						b.setYBodyRot(0f);
						b.setDeltaMovement(Vec3.ZERO);
						i++;
					}
				}
			}
		});
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
