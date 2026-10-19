package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.HeroScene;
import dev.halloween.game.MapJob;
import dev.halloween.game.Skins;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.entity.decoration.Mannequin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Every survivor's opening scene on Haddonfield: him behind them, drawn as Michael, and gone again before the match. */
public class HeroScenesClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-heroes");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("heroes")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw map " + System.getenv().getOrDefault("HW_HERO_MAP", "haddonfield"));
			ctx.waitTicks(2);
			boolean busy = true;
			for (int i = 0; i < 3000 && busy; i++) {
				ctx.waitTicks(10);
				busy = server.computeOnServer(s -> MapJob.busy());
			}
			server.runOnServer(s -> HwConfig.get().botsFillTo = 2);
			server.runCommand("hw shape bot");
			List<String> bad = new ArrayList<>();
			for (String hero : System.getenv().containsKey("HW_HEROES") ? List.of(System.getenv("HW_HEROES").split(",")) : Skins.SURVIVORS) {
				Skins.forced = hero;
				server.runCommand("hw start");
				int ticks = server.computeOnServer(s -> HwConfig.get().countdownSeconds * 20);
				ctx.waitTicks(Math.round(ticks * 0.62f));
				String seen = ctx.computeOnClient(mc -> {
					int ghosts = 0;
					int asHim = 0;
					for (var e : mc.level.entitiesForRendering()) {
						if (e instanceof Mannequin m && mc.player.distanceTo(m) < 16 && m.getEntityData() != null) {
							if (HeroScene.isGhost(m)) {
								asHim++;
							}
							ghosts++;
						}
					}
					return asHim + "/" + ghosts;
				});
				ctx.takeScreenshot("hero_" + hero);
				ctx.waitTicks(Math.round(ticks * 0.45f));
				int left = server.computeOnServer(s -> s.overworld().getEntities(net.minecraft.world.entity.EntityTypes.MANNEQUIN, m -> m.entityTags().contains("hw_ghost")).size());
				LOG.info("{}: him drawn as Michael / mannequins near: {}, ghosts left after the scene: {}", hero, seen, left);
				if (!seen.startsWith("1") || left > 0) {
					bad.add(hero + " " + seen + " left " + left);
				}
				server.runCommand("hw stop");
				ctx.waitTicks(10);
			}
			Skins.forced = null;
			LOG.info("problems: {}", bad);
			if (!bad.isEmpty()) {
				throw new AssertionError("FAILED: " + bad);
			}
		}
		LOG.info("Hero scenes client test finished OK");
	}
}
