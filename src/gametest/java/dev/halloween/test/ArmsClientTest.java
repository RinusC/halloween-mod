package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientAnims;
import dev.halloween.client.ClientDarkness;
import dev.halloween.client.ClientState;
import dev.halloween.client.ViewArms;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.HwItems;
import dev.halloween.game.Runt;
import dev.halloween.net.HwNet;
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

/** Hands in first person (ViewArms): screenshots of what they hold and do, a survivor's and the Runt's. */
public class ArmsClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-arms");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("arms")) {
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
			dev.halloween.game.Weather.forced = dev.halloween.game.Weather.CLEAR;
			server.runCommand("hw start");
			ctx.waitTicks(80);
			ctx.runOnClient(mc -> {
				ClientDarkness.enabled = false;
				mc.options.setCameraType(CameraType.FIRST_PERSON);
			});
			server.runCommand("time set noon");
			this.stage(server);
			ctx.waitTicks(10);
			ctx.takeScreenshot("arms_empty");
			for (HwItems.Kind kind : new HwItems.Kind[]{HwItems.Kind.REVOLVER, HwItems.Kind.RIFLE, HwItems.Kind.SHOTGUN, HwItems.Kind.BAT, HwItems.Kind.KITCHEN_KNIFE,
				HwItems.Kind.ICE_PICK, HwItems.Kind.FLASHLIGHT}) {
				this.hold(ctx, server, kind);
				ctx.takeScreenshot("arms_" + kind.key());
			}
			// Aiming the rifle, and its kick.
			this.hold(ctx, server, HwItems.Kind.RIFLE);
			ViewArms.testAim = true;
			ctx.waitTicks(8);
			ctx.takeScreenshot("arms_rifle_aim");
			this.act(ctx, ViewArms.Act.RECOIL, 1, "arms_rifle_recoil");
			ViewArms.testAim = null;
			ctx.waitTicks(8);
			this.act(ctx, ViewArms.Act.RELOAD, 6, "arms_rifle_reload");
			this.hold(ctx, server, HwItems.Kind.REVOLVER);
			ViewArms.testAim = true;
			ctx.waitTicks(8);
			ctx.takeScreenshot("arms_revolver_aim");
			ViewArms.testAim = null;
			// Swings.
			this.hold(ctx, server, HwItems.Kind.BAT);
			this.swing(ctx, "arms_bat_swing");
			this.hold(ctx, server, HwItems.Kind.KITCHEN_KNIFE);
			this.swing(ctx, "arms_knife_swing");
			// A throw, a spray, a shove, a door.
			this.hold(ctx, server, HwItems.Kind.BRICK);
			this.act(ctx, ViewArms.Act.THROW, 3, "arms_throw_back");
			this.act(ctx, ViewArms.Act.THROW, 6, "arms_throw_out");
			this.hold(ctx, server, HwItems.Kind.HEALING_SPRAY);
			this.act(ctx, ViewArms.Act.SPRAY, 4, "arms_spray");
			this.hold(ctx, server, null);
			this.act(ctx, ViewArms.Act.SHOVE, 4, "arms_shove");
			this.act(ctx, ViewArms.Act.DOOR, 4, "arms_door");
			this.act(ctx, ViewArms.Act.VAULT, 6, "arms_vault");
			// Jobs held down (the server shows them as a progress bar).
			for (String job : new String[]{"search", "call_police", "lock", "wires", "med_kit", "locker"}) {
				ctx.runOnClient(mc -> ClientState.progress = new HwNet.Progress("halloween.hud.progress." + job, 0.5f, false));
				ctx.waitTicks(6);
				ctx.takeScreenshot("arms_job_" + job);
			}
			ctx.runOnClient(mc -> ClientState.progress = HwNet.Progress.NONE);
			// In Michael's grip, and the Runt at the throat.
			ctx.runOnClient(mc -> ClientState.qte = new HwNet.Qte(true, false, 0.3f, 0.2f, 40, mc.level.getGameTime(), 100, 0, 3, 0, 3, false, false));
			ctx.waitTicks(6);
			ctx.takeScreenshot("arms_grabbed");
			ctx.runOnClient(mc -> ClientState.qte = HwNet.Qte.INACTIVE);
			ctx.runOnClient(mc -> ClientAnims.receive(new HwNet.Anim(mc.player.getId(), HwNet.Anim.STRUGGLE)));
			ctx.waitTicks(6);
			ctx.takeScreenshot("arms_strangled");
			ctx.runOnClient(mc -> ClientAnims.receive(new HwNet.Anim(mc.player.getId(), HwNet.Anim.NONE)));
			server.runCommand("hw stop");
			ctx.waitTicks(10);

			// ---------------------------------------------------------------- the Runt
			String me = server.computeOnServer(s -> player(s).getScoreboardName());
			server.runOnServer(s -> HwConfig.get().runtEnabled = true);
			server.runCommand("hw runt " + me);
			server.runCommand("hw start");
			ctx.waitTicks(80);
			server.runCommand("time set noon");
			this.stage(server);
			ctx.waitTicks(10);
			ctx.takeScreenshot("arms_runt_idle");
			ctx.getInput().holdKey(o -> o.keyShift);
			ctx.waitTicks(8);
			ctx.takeScreenshot("arms_runt_crouch");
			ctx.getInput().releaseKey(o -> o.keyShift);
			ctx.waitTicks(4);
			this.act(ctx, ViewArms.Act.KNOCK, 3, "arms_runt_knock");
			server.runOnServer(s -> {
				ServerPlayer runt = player(s);
				// A townsperson (a survivor bot would knock him straight off).
				var bot = dev.halloween.game.Townsfolk.bodies().stream().filter(m -> !dev.halloween.game.Lockers.isInside(m)).findFirst().orElseThrow();
				bot.teleportTo(runt.getX(), runt.getY(), runt.getZ() + 3);
				runt.teleportTo(s.overworld(), bot.getX() + 0.3, bot.getY() + 0.3, bot.getZ(), java.util.Set.of(), 0f, 0f, false);
				Runt.state(runt).leapTicks = 1;
			});
			ctx.waitTicks(15);
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
			ctx.waitTicks(4);
			ctx.takeScreenshot("arms_runt_holding");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			dev.halloween.game.Weather.forced = -1;
			ctx.runOnClient(mc -> ClientDarkness.enabled = true);
		}
		LOG.info("Arms client test finished OK");
	}

	/** Up on a clear stage in the air, looking ahead and a little down. */
	private void stage(TestServerContext server) {
		server.runOnServer(s -> {
			ServerPlayer p = player(s);
			int x = (int) Math.floor(p.getX());
			int z = (int) Math.floor(p.getZ());
			s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(),
				"fill " + (x - 8) + " 150 " + (z - 8) + " " + (x + 8) + " 150 " + (z + 8) + " minecraft:smooth_stone");
			p.teleportTo(s.overworld(), x + 0.5, 151, z + 0.5, java.util.Set.of(), 0f, 10f, false);
		});
	}

	private void hold(ClientGameTestContext ctx, TestServerContext server, HwItems.Kind kind) {
		server.runOnServer(s -> player(s).setItemInHand(InteractionHand.MAIN_HAND, kind == null ? net.minecraft.world.item.ItemStack.EMPTY : HwItems.create(kind)));
		ctx.waitTicks(12);
	}

	private void act(ClientGameTestContext ctx, ViewArms.Act a, int after, String shot) {
		ctx.runOnClient(mc -> ViewArms.trigger(a));
		ctx.waitTicks(after);
		ctx.takeScreenshot(shot);
		ctx.waitTicks(16);
	}

	private void swing(ClientGameTestContext ctx, String shot) {
		ctx.runOnClient(mc -> mc.player.swing(InteractionHand.MAIN_HAND));
		ctx.waitTicks(2);
		ctx.takeScreenshot(shot);
		ctx.waitTicks(10);
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
