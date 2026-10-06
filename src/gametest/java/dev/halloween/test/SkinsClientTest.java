package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.client.ClientSkins;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Bots;
import dev.halloween.game.Match;
import dev.halloween.game.MatchManager;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Match skins: the Shape wears Michael, bots wear the survivors they are named after, everyone gets their own back after. */
public class SkinsClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-skins-test");

	private BlockPos origin;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("skins")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			this.origin = server.computeOnServer(s -> player(s).blockPosition());
			server.runOnServer(s -> HwConfig.get().countdownSeconds = 30);
			server.runCommand("hw point clear npc");

			// The player is the Shape: Michael; the bots are survivors of the game, each their own.
			// A long countdown: everyone stands still for the pictures.
			server.runCommand("hw start @p");
			ctx.waitTicks(40);
			check(server.computeOnServer(s -> MatchManager.role(player(s))) == Role.SHAPE, "the player is the Shape");
			String mine = ctx.computeOnClient(mc -> path(ClientSkins.forEntity(mc.player.getId())));
			check(mine.endsWith("skin/michael.png"), "the Shape wears Michael's skin (" + mine + ")");
			String bots = ctx.computeOnClient(mc -> {
				StringBuilder bad = new StringBuilder();
				for (var p : mc.level.players()) {
					if (p == mc.player) {
						continue;
					}
					String skin = path(ClientSkins.forEntity(p.getId()));
					String name = p.getPlainTextName().toLowerCase(Locale.ROOT);
					LOG.info("{} wears {}", name, skin);
					if (!skin.endsWith("skin/" + name + ".png")) {
						bad.append(name).append(" -> ").append(skin).append("; ");
					}
				}
				return bad.toString();
			});
			check(bots.isEmpty(), "each bot wears the survivor it is named after (" + bots + ")");

			// Line them up in front of the camera, facing it, and Michael's face on a stand-in beside them.
			server.runOnServer(s -> {
				ServerPlayer shape = player(s);
				Vec3 at = this.at(10.5, 0, -10.5);
				shape.teleportTo(s.overworld(), at.x, at.y, at.z, java.util.Set.of(), 180f, 5f, false);
				List<BotPlayer> list = Bots.all();
				for (int i = 0; i < list.size(); i++) {
					list.get(i).teleportTo(s.overworld(), at.x - 3 + i * 2, at.y, at.z - 5, java.util.Set.of(), 0f, 0f, false);
				}
				s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), String.format(Locale.ROOT,
					"summon minecraft:mannequin %.1f %.1f %.1f {profile:{texture:\"halloween:entity/skin/michael\",model:\"wide\"},Rotation:[0f,0f],hide_description:1b}",
					at.x - 1.6, at.y, at.z - 2.6));
			});
			ctx.waitTicks(15);
			ctx.takeScreenshot("s01_survivors_and_michael");
			ctx.waitTicks(20);
			ctx.takeScreenshot("s02_michael_back");

			server.runCommand("hw stop");
			ctx.waitTicks(10);
			check(ctx.computeOnClient(mc -> ClientSkins.forEntity(mc.player.getId()) == null), "after the match everyone has their own skin back");

			// A lone survivor with bots: a survivor's skin nobody else wears.
			server.runCommand("kill @e[type=minecraft:mannequin]");
			server.runCommand("hw start");
			ctx.waitTicks(40);
			check(server.computeOnServer(s -> Match.frozen() && MatchManager.role(player(s)) == Role.CIVILIAN), "the player is a survivor");
			String me = ctx.computeOnClient(mc -> path(ClientSkins.forEntity(mc.player.getId())));
			String taken = ctx.computeOnClient(mc -> {
				StringBuilder sb = new StringBuilder();
				for (var p : mc.level.players()) {
					if (p != mc.player) {
						sb.append(path(ClientSkins.forEntity(p.getId()))).append(' ');
					}
				}
				return sb.toString();
			});
			LOG.info("Me: {}, the bots: {}", me, taken);
			check(me.contains("skin/") && !me.endsWith("michael.png") && !taken.contains(me), "the player wears a survivor nobody else does (" + me + ")");
			server.runOnServer(s -> {
				ServerPlayer p = player(s);
				Vec3 at = this.at(10.5, 0, -10.5);
				p.teleportTo(s.overworld(), at.x, at.y, at.z, java.util.Set.of(), 180f, 5f, false);
				List<BotPlayer> list = Bots.all();
				for (int i = 0; i < list.size(); i++) {
					list.get(i).teleportTo(s.overworld(), at.x - 2 + i * 2, at.y, at.z - 4, java.util.Set.of(), 0f, 0f, false);
				}
			});
			ctx.waitTicks(15);
			ctx.takeScreenshot("s03_fellow_survivors");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
		}
		HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds;
		LOG.info("Skins client test finished OK");
	}

	private static String path(PlayerSkin skin) {
		return skin == null ? "own" : skin.body().texturePath().toString();
	}

	private Vec3 at(double dx, double dy, double dz) {
		return new Vec3(this.origin.getX() + dx, this.origin.getY() + dy, this.origin.getZ() + dz);
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
