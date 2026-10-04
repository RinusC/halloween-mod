package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientState;
import dev.halloween.client.HalloweenClient;
import dev.halloween.game.FocusPoints;
import dev.halloween.game.MatchManager;
import dev.halloween.game.ShapeState;
import dev.halloween.net.HwNet;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plays through slice 1 in a real client: builds the arena, stalks a mannequin through a window,
 * Shape Jumps, kills the breaker, grabs and finishes a victim through the window, strikes another,
 * and shows the civilian QTE. Screenshots land in the run directory's screenshots folder.
 */
public class SliceOneClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-test");

	private BlockPos origin;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);

			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			this.origin = server.computeOnServer(s -> player(s).blockPosition());
			LOG.info("Arena origin {}", this.origin);

			server.runCommand("hw role @p shape");
			ctx.waitTicks(20);
			check(ctx.computeOnClient(mc -> ClientState.myRole()) == dev.halloween.Role.SHAPE, "client knows it is the Shape");

			this.tp(server, 2.5, 0, 4.5);
			this.lookAt(ctx, new Vec3(this.origin.getX() + 2.5, this.origin.getY() + 1.5, this.origin.getZ() + 9.5));
			ctx.waitTicks(10);
			ctx.takeScreenshot("01_street_view_house");

			this.stalkTest(ctx, server);
			this.shapeJumpTest(ctx, server);
			this.breakerTest(ctx, server);
			this.windowFinisherTest(ctx, server);
			this.strikeTest(ctx, server);
			this.civilianHudTest(ctx, server);

			server.runCommand("hw role @p none");
			ctx.waitTicks(5);
		}
		LOG.info("Slice 1 client test finished OK");
	}

	// ---------------------------------------------------------------- steps

	private void stalkTest(ClientGameTestContext ctx, TestServerContext server) {
		Mannequin target = server.computeOnServer(s -> this.mannequinNear(s, 2.5, 9.5));
		check(target != null, "window mannequin exists");
		int id = target.getId();

		ctx.getInput().holdKey(HalloweenClient.KEY_SENSE);
		float stalk = 0;
		for (int i = 0; i < 160 && stalk < 100f; i++) {
			// Keep the crosshair on whichever focus point the server currently highlights.
			ctx.runOnClient(mc -> {
				HwNet.VictimInfo info = ClientState.VICTIMS.get(id);
				if (info != null && mc.level.getEntity(id) instanceof LivingEntity living) {
					Vec3 p = FocusPoints.get(living, info.focus(), 1f);
					float[] rot = rotationTo(mc.player.getEyePosition(), p);
					mc.player.setYRot(rot[0]);
					mc.player.setXRot(rot[1]);
				}
			});
			ctx.waitTicks(5);
			if (i == 12) {
				ctx.takeScreenshot("02_killer_sense_mask");
			}
			stalk = server.computeOnServer(s -> MatchManager.stalkOf(this.mannequinNear(s, 2.5, 9.5)));
		}
		ctx.getInput().releaseKey(HalloweenClient.KEY_SENSE);
		float finalStalk = stalk;
		float bloodthirst = server.computeOnServer(s -> shape(s).bloodthirst);
		LOG.info("Stalk after sense: {}, bloodthirst {}", finalStalk, bloodthirst);
		check(finalStalk >= 100f, "stalk reaches 100 through the window (got " + finalStalk + ")");
		check(bloodthirst > 0, "stalking feeds Bloodthirst");
		ctx.waitTicks(10);
		ctx.takeScreenshot("02b_after_stalk_hud");
	}

	private void shapeJumpTest(ClientGameTestContext ctx, TestServerContext server) {
		// Under a street lamp the jump must be refused.
		this.tp(server, 6.5, 0, -3.5);
		ctx.waitTicks(10);
		ctx.getInput().pressKey(HalloweenClient.KEY_SHAPE_JUMP);
		ctx.waitTicks(5);
		int litShadow = server.computeOnServer(s -> shape(s).shadowTicks);
		check(litShadow == 0, "Shape Jump is blocked in light");

		// In the dark backyard it works.
		this.tp(server, -10.5, 0, 26.5);
		ctx.waitTicks(10);
		ctx.getInput().pressKey(HalloweenClient.KEY_SHAPE_JUMP);
		ctx.waitTicks(5);
		int shadow = server.computeOnServer(s -> shape(s).shadowTicks);
		boolean invisible = server.computeOnServer(s -> player(s).isInvisible());
		check(shadow > 0 && invisible, "Shape Jump enters the shadow in the dark (ticks " + shadow + ")");
		ctx.getInput().holdKeyFor(o -> o.keyUp, 20);
		ctx.takeScreenshot("03_shape_jump_overlay");
		ctx.waitTicks(HwConfig.get().shapeJumpDurationTicks);
		int after = server.computeOnServer(s -> shape(s).shadowTicks);
		int cooldown = server.computeOnServer(s -> shape(s).jumpCooldown);
		check(after == 0 && cooldown > 0, "shadow ends and the cooldown starts");
	}

	private void breakerTest(ClientGameTestContext ctx, TestServerContext server) {
		BlockPos bulb = this.origin.offset(-2, 4, 12);
		BlockPos lever = this.origin.offset(5, 1, 15);
		check(server.computeOnServer(s -> s.overworld().getBlockState(bulb).getValue(CopperBulbBlock.LIT)), "house light starts on");
		this.tp(server, 6.5, 0, 15.5);
		ctx.waitTicks(5);
		ctx.getInput().lookAt(lever);
		ctx.waitTicks(2);
		ctx.getInput().pressKey(o -> o.keyUse);
		ctx.waitTicks(5);
		boolean lit = server.computeOnServer(s -> s.overworld().getBlockState(bulb).getValue(CopperBulbBlock.LIT));
		check(!lit, "breaker turns the house light off");
		this.tp(server, 0.5, 0, 13.5);
		this.lookAt(ctx, new Vec3(this.origin.getX() + 0.5, this.origin.getY() + 1.6, this.origin.getZ() + 8.5));
		ctx.waitTicks(10);
		ctx.takeScreenshot("04_house_dark_after_breaker");
	}

	private void windowFinisherTest(ClientGameTestContext ctx, TestServerContext server) {
		server.runOnServer(s -> HwConfig.get().mannequinQteSuccessChance = 0f);
		float bloodthirstBefore = server.computeOnServer(s -> shape(s).bloodthirst);
		this.tp(server, 2.5, 0, 11.6);
		this.lookAt(ctx, new Vec3(this.origin.getX() + 2.5, this.origin.getY() + 1.2, this.origin.getZ() + 9.5));
		ctx.waitTicks(5);
		ctx.getInput().pressKey(o -> o.keyUse);
		ctx.waitTicks(5);
		check(server.computeOnServer(s -> shape(s).grab != null), "right click grabs the victim");
		ctx.takeScreenshot("05_grab");
		int waited = server.waitFor(s -> this.mannequinNear(s, 2.5, 9.5) == null && shape(s).grab == null, 400);
		LOG.info("Grab resolved after {} ticks", waited);
		ctx.waitTicks(4);
		ctx.takeScreenshot("06_after_window_finisher");
		float bloodthirstAfter = server.computeOnServer(s -> shape(s).bloodthirst);
		long glassLeft = server.computeOnServer(s -> BlockPos.betweenClosedStream(this.origin.offset(2, 1, 8), this.origin.offset(3, 2, 8))
			.filter(p -> !s.overworld().getBlockState(p).isAir()).count());
		LOG.info("Bloodthirst {} -> {}, panes left at front-right window: {}", bloodthirstBefore, bloodthirstAfter, glassLeft);
		check(bloodthirstAfter - bloodthirstBefore >= HwConfig.get().bloodthirstPerWindowKill - 0.01f, "window kill adds window Bloodthirst");
		check(glassLeft < 4, "the window was smashed");
		server.runOnServer(s -> HwConfig.get().mannequinQteSuccessChance = 0.35f);
	}

	private void strikeTest(ClientGameTestContext ctx, TestServerContext server) {
		this.tp(server, 7.5, 0, -1.6);
		this.lookAt(ctx, new Vec3(this.origin.getX() + 7.5, this.origin.getY() + 1.0, this.origin.getZ() - 3.5));
		ctx.waitTicks(30);
		float before = server.computeOnServer(s -> this.mannequinNear(s, 7.5, -3.5).getHealth());
		ctx.getInput().pressKey(o -> o.keyAttack);
		ctx.waitTicks(5);
		Mannequin after = server.computeOnServer(s -> this.mannequinNear(s, 7.5, -3.5));
		float health = after == null ? 0 : after.getHealth();
		LOG.info("Strike: mannequin health {} -> {}", before, health);
		check(health < before, "knife strike hurts the victim");
		ctx.takeScreenshot("07_strike_under_lamp");
	}

	private void civilianHudTest(ClientGameTestContext ctx, TestServerContext server) {
		server.runCommand("hw role @p civilian");
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> {
			long now = mc.level.getGameTime();
			ClientState.qte = new HwNet.Qte(true, 0.4f, 0.22f, 30, now - 7, 50, 1, 3, 1, 3);
		});
		ctx.waitTicks(1);
		ctx.takeScreenshot("08_civilian_qte");
		ctx.runOnClient(mc -> ClientState.qte = HwNet.Qte.INACTIVE);
	}

	// ---------------------------------------------------------------- helpers

	private static ServerPlayer player(MinecraftServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		return players.get(0);
	}

	private static ShapeState shape(MinecraftServer server) {
		return MatchManager.shapeState(player(server));
	}

	private Mannequin mannequinNear(MinecraftServer server, double dx, double dz) {
		Vec3 p = new Vec3(this.origin.getX() + dx, this.origin.getY() + 1, this.origin.getZ() + dz);
		return server.overworld().getEntitiesOfClass(Mannequin.class, new AABB(p, p).inflate(1.5), Mannequin::isAlive)
			.stream().findFirst().orElse(null);
	}

	private void tp(TestServerContext server, double dx, double dy, double dz) {
		server.runCommand(String.format(Locale.ROOT, "tp @p %.2f %.2f %.2f",
			this.origin.getX() + dx, this.origin.getY() + dy, this.origin.getZ() + dz));
	}

	private void lookAt(ClientGameTestContext ctx, Vec3 target) {
		ctx.waitTicks(2);
		float[] rot = ctx.computeOnClient(mc -> rotationTo(mc.player.getEyePosition(), target));
		ctx.getInput().lookAt(rot[0], rot[1]);
	}

	private static float[] rotationTo(Vec3 eye, Vec3 target) {
		Vec3 d = target.subtract(eye);
		float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
		float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
		return new float[]{yaw, pitch};
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
