package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientState;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.HwItems;
import dev.halloween.game.Lockers;
import dev.halloween.game.MatchManager;
import dev.halloween.game.Traps;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A bear trap holds Michael, tin cans give him away to the survivors; out of a locker for air, no getting back in at once. */
public class TrapsClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-traps");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("traps")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = 1;
				HwConfig.get().botsFillTo = 2;
			});
			server.runCommand("hw start");
			ctx.waitTicks(40);
			// Set a trap and a string of cans a few steps apart.
			BlockPos trap = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				p.setItemInHand(InteractionHand.MAIN_HAND, HwItems.create(HwItems.Kind.BEAR_TRAP));
				Traps.place(p, Traps.Kind.BEAR_TRAP);
				return p.blockPosition();
			});
			check(server.computeOnServer(s -> Traps.isTrap(trap)), "a bear trap is set at your feet");
			BlockPos cans = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				p.teleportTo(s.overworld(), p.getX() + 3, p.getY(), p.getZ(), java.util.Set.of(), 0f, 0f, false);
				p.setItemInHand(InteractionHand.MAIN_HAND, HwItems.create(HwItems.Kind.TIN_CANS));
				Traps.place(p, Traps.Kind.CANS);
				return p.blockPosition();
			});
			check(server.computeOnServer(s -> Traps.isTrap(cans)), "and the cans");
			ctx.takeScreenshot("traps_set");
			// A bot plays Michael and walks into them.
			String michael = server.computeOnServer(s -> s.getPlayerList().getPlayers().stream().filter(p -> p instanceof BotPlayer).findFirst().orElseThrow().getScoreboardName());
			server.runCommand("hw role " + michael + " shape");
			ctx.waitTicks(5);
			server.runOnServer(s -> {
				ServerPlayer m = s.getPlayerList().getPlayerByName(michael);
				m.teleportTo(s.overworld(), trap.getX() + 0.5, trap.getY(), trap.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
			});
			ctx.waitTicks(3);
			check(server.computeOnServer(s -> MatchManager.shapeState(s.getPlayerList().getPlayerByName(michael)).trappedTicks > 0 && !Traps.isTrap(trap)),
				"the trap snaps shut on him");
			server.runOnServer(s -> {
				ServerPlayer m = s.getPlayerList().getPlayerByName(michael);
				MatchManager.shapeState(m).trappedTicks = 0;
				m.teleportTo(s.overworld(), cans.getX() + 0.5, cans.getY(), cans.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
			});
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> !Traps.isTrap(cans)) && ctx.computeOnClient(mc -> !ClientState.ALERTS.isEmpty()), "the cans rattle and the survivors see where");
			// Out of a locker for air: not straight back in.
			BlockPos locker = server.computeOnServer(s -> Lockers.nearestFree(s.overworld(), player(s).blockPosition(), 60));
			boolean refused = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				BlockPos front = Lockers.front(s.overworld(), locker);
				p.teleportTo(s.overworld(), front.getX() + 0.5, front.getY(), front.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
				boolean in = Lockers.enter(p, locker);
				Lockers.leave(p, true);
				return in && !Lockers.enter(p, locker);
			});
			check(refused, "out for air, no getting back into a locker at once");
			// A townsperson out for air goes back in once its breath is back (it used to give lockers up for good).
			server.runOnServer(s -> HwConfig.get().lockerBreathTicks = 60);
			int npcId = server.computeOnServer(s -> {
				var npc = s.overworld().getEntities(net.minecraft.world.entity.EntityTypes.MANNEQUIN, dev.halloween.game.Townsfolk::isNpc).stream()
					.filter(m -> !Lockers.isInside(m)).findFirst().orElseThrow();
				BlockPos front = Lockers.front(s.overworld(), locker);
				npc.teleportTo(front.getX() + 0.5, front.getY(), front.getZ() + 0.5);
				dev.halloween.game.Townsfolk.apply(player(s), npc, dev.halloween.game.Townsfolk.HIDE);
				return npc.getId();
			});
			boolean hid = false;
			for (int i = 0; i < 20 && !hid; i++) {
				ctx.waitTicks(10);
				hid = server.computeOnServer(s -> Lockers.isInside((net.minecraft.world.entity.LivingEntity) s.overworld().getEntity(npcId)));
			}
			check(hid, "a townsperson told to hide gets into a locker");
			server.runOnServer(s -> Lockers.leave((net.minecraft.world.entity.LivingEntity) s.overworld().getEntity(npcId), true));
			ctx.waitTicks(30);
			check(server.computeOnServer(s -> !Lockers.isInside((net.minecraft.world.entity.LivingEntity) s.overworld().getEntity(npcId))), "out of air, it stays out a while");
			boolean back = false;
			for (int i = 0; i < 20 && !back; i++) {
				ctx.waitTicks(10);
				back = server.computeOnServer(s -> Lockers.isInside((net.minecraft.world.entity.LivingEntity) s.overworld().getEntity(npcId)));
			}
			check(back, "and climbs back in once its breath is back");
			server.runOnServer(s -> HwConfig.get().lockerBreathTicks = new HwConfig().lockerBreathTicks);
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds;
				HwConfig.get().botsFillTo = new HwConfig().botsFillTo;
			});
		}
		LOG.info("Traps client test finished OK");
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
