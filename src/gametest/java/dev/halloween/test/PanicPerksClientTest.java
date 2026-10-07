package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientPanic;
import dev.halloween.client.ClientPanting;
import dev.halloween.client.ClientState;
import dev.halloween.client.PerkScreen;
import dev.halloween.game.AiStats;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Guns;
import dev.halloween.game.HwItems;
import dev.halloween.game.Loot;
import dev.halloween.game.Noise;
import dev.halloween.game.Panic;
import dev.halloween.game.Perks;
import dev.halloween.game.Stamina;
import dev.halloween.net.HwNet;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The new survivor mechanics: loud surfaces and Michael hearing footsteps, panic, perks. */
public class PanicPerksClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-panic-perks");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("panic")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);

			// Loud surfaces.
			boolean surfaces = server.computeOnServer(s -> {
				var level = s.overworld();
				BlockPos at = player(s).blockPosition().offset(0, 0, 3);
				level.setBlock(at.below(), Blocks.GRAVEL.defaultBlockState(), 2);
				boolean gravel = Noise.surface(level, at) == Noise.Surface.GRAVEL;
				level.setBlock(at.below(), Blocks.STONE.defaultBlockState(), 2);
				level.setBlock(at, Blocks.LEAF_LITTER.defaultBlockState().setValue(BlockStateProperties.SEGMENT_AMOUNT, 3), 2);
				boolean pile = Noise.surface(level, at) == Noise.Surface.LEAVES;
				level.setBlock(at, Blocks.LEAF_LITTER.defaultBlockState(), 2);
				boolean single = Noise.surface(level, at) == null;
				level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
				return gravel && pile && single;
			});
			check(surfaces, "gravel and leaf piles are loud, a single leaf isn't");
			// A smashed window is put back at the next match (kept with the world, so even after the game was closed).
			BlockPos window = server.computeOnServer(s -> {
				BlockPos c = player(s).blockPosition();
				for (BlockPos p : BlockPos.betweenClosed(c.offset(-40, -2, -40), c.offset(40, 4, 40))) {
					if (dev.halloween.game.Windows.isWindow(s.overworld().getBlockState(p)) && !dev.halloween.game.Windows.isBrokenPane(s.overworld().getBlockState(p))) {
						BlockPos at = p.immutable();
						dev.halloween.game.Windows.breakColumn(s.overworld(), at);
						return at;
					}
				}
				return null;
			});
			check(window != null && server.computeOnServer(s -> dev.halloween.game.Windows.isBrokenPane(s.overworld().getBlockState(window))), "a window smashed");
			server.runOnServer(s -> HwConfig.get().botsFillTo = 0);
			server.runCommand("hw start");
			ctx.waitTicks(20);
			check(server.computeOnServer(s -> !dev.halloween.game.Windows.isBrokenPane(s.overworld().getBlockState(window))), "and whole again at the next match");
			server.runCommand("hw stop");
			ctx.waitTicks(5);

			// Michael hears survivors running.
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = 1;
				HwConfig.get().botsFillTo = 2;
			});
			server.runCommand("hw start @p");
			ctx.waitTicks(40);
			ServerPlayer bot = server.computeOnServer(s -> s.getPlayerList().getPlayers().stream().filter(p -> p instanceof BotPlayer).findFirst().orElse(null));
			int heard = 0;
			for (int i = 0; i < 20 && heard == 0; i++) {
				server.runOnServer(s -> {
					ServerPlayer shape = player(s);
					ServerPlayer b = s.getPlayerList().getPlayer(bot.getUUID());
					shape.teleportTo(s.overworld(), b.getX() + 4, b.getY(), b.getZ(), java.util.Set.of(), 90f, 0f, false);
				});
				ctx.waitTicks(30);
				heard = server.computeOnServer(s -> AiStats.snapshot().getOrDefault("*total", Map.of()).getOrDefault("noise.run", 0)
					+ AiStats.snapshot().getOrDefault("*total", Map.of()).getOrDefault("noise.loud", 0));
			}
			check(heard > 0, "Michael hears a survivor running (" + heard + " bursts)");
			ctx.takeScreenshot("noise_burst");
			server.runCommand("hw stop");
			ctx.waitTicks(5);

			// Panic (alone: no Michael about, so it ebbs away again).
			server.runOnServer(s -> HwConfig.get().botsFillTo = 0);
			server.runCommand("hw start");
			ctx.waitTicks(40);
			server.runOnServer(s -> {
				Panic.set(player(s), 1f);
				Stamina.set(player(s), 0.5f);
			});
			ctx.waitTicks(10);
			check(ctx.computeOnClient(mc -> ClientState.panic > 0.9f && ClientPanic.shake(10f) != null), "full panic reaches the client: the view shakes");
			check(ctx.computeOnClient(mc -> ClientPanting.panting(mc.player.getId())), "and the heavy breathing starts");
			float spread = server.computeOnServer(s -> Guns.spread(player(s), HwItems.Kind.REVOLVER, true));
			check(spread > HwConfig.get().revolverAimSpread + 3f, "a gun shakes in a panicking hand (" + spread + " degrees)");
			float before = server.computeOnServer(s -> Stamina.value(player(s)));
			ctx.waitTicks(40);
			float after = server.computeOnServer(s -> Stamina.value(player(s)));
			float expectedCalm = 40f / HwConfig.get().staminaRestRegenTicks;
			check(after - before < expectedCalm * 0.8f, "the breath comes back slower (" + (after - before) + " in 2 s)");
			ctx.takeScreenshot("panic");
			float panicLater = server.computeOnServer(s -> Panic.value(player(s)));
			check(panicLater < 1f, "with Michael far away it ebbs (" + panicLater + ")");
			server.runCommand("hw stop");
			ctx.waitTicks(5);

			// Perks: bought with points, kept for good, up to three in the build.
			server.runOnServer(s -> {
				Perks.give(s, player(s).getUUID(), 7500);
				Perks.sync(player(s));
			});
			ctx.waitTicks(5);
			check(ctx.computeOnClient(mc -> ClientState.perkPoints >= 7500), "points reach the client");
			for (String key : new String[]{"quick_search", "tough", "glass_walker", "barricade", "slippery"}) {
				ctx.runOnClient(mc -> ClientPlayNetworking.send(new HwNet.PerkAction(key, Perks.BUY)));
				ctx.waitTicks(3);
			}
			check(server.computeOnServer(s -> Perks.points(s, player(s).getUUID())) == 600, "four perks bought, the points spent (600 left)");
			check(server.computeOnServer(s -> !Perks.owns(s, player(s).getUUID(), Perks.Perk.SLIPPERY)), "one too dear can't be bought");
			check(server.computeOnServer(s -> Perks.equipped(s, player(s).getUUID()).equals(java.util.EnumSet.of(Perks.Perk.QUICK_SEARCH, Perks.Perk.TOUGH, Perks.Perk.GLASS_WALKER))),
				"the first three went into the build");
			ctx.runOnClient(mc -> ClientPlayNetworking.send(new HwNet.PerkAction("barricade", Perks.EQUIP)));
			ctx.waitTicks(3);
			check(server.computeOnServer(s -> !Perks.equipped(s, player(s).getUUID()).contains(Perks.Perk.BARRICADE)), "no fourth in the build");
			ctx.runOnClient(mc -> mc.gui.setScreen(new PerkScreen()));
			ctx.waitTicks(5);
			ctx.takeScreenshot("perks");
			ctx.runOnClient(mc -> mc.gui.setScreen(null));
			server.runCommand("hw start");
			ctx.waitTicks(40);
			int searchTicks = server.computeOnServer(s -> {
				BlockPos container = Loot.containers().stream().filter(c -> !Loot.searched().contains(c)).findFirst().orElse(null);
				var hold = container == null ? null : Loot.resolve(player(s), container);
				return hold == null ? -1 : hold.ticks();
			});
			check(searchTicks == Math.round(HwConfig.get().searchTicks * HwConfig.get().perkQuickSearchFactor), "quick search: " + searchTicks + " ticks");
			check(server.computeOnServer(s -> player(s).getMaxHealth() == 30f), "tough: 30 health");
			check(server.computeOnServer(s -> Math.abs(dev.halloween.game.Windows.cutDamage(player(s)) - 30f * HwConfig.get().vaultDamageFraction * 0.5f) < 1e-3),
				"glass walker: half the cuts");
			ctx.runOnClient(mc -> ClientPlayNetworking.send(new HwNet.PerkAction("tough", Perks.UNEQUIP)));
			ctx.waitTicks(3);
			check(server.computeOnServer(s -> Perks.equipped(s, player(s).getUUID()).contains(Perks.Perk.TOUGH)), "the build stays put during a match");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds;
				HwConfig.get().botsFillTo = new HwConfig().botsFillTo;
			});
		}
		LOG.info("Panic and perks client test finished OK");
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
