package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.client.CharacterSelectScreen;
import dev.halloween.client.ClientState;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.HeroPicks;
import dev.halloween.game.MatchManager;
import dev.halloween.game.Perks;
import dev.halloween.game.Skins;
import dev.halloween.game.Vecna;
import dev.halloween.net.HwNet;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The character select (HW_ONLY=select): the screen on both tabs, buying a hero, the pick dealt, the killer drawn. */
public class CharacterSelectClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-select");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("select")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runOnServer(s -> {
				HeroPicks.clear();
				HwConfig.get().botsFillTo = 0;
				HwConfig.get().countdownSeconds = 1;
				Perks.give(s, player(s).getUUID(), 7000);
				Perks.sync(player(s));
			});
			ctx.waitTicks(5);

			ctx.runOnClient(mc -> mc.gui.setScreen(new CharacterSelectScreen()));
			ctx.waitTicks(10);
			ctx.runOnClient(mc -> ((CharacterSelectScreen) mc.gui.screen()).showForTest(0, "laurie", false));
			ctx.waitTicks(10);
			ctx.takeScreenshot("select_laurie");
			// And at 1080p (the GUI scale a player there has).
			ctx.getInput().resizeWindow(1920, 1080);
			ctx.waitTicks(5);
			ctx.takeScreenshot("select_laurie_1080");
			ctx.getInput().resizeWindow(854, 480);
			ctx.waitTicks(5);
			// A locked one: bought (the points spent), then picked.
			ctx.runOnClient(mc -> ((CharacterSelectScreen) mc.gui.screen()).showForTest(0, "annie", false));
			ctx.waitTicks(5);
			ctx.takeScreenshot("select_annie_locked");
			ctx.runOnClient(mc -> ClientPlayNetworking.send(new HwNet.HeroPick(HeroPicks.BUY, "annie")));
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> Perks.ownsHero(s, player(s).getUUID(), "annie")), "Annie bought");
			check(server.computeOnServer(s -> Perks.points(s, player(s).getUUID())) == 7000 - HwConfig.get().heroPrice, "the price spent");
			check(server.computeOnServer(s -> "annie".equals(HeroPicks.survivor(player(s).getUUID()))), "and picked straight away");
			check(ctx.computeOnClient(mc -> ClientState.heroesOwned.contains("annie")), "the client knows");
			ctx.runOnClient(mc -> ClientPlayNetworking.send(new HwNet.HeroPick(HeroPicks.SURVIVOR, "rachel")));
			ctx.waitTicks(3);
			check(server.computeOnServer(s -> "annie".equals(HeroPicks.survivor(player(s).getUUID()))), "a locked one can't be picked");
			ctx.runOnClient(mc -> ((CharacterSelectScreen) mc.gui.screen()).showForTest(0, "annie", true));
			ctx.waitTicks(5);
			ctx.takeScreenshot("select_annie_scene");

			// The killers' tab.
			ctx.runOnClient(mc -> ((CharacterSelectScreen) mc.gui.screen()).showForTest(1, "michael", false));
			ctx.waitTicks(5);
			ctx.takeScreenshot("select_michael");
			ctx.getInput().resizeWindow(1920, 1080);
			ctx.waitTicks(5);
			ctx.takeScreenshot("select_michael_1080");
			ctx.getInput().resizeWindow(854, 480);
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> ClientPlayNetworking.send(new HwNet.HeroPick(HeroPicks.BUY, "vecna")));
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> "vecna".equals(HeroPicks.killer(player(s).getUUID()))), "Vecna bought and wanted");
			ctx.runOnClient(mc -> ((CharacterSelectScreen) mc.gui.screen()).showForTest(1, "vecna", false));
			ctx.waitTicks(5);
			ctx.takeScreenshot("select_vecna");
			ctx.runOnClient(mc -> ((CharacterSelectScreen) mc.gui.screen()).showForTest(1, "demogorgon", false));
			ctx.waitTicks(5);
			ctx.takeScreenshot("select_demogorgon");
			ctx.runOnClient(mc -> mc.gui.setScreen(null));

			// The match: the one who wants the killer plays the one they picked.
			server.runCommand("hw start");
			ctx.waitTicks(10);
			check(server.computeOnServer(s -> MatchManager.role(player(s)) == Role.SHAPE && Vecna.active()), "wanted the killer: plays Vecna");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			// Not the killer any more: plays the survivor picked.
			ctx.runOnClient(mc -> ClientPlayNetworking.send(new HwNet.HeroPick(HeroPicks.KILLER, "")));
			ctx.waitTicks(3);
			server.runCommand("hw start");
			ctx.waitTicks(10);
			check(server.computeOnServer(s -> "annie".equals(Skins.dealt(player(s).getUUID()))), "dealt the survivor picked");
			check(server.computeOnServer(s -> HeroPicks.matchKiller() == null), "the killer back to /hw killer");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			// The new skills: Thomas lies low (gone while still), Lynda runs free.
			for (String hero : new String[]{"thomas", "lynda"}) {
				server.runOnServer(s -> Skins.forced = hero);
				server.runCommand("hw start");
				ctx.waitTicks(40);
				server.runOnServer(s -> dev.halloween.game.Heroes.use(player(s)));
				ctx.waitTicks(3);
				if (hero.equals("thomas")) {
					check(server.computeOnServer(s -> dev.halloween.game.Heroes.lyingLow(player(s)) && !MatchManager.exposed(player(s))), "Thomas lies low");
					server.runOnServer(s -> player(s).teleportTo(player(s).getX() + 1, player(s).getY(), player(s).getZ()));
					ctx.waitTicks(3);
					check(server.computeOnServer(s -> !dev.halloween.game.Heroes.lyingLow(player(s)) && !player(s).isInvisible()), "moving gives him away");
				} else {
					check(server.computeOnServer(s -> dev.halloween.game.Heroes.active(player(s), dev.halloween.game.Heroes.Skill.CALM)), "Lynda runs free");
				}
				server.runCommand("hw stop");
				ctx.waitTicks(5);
			}
			server.runOnServer(s -> {
				Skins.forced = null;
				HeroPicks.clear();
				HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds;
				HwConfig.get().botsFillTo = new HwConfig().botsFillTo;
			});
		}
		LOG.info("Character select client test finished OK");
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
