package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.client.ClientWeather;
import dev.halloween.client.MatchEndScreen;
import dev.halloween.game.AiStats;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Bots;
import dev.halloween.game.Fight;
import dev.halloween.game.HwItems;
import dev.halloween.game.Lockers;
import dev.halloween.game.Match;
import dev.halloween.game.MatchManager;
import dev.halloween.game.Tutorial;
import dev.halloween.game.Weather;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The new bits only: a foggy night (thick fog on the client, shorter sight on the server), the flashlight
 * flickering on its last charge, the summary screen with the night's chronicle, and the practice course
 * (its first steps, then the switch to the Shape's part).
 */
public class NightClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-night-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("night")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = 2;
				Weather.forced = Weather.FOG;
			});
			server.runCommand("hw start");
			ctx.waitTicks(100);
			server.runOnServer(s -> Weather.forced = -1);

			// Fog.
			check(server.computeOnServer(s -> Weather.current() == Weather.FOG), "the night is foggy");
			check(server.computeOnServer(s -> Math.abs(Weather.sight() - HwConfig.get().fogSightFactor) < 1e-6), "fog shortens sight on the server");
			check(ctx.computeOnClient(mc -> ClientWeather.kind == Weather.FOG && ClientWeather.thickness() > 0.9f), "the client draws the fog thick");
			ctx.takeScreenshot("night_fog");

			// The flashlight on its last charge.
			server.runOnServer(s -> {
				ServerPlayer p = player(s);
				ItemStack light = HwItems.create(HwItems.Kind.FLASHLIGHT);
				light.setDamageValue(light.getMaxDamage() - 55);
				p.setItemInHand(InteractionHand.MAIN_HAND, light);
				Fight.toggleFlashlight(p);
			});
			int before = server.computeOnServer(s -> AiStats.snapshot().getOrDefault("*total", java.util.Map.of()).getOrDefault("flashlight.flicker", 0));
			ctx.waitTicks(45);
			int after = server.computeOnServer(s -> AiStats.snapshot().getOrDefault("*total", java.util.Map.of()).getOrDefault("flashlight.flicker", 0));
			check(after > before, "the dying flashlight flickers (" + (after - before) + " ticks dark)");
			check(server.computeOnServer(s -> Fight.flashlightOn(player(s))), "and still shines between the drop-outs");

			// The summary screen.
			server.runOnServer(s -> Match.escape(player(s), Component.translatable("halloween.exit.cellar")));
			server.runCommand("hw timer 1");
			ctx.waitTicks(40);
			check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof MatchEndScreen), "the summary screen opens when the match ends");
			check(ctx.computeOnClient(mc -> dev.halloween.client.ClientState.matchEnd != null && !dev.halloween.client.ClientState.matchEnd.chronicle().isEmpty()),
				"the chronicle has the night's events");
			ctx.takeScreenshot("night_summary");
			check(server.computeOnServer(s -> Weather.current() == Weather.CLEAR), "the weather clears after the match");
			ctx.runOnClient(mc -> mc.gui.setScreen(null));

			// The practice course.
			server.runOnServer(s -> HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds);
			server.runOnServer(s -> {
				player(s).setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
				Tutorial.start(s, player(s));
			});
			ctx.waitTicks(80);
			check(server.computeOnServer(s -> Tutorial.step() == Tutorial.Step.SEARCH), "practice starts with searching");
			check(server.computeOnServer(s -> s.getPlayerList().getPlayers().stream().noneMatch(Bots::isBot)), "alone: no bots");
			check(server.computeOnServer(s -> MatchManager.role(player(s)) == Role.CIVILIAN), "as a survivor");
			ctx.takeScreenshot("tutorial_search");
			server.runOnServer(s -> Tutorial.holdDone(player(s), "halloween.hud.progress.search"));
			check(server.computeOnServer(s -> Tutorial.step() == Tutorial.Step.HIDE), "searching done: hide next");
			server.runOnServer(s -> {
				BlockPos locker = Lockers.nearestFree(s.overworld(), player(s).blockPosition(), 64);
				if (locker != null) {
					BlockPos front = Lockers.front(s.overworld(), locker);
					player(s).teleportTo(s.overworld(), front.getX() + 0.5, front.getY(), front.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
					LOG.info("Locker at {}: in {}", locker, Lockers.enter(player(s), locker));
				} else {
					LOG.info("No locker near {}", player(s).blockPosition());
				}
			});
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> Tutorial.step() == Tutorial.Step.LEAVE), "in the locker: get out next");
			server.runOnServer(s -> Lockers.leave(player(s), false));
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> Tutorial.step() == Tutorial.Step.WINDOW), "out: the window next");
			// Skip to locking a door (the window is checked elsewhere) and lock one.
			BlockPos door = server.computeOnServer(s -> {
				try {
					var f = Tutorial.class.getDeclaredField("step");
					f.setAccessible(true);
					f.set(null, Tutorial.Step.LOCK);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
				BlockPos c = player(s).blockPosition();
				for (BlockPos p : BlockPos.betweenClosed(c.offset(-40, -2, -40), c.offset(40, 3, 40))) {
					var st = s.overworld().getBlockState(p);
					if (st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
						&& st.getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
						return p.immutable();
					}
				}
				return null;
			});
			server.runOnServer(s -> {
				var facing = s.overworld().getBlockState(door).getValue(net.minecraft.world.level.block.DoorBlock.FACING);
				BlockPos at = door.relative(facing.getOpposite());
				player(s).teleportTo(s.overworld(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, java.util.Set.of(), facing.toYRot(), 20f, false);
				player(s).setShiftKeyDown(true);
			});
			for (int i = 0; i < 20; i++) {
				server.runOnServer(s -> dev.halloween.game.DoorLocks.ping(player(s), door));
				ctx.waitTicks(4);
			}
			server.runOnServer(s -> player(s).setShiftKeyDown(false));
			check(server.computeOnServer(s -> Tutorial.step() == Tutorial.Step.CELLAR), "a locked door moves the practice on (to the cellar)");
			server.runOnServer(s -> Match.escape(player(s), Component.translatable("halloween.exit.cellar")));
			ctx.waitTicks(20);
			check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof MatchEndScreen), "the survivor part ends with the summary");
			ctx.runOnClient(mc -> mc.gui.setScreen(null));
			ctx.waitTicks(220);
			check(server.computeOnServer(s -> MatchManager.role(player(s)) == Role.SHAPE && Tutorial.step() == Tutorial.Step.JUMP), "then the Shape part: Shape Jump first");
			check(server.computeOnServer(s -> Match.running()), "and the match keeps going with nobody on the civilians' side");
			server.runOnServer(s -> MatchManager.shapeState(player(s)).shadowTicks = 5);
			ctx.waitTicks(3);
			check(server.computeOnServer(s -> Tutorial.step() == Tutorial.Step.UNJUMP), "in Shape Jump: come out next");
			ctx.takeScreenshot("tutorial_shape");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> !Tutorial.active()), "hw stop ends the practice");
		}
		LOG.info("Night client test finished OK");
	}

	private static ServerPlayer player(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!(p instanceof BotPlayer)) {
				return p;
			}
		}
		throw new IllegalStateException("no player");
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
