package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.HwItems;
import dev.halloween.game.Leftovers;
import dev.halloween.game.Townsfolk;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.item.ItemEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A match's leftovers (an item on the floor, a townsperson with no mind) are gone when their chunk loads in a later match. */
public class LeftoversClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-leftovers");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("leftovers")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = 1;
				HwConfig.get().botsFillTo = 0;
			});
			server.runCommand("hw start");
			ctx.waitTicks(40);
			ItemEntity item = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				ItemEntity e = new ItemEntity(s.overworld(), p.getX() + 2, p.getY(), p.getZ(), HwItems.create(HwItems.Kind.BRICK));
				s.overworld().addFreshEntity(e);
				return e;
			});
			ctx.waitTicks(2);
			check(server.computeOnServer(s -> item.entityTags().stream().anyMatch(t -> t.startsWith("hw_m"))), "a dropped item gets its match's tag");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runCommand("hw start");
			ctx.waitTicks(40);
			// The chunk "loads again" in the new match: the old item goes; so does a townsperson that wasn't set up by it.
			Mannequin stray = server.computeOnServer(s -> {
				Mannequin m = EntityTypes.MANNEQUIN.create(s.overworld(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				m.addTag(Townsfolk.TAG);
				m.setPos(player(s).position().add(3, 0, 0));
				Leftovers.onLoad(item, s.overworld());
				Leftovers.onLoad(m, s.overworld());
				return m;
			});
			ctx.waitTicks(3);
			check(server.computeOnServer(s -> item.isRemoved()), "the last match's item is cleared away");
			check(server.computeOnServer(s -> stray.isRemoved()), "and a mindless townsperson too");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds;
				HwConfig.get().botsFillTo = new HwConfig().botsFillTo;
			});
		}
		LOG.info("Leftovers client test finished OK");
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
