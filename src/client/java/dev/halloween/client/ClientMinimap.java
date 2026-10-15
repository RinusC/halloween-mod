package dev.halloween.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.halloween.game.Minimap;
import dev.halloween.net.HwNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * The round minimap in the top right corner: the town's plan from the server ({@link Minimap}) drawn as a
 * dark blueprint (buildings outlined, roads a shade lighter, trees darker), turned so that where you look is
 * up, with your arrow in the middle and N on the rim. Nobody else is on it.
 */
public final class ClientMinimap {
	/** Blocks from the middle to the rim; the picture has one pixel per block. */
	private static final int RADIUS = 40;
	private static final int SIZE = RADIUS * 2 + 1;
	private static final Identifier ID = Identifier.fromNamespaceAndPath("halloween", "minimap");

	private static final int OUTSIDE = 0xFF141A23;
	private static final int GROUND = 0xFF2B3746;
	private static final int ROAD = 0xFF3B4859;
	private static final int TREES = 0xFF1E2833;
	private static final int BUILDING = 0xFF151B26;
	private static final int OUTLINE = 0xFFB8C3CF;
	private static final int RIM = 0xFF9AA6B4;

	private static byte[] plan;
	private static boolean[] edge;
	private static int x0;
	private static int z0;
	private static int w;
	private static int h;
	private static DynamicTexture texture;

	private ClientMinimap() {
	}

	public static void apply(HwNet.MinimapSync sync) {
		if (sync.width() <= 0) {
			plan = null;
			return;
		}
		x0 = sync.x0();
		z0 = sync.z0();
		w = sync.width();
		h = sync.height();
		plan = sync.plan();
		// Building cells next to anything else are the walls: drawn as a light outline.
		edge = new boolean[w * h];
		for (int z = 0; z < h; z++) {
			for (int x = 0; x < w; x++) {
				if (plan[z * w + x] != Minimap.BUILDING) {
					continue;
				}
				boolean outer = false;
				for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
					int nx = x + d[0];
					int nz = z + d[1];
					outer |= nx < 0 || nz < 0 || nx >= w || nz >= h || plan[nz * w + nx] != Minimap.BUILDING;
				}
				edge[z * w + x] = outer;
			}
		}
	}

	public static void clear() {
		plan = null;
	}

	public static boolean active() {
		return plan != null;
	}

	private static int colorAt(int x, int z) {
		int px = x - x0;
		int pz = z - z0;
		if (px < 0 || pz < 0 || px >= w || pz >= h) {
			return OUTSIDE;
		}
		int i = pz * w + px;
		return switch (plan[i]) {
			case Minimap.ROAD -> ROAD;
			case Minimap.TREES -> TREES;
			case Minimap.BUILDING -> ClientUpsideDown.active() && ClientUpsideDown.occupied(x, z)
				? (edge[i] ? 0xFFFF5040 : beatRed()) : edge[i] ? OUTLINE : BUILDING;
			default -> GROUND;
		};
	}

	/** A house with someone in it, from the Upside Down: red, beating. */
	private static int beatRed() {
		float b = ClientUpsideDown.beat(0f);
		int r = (int) (110 + 120 * b);
		return 0xFF000000 | r << 16 | (int) (10 + 20 * b) << 8 | 14;
	}

	/** Draws the map with its top left at (x, y), {@code size} GUI pixels across. */
	public static void render(GuiGraphicsExtractor g, Minecraft mc, int x, int y, int size, float partial) {
		if (texture == null) {
			texture = new DynamicTexture(() -> "halloween minimap", SIZE, SIZE, true);
			mc.getTextureManager().register(ID, texture);
		}
		NativeImage img = texture.getPixels();
		double px = Mth.lerp(partial, mc.player.xo, mc.player.getX());
		double pz = Mth.lerp(partial, mc.player.zo, mc.player.getZ());
		double yaw = Math.toRadians(mc.player.getViewYRot(partial));
		// Where you look is up; to the right is your right.
		double fx = -Math.sin(yaw);
		double fz = Math.cos(yaw);
		double rx = -Math.cos(yaw);
		double rz = -Math.sin(yaw);
		for (int iy = 0; iy < SIZE; iy++) {
			for (int ix = 0; ix < SIZE; ix++) {
				double dx = ix - RADIUS;
				double dy = iy - RADIUS;
				double d = Math.sqrt(dx * dx + dy * dy);
				if (d > RADIUS + 0.5) {
					img.setPixel(ix, iy, 0);
					continue;
				}
				int color;
				if (d > RADIUS - 1.2) {
					color = RIM;
				} else {
					double wx = px + rx * dx - fx * dy;
					double wz = pz + rz * dx - fz * dy;
					color = colorAt(Mth.floor(wx), Mth.floor(wz));
				}
				// Fading towards the rim, like a lens.
				float fade = Mth.clamp((float) (RADIUS - d) / 8f, 0.45f, 1f);
				int alpha = (int) (0xE6 * fade);
				img.setPixel(ix, iy, (alpha << 24) | (color & 0xFFFFFF));
			}
		}
		arrow(img);
		texture.upload();
		g.blit(RenderPipelines.GUI_TEXTURED, ID, x, y, 0, 0, size, size, SIZE, SIZE, SIZE, SIZE);
		// North on the rim.
		double nx = 0 * rx + -1 * rz;
		double ny = -(0 * fx + -1 * fz);
		double scale = size / (double) SIZE;
		int cx = x + size / 2;
		int cy = y + size / 2;
		int tx = (int) Math.round(cx + nx * (RADIUS - 5) * scale);
		int ty = (int) Math.round(cy + ny * (RADIUS - 5) * scale);
		g.centeredText(mc.font, Component.literal("N"), tx, ty - 4, 0xFFE8EEF4);
	}

	/** Your arrow in the middle, pointing up: white with a dark edge. */
	private static void arrow(NativeImage img) {
		String[] rows = {
			"...#...",
			"..###..",
			"..#.#..",
			".##.##.",
			".#...#.",
			"##...##",
			"#.....#"};
		int c = RADIUS;
		for (int r = 0; r < rows.length; r++) {
			for (int k = 0; k < 7; k++) {
				if (rows[r].charAt(k) == '#') {
					int ax = c - 3 + k;
					int ay = c - 3 + r;
					// A dark shadow under each pixel, then the white.
					if (ay + 1 < SIZE) {
						img.setPixel(ax, ay + 1, 0xFF0A0E14);
					}
				}
			}
		}
		for (int r = 0; r < rows.length; r++) {
			for (int k = 0; k < 7; k++) {
				if (rows[r].charAt(k) == '#') {
					img.setPixel(c - 3 + k, c - 3 + r, 0xFFFFFFFF);
				}
			}
		}
	}
}
