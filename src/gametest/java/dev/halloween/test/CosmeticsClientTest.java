package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.CharacterSelectScreen;
import dev.halloween.client.ClientDarkness;
import dev.halloween.client.CosmeticRenderer;
import dev.halloween.client.CosmeticScreen;
import dev.halloween.game.Cosmetics;
import dev.halloween.game.Lobby;
import dev.halloween.game.Perks;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Things to wear (HW_ONLY=cosmetics): shut until their level, one per slot, told to the client; every one of the
 * twenty tried on in the Look screen; worn in the lobby (front and back) and by Michael in a match.
 */
public class CosmeticsClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("cosmetics")) {
			return;
		}
		check(Cosmetics.ITEMS.size() == 20, "twenty things");
		for (int lv = 1; lv <= 10; lv++) {
			check(Cosmetics.at(Cosmetics.SURVIVOR, lv) != null && Cosmetics.at(Cosmetics.KILLER, lv) != null, "one a level on each track: " + lv);
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runCommand("hw lobby");
			ctx.waitTicks(60);
			server.runCommand("time set midnight");
			ctx.runOnClient(mc -> ClientDarkness.enabled = false);
			check(server.computeOnServer(s -> Lobby.exists(s) && Lobby.inside(player(s))), "in the lobby");
			check(ctx.computeOnClient(mc -> CosmeticRenderer.modelled().size()) == 20, "a model for each");
			// Level 1: the bandages and the knife are open, the pumpkin head is not.
			server.runOnServer(s -> {
				Cosmetics.clear(s);
				Cosmetics.act(player(s), "pumpkin_head", true);
				Cosmetics.act(player(s), "bandages", true);
			});
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> Cosmetics.worn(s, player(s).getUUID()).equals(List.of("bandages"))), "only what is open goes on");
			// Up to the top level: everything open; one per slot.
			server.runOnServer(s -> {
				Perks.give(s, player(s).getUUID(), 500000);
				Perks.sync(player(s));
				Cosmetics.act(player(s), "pumpkin_head", true);
				Cosmetics.act(player(s), "radio", true);
				Cosmetics.act(player(s), "cat", true);
				Cosmetics.act(player(s), "cleaver", true);
			});
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> Cosmetics.worn(s, player(s).getUUID())).equals(List.of("pumpkin_head", "radio", "cat", "cleaver")),
				"the pumpkin head took the bandages' place");
			check(ctx.computeOnClient(mc -> CosmeticRenderer.worn(mc.player.getUUID())).equals(List.of("pumpkin_head", "radio", "cat", "cleaver")),
				"the client is told");
			server.runOnServer(s -> Cosmetics.act(player(s), "radio", false));
			ctx.waitTicks(5);
			check(ctx.computeOnClient(mc -> CosmeticRenderer.worn(mc.player.getUUID())).equals(List.of("pumpkin_head", "cat", "cleaver")), "taken off");

			// The Look screen: each one tried on.
			ctx.getInput().resizeWindow(1280, 720);
			ctx.runOnClient(mc -> mc.gui.setScreen(new CosmeticScreen(new CharacterSelectScreen(), "laurie")));
			ctx.waitTicks(20);
			ctx.takeScreenshot("cosmetic_screen");
			for (Cosmetics.Item it : Cosmetics.ITEMS) {
				if (System.getenv("HW_COS") != null && !List.of(System.getenv("HW_COS").split(",")).contains(it.id())) {
					continue;
				}
				ctx.runOnClient(mc -> {
					if (mc.gui.screen() instanceof CosmeticScreen c) {
						c.showForTest(it.track(), it.id());
					}
				});
				ctx.waitTicks(30);
				ctx.takeScreenshot("cosmetic_try_" + it.id());
			}
			// The level road: a level's window with its two things.
			for (int lv : new int[] {1, 8, 10}) {
				ctx.runOnClient(mc -> {
					dev.halloween.client.LevelScreen l = new dev.halloween.client.LevelScreen(null);
					l.testHover = lv;
					mc.gui.setScreen(l);
				});
				ctx.waitTicks(25);
				ctx.takeScreenshot("cosmetic_level_tip_" + lv);
			}
			ctx.runOnClient(mc -> mc.gui.setScreen(null));
			ctx.getInput().resizeWindow(854, 480);

			// In the lobby, on the player: front and back.
			String[][] sets = {{"pumpkin_head", "bat_wings", "ghost"}, {"witch_hat", "broom", "cat"}, {"antlers", "radio", "spider"}, {"bandages", "bat_wings"}};
			for (int i = 0; i < sets.length; i++) {
				List<String> set = List.of(sets[i]);
				ctx.runOnClient(mc -> {
					CosmeticRenderer.forced = set;
					mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
				});
				ctx.waitTicks(20);
				ctx.takeScreenshot("cosmetic_lobby_front_" + i);
				ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
				ctx.waitTicks(10);
				ctx.takeScreenshot("cosmetic_lobby_back_" + i);
				// A jump: the wings beat.
				ctx.runOnClient(mc -> mc.player.jumpFromGround());
				ctx.waitTicks(4);
				ctx.takeScreenshot("cosmetic_lobby_jump_" + i);
				ctx.waitTicks(16);
			}

			// Michael in a match, wearing his.
			server.runOnServer(s -> {
				HwConfig cfg = HwConfig.get();
				cfg.countdownSeconds = 1;
				cfg.botsFillTo = 2;
				cfg.npcMax = 0;
				cfg.eventsEnabled = false;
			});
			server.runCommand("hw shape off");
			server.runCommand("hw start @p");
			ctx.waitTicks(100);
			server.runCommand("time set noon");
			String[][] his = {{"pumpkin_mask", "cloak", "crow", "cleaver", "garland"}, {"cracked_mask", "chain", "rusty_knife", "shadow"},
				{"candy_sack", "cracked_mask", "crow"}};
			for (int i = 0; i < his.length; i++) {
				List<String> set = List.of(his[i]);
				ctx.runOnClient(mc -> {
					CosmeticRenderer.forced = set;
					mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
				});
				ctx.waitTicks(15);
				ctx.takeScreenshot("cosmetic_michael_front_" + i);
				ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
				ctx.waitTicks(10);
				ctx.takeScreenshot("cosmetic_michael_back_" + i);
			}
			ctx.runOnClient(mc -> {
				CosmeticRenderer.forced = null;
				mc.options.setCameraType(CameraType.FIRST_PERSON);
			});
			server.runCommand("hw stop");
			ctx.waitTicks(10);
		}
	}

	private static ServerPlayer player(MinecraftServer s) {
		return s.getPlayerList().getPlayers().getFirst();
	}

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("cosmetics: " + what);
		}
	}
}
