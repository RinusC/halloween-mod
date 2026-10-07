package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.BloodParticles;
import dev.halloween.client.DashSteps;
import dev.halloween.game.AiStats;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Bots;
import dev.halloween.game.MatchManager;
import dev.halloween.game.Townsfolk;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Blood (drops that leave splats, pools under the dead, a trail behind the wounded), Michael's knife (clean,
 * then bloody after a kill), bodies keeping the victim's skin, and his footsteps while he speeds up.
 */
public class BloodClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-blood-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("blood")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runCommand("time set noon");
			// The knife in hand, clean and bloody, on two mannequins in daylight (a look, not a check).
			BlockPos room = server.computeOnServer(s -> player(s).blockPosition()).offset(130, 0, 0);
			server.runOnServer(s -> {
				for (int x = -4; x <= 4; x++) {
					for (int z = -4; z <= 4; z++) {
						s.overworld().setBlock(room.offset(x, -1, z), net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(), 2);
						for (int y = 0; y <= 3; y++) {
							s.overworld().setBlock(room.offset(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
						}
					}
				}
			});
			for (String[] m : new String[][]{{"-1", "michael_knife"}, {"1", "michael_knife_bloody"}}) {
				server.runCommand(String.format(Locale.ROOT, "summon minecraft:mannequin %d %d %d {immovable:1b,Rotation:[90f,0f],equipment:{mainhand:{id:\"minecraft:iron_sword\",count:1,components:{\"minecraft:item_model\":\"halloween:%s\"}}}}",
					room.getX(), room.getY(), room.getZ() + Integer.parseInt(m[0]), m[1]));
			}
			server.runCommand(String.format(Locale.ROOT, "tp @p %.1f %d %.1f 45 15", room.getX() + 3.5, room.getY(), room.getZ() - 3.0));
			ctx.waitTicks(30);
			ctx.takeScreenshot("b00_knife");
			server.runCommand(String.format(Locale.ROOT, "tp @p %.1f %d %.1f -90 10", room.getX() - 2.5, room.getY(), room.getZ() + 0.5));
			ctx.waitTicks(10);
			ctx.takeScreenshot("b00b_knife_side");
			server.runCommand("kill @e[type=minecraft:mannequin,distance=..6,x=" + room.getX() + ",y=" + room.getY() + ",z=" + room.getZ() + "]");
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = 2;
				HwConfig.get().botQteSuccessChance = 0f;
			});
			server.runCommand("hw start @p");
			ctx.waitTicks(60);
			check(server.computeOnServer(s -> knifeModel(player(s))).equals("halloween:michael_knife"), "Michael holds his kitchen knife");

			// A blow that doesn't kill: drops fly and leave splats; the survivor starts to leave a trail.
			int drops0 = ctx.computeOnClient(mc -> BloodParticles.dropsSeen);
			int stains0 = ctx.computeOnClient(mc -> BloodParticles.stainsLaid);
			String wounded = server.computeOnServer(s -> {
				BotPlayer bot = Bots.all().get(1);
				ServerPlayer shape = player(s);
				Vec3 at = shape.position();
				bot.teleportTo(s.overworld(), at.x, at.y, at.z - 1.4, java.util.Set.of(), 0f, 0f, false);
				shape.teleportTo(s.overworld(), at.x, at.y, at.z, java.util.Set.of(), 180f, 10f, false);
				bot.setHealth(bot.getMaxHealth());
				MatchManager.shapeState(shape).attackCooldown = 0;
				MatchManager.onShapeAttack(shape, bot.getId(), false);
				return bot.getScoreboardName() + " " + bot.getHealth() + "/" + bot.getMaxHealth();
			});
			LOG.info("Wounded {}", wounded);
			ctx.waitTicks(50);
			check(ctx.computeOnClient(mc -> BloodParticles.dropsSeen) > drops0, "blood drops fly from the wound");
			check(ctx.computeOnClient(mc -> BloodParticles.stainsLaid) > stains0, "and leave splats where they land");
			ctx.takeScreenshot("b01_blood_splats");
			ctx.waitTicks(100);
			int drips = server.computeOnServer(s -> count(Bots.all().get(1), "blood.drip"));
			check(drips >= 1, "the wounded survivor leaves a trail of drips (" + drips + ")");

			// A kill: a pool spreads, the body keeps the survivor's skin, the knife is bloody.
			int pools0 = server.computeOnServer(s -> total("blood.pool"));
			String skin = server.computeOnServer(s -> {
				BotPlayer bot = Bots.all().get(2);
				ServerPlayer shape = player(s);
				Vec3 at = shape.position();
				String expected = "halloween:entity/skin/" + bot.getScoreboardName().toLowerCase(Locale.ROOT);
				bot.teleportTo(s.overworld(), at.x, at.y, at.z - 1.4, java.util.Set.of(), 0f, 0f, false);
				shape.teleportTo(s.overworld(), at.x, at.y, at.z, java.util.Set.of(), 180f, 10f, false);
				bot.setHealth(1f);
				MatchManager.shapeState(shape).attackCooldown = 0;
				MatchManager.onShapeAttack(shape, bot.getId(), true);
				String got = corpseTexture(s, at.add(0, 0, -1.4));
				return expected.equals(got) ? "ok" : "expected " + expected + ", got " + got;
			});
			check("ok".equals(skin), "the survivor's body keeps their skin (" + skin + ")");
			check(server.computeOnServer(s -> total("blood.pool")) > pools0, "a pool of blood spreads under the body");
			check(server.computeOnServer(s -> knifeModel(player(s))).equals("halloween:michael_knife_bloody"), "the knife is bloody after the kill");
			ctx.waitTicks(100);
			server.runCommand(String.format(Locale.ROOT, "tp @p ~ ~ ~ 180 55"));
			ctx.waitTicks(10);
			ctx.takeScreenshot("b02_pool_and_knife");

			// A townsperson's body keeps their look too.
			String npcSkin = server.computeOnServer(s -> {
				ServerPlayer shape = player(s);
				Mannequin npc = null;
				for (Mannequin m : s.overworld().getEntities(EntityTypes.MANNEQUIN, Townsfolk::isNpc)) {
					npc = m;
					break;
				}
				if (npc == null) {
					return "no townsfolk";
				}
				String expected = String.valueOf(npc.getProfile().skinPatch().body().map(b -> b.id().toString()).orElse("?"));
				Vec3 at = shape.position();
				npc.teleportTo(at.x, at.y, at.z - 1.4);
				npc.setHealth(1f);
				MatchManager.shapeState(shape).attackCooldown = 0;
				MatchManager.onShapeAttack(shape, npc.getId(), true);
				String got = corpseTexture(s, at.add(0, 0, -1.4));
				return expected.equals(got) ? "ok"
					: "expected " + expected + ", got " + got;
			});
			check("ok".equals(npcSkin), "a townsperson's body keeps their look (" + npcSkin + ")");

			// Speeding up: footsteps, one every stride.
			int steps0 = ctx.computeOnClient(mc -> DashSteps.played);
			server.runOnServer(s -> MatchManager.shapeState(player(s)).dashTicks = 80);
			server.runCommand("tp @p ~ ~ ~ 0 10");
			ctx.waitTicks(5);
			ctx.getInput().holdKey(o -> o.keyUp);
			ctx.waitTicks(40);
			ctx.getInput().releaseKey(o -> o.keyUp);
			int steps = ctx.computeOnClient(mc -> DashSteps.played) - steps0;
			check(steps >= 2, "Michael's footsteps sound while he speeds up (" + steps + ")");

			server.runCommand("hw stop");
			ctx.waitTicks(20);
			check(ctx.computeOnClient(mc -> BloodParticles.stains()) == 0, "the blood is washed away when the match ends");
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds;
				HwConfig.get().botQteSuccessChance = new HwConfig().botQteSuccessChance;
			});
		}
		LOG.info("Blood client test finished OK");
	}

	private static String knifeModel(ServerPlayer shape) {
		Identifier id = shape.getInventory().getItem(0).get(DataComponents.ITEM_MODEL);
		return String.valueOf(id);
	}

	/** The texture of the body lying nearest to {@code at}. */
	private static String corpseTexture(MinecraftServer s, Vec3 at) {
		Mannequin best = null;
		for (Mannequin m : s.overworld().getEntities(EntityTypes.MANNEQUIN, m -> m.entityTags().contains(MatchManager.CORPSE_TAG))) {
			if (m.position().distanceTo(at) < 3 && (best == null || m.tickCount < best.tickCount)) {
				best = m;
			}
		}
		if (best == null) {
			return null;
		}
		return best.getProfile().skinPatch().body().map(b -> b.id().toString()).orElse(null);
	}

	private static int count(LivingEntity who, String key) {
		Map<String, Map<String, Integer>> all = AiStats.snapshot();
		return all.getOrDefault(who.getPlainTextName(), Map.of()).getOrDefault(key, 0);
	}

	private static int total(String key) {
		return AiStats.snapshot().getOrDefault("*total", Map.of()).getOrDefault(key, 0);
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
