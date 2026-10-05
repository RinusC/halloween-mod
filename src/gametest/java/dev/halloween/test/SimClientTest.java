package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.client.ClientState;
import dev.halloween.game.AiStats;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Bots;
import dev.halloween.game.MapPoints;
import dev.halloween.game.Match;
import dev.halloween.game.MatchManager;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI research run (only with the environment variable HW_SIM set): whole matches of four bot civilians
 * and townsfolk against {@link SimShape}, sped up with /tick sprint, logging what everyone did.
 * HW_SIM = number of matches, HW_SIM_SECONDS = match length.
 */
public class SimClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-sim");
	private static boolean hooked;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		String runsEnv = System.getenv("HW_SIM");
		if (runsEnv == null) {
			LOG.info("AI simulation skipped (set HW_SIM to run it)");
			return;
		}
		int runs = Integer.parseInt(runsEnv);
		int seconds = Integer.parseInt(System.getenv().getOrDefault("HW_SIM_SECONDS", "300"));
		if (!hooked) {
			hooked = true;
			ServerTickEvents.END_SERVER_TICK.register(SimShape::tick);
		}
		Map<String, Integer> totals = new TreeMap<>();
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runOnServer(s -> {
				HwConfig cfg = HwConfig.get();
				cfg.countdownSeconds = 1;
				cfg.matchSeconds = seconds;
				cfg.gateOpenLastSeconds = seconds / 3;
				cfg.policeArriveSeconds = 60;
				cfg.policeStaySeconds = 60;
			});
			for (int run = 1; run <= runs; run++) {
				AiStats.reset();
				SimShape.MODE_SECONDS.clear();
				server.runCommand("hw start @p");
				ctx.waitTicks(5);
				server.runOnServer(s -> {
					ServerPlayer human = s.getPlayerList().getPlayers().stream().filter(p -> !(p instanceof BotPlayer)).findFirst().orElseThrow();
					BlockPos spawn = MapPoints.get(s.overworld()).first(MapPoints.Type.SPAWN_SHAPE);
					BotPlayer shape = Bots.spawn(s, "TheShape", spawn);
					MatchManager.applyRole(human, Role.NONE);
					human.setGameMode(GameType.SPECTATOR);
					BlockPos center = MapPoints.get(s.overworld()).first(MapPoints.Type.CENTER);
					human.teleportTo(s.overworld(), center.getX() + 0.5, center.getY() + 40, center.getZ() + 0.5, java.util.Set.of(), 0f, 90f, false);
					MatchManager.applyRole(shape, Role.SHAPE);
					Match.setShape(shape);
					SimShape.attach(shape);
				});
				server.runCommand("tick sprint " + (seconds * 20 + 200));
				long started = System.currentTimeMillis();
				int lastLeft = -1;
				while (true) {
					ctx.waitTicks(20);
					Match.State state = server.computeOnServer(s -> Match.state());
					int left = server.computeOnServer(s -> Match.ticksLeft());
					if (left / 1200 != lastLeft / 1200) {
						lastLeft = left;
						LOG.info("run {}: {} s left, modes {}", run, left / 20, SimShape.MODE_SECONDS);
					}
					if (state == Match.State.IDLE || System.currentTimeMillis() - started > (seconds + 120) * 1000L) {
						break;
					}
				}
				server.runCommand("tick sprint stop");
				SimShape.detach();
				ctx.waitTicks(10);
				String board = ctx.computeOnClient(mc -> ClientState.matchEnd == null ? "(no board)"
					: ClientState.matchEnd.lines().stream().map(l -> l.getString()).reduce("", (a, b) -> a + "\n    " + b));
				LOG.info("===== run {} finished in {} s real time{}", run, (System.currentTimeMillis() - started) / 1000, board);
				Map<String, Map<String, Integer>> stats = AiStats.snapshot();
				stats.forEach((who, events) -> LOG.info("  {}: {}", who, events));
				LOG.info("  seconds per bot mode: {}", SimShape.MODE_SECONDS);
				stats.getOrDefault("*total", Map.of()).forEach((k, v) -> totals.merge(k, v, Integer::sum));
				SimShape.MODE_SECONDS.forEach((k, v) -> totals.merge("seconds." + k, v, Integer::sum));
				server.runCommand("hw stop");
				ctx.waitTicks(20);
			}
		}
		LOG.info("===== totals over {} runs: {}", runs, totals);
	}
}
