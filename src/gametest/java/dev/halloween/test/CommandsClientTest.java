package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.game.HwItems;
import dev.halloween.game.MatchManager;
import dev.halloween.game.Windows;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** /hw give hands out every item; the Shape in Shape Jump slips through a window in shapeWindowTicks. */
public class CommandsClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-commands-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("cmd")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			BlockPos origin = server.computeOnServer(s -> player(s).blockPosition());

			// Every item, one at a time.
			StringBuilder missing = new StringBuilder();
			for (HwItems.Kind kind : HwItems.Kind.values()) {
				server.runOnServer(s -> player(s).getInventory().clearContent());
				server.runCommand("execute as @p run hw give " + kind.key());
				ctx.waitTicks(1);
				boolean has = server.computeOnServer(s -> {
					var inv = player(s).getInventory();
					for (int i = 0; i < inv.getContainerSize(); i++) {
						if (HwItems.is(inv.getItem(i), kind)) {
							return true;
						}
					}
					return false;
				});
				if (!has) {
					missing.append(kind.key()).append(' ');
				}
			}
			check(missing.isEmpty(), "/hw give gives every item (" + missing + ")");
			server.runOnServer(s -> player(s).getInventory().clearContent());
			server.runCommand("execute as @p run hw give revolver_ammo 2");
			ctx.waitTicks(1);
			int rounds = server.computeOnServer(s -> {
				var inv = player(s).getInventory();
				int n = 0;
				for (int i = 0; i < inv.getContainerSize(); i++) {
					if (HwItems.is(inv.getItem(i), HwItems.Kind.REVOLVER_AMMO)) {
						n += inv.getItem(i).getCount();
					}
				}
				return n;
			});
			check(rounds == 2 * HwConfig.get().revolverAmmoPerFind, "/hw give revolver_ammo 2 gives two handfuls of rounds (" + rounds + ")");

			// The Shape in Shape Jump through the front window of the middle house.
			server.runCommand("hw role @p shape");
			ctx.waitTicks(10);
			BlockPos glass = origin.offset(2, 1, 8);
			int took = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				p.teleportTo(s.overworld(), glass.getX() + 0.5, origin.getY(), glass.getZ() - 1.2, java.util.Set.of(), 0f, 0f, false);
				MatchManager.shapeState(p).shadowTicks = 1;
				Windows.request(p, glass, false);
				return Windows.isVaulting(p) ? 0 : -1;
			});
			check(took == 0, "in Shape Jump the Shape starts through the window");
			int ticks = 0;
			while (ticks < 200 && server.computeOnServer(s -> Windows.isVaulting(player(s)))) {
				ctx.waitTicks(1);
				ticks++;
			}
			LOG.info("Through the window in {} ticks", ticks);
			check(Math.abs(ticks - HwConfig.get().shapeWindowTicks) <= 3 && HwConfig.get().shapeWindowTicks == 50,
				"and is through in 2.5 s (" + ticks + " ticks)");
			check(server.computeOnServer(s -> player(s).getZ() > glass.getZ() + 0.5 || player(s).getZ() < glass.getZ()), "on the other side of the wall");
			check(server.computeOnServer(s -> Windows.isWindow(s.overworld().getBlockState(glass)) && !Windows.isBrokenPane(s.overworld().getBlockState(glass))),
				"the glass stays whole");
		}
		LOG.info("Commands client test finished OK");
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().get(0);
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
