package dev.halloween.test;

import com.mojang.blaze3d.audio.SoundBuffer;
import dev.halloween.HwConfig;
import dev.halloween.client.ClientAnims;
import dev.halloween.client.ClientState;
import dev.halloween.client.mixin.SoundManagerAccessor;
import dev.halloween.game.AiStats;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Bots;
import dev.halloween.game.Exits;
import dev.halloween.game.HwItems;
import dev.halloween.game.Inventory;
import dev.halloween.game.MapBoards;
import dev.halloween.game.MapPoints;
import dev.halloween.game.MatchManager;
import dev.halloween.game.Talks;
import dev.halloween.game.Townsfolk;
import dev.halloween.net.HwNet;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The newest mechanics only: the town map board (frames, maps, marks), talking a townsperson round (babble and
 * subtitles), the car that waits for its driver (in, out, in again, off), and the grab (choking, the poses: the
 * reach, the hold, the struggle, tearing free, the stab).
 */
public class NewMechanicsClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-new-test");
	private BlockPos origin;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("newmech")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			// The new sounds load (WAV through our loader).
			String bad = ctx.computeOnClient(mc -> {
				StringBuilder sb = new StringBuilder();
				try {
					SoundEngine engine = ((SoundManagerAccessor) mc.getSoundManager()).halloween$soundEngine();
					SoundBufferLibrary library = null;
					for (Field f : SoundEngine.class.getDeclaredFields()) {
						if (f.getType() == SoundBufferLibrary.class) {
							f.setAccessible(true);
							library = (SoundBufferLibrary) f.get(engine);
						}
					}
					for (String path : new String[]{"voice/choke_1", "voice/choke_4", "talk/damien_black_request_1", "talk/claribel_dervla_question_2", "talk/royston_min_agree_3", "talk/sofia_hellen_refuse_4"}) {
						SoundBuffer b = library.getCompleteBuffer(Identifier.fromNamespaceAndPath("halloween", "sounds/" + path + ".ogg")).get();
						if (!b.isValid()) {
							sb.append(path).append(' ');
						}
					}
				} catch (Exception e) {
					return "error " + e;
				}
				return sb.toString();
			});
			check(bad.isEmpty(), "the choking and talk sounds load (" + bad + ")");

			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			this.origin = server.computeOnServer(s -> player(s).blockPosition());
			server.runCommand("time set noon");
			// A map board at the arena's edge (the town has two, see Haddonfield).
			BlockPos board = this.origin.offset(-4, 1, -34);
			server.runOnServer(s -> {
				var level = s.overworld();
				for (int x = -1; x <= MapBoards.TX; x++) {
					for (int y = -1; y <= MapBoards.TY + 1; y++) {
						level.setBlock(board.offset(x, y, 0), Blocks.DARK_OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS);
						for (int z = 1; z <= 4; z++) {
							level.setBlock(board.offset(x, y, z), y < 0 ? Blocks.SMOOTH_STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
						}
					}
				}
				level.setBlock(board.offset(-1, 0, 1), Blocks.DARK_OAK_WALL_SIGN.defaultBlockState()
					.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.SOUTH), Block.UPDATE_CLIENTS);
				MapPoints.get(level).add(MapPoints.Type.MAP_BOARD, board);
				HwConfig.get().countdownSeconds = 2;
				HwConfig.get().botsFillTo = 0;
			});
			server.runCommand("hw start");
			ctx.waitTicks(60);
			String boardCheck = server.computeOnServer(s -> {
				var level = s.overworld();
				List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, new AABB(board).inflate(5), f -> f.entityTags().contains(MapBoards.TAG));
				if (frames.size() != MapBoards.TX * MapBoards.TY) {
					return frames.size() + " frames";
				}
				int red = 0;
				int brown = 0;
				for (ItemFrame f : frames) {
					MapItemSavedData data = level.getMapData(f.getItem().get(DataComponents.MAP_ID));
					for (byte c : data.colors) {
						red += c == MapColor.COLOR_RED.getPackedId(MapColor.Brightness.NORMAL) ? 1 : 0;
						brown += c == MapColor.COLOR_BROWN.getPackedId(MapColor.Brightness.NORMAL) ? 1 : 0;
					}
				}
				return red > 5 && brown > 20 ? "ok" : "red " + red + ", brown " + brown;
			});
			check("ok".equals(boardCheck), "the map board shows the town with the cellars marked and where you are (" + boardCheck + ")");
			server.runCommand(String.format(Locale.ROOT, "tp @p %.1f %d %.1f 180 5", board.getX() + 2.0, this.origin.getY(), board.getZ() + 5.2));
			ctx.waitTicks(40);
			ctx.takeScreenshot("n01_map_board");

			// Talking a townsperson round: the survivor asks, they ask back, they agree; subtitles come up.
			int say0 = server.computeOnServer(s -> total("talk.say.request") + total("talk.say.question") + total("talk.say.agree"));
			boolean started = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				Mannequin npc = s.overworld().getEntities(EntityTypes.MANNEQUIN, Townsfolk::isNpc).stream().findFirst().orElse(null);
				if (npc == null) {
					return false;
				}
				p.teleportTo(s.overworld(), npc.getX() + 1.5, npc.getY(), npc.getZ(), java.util.Set.of(), 90f, 0f, false);
				return Talks.start(p, npc, Townsfolk.FOLLOW);
			});
			check(started, "a talk with a townsperson starts");
			ctx.waitTicks(HwConfig.get().talkTicks + 30);
			int said = server.computeOnServer(s -> total("talk.say.request") + total("talk.say.question") + total("talk.say.agree")) - say0;
			check(said >= 3, "the survivor asks, the townsperson asks back and agrees, in babble (" + said + " lines)");
			ctx.takeScreenshot("n02_talk_subtitles");

			// The car: started with the keys it waits; the driver gets in, out again, in, and drives off.
			HwNet.ExitInfo carInfo = ctx.computeOnClient(mc -> ClientState.match.exits().stream().filter(e -> e.type() == Exits.CAR).findFirst().orElseThrow());
			BlockPos car = carInfo.pos();
			server.runOnServer(s -> {
				Exits.forceOpen(s.overworld(), Exits.CAR);
				Inventory.give(player(s), HwItems.create(HwItems.Kind.SEDAN_KEYS));
				player(s).teleportTo(s.overworld(), car.getX() + 0.5, car.getY(), car.getZ() + 2.6, java.util.Set.of(), 180f, 20f, false);
			});
			ctx.waitTicks(10);
			this.lookAt(ctx, Vec3.atCenterOf(car));
			this.holdUse(ctx, HwConfig.get().startCarTicks + 10);
			check(server.computeOnServer(s -> Exits.carWaiting() && !player(s).isSpectator()), "the engine starts and the one who started it is still out (to fetch others)");
			ctx.waitTicks(10);
			this.holdUse(ctx, HwConfig.get().escapeTicks + 8);
			check(server.computeOnServer(s -> Exits.isSeated(player(s)) && player(s).isSpectator()), "getting in: the first in is the driver");
			ctx.waitTicks(30);
			check(server.computeOnServer(s -> Exits.carWaiting() && Exits.isSeated(player(s))), "the car waits for the driver (no timer)");
			ctx.takeScreenshot("n03_in_the_car");
			ctx.getInput().holdKey(o -> o.keyShift);
			ctx.waitTicks(6);
			ctx.getInput().releaseKey(o -> o.keyShift);
			ctx.waitTicks(6);
			check(server.computeOnServer(s -> !Exits.isSeated(player(s)) && !player(s).isSpectator() && Exits.carWaiting()), "Shift gets the driver out again, the engine still running");
			this.lookAt(ctx, Vec3.atCenterOf(car));
			this.holdUse(ctx, HwConfig.get().escapeTicks + 8);
			check(server.computeOnServer(s -> Exits.isSeated(player(s))), "and in again");
			ctx.getInput().holdKey(o -> o.keyJump);
			ctx.waitTicks(6);
			ctx.getInput().releaseKey(o -> o.keyJump);
			ctx.waitTicks(10);
			check(server.computeOnServer(s -> !Exits.carWaiting() && !Exits.isSeated(player(s)) && count(player(s), "escaped") > 0), "Space: the driver drives off and gets away");
			server.runCommand("hw stop");
			ctx.waitTicks(10);

			// The grab, as the Shape with bots about.
			server.runOnServer(s -> {
				HwConfig.get().botsFillTo = new HwConfig().botsFillTo;
				HwConfig.get().botQteSuccessChance = 0f;
				HwConfig.get().victimPointsToEscape = 1;
			});
			server.runCommand("hw start @p");
			ctx.waitTicks(60);
			int shapeId = server.computeOnServer(s -> player(s).getId());
			server.runOnServer(s -> {
				// A grab at someone out of reach: he still lunges.
				BotPlayer far = Bots.all().getFirst();
				MatchManager.onGrab(player(s), far.getId());
			});
			ctx.waitTicks(4);
			check(ctx.computeOnClient(mc -> ClientAnims.LAST.getOrDefault(shapeId, -1)) == HwNet.Anim.REACH, "a grab lunges, even a miss");
			int botId = server.computeOnServer(s -> {
				BotPlayer bot = Bots.all().getFirst();
				ServerPlayer shape = player(s);
				bot.teleportTo(s.overworld(), shape.getX(), shape.getY(), shape.getZ() - 1.2, java.util.Set.of(), 0f, 0f, false);
				shape.teleportTo(s.overworld(), shape.getX(), shape.getY(), shape.getZ(), java.util.Set.of(), 180f, 25f, false);
				MatchManager.grabOut(shape, bot);
				return bot.getId();
			});
			ctx.waitTicks(30);
			check(ctx.computeOnClient(mc -> ClientAnims.LAST.getOrDefault(shapeId, -1) == HwNet.Anim.HOLD && ClientAnims.LAST.getOrDefault(botId, -1) == HwNet.Anim.STRUGGLE),
				"held by the throat: he holds, the victim struggles");
			check(server.computeOnServer(s -> total("voice.choke")) > 0, "the victim chokes and gasps");
			ctx.takeScreenshot("n04_grab_hold");
			int escaped = 0;
			for (int i = 0; i < 100 && escaped == 0; i++) {
				ctx.waitTicks(2);
				escaped = ctx.computeOnClient(mc -> ClientAnims.LAST.getOrDefault(botId, -1) == HwNet.Anim.BREAK_FREE && ClientAnims.LAST.getOrDefault(shapeId, -1) == HwNet.Anim.STAGGER ? 1 : 0);
			}
			ctx.takeScreenshot("n05_break_free");
			check(escaped == 1, "tearing free: the victim shoves, he staggers back");
			server.runOnServer(s -> {
				HwConfig.get().victimPointsToEscape = new HwConfig().victimPointsToEscape;
				var st = MatchManager.shapeState(player(s));
				st.grabCooldown = 0;
				st.recoverTicks = 0;
				st.stunnedTicks = 0;
				BotPlayer bot = (BotPlayer) s.overworld().getEntity(botId);
				Inventory.give(bot, HwItems.create(HwItems.Kind.POCKET_KNIFE));
				ServerPlayer shape = player(s);
				bot.teleportTo(s.overworld(), shape.getX(), shape.getY(), shape.getZ() - 1.2, java.util.Set.of(), 0f, 0f, false);
				MatchManager.grabOut(shape, bot);
			});
			int stabbed = 0;
			for (int i = 0; i < 60 && stabbed == 0; i++) {
				ctx.waitTicks(2);
				stabbed = ctx.computeOnClient(mc -> ClientAnims.LAST.getOrDefault(botId, -1) == HwNet.Anim.STAB && ClientAnims.LAST.getOrDefault(shapeId, -1) == HwNet.Anim.STABBED ? 1 : 0);
			}
			ctx.takeScreenshot("n06_stab");
			check(stabbed == 1, "a pocket knife gets the victim out: the stab, his hand to the wound");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds;
				HwConfig.get().botQteSuccessChance = new HwConfig().botQteSuccessChance;
			});
		}
		LOG.info("New mechanics client test finished OK");
	}

	private void holdUse(ClientGameTestContext ctx, int ticks) {
		ctx.getInput().holdKey(o -> o.keyUse);
		ctx.waitTicks(ticks);
		ctx.getInput().releaseKey(o -> o.keyUse);
		ctx.waitTicks(4);
	}

	private void lookAt(ClientGameTestContext ctx, Vec3 target) {
		for (int i = 0; i < 10; i++) {
			ctx.waitTicks(2);
			float[] rot = ctx.computeOnClient(mc -> {
				Vec3 d = target.subtract(dev.halloween.client.CameraAim.origin());
				return new float[]{(float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f, (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG)};
			});
			ctx.getInput().lookAt(rot[0], rot[1]);
		}
	}

	private static int total(String key) {
		return AiStats.snapshot().getOrDefault("*total", Map.of()).getOrDefault(key, 0);
	}

	private static int count(ServerPlayer who, String key) {
		return AiStats.snapshot().getOrDefault(who.getPlainTextName(), Map.of()).getOrDefault(key, 0);
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
