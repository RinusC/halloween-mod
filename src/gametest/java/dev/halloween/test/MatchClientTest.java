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
			server.runOnServer(s -> HwConfig.get().revolverFindChance = 1f);
			this.tp(server, 1.5, 0, 13.4);
			this.lookAt(ctx, Vec3.atCenterOf(chest));
			this.holdUse(ctx, HwConfig.get().searchTicks + 12);
			check(server.computeOnServer(s -> Inventory.has(player(s), HwItems.Kind.BOLT_CUTTERS)), "searching finds the bolt cutters");
			check(server.computeOnServer(s -> Inventory.has(player(s), HwItems.Kind.REVOLVER)), "now and then a container holds a revolver too");
			server.runOnServer(s -> {
				HwConfig.get().revolverFindChance = new HwConfig().revolverFindChance;
				player(s).getInventory().setItem(Inventory.slotOf(player(s), HwItems.Kind.REVOLVER), net.minecraft.world.item.ItemStack.EMPTY);
			});
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
			// The battery runs flat; a spare one from the pockets goes in on the next click.
			server.runOnServer(s -> player(s).getMainHandItem().setDamageValue(player(s).getMainHandItem().getMaxDamage() - 5));
			this.holdUse(ctx, 3);
			ctx.waitTicks(20);
			check(!anyLight.get() && server.computeOnServer(s -> HwItems.charge(player(s).getMainHandItem())) <= 0, "the flashlight goes out when the battery is flat");
			server.runOnServer(s -> Inventory.give(player(s), HwItems.create(HwItems.Kind.BATTERY)));
			ctx.waitTicks(2);
			this.holdUse(ctx, 3);
			ctx.waitTicks(6);
			check(anyLight.get() && !server.computeOnServer(s -> Inventory.has(player(s), HwItems.Kind.BATTERY)), "a spare battery brings it back");
			this.holdUse(ctx, 3);
			ctx.waitTicks(4);
			check(server.computeOnServer(s -> player(s).getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH)) < 0.01,
				"nobody jumps in a match");
			ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));

			this.gunsTest(ctx, server);

			// A dark breaker box: the power comes back by joining the wires on a panel.
			BlockPos breaker = this.origin.offset(-17, 1, 15);
			server.runOnServer(s -> dev.halloween.game.LightSystem.flip(s.overworld(), breaker, false));
			this.tp(server, -15.5, 0, 15.5);
			this.lookAt(ctx, Vec3.atCenterOf(breaker));
			ctx.waitTicks(5);
			this.holdUse(ctx, 3);
			ctx.waitTicks(3);
			check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof dev.halloween.client.WiresScreen), "using a dark breaker box opens the wires panel");
			check(server.computeOnServer(s -> !dev.halloween.game.LightSystem.isOn(s.overworld(), breaker)), "the click alone doesn't switch the power on");
			ctx.takeScreenshot("m03a_wires");
			// Esc and back: the joined wire stays joined.
			ctx.runOnClient(mc -> ((dev.halloween.client.WiresScreen) mc.gui.screen()).join(0));
			ctx.runOnClient(mc -> mc.gui.screen().onClose());
			ctx.waitTicks(3);
			check(server.computeOnServer(s -> !dev.halloween.game.Minigames.active(player(s))), "Esc steps away from the panel");
			this.holdUse(ctx, 3);
			ctx.waitTicks(3);
			String wires = ctx.computeOnClient(mc -> {
				if (!(mc.gui.screen() instanceof dev.halloween.client.WiresScreen panel)) {
					return "no panel";
				}
				// Drag every wire by hand; a wrong terminal doesn't count.
				for (int i = 0; i < dev.halloween.client.WiresScreen.WIRES; i++) {
					double[] from = panel.leftEnd(i);
					double[] to = panel.rightEndFor(i);
					panel.mouseClicked(mouse(from[0], from[1]), false);
					panel.mouseDragged(mouse(to[0], to[1]), 0, 0);
					panel.mouseReleased(mouse(to[0], to[1]));
					if (mc.gui.screen() != panel) {
						return i == dev.halloween.client.WiresScreen.WIRES - 1 ? "ok" : "closed early at " + i;
					}
				}
				return "still open";
			});
			check("ok".equals(wires), "joining the four wires of matching colour finishes the job (" + wires + ")");
			ctx.waitTicks(4);
			check(server.computeOnServer(s -> dev.halloween.game.LightSystem.isOn(s.overworld(), breaker)), "the power is back on");

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
			// The repair kit is a minigame: follow the wavy line, three times.
			this.holdUse(ctx, 6);
			check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof dev.halloween.client.CurveScreen), "the repair kit opens the line minigame");
			ctx.waitTicks(2);
			check(ctx.computeOnClient(mc -> !mc.mouseHandler.isMouseGrabbed()), "the mouse cursor is free on the panel");
			check(server.computeOnServer(s -> dev.halloween.game.Minigames.active(player(s))), "the server knows the player is busy at the car");
			ctx.takeScreenshot("m04a_curve");
			// Slipping off the line starts it over.
			String slip = ctx.computeOnClient(mc -> {
				var curve = (dev.halloween.client.CurveScreen) mc.gui.screen();
				double[] a = curve.point(0);
				double[] b = curve.point(30);
				curve.mouseClicked(mouse(a[0], a[1]), false);
				curve.mouseDragged(mouse(b[0], b[1]), 0, 0);
				curve.mouseDragged(mouse(b[0], b[1] + 30), 0, 0);
				return curve.linesDone() + "";
			});
			check("0".equals(slip), "slipping off the line starts it over");
			ctx.waitTicks(2);
			int lines = ctx.computeOnClient(mc -> {
				var curve = (dev.halloween.client.CurveScreen) mc.gui.screen();
				for (int line = 0; line < dev.halloween.client.CurveScreen.LINES; line++) {
					double[] p = curve.point(0);
					curve.mouseClicked(mouse(p[0], p[1]), false);
					for (int dx = 4; dx <= 210; dx += 4) {
						double[] q = curve.point(Math.min(dx, 208));
						curve.mouseDragged(mouse(q[0], q[1]), 0, 0);
						if (mc.gui.screen() != curve) {
							return dev.halloween.client.CurveScreen.LINES;
						}
					}
					curve.mouseReleased(mouse(p[0], p[1]));
				}
				return curve.linesDone();
			});
			check(lines == dev.halloween.client.CurveScreen.LINES, "tracing all three lines finishes the job (" + lines + ")");
			ctx.waitTicks(4);
			check(ctx.computeOnClient(mc -> mc.gui.screen() == null), "the panel closes when the job is done");
			check((this.exit(ctx, Exits.CAR).state() & 2) != 0, "the car gets repaired");
			ctx.takeScreenshot("m04_car_fixed");

			// The police: call from a phone, the van arrives.
			BlockPos phone = this.origin.offset(3, 1, 14);
			this.tp(server, 3.5, 0, 12.3);
			this.lookAt(ctx, new Vec3(phone.getX() + 0.5, phone.getY() + 0.15, phone.getZ() + 0.5));
			// Nothing to report yet: no call goes through.
			this.holdUse(ctx, 40);
			check(this.exit(ctx, Exits.POLICE).state() == Exits.POLICE_NEED_BODY && server.computeOnServer(s -> dev.halloween.game.Police.wanted()) == 0,
				"without a body found nobody can call the police");
			// A body at the player's feet (as if the Shape had killed someone here): found at once.
			server.runOnServer(s -> dev.halloween.game.Police.addBody(player(s).position().add(0, 0, 1.0), net.minecraft.network.chat.Component.literal("Annie")));
			ctx.waitTicks(25);
			check(server.computeOnServer(s -> dev.halloween.game.Police.reportable()) == 1, "a civilian finds the body");
			check(this.exit(ctx, Exits.POLICE).state() == Exits.POLICE_IDLE, "now there is something to report");
			this.holdUse(ctx, HwConfig.get().callPoliceTicks + 12);
			check(this.exit(ctx, Exits.POLICE).state() == Exits.POLICE_COMING, "the phone calls the police");
			check(ctx.computeOnClient(mc -> ClientState.match.wanted()) == 1, "Michael's wanted level goes up to 1");
			this.holdUse(ctx, 40);
			check(server.computeOnServer(s -> dev.halloween.game.Police.wanted()) == 1, "one body, one call");
			ctx.waitTicks(HwConfig.get().policeArriveSeconds * 20 + 20);
			check(this.exit(ctx, Exits.POLICE).state() == 2, "the police van arrives");
			BlockPos van = this.exit(ctx, Exits.POLICE).pos();
			check(server.computeOnServer(s -> net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.overworld().getBlockState(van).getBlock()).getPath().equals("white_concrete")), "the van is built on its spot");
			check(server.computeOnServer(s -> dev.halloween.game.Police.officers()) == 2, "two officers come with level 1");
			ctx.waitTicks(40);
			String wards = server.computeOnServer(s -> String.join(", ", dev.halloween.game.Police.wards(s.overworld())));
			String me = server.computeOnServer(s -> player(s).getPlainTextName());
			LOG.info("Officers guard: {}", wards);
			check(wards.contains("-> " + me), "the officers guard the survivor (" + wards + ")");
			// Bring one over to see the uniform.
			Vec3 officerAt = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				Vec3 at = p.position().add(p.getViewVector(1f).multiply(1, 0, 1).normalize().scale(2.5));
				s.overworld().getEntity(dev.halloween.game.Police.ids().getFirst()).teleportTo(at.x, p.getY(), at.z);
				return new Vec3(at.x, p.getY() + 1.2, at.z);
			});
			this.lookAt(ctx, officerAt);
			ctx.waitTicks(5);
			ctx.takeScreenshot("m04b_police");
			// Two more bodies, two more calls: level 2 brings officers, level 3 officers and another van seat.
			server.runOnServer(s -> {
				dev.halloween.game.Police.addBody(player(s).position(), net.minecraft.network.chat.Component.literal("Lynda"));
				dev.halloween.game.Police.addBody(player(s).position(), net.minecraft.network.chat.Component.literal("Bob"));
				dev.halloween.game.Police.findAll();
				dev.halloween.game.Police.reportCall(s.overworld(), net.minecraft.network.chat.Component.literal("test call"));
			});
			ctx.waitTicks(HwConfig.get().policeArriveSeconds * 20 + 20);
			check(server.computeOnServer(s -> dev.halloween.game.Police.officers()) == 4, "level 2 brings two more officers");
			server.runOnServer(s -> dev.halloween.game.Police.reportCall(s.overworld(), net.minecraft.network.chat.Component.literal("test call")));
			ctx.waitTicks(HwConfig.get().policeArriveSeconds * 20 + 20);
			check(server.computeOnServer(s -> dev.halloween.game.Police.officers()) == 6 && dev.halloween.game.Police.wanted() == 3,
				"level 3 brings two more");
			check(server.computeOnServer(s -> Exits.vanSeats()) == 2, "and one more seat in the van");
			check(ctx.computeOnClient(mc -> ClientState.match.wanted()) == 3, "the wanted meter is full");
			// Out of the way of the rest of the test.
			server.runOnServer(s -> dev.halloween.game.Police.clear(s));

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

	/** The deputy's revolver: firing, a dry click, reloading, aiming, a stray bullet; then the gun case and the rifle. */
	private void gunsTest(ClientGameTestContext ctx, TestServerContext server) {
		check(server.computeOnServer(s -> Inventory.has(player(s), HwItems.Kind.REVOLVER)
			&& dev.halloween.game.Guns.loaded(player(s).getInventory().getItem(Inventory.slotOf(player(s), HwItems.Kind.REVOLVER))) == 3),
			"the deputy carries a loaded revolver (3 rounds)");
		int gunSlot = server.computeOnServer(s -> Inventory.slotOf(player(s), HwItems.Kind.REVOLVER));
		ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(gunSlot));
		this.tp(server, 0.5, 0, 0.5);
		this.lookAt(ctx, new Vec3(this.origin.getX() + 0.5, this.origin.getY() + 1.5, this.origin.getZ() + 9.0));
		ctx.waitTicks(5);
		java.util.function.Supplier<Integer> loaded = () -> server.computeOnServer(s -> dev.halloween.game.Guns.loaded(player(s).getMainHandItem()));
		// Aiming: the gun goes up, the view zooms in.
		ctx.getInput().holdKey(o -> o.keyUse);
		ctx.waitTicks(4);
		check(server.computeOnServer(s -> dev.halloween.game.Guns.aiming(player(s))) && ctx.computeOnClient(mc -> ClientState.fovMultiplier()) < 1f,
			"right mouse raises the revolver and zooms in");
		ctx.takeScreenshot("m03b_aiming");
		float pitchBefore = ctx.computeOnClient(mc -> mc.player.getXRot());
		ctx.getInput().pressKey(o -> o.keyAttack);
		ctx.waitTicks(4);
		ctx.getInput().releaseKey(o -> o.keyUse);
		check(loaded.get() == 2, "a shot uses a round (" + loaded.get() + " left)");
		check(ctx.computeOnClient(mc -> mc.player.getXRot()) < pitchBefore - 1f, "the recoil kicks the view up");
		for (int i = 0; i < 2; i++) {
			ctx.waitTicks(HwConfig.get().revolverShotTicks + 2);
			ctx.getInput().pressKey(o -> o.keyAttack);
		}
		ctx.waitTicks(4);
		check(loaded.get() == 0, "three shots empty the cylinder");
		ctx.waitTicks(12);
		ctx.getInput().pressKey(o -> o.keyAttack);
		ctx.waitTicks(4);
		check(loaded.get() == 0, "an empty revolver only clicks");
		// R with rounds in a pocket: one at a time into the cylinder.
		ctx.getInput().pressKey(dev.halloween.client.HalloweenClient.KEY_SENSE);
		ctx.waitTicks(4);
		check(loaded.get() == 0 && server.computeOnServer(s -> !dev.halloween.game.Guns.reloading(player(s))), "no rounds, no reload");
		server.runOnServer(s -> Inventory.give(player(s), Loot.found(HwItems.Kind.REVOLVER_AMMO)));
		ctx.waitTicks(2);
		ctx.getInput().pressKey(dev.halloween.client.HalloweenClient.KEY_SENSE);
		ctx.waitTicks(HwConfig.get().revolverRoundTicks + 4);
		check(loaded.get() == 1 && ctx.computeOnClient(mc -> ClientState.progress.label()).equals("halloween.hud.progress.reload"),
			"reloading puts the rounds in one by one");
		ctx.waitTicks(HwConfig.get().revolverRoundTicks * 2 + 6);
		check(loaded.get() == 3 && server.computeOnServer(s -> Inventory.count(player(s), HwItems.Kind.REVOLVER_AMMO)) == 0,
			"three rounds from the pocket fill the cylinder");
		// A stray bullet wounds whoever is in the way.
		server.runCommand(String.format(Locale.ROOT, "summon minecraft:mannequin %.1f %d %.1f {Tags:[\"%s\"],hide_description:1b}",
			this.origin.getX() + 0.5, this.origin.getY(), this.origin.getZ() + 4.5, MatchManager.VICTIM_TAG));
		ctx.waitTicks(5);
		this.lookAt(ctx, new Vec3(this.origin.getX() + 0.5, this.origin.getY() + 1.1, this.origin.getZ() + 4.5));
		ctx.getInput().holdKey(o -> o.keyUse);
		ctx.waitTicks(6);
		ctx.getInput().pressKey(o -> o.keyAttack);
		ctx.waitTicks(4);
		ctx.getInput().releaseKey(o -> o.keyUse);
		float health = server.computeOnServer(s -> s.overworld().getEntitiesOfClass(net.minecraft.world.entity.decoration.Mannequin.class,
			new AABB(this.origin).inflate(8), m -> m.entityTags().contains(MatchManager.VICTIM_TAG)).stream().findFirst().map(m -> m.getHealth()).orElse(-1f));
		check(health >= 0 && health < 20, "a stray bullet wounds a person in the way (" + health + ")");
		server.runCommand("kill @e[type=minecraft:mannequin,tag=" + MatchManager.VICTIM_TAG + ",tag=!" + dev.halloween.game.Townsfolk.TAG + ",tag=!" + dev.halloween.game.Police.TAG + "]");
		ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));

		// The gun case: the code from the note opens it, a wrong one doesn't.
		BlockPos casePos = server.computeOnServer(s -> dev.halloween.game.GunCase.closed().isEmpty() ? null : dev.halloween.game.GunCase.closed().getFirst());
		check(casePos != null, "a gun case stands on the map");
		String code = server.computeOnServer(s -> dev.halloween.game.GunCase.code());
		server.runOnServer(s -> {
			Inventory.dropAll(player(s));
			Inventory.give(player(s), Loot.found(HwItems.Kind.SAFE_CODE));
		});
		ctx.waitTicks(2);
		check(server.computeOnServer(s -> player(s).getInventory().getItem(Inventory.slotOf(player(s), HwItems.Kind.SAFE_CODE)).getHoverName().getString()).contains(code),
			"the note has the code on it");
		server.runCommand(String.format(Locale.ROOT, "tp @p %.1f %d %.1f", casePos.getX() + 1.5, casePos.getY(), casePos.getZ() + 0.5));
		ctx.waitTicks(3);
		this.lookAt(ctx, Vec3.atCenterOf(casePos));
		this.holdUse(ctx, 6);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof dev.halloween.client.KeypadScreen), "using the case brings up its keypad");
		ctx.takeScreenshot("m03c_keypad");
		String wrong = String.format(Locale.ROOT, "%04d", (Integer.parseInt(code) + 1) % 10000);
		ctx.runOnClient(mc -> {
			var pad = (dev.halloween.client.KeypadScreen) mc.gui.screen();
			for (char c : wrong.toCharArray()) {
				pad.press(String.valueOf(c));
			}
			pad.press("OK");
		});
		ctx.waitTicks(5);
		check(server.computeOnServer(s -> !dev.halloween.game.GunCase.closed().isEmpty()) && ctx.computeOnClient(mc -> mc.gui.screen() instanceof dev.halloween.client.KeypadScreen pad && pad.entered().isEmpty()),
			"a wrong code doesn't open it");
		ctx.runOnClient(mc -> {
			var pad = (dev.halloween.client.KeypadScreen) mc.gui.screen();
			for (char c : code.toCharArray()) {
				pad.press(String.valueOf(c));
			}
			pad.press("OK");
		});
		ctx.waitTicks(5);
		check(ctx.computeOnClient(mc -> mc.gui.screen() == null) && server.computeOnServer(s -> Inventory.has(player(s), HwItems.Kind.RIFLE)),
			"the right code opens the case: the rifle");
		// The rifle: one round, a long reload.
		int rifleSlot = server.computeOnServer(s -> Inventory.slotOf(player(s), HwItems.Kind.RIFLE));
		ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(rifleSlot));
		this.lookAt(ctx, Vec3.atCenterOf(casePos).add(0, 1.5, 0));
		ctx.waitTicks(3);
		check(loaded.get() == 1, "the rifle holds a single round");
		ctx.getInput().pressKey(o -> o.keyAttack);
		ctx.waitTicks(4);
		check(loaded.get() == 0, "and it's gone after one shot");
		server.runOnServer(s -> Inventory.give(player(s), Loot.found(HwItems.Kind.RIFLE_AMMO)));
		ctx.waitTicks(2);
		ctx.getInput().pressKey(dev.halloween.client.HalloweenClient.KEY_SENSE);
		ctx.waitTicks(HwConfig.get().rifleReloadTicks / 2);
		check(loaded.get() == 0, "the rifle takes a long time to reload");
		ctx.waitTicks(HwConfig.get().rifleReloadTicks / 2 + 8);
		check(loaded.get() == 1 && server.computeOnServer(s -> Inventory.count(player(s), HwItems.Kind.RIFLE_AMMO)) == HwConfig.get().rifleAmmoPerFind - 1,
			"a new round is in after the reload");
		server.runOnServer(s -> {
			Inventory.dropAll(player(s));
			Inventory.give(player(s), HwItems.create(HwItems.Kind.BATON));
			Inventory.give(player(s), HwItems.create(HwItems.Kind.FLASHLIGHT));
		});
		server.runCommand("kill @e[type=item]");
		ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
	}

	private void holdUse(ClientGameTestContext ctx, int ticks) {
		ctx.getInput().holdKey(o -> o.keyUse);
		ctx.waitTicks(ticks);
		ctx.getInput().releaseKey(o -> o.keyUse);
		ctx.waitTicks(4);
	}

	private static net.minecraft.client.input.MouseButtonEvent mouse(double x, double y) {
		return new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(0, 0));
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
