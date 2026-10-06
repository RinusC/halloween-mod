package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.client.CameraAim;
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
 * execution, admiring the corpse, light/heavy strikes, no push, dash, impulse, and the civilian QTE.
 * Screenshots land in build/run/clientGameTest/screenshots.
 */
public class SliceOneClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-test");

	private BlockPos origin;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("slice")) {
			return;
		}
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
			check(server.computeOnServer(s -> shape(s).impulseCooldown > 0), "the detection impulse is on cooldown at the start");

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
			this.doorsAndWindowsTest(ctx, server);
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
		for (int i = 0; i < 120 && stalk < 1f; i++) {
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
		for (int i = 0; i < 300 && stalk < 3f; i++) {
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
		// Under a street lamp: refused, Shape Jump needs darkness.
		this.tp(server, 6.5, 0, -3.5);
		ctx.waitTicks(10);
		ctx.getInput().pressKey(HalloweenClient.KEY_SHAPE_JUMP);
		ctx.waitTicks(5);
		check(server.computeOnServer(s -> shape(s).shadowTicks == 0), "Shape Jump is refused in light");

		// Bloodthirst level 3 shows beams over the victims, for 2 s out of every 10.
		server.runCommand("execute as @p run hw bloodthirst 250");
		this.tp(server, -10.5, 0, 26.5);
		this.lookAt(ctx, this.at(0, 1, 0));
		ctx.waitTicks(10);
		ctx.getInput().pressKey(HalloweenClient.KEY_SHAPE_JUMP);
		ctx.waitTicks(6);
		check(server.computeOnServer(s -> shape(s).shadowTicks > 0 && player(s).isInvisible()), "Shape Jump enters the shadow in the dark");
		check(Identifier.fromNamespaceAndPath("halloween", "shape_jump").equals(ctx.computeOnClient(mc -> mc.gameRenderer.currentPostEffect())),
			"Shape Jump switches on its shader");
		check(ctx.computeOnClient(mc -> ClientState.hud != null && !ClientState.hud.beams().isEmpty()), "beams point at the victims");
		ctx.takeScreenshot("05_shape_jump_beams");

		// No time limit.
		ctx.waitTicks(160);
		check(server.computeOnServer(s -> shape(s).shadowTicks > 150), "Shape Jump lasts as long as the Shape wants");
		check(ctx.computeOnClient(mc -> ClientState.hud != null && ClientState.hud.beams().isEmpty()), "the beams flash up only now and then");

		// A shadow can't work a breaker (nor a door).
		BlockPos bulb = this.origin.offset(-2, 4, 12);
		BlockPos lever = this.origin.offset(5, 1, 15);
		this.tp(server, 6.5, 0, 15.5);
		ctx.waitTicks(5);
		ctx.getInput().lookAt(lever);
		ctx.waitTicks(2);
		ctx.getInput().pressKey(o -> o.keyUse);
		ctx.waitTicks(5);
		check(server.computeOnServer(s -> s.overworld().getBlockState(bulb).getValue(CopperBulbBlock.LIT)), "in Shape Jump the breaker can't be switched");

		// Leaving under the lamp is refused.
		this.tp(server, 6.5, 0, -3.5);
		ctx.waitTicks(5);
		ctx.getInput().pressKey(HalloweenClient.KEY_SHAPE_JUMP);
		ctx.waitTicks(5);
		check(server.computeOnServer(s -> shape(s).shadowTicks > 0), "Shape Jump can't be left in light");
		ctx.takeScreenshot("05b_cant_leave_in_light");

		// Back in the dark it can be left, and the cooldown starts.
		this.tp(server, -10.5, 0, 26.5);
		ctx.waitTicks(5);
		ctx.getInput().pressKey(HalloweenClient.KEY_SHAPE_JUMP);
		ctx.waitTicks(5);
		check(server.computeOnServer(s -> shape(s).shadowTicks == 0 && shape(s).jumpCooldown > 0), "Shape Jump is left in the dark and the cooldown starts");
		server.runOnServer(s -> shape(s).jumpCooldown = 0);
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
		String shuffle = server.computeOnServer(s -> {
			var speed = player(s).getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
			return speed.getValue() > 0.001 && speed.getValue() < speed.getBaseValue() * 0.3 ? "ok" : speed.getValue() + " of " + speed.getBaseValue();
		});
		check("ok".equals(shuffle), "holding someone the Shape can only shuffle along very slowly (" + shuffle + ")");
		check(ctx.computeOnClient(mc -> ClientState.qte.active() && ClientState.qte.forShape()), "the Shape gets his reaction ring");
		check(ctx.computeOnClient(mc -> ClientState.qte.window() && ClientState.qte.bloody()), "ring shows window and bloody execution hints");
		ctx.takeScreenshot("06_grab_ring");
		// Turned away from the window the throw is off; back to it, on again (he may shuffle up to one mid-grab).
		this.lookAt(ctx, this.at(-3.5, 1.2, 11.6));
		ctx.waitTicks(12);
		check(ctx.computeOnClient(mc -> !ClientState.qte.window()), "turned away from the window, the throw is off");
		this.lookAt(ctx, this.at(2.5, 1.2, 9.5));
		ctx.waitTicks(12);
		check(ctx.computeOnClient(mc -> ClientState.qte.window()), "facing a window again mid-grab, the throw is back on at once");

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
			.filter(p -> s.overworld().getBlockState(p).is(net.minecraft.world.level.block.Blocks.GLASS_PANE)).count());
		check(server.computeOnServer(s -> this.victimNear(s, 2.5, 9.5) == null), "the victim is dead");
		check(glassLeft == 0, "the window was smashed (shards left in the frame)");
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

		server.runOnServer(s -> shape(s).impulseCooldown = 0);
		ctx.getInput().pressKey(HalloweenClient.KEY_IMPULSE);
		ctx.waitTicks(5);
		check(ctx.computeOnClient(mc -> ClientState.impulseActive() && (!ClientState.IMPULSE_IDS.isEmpty() || !ClientState.impulseSpots.isEmpty())),
			"detection impulse reveals victims");
		ctx.takeScreenshot("09_impulse_hud");
	}

	private void doorsAndWindowsTest(ClientGameTestContext ctx, TestServerContext server) {
		server.runCommand("hw role @p civilian");
		ctx.waitTicks(10);
		BlockPos door = this.origin.offset(0, 0, 8);

		// Lock: crouch and hold use.
		this.tp(server, 0.5, 0, 6.6);
		this.lookAt(ctx, this.at(0.5, 1.0, 8.5));
		int lockTicks = this.holdLockUntil(ctx, server, door, true);
		check(lockTicks > 0, "a civilian locks the door (" + lockTicks + " ticks)");
		ctx.takeScreenshot("11_locked_door_prompt");
		ctx.getInput().pressKey(o -> o.keyUse);
		ctx.waitTicks(5);
		check(!server.computeOnServer(s -> s.overworld().getBlockState(door).getValue(net.minecraft.world.level.block.DoorBlock.OPEN)), "a locked door does not open");

		// Unlock takes about twice as long.
		int unlockTicks = this.holdLockUntil(ctx, server, door, false);
		LOG.info("Lock {} ticks, unlock {} ticks", lockTicks, unlockTicks);
		check(unlockTicks >= lockTicks * 1.7, "unlocking takes twice as long (" + lockTicks + " vs " + unlockTicks + ")");
		check(this.holdLockUntil(ctx, server, door, true) > 0, "locked again for the Shape");

		// Slow climb through an intact window: no damage, glass stays.
		BlockPos pane = this.origin.offset(-2, 1, 8);
		this.tp(server, -2.5, 0, 6.8);
		this.lookAt(ctx, this.at(-2.5, 1.5, 8.5));
		ctx.takeScreenshot("12_window_prompt");
		ctx.getInput().pressKey(o -> o.keyUse);
		ctx.waitTicks(10);
		ctx.takeScreenshot("13_climbing");
		ctx.waitTicks(HwConfig.get().vaultSlowTicks);
		check(server.computeOnServer(s -> player(s).getZ()) > this.origin.getZ() + 8.6, "slow climb gets inside");
		check(server.computeOnServer(s -> player(s).getHealth()) >= 20f, "slow climb through glass does no damage");
		check(server.computeOnServer(s -> s.overworld().getBlockState(pane).is(net.minecraft.world.level.block.Blocks.GLASS_PANE)), "slow climb leaves the glass");

		// Fast jump back out: 1 s, smashes the glass, costs a fifth of health.
		this.lookAt(ctx, this.at(-2.5, 1.5, 8.5));
		ctx.getInput().holdKey(o -> o.keySprint);
		ctx.getInput().pressKey(o -> o.keyUse);
		ctx.waitTicks(3);
		ctx.getInput().releaseKey(o -> o.keySprint);
		ctx.waitTicks(HwConfig.get().vaultFastTicks + 4);
		check(server.computeOnServer(s -> player(s).getZ()) < this.origin.getZ() + 8.4, "fast jump gets outside");
		float afterFast = server.computeOnServer(s -> player(s).getHealth());
		check(Math.abs(afterFast - 16f) < 0.01f, "fast jump costs a fifth of health (" + afterFast + ")");
		check(server.computeOnServer(s -> s.overworld().getBlockState(pane).is(dev.halloween.HwBlocks.BROKEN_GLASS_PANE)), "fast jump leaves broken glass");
		this.lookAt(ctx, this.at(-2.5, 1.5, 8.5));
		ctx.takeScreenshot("14_broken_window");

		// Even a slow climb through shards hurts.
		this.lookAt(ctx, this.at(-2.5, 1.5, 8.5));
		ctx.getInput().pressKey(o -> o.keyUse);
		ctx.waitTicks(HwConfig.get().vaultSlowTicks + 6);
		float afterShards = server.computeOnServer(s -> player(s).getHealth());
		check(Math.abs(afterShards - 12f) < 0.01f, "shards cut on a slow climb too (" + afterShards + ")");

		// Too hurt for a fast jump.
		server.runOnServer(s -> player(s).setHealth(3f));
		double zBefore = server.computeOnServer(s -> player(s).getZ());
		this.lookAt(ctx, this.at(-2.5, 1.5, 8.5));
		ctx.getInput().holdKey(o -> o.keySprint);
		ctx.getInput().pressKey(o -> o.keyUse);
		ctx.waitTicks(3);
		ctx.getInput().releaseKey(o -> o.keySprint);
		ctx.waitTicks(HwConfig.get().vaultFastTicks + 4);
		check(Math.abs(server.computeOnServer(s -> player(s).getZ()) - zBefore) < 0.5, "no fast jump with a fifth of health or less");
		server.runOnServer(s -> player(s).setHealth(20f));

		// The Shape: a locked door is a wall, Shape Jump goes through it, blows break it.
		server.runCommand("hw role @p shape");
		ctx.waitTicks(10);
		this.tp(server, 0.5, 0, 6.4);
		this.lookAt(ctx, this.at(0.5, 1.0, 12));
		ctx.getInput().pressKey(o -> o.keyUse);
		ctx.waitTicks(5);
		check(!server.computeOnServer(s -> s.overworld().getBlockState(door).getValue(net.minecraft.world.level.block.DoorBlock.OPEN)), "the Shape can't open a locked door");
		server.runOnServer(s -> shape(s).jumpCooldown = 0);
		ctx.getInput().pressKey(HalloweenClient.KEY_SHAPE_JUMP);
		ctx.waitTicks(6);
		check(server.computeOnServer(s -> shape(s).shadowTicks > 0), "Shape Jump before the door");
		ctx.getInput().holdKeyFor(o -> o.keyUp, 30);
		ctx.waitTicks(5);
		check(server.computeOnServer(s -> player(s).getZ()) > this.origin.getZ() + 9.2, "Shape Jump walks through the locked door");
		ctx.getInput().pressKey(HalloweenClient.KEY_SHAPE_JUMP);
		ctx.waitTicks(5);
		check(server.computeOnServer(s -> shape(s).shadowTicks == 0), "and comes out inside");

		double insideZ = server.computeOnServer(s -> player(s).getZ() - this.origin.getZ());
		LOG.info("Shape inside at z={}", insideZ);
		this.tp(server, 0.5, 0, 10.0);
		this.lookAt(ctx, this.at(0.5, 1.0, 8.5));
		for (int i = 0; i < 2; i++) {
			ctx.getInput().holdKey(o -> o.keyAttack);
			ctx.waitTicks(HwConfig.get().heavyChargeTicks + 4);
			ctx.getInput().releaseKey(o -> o.keyAttack);
			ctx.waitTicks(HwConfig.get().heavyCooldownTicks + 4);
		}
		check(server.computeOnServer(s -> dev.halloween.game.DoorLocks.isLockBroken(s.overworld(), door)), "two heavy blows break the lock");
		check(server.computeOnServer(s -> s.overworld().getBlockState(door).getValue(net.minecraft.world.level.block.DoorBlock.OPEN)), "the broken door swings open");
		ctx.takeScreenshot("15_door_broken");
	}

	/** Crouch and hold use on the door until its lock flips; returns ticks taken or -1. */
	private int holdLockUntil(ClientGameTestContext ctx, TestServerContext server, BlockPos door, boolean locked) {
		ctx.getInput().holdKey(o -> o.keyShift);
		ctx.waitTicks(2);
		ctx.getInput().holdKey(o -> o.keyUse);
		int result = -1;
		for (int t = 1; t <= 120; t++) {
			ctx.waitTicks(1);
			if (server.computeOnServer(s -> dev.halloween.game.DoorLocks.isLocked(s.overworld(), door)) == locked) {
				result = t;
				break;
			}
		}
		ctx.getInput().releaseKey(o -> o.keyUse);
		ctx.getInput().releaseKey(o -> o.keyShift);
		ctx.waitTicks(5);
		return result;
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
				// Aim the camera ray (screen centre), which is what the server uses for stalking.
				float[] rot = rotationTo(CameraAim.origin(), best);
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

	/** Turns so the reticle (camera ray through the screen centre) points at the target. */
	private void lookAt(ClientGameTestContext ctx, Vec3 target) {
		// The shoulder camera moves as we turn, so repeat until the centre ray really points at the target.
		for (int i = 0; i < 12; i++) {
			ctx.waitTicks(2);
			double error = ctx.computeOnClient(mc -> {
				Vec3 want = target.subtract(CameraAim.origin()).normalize();
				return Math.toDegrees(Math.acos(Mth.clamp(want.dot(CameraAim.direction()), -1, 1)));
			});
			if (error < 0.5) {
				break;
			}
			float[] rot = ctx.computeOnClient(mc -> rotationTo(CameraAim.origin(), target));
			ctx.getInput().lookAt(rot[0], rot[1]);
		}
		ctx.waitTicks(2);
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
