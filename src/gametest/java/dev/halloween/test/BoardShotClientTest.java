package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.game.MapBoards;
import dev.halloween.game.MapJob;
import dev.halloween.game.MapPoints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Pictures only: a town map board on Haddonfield in a match, and the map itself as an image. */
public class BoardShotClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-board-shot");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("board")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			BlockPos origin = server.computeOnServer(s -> player(s).blockPosition());
			server.runCommand("execute as @p at @p run hw map haddonfield");
			ctx.waitTicks(2);
			boolean busy = true;
			for (int i = 0; i < 3000 && busy; i++) {
				ctx.waitTicks(10);
				busy = server.computeOnServer(s -> MapJob.busy());
			}
			server.runOnServer(s -> HwConfig.get().countdownSeconds = 2);
			server.runOnServer(s -> dev.halloween.game.Weather.forced = dev.halloween.game.Weather.RAIN);
			server.runCommand("hw start");
			ctx.waitTicks(80);
			List<BlockPos> boards = server.computeOnServer(s -> MapPoints.get(s.overworld()).list(MapPoints.Type.MAP_BOARD));
			LOG.info("Boards at {}", boards);
			for (int b = 0; b < boards.size(); b++) {
				BlockPos board = boards.get(b);
				int[] argb = server.computeOnServer(s -> {
					List<ItemFrame> frames = s.overworld().getEntitiesOfClass(ItemFrame.class, new AABB(board).inflate(3), f -> f.entityTags().contains(MapBoards.TAG));
					frames.sort((x, y) -> x.getBlockY() != y.getBlockY() ? Integer.compare(y.getBlockY(), x.getBlockY()) : Integer.compare(x.getBlockX(), y.getBlockX()));
					int[] px = new int[MapBoards.TX * 128 * MapBoards.TY * 128];
					for (int t = 0; t < frames.size() && t < MapBoards.TX * MapBoards.TY; t++) {
						MapItemSavedData data = s.overworld().getMapData(frames.get(t).getItem().get(DataComponents.MAP_ID));
						for (int y = 0; y < 128; y++) {
							for (int x = 0; x < 128; x++) {
								px[((t / MapBoards.TX) * 128 + y) * MapBoards.TX * 128 + (t % MapBoards.TX) * 128 + x] = MapColor.getColorFromPackedId(data.colors[y * 128 + x]);
							}
						}
					}
					return px;
				});
				try {
					int iw = MapBoards.TX * 128;
					int ih = MapBoards.TY * 128;
					BufferedImage img = new BufferedImage(iw * 2, ih * 2, BufferedImage.TYPE_INT_RGB);
					for (int y = 0; y < ih * 2; y++) {
						for (int x = 0; x < iw * 2; x++) {
							img.setRGB(x, y, argb[(y / 2) * iw + x / 2] & 0xFFFFFF);
						}
					}
					ImageIO.write(img, "png", new File("map_board_" + b + ".png"));
				} catch (Exception e) {
					LOG.error("image", e);
				}
				server.runOnServer(s -> player(s).teleportTo(s.overworld(), board.getX() + MapBoards.TX / 2.0, board.getY() - 1, board.getZ() + 5.0, java.util.Set.of(), 180f, 0f, false));
				ctx.waitTicks(60);
				ctx.takeScreenshot("board_" + b);
			}
			boolean rain = ctx.computeOnClient(mc -> mc.level.isRaining() && mc.level.getBiome(mc.player.blockPosition()).value().getPrecipitationAt(mc.player.blockPosition(), mc.level.getSeaLevel()) == net.minecraft.world.level.biome.Biome.Precipitation.RAIN);
			LOG.info("Rain in Haddonfield: {}", rain);
			if (!rain) {
				throw new AssertionError("FAILED: it rains in Haddonfield");
			}
			server.runOnServer(s -> dev.halloween.game.Weather.forced = -1);
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds);
		}
		LOG.info("Board shots done");
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().get(0);
	}
}
