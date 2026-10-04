package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.Role;
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
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
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
 * Plays through the Shape's kit in a real client on the test arena: passive and sensed stalking,
 * focus, Shape Jump with beams, the breaker, a grab won through reaction checks ending in a window
 * execution, admiring the corpse, light/heavy strikes, push, dash, impulse, and the civilian QTE.
 * Screenshots land in build/run/clientGameTest/screenshots.
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
			check(ctx.computeOnClient(mc -> ClientState.myRole()) == Role.SHAPE, "client knows it is the Shape");
			check(ctx.computeOnClient(mc -> mc.options.getCameraType()) == CameraType.THIRD_PERSON_BACK, "the Shape gets the over-the-shoulder camera");

			this.tp(server, 2.5, 0, 4.5);
			this.lookAt(ctx, this.at(2.5, 1.5, 9.5));
			ctx.waitTicks(10);
			ctx.takeScreenshot("01_shoulder_camera");

			this.passiveStalkTest(ctx, server);
			this.senseStalkTest(ctx, server);
			this.shapeJumpTest(ctx, server);
			this.breakerTest(ctx, server);
			this.grabAndWindowTest(ctx, server);
			this.corpseTest(ctx, server);
			this.strikeAndPushTest(ctx, server);
			this.dashAndImpulseTest(ctx, server);
			this.civilianHudTest(ctx, server);

			server.runCommand("hw role @p none");
			ctx.waitTicks(5);
		}
		LOG.info("Slice 1 client test finished OK");
	}

	// ---------------------------------------------------------------- steps

	private void passiveStalkTest(ClientGameTestContext ctx, TestServerContext server) {
		// Just watching through the window: stalking stops at level 1 without Killer Sense.
		float stalk = 0;
		for (int i = 0; i < 60 && stalk < 1f; i++) {
			this.lookAt(ctx, this.at(2.5, 1.2, 9.5));
			ctx.waitTicks(5);
			stalk = server.computeOnServer(s -> MatchManager.stalkOf(this.victimNear(s, 2.5, 9.5)));
		}
		check(stalk >= 0.999f, "passive stalking reaches level 1 (got " + stalk + ")");
		ctx.waitTicks(60);
		float after = server.computeOnServer(s -> MatchManager.stalkOf(this.victimNear(s, 2.5, 9.5)));
		check(after <= 1.0001f, "passive stalking never goes past level 1 (got " + after + ")");
	}

	private void senseStalkTest(ClientGameTestContext ctx, TestServerContext server) {
		Mannequin target = server.computeOnServer(s -> this.victimNear(s, 2.5, 9.5));
		int id = target.getId();
		ctx.getInput().holdKey(HalloweenClient.KEY_SENSE);
		ctx.waitTicks(5);
		check(Identifier.fromNamespaceAndPath("halloween", "killer_sense").equals(ctx.computeOnClient(mc -> mc.gameRenderer.currentPostEffect())),
			"Killer Sense switches on its shader");
		float stalk = 0;
		boolean focusShot = false;
		for (int i = 0; i < 200 && stalk < 3f; i++) {
			this.aimAtNearestFocusPoint(ctx, id);
			ctx.waitTicks(4);
			if (i == 6) {
				ctx.takeScreenshot("02_killer_sense_markers");
				ctx.getInput().holdKey(o -> o.keyUse);
			}
			if (i == 10 && !focusShot) {
				check(ctx.computeOnClient(mc -> ClientState.focusActive()), "holding use in Killer Sense focuses");
				ctx.takeScreenshot("03_focus");
				focusShot = true;
			}
			stalk = server.computeOnServer(s -> MatchManager.stalkOf(this.victimNear(s, 2.5, 9.5)));
		}
		ctx.getInput().releaseKey(o -> o.keyUse);
		ctx.getInput().releaseKey(HalloweenClient.KEY_SENSE);
		float finalStalk = stalk;
		float bloodthirst = server.computeOnServer(s -> shape(s).bloodthirst);
		LOG.info("Stalk after sense: {}, bloodthirst {}", finalStalk, bloodthirst);
		check(finalStalk >= 3f, "Killer Sense takes stalking to level 3 (got " + finalStalk + ")");
		check(bloodthirst > 0, "stalk levels feed Bloodthirst");
		ctx.waitTicks(10);
		check(ctx.computeOnClient(mc -> mc.gameRenderer.currentPostEffect()) == null, "the shader goes away with Killer Sense");
		check(ctx.computeOnClient(mc -> !ClientState.FEED.isEmpty()), "stalk levels show up in the XP feed");
		ctx.takeScreenshot("04_after_stalk_feed");
	}

	private void shapeJumpTest(ClientGameTestContext ctx, TestServerContext server) {
		// Under a street lamp but unseen: allowed (the original needs "unseen OR in shadow").
		this.tp(server, 6.5, 0, -3.5);
		ctx.waitTicks(10);
		ctx.getInput().pressKey(HalloweenClient.KEY_SHAPE_JUMP);
		ctx.waitTicks(5);
		check(server.computeOnServer(s -> shape(s).shadowTicks > 0), "Shape Jump works in light when nobody is watching");
		ctx.getInput().pressKey(HalloweenClient.KEY_SHAPE_JUMP);
		ctx.waitTicks(5);
		check(server.computeOnServer(s -> shape(s).shadowTicks == 0 && shape(s).jumpCooldown > 0), "second press cancels Shape Jump");
		server.runOnServer(s -> shape(s).jumpCooldown = 0);

		// Bloodthirst level 2 shows beams over the victims.
		server.runCommand("execute as @p run hw bloodthirst 150");
		this.tp(server, -10.5, 0, 26.5);
		this.lookAt(ctx, this.at(0, 1, 0));
		ctx.waitTicks(10);
		ctx.getInput().pressKey(HalloweenClient.KEY_SHAPE_JUMP);
		ctx.waitTicks(6);
		check(server.computeOnServer(s -> shape(s).shadowTicks > 0 && player(s).isInvisible()), "Shape Jump enters the shadow");
		check(Identifier.fromNamespaceAndPath("halloween", "shape_jump").equals(ctx.computeOnClient(mc -> mc.gameRenderer.currentPostEffect())),
			"Shape Jump switches on its shader");
		check(ctx.computeOnClient(mc -> ClientState.hud != null && !ClientState.hud.beams().isEmpty()), "beams point at the victims");
		ctx.takeScreenshot("05_shape_jump_beams");
		ctx.waitTicks(HwConfig.get().shapeJumpDurationTicks);
		check(server.computeOnServer(s -> shape(s).shadowTicks == 0 && shape(s).jumpCooldown > 0), "shadow ends and the cooldown starts");
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
		check(!server.computeOnServer(s -> s.overworld().getBlockState(bulb).getValue(CopperBulbBlock.LIT)), "breaker turns the house light off");
	}

	private void grabAndWindowTest(ClientGameTestContext ctx, TestServerContext server) {
		server.runOnServer(s -> HwConfig.get().mannequinQteSuccessChance = 0f);
		server.runCommand("execute as @p run hw bloodthirst 250");
		float before = server.computeOnServer(s -> shape(s).bloodthirst);
		this.tp(server, 2.5, 0, 11.6);
		this.lookAt(ctx, this.at(2.5, 1.2, 9.5));
		ctx.waitTicks(5);
		ctx.getInput().pressKey(o -> o.keyUse);
		ctx.waitTicks(5);
		check(server.computeOnServer(s -> shape(s).grab != null), "right click grabs the victim");
		check(ctx.computeOnClient(mc -> ClientState.qte.active() && ClientState.qte.forShape()), "the Shape gets his reaction ring");
		check(ctx.computeOnClient(mc -> ClientState.qte.window() && ClientState.qte.bloody()), "ring shows window and bloody execution hints");
		ctx.takeScreenshot("06_grab_ring");

		// Hit the needle inside the zone twice.
		int presses = 0;
		for (int t = 0; t < 400 && server.computeOnServer(s -> shape(s).grab != null); t++) {
			boolean inZone = ctx.computeOnClient(mc -> {
				HwNet.Qte q = ClientState.qte;
				if (!q.active() || !q.forShape()) {
					return false;
				}
				float needle = HwNet.needle(mc.level.getGameTime() - q.roundStart(), q.period());
				return needle >= q.zoneStart() + 0.02f && needle <= q.zoneStart() + q.zoneWidth() - 0.02f;
			});
			if (inZone) {
				ctx.getInput().holdKey(o -> o.keyAttack);
				ctx.waitTicks(1);
				ctx.getInput().releaseKey(o -> o.keyAttack);
				presses++;
				ctx.waitTicks(4);
			} else {
				ctx.waitTicks(1);
			}
		}
		LOG.info("Grab finished after {} presses", presses);
		ctx.waitTicks(30);
		ctx.takeScreenshot("07_after_window_execution");
		float after = server.computeOnServer(s -> shape(s).bloodthirst);
		long glassLeft = server.computeOnServer(s -> BlockPos.betweenClosedStream(this.origin.offset(2, 1, 8), this.origin.offset(3, 2, 8))
			.filter(p -> !s.overworld().getBlockState(p).isAir()).count());
		check(server.computeOnServer(s -> this.victimNear(s, 2.5, 9.5) == null), "the victim is dead");
		check(glassLeft < 4, "the window was smashed");
		check(server.computeOnServer(s -> !this.corpses(s).isEmpty()), "a corpse is left behind");
		check(after > before, "the kill feeds Bloodthirst (" + before + " -> " + after + ")");
		server.runOnServer(s -> HwConfig.get().mannequinQteSuccessChance = 0.35f);
	}

	private void corpseTest(ClientGameTestContext ctx, TestServerContext server) {
		Vec3 corpse = server.computeOnServer(s -> this.corpses(s).get(0).position());
		server.runCommand("execute as @p run hw bloodthirst 10");
		// Stand outside next to the body (from inside, the wall under the window hides it).
		server.runCommand(String.format(Locale.ROOT, "tp @p %.2f %.2f %.2f", corpse.x, corpse.y, corpse.z - 2.5));
		ctx.waitTicks(5);
		this.lookAt(ctx, corpse.add(0, 0.3, 0));
		ctx.waitTicks(60);
		float bt = server.computeOnServer(s -> shape(s).bloodthirst);
		LOG.info("Bloodthirst after admiring: {}", bt);
		check(bt > 10f, "admiring the corpse feeds Bloodthirst");
	}

	private void strikeAndPushTest(ClientGameTestContext ctx, TestServerContext server) {
		Vec3 lamp = server.computeOnServer(s -> this.victimNear(s, 7.5, -3.5, 4).position());
		server.runCommand(String.format(Locale.ROOT, "tp @p %.2f %.2f %.2f", lamp.x, lamp.y, lamp.z + 1.9));
		ctx.waitTicks(3);
		this.lookAt(ctx, lamp.add(0, 1.0, 0));
		ctx.waitTicks(20);
		float h0 = server.computeOnServer(s -> this.victimNear(s, 7.5, -3.5, 4).getHealth());
		ctx.getInput().holdKey(o -> o.keyAttack);
		ctx.waitTicks(2);
		ctx.getInput().releaseKey(o -> o.keyAttack);
		ctx.waitTicks(20);
		float h1 = server.computeOnServer(s -> this.victimNear(s, 7.5, -3.5, 4).getHealth());
		this.lookAt(ctx, server.computeOnServer(s -> this.victimNear(s, 7.5, -3.5, 4).position()).add(0, 1.0, 0));
		ctx.getInput().holdKey(o -> o.keyAttack);
		ctx.waitTicks(HwConfig.get().heavyChargeTicks + 4);
		ctx.getInput().releaseKey(o -> o.keyAttack);
		ctx.waitTicks(5);
		Mannequin m = server.computeOnServer(s -> this.victimNear(s, 7.5, -3.5, 4));
		float h2 = m == null ? 0 : m.getHealth();
		LOG.info("Strikes: {} -> {} (light) -> {} (heavy)", h0, h1, h2);
		check(h1 < h0, "light attack hurts");
		check(h1 - h2 > h0 - h1, "heavy attack hurts more than light");
		ctx.takeScreenshot("08_strike");

		if (m != null) {
			Vec3 p0 = m.position();
			ctx.waitTicks(30);
			this.lookAt(ctx, m.position().add(0, 1, 0));
			ctx.getInput().pressKey(HalloweenClient.KEY_PUSH);
			ctx.waitTicks(10);
			int mid = m.getId();
			Vec3 p1 = server.computeOnServer(s -> s.overworld().getEntity(mid).position());
			LOG.info("Push moved the victim {} blocks", p0.distanceTo(p1));
			check(p0.distanceTo(p1) > 0.3, "push shoves the victim");
		}
	}

	private void dashAndImpulseTest(ClientGameTestContext ctx, TestServerContext server) {
		this.tp(server, 0.5, 0, 0.5);
		ctx.waitTicks(10);
		ctx.getInput().holdKey(o -> o.keyUp);
		ctx.getInput().pressKey(o -> o.keySprint);
		ctx.waitTicks(3);
		check(server.computeOnServer(s -> shape(s).dashTicks > 0), "sprint key dashes");
		ctx.getInput().releaseKey(o -> o.keyUp);
		ctx.waitTicks(40);

		ctx.getInput().pressKey(HalloweenClient.KEY_IMPULSE);
		ctx.waitTicks(5);
		check(ctx.computeOnClient(mc -> ClientState.impulseActive() && (!ClientState.IMPULSE_IDS.isEmpty() || !ClientState.impulseSpots.isEmpty())),
			"detection impulse reveals victims");
		ctx.takeScreenshot("09_impulse_hud");
	}

	private void civilianHudTest(ClientGameTestContext ctx, TestServerContext server) {
		server.runCommand("hw role @p civilian");
		ctx.waitTicks(10);
		check(ctx.computeOnClient(mc -> mc.options.getCameraType()) == CameraType.FIRST_PERSON, "civilians stay in first person");
		ctx.runOnClient(mc -> {
			long now = mc.level.getGameTime();
			ClientState.qte = new HwNet.Qte(true, false, 0.4f, 0.22f, 30, now - 7, 50, 1, 3, 1, 2, false, false);
		});
		ctx.waitTicks(1);
		ctx.takeScreenshot("10_civilian_qte");
		ctx.runOnClient(mc -> ClientState.qte = HwNet.Qte.INACTIVE);
	}

	// ---------------------------------------------------------------- helpers

	private void aimAtNearestFocusPoint(ClientGameTestContext ctx, int id) {
		ctx.runOnClient(mc -> {
			if (mc.level.getEntity(id) instanceof LivingEntity living) {
				Vec3 eye = mc.player.getEyePosition();
				Vec3 best = null;
				for (int i = 0; i < FocusPoints.COUNT; i++) {
					Vec3 p = FocusPoints.get(living, i, 1f);
					if (best == null || p.distanceTo(eye) < best.distanceTo(eye)) {
						best = p;
					}
				}
				float[] rot = rotationTo(eye, best);
				mc.player.setYRot(rot[0]);
				mc.player.setXRot(rot[1]);
			}
		});
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().get(0);
	}

	private static ShapeState shape(MinecraftServer server) {
		return MatchManager.shapeState(player(server));
	}

	private Vec3 at(double dx, double dy, double dz) {
		return new Vec3(this.origin.getX() + dx, this.origin.getY() + dy, this.origin.getZ() + dz);
	}

	private Mannequin victimNear(MinecraftServer server, double dx, double dz) {
		return this.victimNear(server, dx, dz, 1.5);
	}

	private Mannequin victimNear(MinecraftServer server, double dx, double dz, double radius) {
		Vec3 p = this.at(dx, 1, dz);
		return server.overworld().getEntitiesOfClass(Mannequin.class, new AABB(p, p).inflate(radius),
				m -> m.isAlive() && m.entityTags().contains(MatchManager.VICTIM_TAG))
			.stream().findFirst().orElse(null);
	}

	private List<Mannequin> corpses(MinecraftServer server) {
		return server.overworld().getEntitiesOfClass(Mannequin.class, new AABB(this.origin).inflate(40),
			m -> m.entityTags().contains(MatchManager.CORPSE_TAG));
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
