package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.game.AiStats;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.MapJob;
import dev.halloween.game.Match;
import dev.halloween.game.MatchManager;
import dev.halloween.game.ShapeBrain;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** AI Michael in the town against bot survivors: a log of what he does, where, indoors or out, and how often he gets stuck. */
public class MichaelRaidClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-raid");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("raid")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw map " + System.getenv().getOrDefault("HW_RAID_MAP", "town"));
			ctx.waitTicks(2);
			boolean busy = true;
			for (int i = 0; i < 3000 && busy; i++) {
				ctx.waitTicks(10);
				busy = server.computeOnServer(s -> MapJob.busy());
			}
			server.runOnServer(s -> {
				HwConfig cfg = HwConfig.get();
				cfg.countdownSeconds = 1;
				cfg.botsFillTo = 4;
				cfg.eventsEnabled = false;
				cfg.midnightEnabled = false;
				AiStats.reset();
			});
			server.runCommand("hw shape bot");
			server.runCommand("hw start");
			ctx.waitTicks(100);
			// The player out of it, high above.
			server.runOnServer(s -> {
				ServerPlayer p = player(s);
				s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(),
					"fill " + ((int) p.getX() - 2) + " 300 " + ((int) p.getZ() - 2) + " " + ((int) p.getX() + 2) + " 300 " + ((int) p.getZ() + 2) + " minecraft:stone");
				p.teleportTo(s.overworld(), (int) p.getX() + 0.5, 301, (int) p.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
			});
			int seconds = Integer.parseInt(System.getenv().getOrDefault("HW_RAID_SECONDS", "180"));
			for (int t = 0; t < seconds && server.computeOnServer(s -> Match.running()); t += 3) {
				ctx.waitTicks(60);
				int tt = t;
				try {
					LOG.info("t{} {}", tt, (Object) server.computeOnServer(MichaelRaidClientTest::line));
				} catch (Throwable e) {
					LOG.error("line failed", e);
					throw e;
				}
			}
			LOG.info("michael stats: {}", AiStats.snapshot().get("~michael"));
			LOG.info("kills {}", (Object) server.computeOnServer(s -> s.getPlayerList().getPlayers().stream().filter(p -> p instanceof BotPlayer && p.isSpectator()).count()));
			server.runCommand("hw stop");
			server.runCommand("hw shape off");
			ctx.waitTicks(5);
		}
		LOG.info("Raid client test finished OK");
	}

	private static String line(MinecraftServer s) {
		ServerPlayer m = ShapeBrain.body();
		if (m == null) {
			return "no michael";
		}
		StringBuilder b = new StringBuilder(ShapeBrain.debug());
		b.append(m.level().canSeeSky(m.blockPosition().above()) ? " OUT" : " IN");
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (p instanceof BotPlayer && p != m && MatchManager.role(p).survivor() && !p.isSpectator()) {
				b.append(String.format(" | %s %.0f %s%s", p.getScoreboardName(), p.distanceTo(m), p.level().canSeeSky(p.blockPosition().above()) ? "out" : "in",
					dev.halloween.game.Lockers.isInside(p) ? "-locker" : ""));
			}
		}
		return b.toString();
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
