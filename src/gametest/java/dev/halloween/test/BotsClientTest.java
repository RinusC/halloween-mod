package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.client.CameraAim;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Bots;
import dev.halloween.game.Exits;
import dev.halloween.game.Loot;
import dev.halloween.game.Match;
import dev.halloween.game.MatchManager;
import dev.halloween.game.Nav;
import dev.halloween.game.Sight;
import dev.halloween.game.Townsfolk;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bots and townsfolk. First the lone player plays the Shape: four bot civilians join, go about their
 * business, run when they see him; townsfolk scream and he can kill them. Then the player is a lone
 * civilian: no bots, and townsfolk can be led to an exit or told to hide.
 */
public class BotsClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-bots-test");

	private BlockPos origin;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			this.origin = server.computeOnServer(s -> player(s).blockPosition());
			server.runOnServer(s -> HwConfig.get().countdownSeconds = 2);

			// ---------------------------------------------------------------- the player is the Shape
			server.runCommand("hw start @p");
			ctx.waitTicks(60);
			check(server.computeOnServer(s -> Match.running()), "the match runs");
			check(server.computeOnServer(s -> MatchManager.role(player(s))) == Role.SHAPE, "the player is the Shape");
			int bots = server.computeOnServer(s -> (int) s.getPlayerList().getPlayers().stream()
				.filter(p -> p instanceof BotPlayer && MatchManager.role(p) == Role.CIVILIAN).count());
			check(bots == 4, "four bot civilians join (" + bots + ")");
			int npcs = server.computeOnServer(s -> Townsfolk.count());
			check(npcs == 6, "townsfolk are at home in the houses (" + npcs + ")");

			// Keep the Shape out of everyone's way while the bots get on with it.
			this.tp(server, 30.5, 0, -30.5);
			Map<UUID, Vec3> start = server.computeOnServer(s -> {
				Map<UUID, Vec3> map = new HashMap<>();
				Bots.all().forEach(b -> map.put(b.getUUID(), b.position()));
				return map;
			});
			ctx.waitTicks(400);
			double moved = server.computeOnServer(s -> Bots.all().stream()
				.mapToDouble(b -> b.position().distanceTo(start.getOrDefault(b.getUUID(), b.position()))).max().orElse(0));
			LOG.info("Furthest bot walk: {}", moved);
			check(moved > 5, "bots walk around the map");
			int searched = server.computeOnServer(s -> Loot.searched().size());
			check(searched > 0, "bots search containers (" + searched + ")");
			ctx.takeScreenshot("b01_bots_at_work");

			// A bot that sees the Shape runs away.
			String fled = server.computeOnServer(s -> {
				BotPlayer bot = Bots.all().stream().filter(b -> !b.isSpectator()).findFirst().orElse(null);
				if (bot == null) {
					return "no bot";
				}
				Vec3 front = near(s, bot, 3);
				player(s).teleportTo(s.overworld(), front.x, front.y, front.z, java.util.Set.of(), 0f, 0f, false);
				return bot.getUUID().toString();
			});
			check(!"no bot".equals(fled), "a bot is still around");
			UUID fleeing = UUID.fromString(fled);
			double before = server.computeOnServer(s -> distance(s, fleeing));
			ctx.waitTicks(80);
			double after = server.computeOnServer(s -> distance(s, fleeing));
			LOG.info("Bot distance to the Shape: {} -> {}", before, after);
			check(after > before + 3, "a bot that sees the Shape runs away");
			ctx.takeScreenshot("b02_bot_flees");

			// A townsperson that sees the Shape screams.
			int screamsBefore = server.computeOnServer(s -> Townsfolk.screams);
			int npcId = server.computeOnServer(s -> {
				Mannequin npc = npcs(s).get(0);
				Vec3 front = near(s, npc, 2);
				player(s).teleportTo(s.overworld(), front.x, front.y, front.z, java.util.Set.of(), 0f, 0f, false);
				return npc.getId();
			});
			ctx.waitTicks(20);
			check(server.computeOnServer(s -> Townsfolk.screams) > screamsBefore, "a townsperson that sees the Shape screams");
			String state = server.computeOnServer(s -> Townsfolk.stateOf(s.overworld().getEntity(npcId)));
			LOG.info("Townsperson state: {}", state);
			check(!state.equals("home"), "and stops minding the house (" + state + ")");

			// The Shape kills a townsperson: a body is left and Bloodthirst grows.
			float bloodBefore = server.computeOnServer(s -> MatchManager.shapeState(player(s)).bloodthirst);
			String kill = "alive";
			for (int i = 0; i < 12 && "alive".equals(kill); i++) {
				kill = server.computeOnServer(s -> {
					Entity npc = s.overworld().getEntity(npcId);
					if (npc == null || npc.isRemoved()) {
						return "dead";
					}
					ServerPlayer shape = player(s);
					shape.teleportTo(s.overworld(), npc.getX() + 1.2, npc.getY(), npc.getZ(), java.util.Set.of(), 90f, 10f, false);
					MatchManager.onShapeAttack(shape, npcId, true);
					return "alive";
				});
				ctx.waitTicks(32);
			}
			check("dead".equals(kill), "the Shape can kill a townsperson");
			check(server.computeOnServer(s -> MatchManager.shapeState(player(s)).bloodthirst) > bloodBefore, "the kill feeds Bloodthirst");
			check(server.computeOnServer(s -> !s.overworld().getEntities(EntityTypes.MANNEQUIN,
				e -> e.entityTags().contains(MatchManager.CORPSE_TAG)).isEmpty()), "a body is left behind");
			ctx.takeScreenshot("b03_townsperson_killed");

			server.runCommand("hw stop");
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> s.getPlayerList().getPlayers().size()) == 1, "bots leave when the match stops");
			check(server.computeOnServer(s -> Townsfolk.count()) == 0, "townsfolk go home when the match stops");

			// ---------------------------------------------------------------- the player is a civilian
			server.runCommand("hw start");
			ctx.waitTicks(60);
			check(server.computeOnServer(s -> s.getPlayerList().getPlayers().size()) == 1, "no bots without a Shape");
			check(server.computeOnServer(s -> MatchManager.role(player(s))) == Role.CIVILIAN, "the player is a civilian");

			// "Follow me": a real right click on a townsperson.
			int guest = server.computeOnServer(s -> npcs(s).get(0).getId());
			Vec3 guestPos = server.computeOnServer(s -> s.overworld().getEntity(guest).position());
			this.place(server, server.computeOnServer(s -> near(s, s.overworld().getEntity(guest), 1.8)));
			this.lookAt(ctx, guestPos.add(0, 1.2, 0));
			ctx.getInput().pressKey(o -> o.keyUse);
			ctx.waitTicks(5);
			check("follow".equals(server.computeOnServer(s -> Townsfolk.stateOf(s.overworld().getEntity(guest)))), "a townsperson follows when asked");
			ctx.takeScreenshot("b04_follow_me");

			// Lead it out: walk a few steps, it keeps up.
			this.place(server, server.computeOnServer(s -> near(s, s.overworld().getEntity(guest), 4)));
			ctx.waitTicks(60);
			double gap = server.computeOnServer(s -> s.overworld().getEntity(guest).distanceTo(player(s)));
			LOG.info("Follow gap: {}", gap);
			check(gap < 3.5, "it keeps up");

			// Near an exit it is rescued and the civilian scores.
			int scoreBefore = server.computeOnServer(s -> Match.scores().values().stream().mapToInt(Integer::intValue).sum());
			server.runOnServer(s -> {
				BlockPos exit = Exits.infos().get(0).pos();
				ServerPlayer p = player(s);
				p.teleportTo(s.overworld(), exit.getX() + 0.5, exit.getY(), exit.getZ() - 1.5, java.util.Set.of(), 0f, 0f, false);
				s.overworld().getEntity(guest).teleportTo(exit.getX() + 0.5, exit.getY(), exit.getZ() - 4.5);
			});
			ctx.waitTicks(80);
			check(server.computeOnServer(s -> s.overworld().getEntity(guest) == null), "a townsperson led to an exit is rescued");
			int scoreAfter = server.computeOnServer(s -> Match.scores().values().stream().mapToInt(Integer::intValue).sum());
			check(scoreAfter >= scoreBefore + HwConfig.get().scoreRescueNpc, "the rescue scores (" + scoreBefore + " -> " + scoreAfter + ")");

			// "Hide here": sneak + right click; it pays out if it lives to the end.
			int hider = server.computeOnServer(s -> npcs(s).get(0).getId());
			Vec3 hiderPos = server.computeOnServer(s -> s.overworld().getEntity(hider).position());
			this.place(server, server.computeOnServer(s -> near(s, s.overworld().getEntity(hider), 1.8)));
			this.lookAt(ctx, hiderPos.add(0, 1.2, 0));
			ctx.getInput().holdKey(o -> o.keyShift);
			ctx.waitTicks(3);
			ctx.getInput().pressKey(o -> o.keyUse);
			ctx.waitTicks(5);
			ctx.getInput().releaseKey(o -> o.keyShift);
			check("hidden".equals(server.computeOnServer(s -> Townsfolk.stateOf(s.overworld().getEntity(hider)))), "a townsperson hides when told");
			int beforeEnd = server.computeOnServer(s -> Match.scores().values().stream().mapToInt(Integer::intValue).sum());
			server.runCommand("hw timer 1");
			ctx.waitTicks(40);
			check(server.computeOnServer(s -> Match.state()) == Match.State.IDLE, "the match ends");
			int expected = beforeEnd + HwConfig.get().scoreHiddenNpc;
			String board = ctx.computeOnClient(mc -> mc.player == null || dev.halloween.client.ClientState.matchEnd == null ? ""
				: dev.halloween.client.ClientState.matchEnd.lines().stream().map(l -> l.getString()).reduce("", (a, b) -> a + " | " + b));
			LOG.info("Final board: {}", board);
			check(board.contains(String.valueOf(expected)), "a hidden townsperson that lives pays out at the end (" + expected + ")");
		}
		LOG.info("Bots client test finished OK");
	}

	private static List<Mannequin> npcs(MinecraftServer server) {
		List<Mannequin> list = new ArrayList<>();
		for (Mannequin m : server.overworld().getEntities(EntityTypes.MANNEQUIN, Townsfolk::isNpc)) {
			list.add(m);
		}
		return list;
	}

	/** A free spot about {@code dist} from the entity that it can see. */
	private static Vec3 near(MinecraftServer server, Entity entity, double dist) {
		var level = server.overworld();
		for (int i = 0; i < 16; i++) {
			double a = Math.toRadians(entity.getYRot() + 90 + i * 22.5);
			Vec3 p = entity.position().add(Math.cos(a) * dist, 0, Math.sin(a) * dist);
			BlockPos feet = BlockPos.containing(p);
			if (Nav.standable(level, feet) && Sight.canSee(level, entity.getEyePosition(), Vec3.atBottomCenterOf(feet).add(0, 1.5, 0), entity)) {
				return Vec3.atBottomCenterOf(feet);
			}
		}
		return entity.position().add(1, 0, 0);
	}

	private void place(TestServerContext server, Vec3 pos) {
		server.runOnServer(s -> player(s).teleportTo(s.overworld(), pos.x, pos.y, pos.z, java.util.Set.of(), 0f, 0f, false));
	}

	private static double distance(MinecraftServer server, UUID botId) {
		ServerPlayer bot = server.getPlayerList().getPlayer(botId);
		return bot == null ? 999 : bot.distanceTo(player(server));
	}

	private static ServerPlayer player(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!(p instanceof BotPlayer)) {
				return p;
			}
		}
		throw new IllegalStateException("no player");
	}

	private void tp(TestServerContext server, double dx, double dy, double dz) {
		this.place(server, new Vec3(this.origin.getX() + dx, this.origin.getY() + dy, this.origin.getZ() + dz));
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
			float[] rot = ctx.computeOnClient(mc -> {
				Vec3 d = target.subtract(CameraAim.origin());
				float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
				float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
				return new float[]{yaw, pitch};
			});
			ctx.getInput().lookAt(rot[0], rot[1]);
		}
		ctx.waitTicks(2);
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
