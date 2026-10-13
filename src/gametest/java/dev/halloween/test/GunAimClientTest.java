package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientDarkness;
import dev.halloween.client.GunPoses;
import dev.halloween.client.ViewArms;
import dev.halloween.game.BotPlayer;
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

/**
 * Aiming over iron sights (client.ViewArms, ClientGuns, GunPoses): for each gun, from the hip, aimed at a red mark
 * ten blocks off at eye height (the sights must sit on it in the middle of the view), the kick of a shot, the
 * shotgun racked; then a bot with a rifle and with a revolver aiming, seen from the side.
 */
public class GunAimClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-gun-aim");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("gunaim")) {
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
			});
			dev.halloween.game.Weather.forced = dev.halloween.game.Weather.CLEAR;
			dev.halloween.game.ShapeBrain.paused = true;
			server.runCommand("hw shape bot");
			server.runCommand("hw start");
			ctx.waitTicks(80);
			ctx.runOnClient(mc -> {
				ClientDarkness.enabled = false;
				mc.options.setCameraType(CameraType.FIRST_PERSON);
			});
			server.runCommand("time set noon");
			int[] at = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				int x = (int) Math.floor(p.getX());
				int z = (int) Math.floor(p.getZ());
				command(s, "fill " + (x - 6) + " 150 " + (z - 14) + " " + (x + 6) + " 150 " + (z + 6) + " minecraft:smooth_stone");
				// The mark: a red block at eye height ten blocks north, white round it.
				command(s, "fill " + (x - 1) + " 151 " + (z - 10) + " " + (x + 1) + " 153 " + (z - 10) + " minecraft:white_wool");
				command(s, "setblock " + x + " 152 " + (z - 10) + " minecraft:red_wool");
				return new int[]{x, z};
			});
			for (HwItems.Kind kind : new HwItems.Kind[]{HwItems.Kind.REVOLVER, HwItems.Kind.RIFLE, HwItems.Kind.SHOTGUN}) {
				this.face(server, at);
				server.runOnServer(s -> player(s).setItemInHand(InteractionHand.MAIN_HAND, HwItems.create(kind)));
				ctx.waitTicks(12);
				ctx.takeScreenshot("gun_" + kind.key() + "_hip");
				ViewArms.testAim = true;
				ctx.waitTicks(8);
				ctx.takeScreenshot("gun_" + kind.key() + "_aim");
				ctx.runOnClient(mc -> ViewArms.trigger(ViewArms.Act.RECOIL));
				ctx.waitTicks(1);
				ctx.takeScreenshot("gun_" + kind.key() + "_kick");
				ctx.waitTicks(4);
				ctx.takeScreenshot("gun_" + kind.key() + "_kick_settle");
				if (kind == HwItems.Kind.SHOTGUN) {
					ctx.waitTicks(5);
					ctx.takeScreenshot("gun_shotgun_pump");
				}
				ctx.waitTicks(14);
				ViewArms.testAim = null;
				ctx.waitTicks(6);
			}
			// Seen from outside: someone (a mannequin, as the officers are) aiming a rifle, then a revolver, side on; a shot.
			server.runOnServer(s -> player(s).setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY));
			GunPoses.testAll = true;
			for (HwItems.Kind kind : new HwItems.Kind[]{HwItems.Kind.RIFLE, HwItems.Kind.REVOLVER}) {
				int id = server.computeOnServer(s -> {
					command(s, "kill @e[tag=hw_test]");
					net.minecraft.world.entity.decoration.Mannequin m = new net.minecraft.world.entity.decoration.Mannequin(
						net.minecraft.world.entity.EntityTypes.MANNEQUIN, s.overworld());
					m.setPos(at[0] + 0.5, 151, at[1] - 3.5);
					m.setYRot(270f);
					m.setYBodyRot(270f);
					m.setYHeadRot(270f);
					m.addTag("hw_test");
					m.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, HwItems.create(kind));
					s.overworld().addFreshEntity(m);
					player(s).teleportTo(s.overworld(), at[0] + 0.5, 151, at[1] + 0.5, java.util.Set.of(), 180f, 10f, false);
					return m.getId();
				});
				ctx.waitTicks(10);
				ctx.takeScreenshot("gun_third_" + kind.key());
				ctx.runOnClient(mc -> GunPoses.receive(new dev.halloween.net.HwNet.GunPose(id, dev.halloween.net.HwNet.GunPose.SHOT)));
				ctx.waitTicks(1);
				ctx.takeScreenshot("gun_third_" + kind.key() + "_shot");
			}
			GunPoses.testAll = false;
			server.runCommand("kill @e[tag=hw_test]");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> ClientDarkness.enabled = true);
			dev.halloween.game.ShapeBrain.paused = false;
			dev.halloween.game.Weather.forced = -1;
		}
		LOG.info("Gun aim client test finished OK");
	}

	/** The player ten blocks from the mark, looking straight at it. */
	private void face(TestServerContext server, int[] at) {
		server.runOnServer(s -> player(s).teleportTo(s.overworld(), at[0] + 0.5, 151, at[1] + 0.5, java.util.Set.of(), 180f, 0f, false));
	}

	private static void command(MinecraftServer s, String command) {
		s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), command);
	}

	private static ServerPlayer player(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!(p instanceof BotPlayer)) {
				return p;
			}
		}
		throw new IllegalStateException("no player");
	}

	private static ServerPlayer bot(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p instanceof BotPlayer && dev.halloween.game.MatchManager.role(p).survivor()) {
				return p;
			}
		}
		throw new IllegalStateException("no survivor bot");
	}
}
