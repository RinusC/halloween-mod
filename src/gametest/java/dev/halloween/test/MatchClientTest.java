package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.client.CameraAim;
import dev.halloween.client.ClientState;
import dev.halloween.game.Exits;
import dev.halloween.game.HwItems;
import dev.halloween.game.Inventory;
import dev.halloween.game.Loot;
import dev.halloween.game.MapPoints;
import dev.halloween.game.Match;
import dev.halloween.game.MatchManager;
import dev.halloween.net.HwNet;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A solo match on the test arena (a lone player is a civilian, no Shape): search, open the cellar and
 * escape, come back as a deputy, use the flashlight, fix the car, call the police, open the gates,
 * throw a dart, and let the match end.
 */
public class MatchClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-match-test");

	private BlockPos origin;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			this.origin = server.computeOnServer(s -> player(s).blockPosition());
			server.runOnServer(s -> {
				HwConfig cfg = HwConfig.get();
				cfg.countdownSeconds = 2;
				cfg.deputyRespawnSeconds = 2;
				cfg.policeArriveSeconds = 2;
			});

			// Townsfolk get their own test; here they would only wander into the line of fire.
			server.runCommand("hw point clear npc");
			server.runCommand("hw start");
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> Match.state()) == Match.State.COUNTDOWN, "the match counts down");
			ctx.waitTicks(50);
			check(server.computeOnServer(s -> Match.running()), "the match runs");
			check(server.computeOnServer(s -> MatchManager.role(player(s))) == Role.CIVILIAN, "a lone player is a civilian");
			check(ctx.computeOnClient(mc -> ClientState.match.exits().size()) == 4, "four exits are active");
			int containers = server.computeOnServer(s -> Loot.containers().size());
			LOG.info("Containers: {}", containers);
			check(containers >= 12, "the arena's chests and barrels are containers (" + containers + ")");
			ctx.takeScreenshot("m01_match_hud");

			// Search a chest that hides bolt cutters.
			BlockPos chest = this.origin.offset(1, 0, 15);
			server.runOnServer(s -> Loot.hide(chest, HwItems.Kind.BOLT_CUTTERS));
			this.tp(server, 1.5, 0, 13.4);
			this.lookAt(ctx, Vec3.atCenterOf(chest));
			this.holdUse(ctx, HwConfig.get().searchTicks + 12);
			check(server.computeOnServer(s -> Inventory.has(player(s), HwItems.Kind.BOLT_CUTTERS)), "searching finds the bolt cutters");
			ctx.takeScreenshot("m02_found_item");

			// Open the cellar and escape through it.
			HwNet.ExitInfo cellar = this.exit(ctx, Exits.CELLAR);
			BlockPos c = cellar.pos();
			String props = server.computeOnServer(s -> {
				var level = s.overworld();
				for (MapPoints.Type type : new MapPoints.Type[]{MapPoints.Type.CELLAR, MapPoints.Type.CAR, MapPoints.Type.GATE}) {
					int exitType = type == MapPoints.Type.CELLAR ? Exits.CELLAR : type == MapPoints.Type.CAR ? Exits.CAR : Exits.GATE;
					BlockPos used = Exits.infos().stream().filter(e -> e.type() == exitType).findFirst().orElseThrow().pos();
					for (BlockPos p : MapPoints.get(level).list(type)) {
						BlockPos probe = type == MapPoints.Type.CELLAR ? p.below() : p;
						boolean built = Exits.isProp(probe);
						if (built != p.equals(used)) {
							return type.key() + " at " + p.toShortString() + (built ? " has a prop but is unused" : " has no prop but is used");
						}
					}
				}
				return "ok";
			});
			check("ok".equals(props), "only the picked exit spots get their props (" + props + ")");
			server.runCommand(String.format(Locale.ROOT, "tp @p %.1f %d %.1f", c.getX() + 0.5, c.getY(), c.getZ() - 1.5));
			this.lookAt(ctx, new Vec3(c.getX() + 0.5, c.getY() - 0.1, c.getZ() + 0.5));
			this.holdUse(ctx, 5);
			check(server.computeOnServer(s -> !s.overworld().getBlockState(c.below()).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)),
				"the cellar hatch doesn't open by hand");
			this.holdUse(ctx, HwConfig.get().boltCuttersTicks + 12);
			check(this.exit(ctx, Exits.CELLAR).state() == 1, "bolt cutters open the cellar");
			this.holdUse(ctx, HwConfig.get().escapeTicks + 10);
			check(server.computeOnServer(s -> player(s).isSpectator()), "a civilian escapes through the cellar");
			check(this.exit(ctx, Exits.CELLAR).state() == 2, "the cellar lets only one out");

			// Back as a deputy.
			ctx.waitTicks(HwConfig.get().deputyRespawnSeconds * 20 + 20);
			check(server.computeOnServer(s -> MatchManager.role(player(s))) == Role.DEPUTY, "the escaped civilian returns as a deputy");
			check(server.computeOnServer(s -> !player(s).isSpectator() && Inventory.has(player(s), HwItems.Kind.BATON)
				&& Inventory.has(player(s), HwItems.Kind.FLASHLIGHT)), "the deputy has a baton and a flashlight");

			// Flashlight: real light where it points.
			int lightSlot = server.computeOnServer(s -> Inventory.slotOf(player(s), HwItems.Kind.FLASHLIGHT));
			ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(lightSlot));
			this.tp(server, 0.5, 0, 0.5);
			this.lookAt(ctx, new Vec3(this.origin.getX() + 0.5, this.origin.getY() + 1, this.origin.getZ() + 10));
			ctx.waitTicks(10);
			java.util.function.Supplier<Boolean> anyLight = () -> server.computeOnServer(s -> BlockPos.betweenClosedStream(this.origin.offset(-3, -1, -2), this.origin.offset(4, 4, 12))
				.anyMatch(p -> s.overworld().getBlockState(p).is(Blocks.LIGHT)));
			check(!anyLight.get(), "the flashlight starts switched off");
			this.holdUse(ctx, 3);
			ctx.waitTicks(6);
			check(anyLight.get(), "right click switches the flashlight on and it lights up what it points at");
			long beam = ctx.computeOnClient(mc -> {
				String count = mc.particleEngine.countParticles();
				return Long.parseLong(count.substring(count.lastIndexOf(" ") + 1));
			});
			LOG.info("Particles in view: {}", beam);
			check(beam > 5, "the beam is visible");
			ctx.takeScreenshot("m03_deputy_flashlight");
			this.holdUse(ctx, 3);
			ctx.waitTicks(4);
			check(!anyLight.get(), "a second click switches it off");
			ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));

			// The car: a deputy can fix it but not drive off.
			server.runOnServer(s -> {
				Inventory.give(player(s), HwItems.create(HwItems.Kind.CAR_PART));
				Inventory.give(player(s), HwItems.create(HwItems.Kind.REPAIR_KIT));
			});
			ctx.waitTicks(2);
			HwNet.ExitInfo car = this.exit(ctx, Exits.CAR);
			BlockPos cp = car.pos();
			server.runCommand(String.format(Locale.ROOT, "tp @p %.1f %d %.1f", cp.getX() + 0.5, cp.getY(), cp.getZ() - 1.6));
			this.lookAt(ctx, new Vec3(cp.getX() + 0.5, cp.getY() + 0.6, cp.getZ() + 0.5));
			this.holdUse(ctx, HwConfig.get().installPartTicks + 12);
			check((this.exit(ctx, Exits.CAR).state() & 1) != 0, "the engine part goes in");
			this.holdUse(ctx, HwConfig.get().repairCarTicks + 12);
			check((this.exit(ctx, Exits.CAR).state() & 2) != 0, "the car gets repaired");
			ctx.takeScreenshot("m04_car_fixed");

			// The police: call from a phone, the van arrives.
			BlockPos phone = this.origin.offset(3, 1, 14);
			this.tp(server, 3.5, 0, 12.3);
			this.lookAt(ctx, new Vec3(phone.getX() + 0.5, phone.getY() + 0.15, phone.getZ() + 0.5));
			this.holdUse(ctx, HwConfig.get().callPoliceTicks + 12);
			check(this.exit(ctx, Exits.POLICE).state() >= 1, "the phone calls the police");
			ctx.waitTicks(HwConfig.get().policeArriveSeconds * 20 + 20);
			check(this.exit(ctx, Exits.POLICE).state() == 2, "the police van arrives");
			BlockPos van = this.exit(ctx, Exits.POLICE).pos();
			check(server.computeOnServer(s -> net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.overworld().getBlockState(van).getBlock()).getPath().equals("white_concrete")), "the van is built on its spot");

			// The gates open only near the end.
			HwNet.ExitInfo gate = this.exit(ctx, Exits.GATE);
			BlockPos gp = gate.pos();
			double inward = Math.signum(this.origin.getZ() - gp.getZ());
			server.runCommand(String.format(Locale.ROOT, "tp @p %.1f %d %.1f", gp.getX() + 1.0, gp.getY(), gp.getZ() + 0.5 + inward * 1.6));
			this.lookAt(ctx, new Vec3(gp.getX() + 1.0, gp.getY() + 0.5, gp.getZ() + 0.5));
			this.holdUse(ctx, 20);
			check(this.exit(ctx, Exits.GATE).state() == 0, "the gates stay shut early in the match");
			server.runCommand("hw timer 200");
			ctx.waitTicks(5);
			this.holdUse(ctx, HwConfig.get().openGateTicks + 12);
			check(this.exit(ctx, Exits.GATE).state() == 1, "the gates open in the last minutes");
			ctx.takeScreenshot("m05_gates_open");

			// A thrown lawn dart.
			server.runOnServer(s -> Inventory.give(player(s), HwItems.create(HwItems.Kind.DART)));
			ctx.waitTicks(2);
			int dartSlot = server.computeOnServer(s -> Inventory.slotOf(player(s), HwItems.Kind.DART));
			check(dartSlot >= 0, "the dart is in a pocket");
			ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(dartSlot));
			this.tp(server, 0.5, 0, 0.5);
			ctx.getInput().lookAt(0f, -20f);
			ctx.waitTicks(3);
			ctx.getInput().pressKey(o -> o.keyUse);
			ctx.waitTicks(3);
			check(server.computeOnServer(s -> !s.overworld().getEntitiesOfClass(Arrow.class, new AABB(this.origin).inflate(60),
				a -> a.entityTags().contains(dev.halloween.game.Fight.DART_TAG)).isEmpty()), "the lawn dart is thrown");

			// The Shape's side, checked within one server tick (a lone player can't really be both sides:
			// with no civilian left the match would end).
			String sabotage = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				MatchManager.applyRole(p, Role.SHAPE);
				dev.halloween.game.Holds.Hold pull = Exits.resolve(p, cp);
				if (pull == null) {
					return "no pull action";
				}
				pull.done().run(p);
				dev.halloween.game.Fight.stun(p, 1f, null);
				boolean stunned = MatchManager.isStunned(p);
				MatchManager.applyRole(p, Role.DEPUTY);
				return stunned ? "ok" : "not stunned";
			});
			check("ok".equals(sabotage), "the Shape can pull the car part and gets knocked down by a full stun meter (" + sabotage + ")");
			check((this.exit(ctx, Exits.CAR).state() & 3) == 0, "the car lost its part");
			check(server.computeOnServer(s -> !s.overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
				new AABB(cp).inflate(4), e -> HwItems.is(e.getItem(), HwItems.Kind.CAR_PART)).isEmpty()), "the part falls on the ground");

			// Let the clock run out.
			server.runCommand("hw timer 2");
			ctx.waitTicks(60);
			check(server.computeOnServer(s -> Match.state()) == Match.State.IDLE, "the match ends when time runs out");
			check(ctx.computeOnClient(mc -> ClientState.matchEnd != null), "everyone sees the final scoreboard");
			ctx.takeScreenshot("m06_end_screen");
			String scores = server.computeOnServer(s -> Match.scores().toString());
			LOG.info("Scores: {}", scores);
		}
		LOG.info("Match client test finished OK");
	}

	private HwNet.ExitInfo exit(ClientGameTestContext ctx, int type) {
		ctx.waitTicks(12);
		return ctx.computeOnClient(mc -> ClientState.match.exits().stream().filter(e -> e.type() == type).findFirst().orElseThrow());
	}

	private void holdUse(ClientGameTestContext ctx, int ticks) {
		ctx.getInput().holdKey(o -> o.keyUse);
		ctx.waitTicks(ticks);
		ctx.getInput().releaseKey(o -> o.keyUse);
		ctx.waitTicks(4);
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().get(0);
	}

	private void tp(TestServerContext server, double dx, double dy, double dz) {
		server.runCommand(String.format(Locale.ROOT, "tp @p %.2f %.2f %.2f",
			this.origin.getX() + dx, this.origin.getY() + dy, this.origin.getZ() + dz));
	}

	private void lookAt(ClientGameTestContext ctx, Vec3 target) {
		for (int i = 0; i < 10; i++) {
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
