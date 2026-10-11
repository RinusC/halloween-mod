package dev.halloween.test;

import dev.halloween.game.HwItems;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The weapons' models (from Blockbench) held, first person and from the front: screenshots to look at. */
public class ModelsClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-models");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("models")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("time set noon");
			server.runCommand("gamemode survival @a");
			for (HwItems.Kind kind : new HwItems.Kind[]{HwItems.Kind.REVOLVER, HwItems.Kind.RIFLE, HwItems.Kind.SHOTGUN, HwItems.Kind.ICE_PICK}) {
				server.runOnServer(s -> {
					ServerPlayer p = player(s);
					p.setItemInHand(InteractionHand.MAIN_HAND, HwItems.create(kind));
					p.teleportTo(s.overworld(), p.getX(), p.getY(), p.getZ(), java.util.Set.of(), 0f, 10f, false);
				});
				ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
				ctx.waitTicks(10);
				ctx.takeScreenshot("model_" + kind.key() + "_first");
				ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
				ctx.waitTicks(10);
				ctx.takeScreenshot("model_" + kind.key() + "_front");
				ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
				ctx.waitTicks(10);
				ctx.takeScreenshot("model_" + kind.key() + "_back");
			}
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));

			// The Runt as his creature: a bot plays him, the player is Michael, by day and with no darkness.
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runOnServer(s -> {
				dev.halloween.HwConfig.get().runtEnabled = true;
				dev.halloween.HwConfig.get().countdownSeconds = 1;
				dev.halloween.HwConfig.get().botsFillTo = 1;
				dev.halloween.HwConfig.get().eventsEnabled = false;
			});
			dev.halloween.game.Weather.forced = dev.halloween.game.Weather.CLEAR;
			server.runCommand("hw runt bot");
			server.runCommand("hw start");
			ctx.waitTicks(80);
			ctx.runOnClient(mc -> dev.halloween.client.ClientDarkness.enabled = false);
			server.runCommand("time set noon");
			for (String view : new String[]{"front", "side"}) {
				server.runOnServer(s -> {
					ServerPlayer runt = dev.halloween.game.Runt.all(s).getFirst();
					dev.halloween.game.Runt.state(runt).stunTicks = 400;
					ServerPlayer p = michael(s);
					float yaw = view.equals("front") ? 180f : 90f;
					runt.teleportTo(s.overworld(), p.getX(), p.getY(), p.getZ() + 3.5, java.util.Set.of(), yaw, 0f, false);
					runt.setYHeadRot(yaw);
					runt.setYBodyRot(yaw);
					p.teleportTo(s.overworld(), p.getX(), p.getY(), p.getZ(), java.util.Set.of(), 0f, 15f, false);
				});
				ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
				ctx.waitTicks(20);
				ctx.takeScreenshot("model_runt_idle_" + view);
			}
			// Crawling: moved along a little every tick.
			for (int i = 0; i < 24; i++) {
				server.runOnServer(s -> {
					ServerPlayer runt = dev.halloween.game.Runt.all(s).getFirst();
					runt.teleportTo(s.overworld(), runt.getX() + 0.12, runt.getY(), runt.getZ(), java.util.Set.of(), 270f, 0f, false);
					runt.setYBodyRot(270f);
				});
				ctx.waitTicks(1);
				if (i == 16) {
					ctx.takeScreenshot("model_runt_walk");
				}
			}
			server.runOnServer(s -> dev.halloween.game.Runt.all(s).getFirst().setShiftKeyDown(true));
			ctx.waitTicks(15);
			ctx.takeScreenshot("model_runt_crouch");
			server.runOnServer(s -> {
				ServerPlayer runt = dev.halloween.game.Runt.all(s).getFirst();
				runt.setShiftKeyDown(false);
				dev.halloween.game.GrabSession.anim(runt, dev.halloween.net.HwNet.Anim.FALL);
			});
			ctx.waitTicks(20);
			ctx.takeScreenshot("model_runt_down");
			server.runCommand("hw stop");
			dev.halloween.game.Weather.forced = -1;
			ctx.waitTicks(5);
		}
		LOG.info("Models client test finished OK");
	}

	private static ServerPlayer michael(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!(p instanceof dev.halloween.game.BotPlayer)) {
				return p;
			}
		}
		throw new IllegalStateException("no player");
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}
}
