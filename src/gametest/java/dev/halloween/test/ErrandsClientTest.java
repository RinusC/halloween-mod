package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientDarkness;
import dev.halloween.client.NpcMenuScreen;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Errands;
import dev.halloween.game.HwItems;
import dev.halloween.game.Inventory;
import dev.halloween.game.Skins;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Townsfolk's favours (game.Errands): the mark over one asking, the talk menu's fourth choice, each kind taken on and done. */
public class ErrandsClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-errands");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("errands")) {
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
				// Asked only by the test.
				cfg.errandEveryTicks = 1000000;
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
			// Michael (a bot) out of the way: far off and frozen there.
			server.runOnServer(s -> {
				for (ServerPlayer p : s.getPlayerList().getPlayers()) {
					if (p instanceof BotPlayer && dev.halloween.game.MatchManager.role(p) == dev.halloween.Role.SHAPE) {
						p.teleportTo(s.overworld(), p.getX() + 400, p.getY() + 30, p.getZ() + 400, java.util.Set.of(), 0f, 0f, false);
						p.setNoGravity(true);
						dev.halloween.game.ShapeBrain.paused = true;
					}
				}
			});
			StringBuilder report = new StringBuilder();
			for (int kind = 0; kind < 4; kind++) {
				final int k = kind;
				boolean asked = server.computeOnServer(s -> Errands.askNow(s.overworld(), k));
				if (!asked) {
					report.append(" kind ").append(k).append(": no place for it on the arena;");
					continue;
				}
				int npcId = server.computeOnServer(s -> Errands.lastNpc().getId());
				// Before it, looking at the mark over its head.
				server.runOnServer(s -> {
					Entity npc = Errands.lastNpc();
					ServerPlayer p = player(s);
					Vec3 at = npc.position().add(npc.getLookAngle().multiply(3, 0, 3));
					p.teleportTo(s.overworld(), at.x, npc.getY(), at.z, java.util.Set.of(), 0f, 0f, false);
					p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, npc.getEyePosition().add(0, 0.6, 0));
				});
				ctx.waitTicks(25);
				ctx.takeScreenshot("errands_" + k + "_mark");
				ctx.runOnClient(mc -> {
					Entity npc = mc.level.getEntity(npcId);
					mc.gui.setScreen(new NpcMenuScreen(npcId, npc == null ? net.minecraft.network.chat.Component.literal("?") : npc.getDisplayName(), false));
				});
				ctx.waitTicks(5);
				ctx.takeScreenshot("errands_" + k + "_menu");
				ctx.runOnClient(mc -> mc.gui.setScreen(null));
				int before = server.computeOnServer(s -> Errands.doneCount);
				// Taken on.
				server.runOnServer(s -> Errands.talk(player(s), Errands.lastNpc()));
				ctx.waitTicks(25);
				ctx.takeScreenshot("errands_" + k + "_taken");
				switch (k) {
					case Errands.MEDICINE -> server.runOnServer(s -> {
						ServerPlayer p = player(s);
						Inventory.give(p, HwItems.create(HwItems.Kind.MED_KIT));
						Errands.talk(p, Errands.lastNpc());
					});
					case Errands.ESCORT -> {
						for (int i = 0; i < 6 && server.computeOnServer(s -> Errands.lastNpc() != null && Errands.targetOf(Errands.lastNpc()) != null); i++) {
							server.runOnServer(s -> {
								Entity npc = Errands.lastNpc();
								ServerPlayer p = player(s);
								BlockPos home = Errands.targetOf(npc);
								p.teleportTo(s.overworld(), home.getX() + 2.5, home.getY(), home.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
								npc.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
							});
							ctx.waitTicks(10);
						}
					}
					case Errands.RADIO -> server.runOnServer(s -> {
						BlockPos radio = Errands.targetOf(Errands.lastNpc());
						Errands.repaired(player(s), radio);
					});
					default -> {
						server.runOnServer(s -> {
							var dog = Errands.dogOf(Errands.lastNpc());
							ServerPlayer p = player(s);
							p.teleportTo(s.overworld(), dog.getX() + 1.5, dog.getY(), dog.getZ(), java.util.Set.of(), 90f, 20f, false);
							Errands.onUseEntity(p, dog);
						});
						ctx.waitTicks(20);
						ctx.takeScreenshot("errands_3_dog");
						for (int i = 0; i < 4; i++) {
							server.runOnServer(s -> {
								Entity npc = Errands.lastNpc();
								var dog = npc == null ? null : Errands.dogOf(npc);
								if (dog != null) {
									// Walked back together: the player by the owner, the dog at heel.
									player(s).teleportTo(s.overworld(), npc.getX() + 2, npc.getY(), npc.getZ(), java.util.Set.of(), 90f, 20f, false);
									dog.teleportTo(npc.getX() + 1, npc.getY(), npc.getZ());
								}
							});
							ctx.waitTicks(10);
						}
					}
				}
				ctx.waitTicks(20);
				int after = server.computeOnServer(s -> Errands.doneCount);
				if (after != before + 1) {
					throw new AssertionError("Favour of kind " + k + " was not done");
				}
				report.append(" kind ").append(k).append(": done;");
			}
			LOG.info("Errands:{}", report);
			// Michael close calls a favour off.
			boolean asked = server.computeOnServer(s -> Errands.askNow(s.overworld(), Errands.MEDICINE));
			if (asked) {
				server.runOnServer(s -> {
					Errands.talk(player(s), Errands.lastNpc());
					Entity npc = Errands.lastNpc();
					for (ServerPlayer p : s.getPlayerList().getPlayers()) {
						if (dev.halloween.game.MatchManager.role(p) == dev.halloween.Role.SHAPE) {
							p.teleportTo(s.overworld(), npc.getX() + 4, npc.getY(), npc.getZ(), java.util.Set.of(), 0f, 0f, false);
						}
					}
				});
				ctx.waitTicks(30);
				if (server.computeOnServer(s -> Errands.count()) != 0) {
					throw new AssertionError("Michael near did not call the favour off");
				}
			}
			server.runCommand("hw stop");
			ctx.waitTicks(10);
			dev.halloween.game.ShapeBrain.paused = false;
			ctx.runOnClient(mc -> ClientDarkness.enabled = true);
			dev.halloween.game.Weather.forced = -1;
			Skins.forced = null;
		}
		LOG.info("Errands client test finished OK");
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
