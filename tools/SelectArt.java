import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * The character select's art (CharacterSelectScreen): java tools/SelectArt.java writes it under
 * assets/halloween/textures/gui/select. Most of it white, tinted when drawn (orange for the survivors, blood red for
 * the killers); four texels to a GUI pixel, smoothed (each with its .mcmeta), so it holds at any GUI scale. The
 * backdrop is a night street on the 31st, blurred as if far behind the hero.
 */
public class SelectArt {
	static final int D = 4;
	static File dir;

	public static void main(String[] args) throws Exception {
		dir = new File(args.length > 0 ? args[0] : "src/main/resources/assets/halloween/textures/gui/select");
		dir.mkdirs();
		panel();
		button();
		shapes();
		glows();
		pedestal();
		backdrop();
		fog();
		grain();
		vignette();
	}

	// ------------------------------------------------------------ helpers

	static BufferedImage img(int w, int h) {
		return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
	}

	static Graphics2D g(BufferedImage i) {
		Graphics2D g = i.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		return g;
	}

	/** Saved, smoothed; clamped unless it tiles. */
	static void save(BufferedImage i, String name, boolean blur, boolean clamp) throws Exception {
		ImageIO.write(i, "png", new File(dir, name + ".png"));
		Files.writeString(new File(dir, name + ".png.mcmeta").toPath(),
			"{\n  \"texture\": {\n    \"blur\": " + blur + ",\n    \"clamp\": " + clamp + "\n  }\n}\n");
		System.out.println(name + " " + i.getWidth() + "x" + i.getHeight());
	}

	/** An octagon: a w by h box with c cut off each corner (texels), inset by in. */
	static Path2D octagon(double w, double h, double c, double in) {
		double k = in * (Math.sqrt(2) - 1);
		Path2D p = new Path2D.Double();
		p.moveTo(c + k, in);
		p.lineTo(w - c - k, in);
		p.lineTo(w - in, c + k);
		p.lineTo(w - in, h - c - k);
		p.lineTo(w - c - k, h - in);
		p.lineTo(c + k, h - in);
		p.lineTo(in, h - c - k);
		p.lineTo(in, c + k);
		p.closePath();
		return p;
	}

	static double clamp(double v) {
		return Math.max(0, Math.min(1, v));
	}

	static int argb(double a, double r, double g, double b) {
		return (int) Math.round(clamp(a) * 255) << 24 | (int) Math.round(clamp(r) * 255) << 16 | (int) Math.round(clamp(g) * 255) << 8
			| (int) Math.round(clamp(b) * 255);
	}

	/** Darkens each pixel's white towards its shape's edges: the inner shadow. d is the distance to the edge, texels. */
	interface Dist {
		double at(double x, double y);
	}

	static void innerShadow(BufferedImage i, Dist dist, double reach, double depth) {
		for (int y = 0; y < i.getHeight(); y++) {
			for (int x = 0; x < i.getWidth(); x++) {
				int p = i.getRGB(x, y);
				int a = p >>> 24;
				if (a == 0) {
					continue;
				}
				double d = Math.max(0, dist.at(x + 0.5, y + 0.5));
				double s = 1 - depth * Math.exp(-d / reach);
				i.setRGB(x, y, argb(a / 255.0, s, s, s));
			}
		}
	}

	// ------------------------------------------------------------ the panel: cut corners, a thin edge, brackets

	static void panel() throws Exception {
		// 24 by 24 GUI pixels, the corners cut 5, sliced 9 in from each side.
		int s = 24 * D;
		double c = 5 * D;
		BufferedImage fill = img(s, s);
		Graphics2D g = g(fill);
		g.setColor(Color.WHITE);
		g.fill(octagon(s, s, c, 0));
		g.dispose();
		innerShadow(fill, (x, y) -> Math.min(Math.min(Math.min(x, y), Math.min(s - x, s - y)),
			Math.min(Math.min(x + y - c, (s - x) + y - c), Math.min(x + (s - y) - c, (s - x) + (s - y) - c)) / Math.sqrt(2)), 2.5 * D, 0.55);
		save(fill, "panel_fill", true, true);

		BufferedImage line = img(s, s);
		g = g(line);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(0.75f * D));
		g.draw(octagon(s, s, c, 0.375 * D));
		g.dispose();
		save(line, "panel_line", true, true);

		// The brackets, top left and bottom right: along the cut and on down the sides.
		BufferedImage corner = img(s, s);
		g = g(corner);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(1.25f * D, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
		double i = 0.625 * D;
		double k = i * (Math.sqrt(2) - 1);
		double arm = 8 * D;
		Path2D tl = new Path2D.Double();
		tl.moveTo(i, c + k + arm * 0.5);
		tl.lineTo(i, c + k);
		tl.lineTo(c + k, i);
		tl.lineTo(c + k + arm, i);
		g.draw(tl);
		Path2D br = new Path2D.Double();
		br.moveTo(s - i, s - c - k - arm * 0.5);
		br.lineTo(s - i, s - c - k);
		br.lineTo(s - c - k, s - i);
		br.lineTo(s - c - k - arm, s - i);
		g.draw(br);
		g.dispose();
		save(corner, "panel_corner", true, true);
	}

	// ------------------------------------------------------------ the button: a slanted slab

	static void button() throws Exception {
		// 24 by 14, leaning 4 over its height; sliced 6 in from either side, stretched up and down.
		int w = 24 * D;
		int h = 14 * D;
		double lean = 4 * D;
		Path2D p = new Path2D.Double();
		p.moveTo(lean, 0);
		p.lineTo(w, 0);
		p.lineTo(w - lean, h);
		p.lineTo(0, h);
		p.closePath();
		BufferedImage fill = img(w, h);
		Graphics2D g = g(fill);
		g.setColor(Color.WHITE);
		g.fill(p);
		g.dispose();
		// Lit from above: a little brighter along the top.
		for (int y = 0; y < h; y++) {
			double s = 1.0 - 0.28 * y / (double) h;
			for (int x = 0; x < w; x++) {
				int a = fill.getRGB(x, y) >>> 24;
				if (a > 0) {
					fill.setRGB(x, y, argb(a / 255.0, s, s, s));
				}
			}
		}
		save(fill, "btn_fill", true, true);
		BufferedImage line = img(w, h);
		g = g(line);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(0.75f * D));
		double in = 0.375 * D;
		Path2D q = new Path2D.Double();
		q.moveTo(lean + in * 0.6, in);
		q.lineTo(w - in * 1.6, in);
		q.lineTo(w - lean - in * 0.6, h - in);
		q.lineTo(in * 1.6, h - in);
		q.closePath();
		g.draw(q);
		g.dispose();
		save(line, "btn_line", true, true);
	}

	// ------------------------------------------------------------ small shapes

	static void shapes() throws Exception {
		// A bar's segment: 5 by 5, leaning 1.5.
		int w = 5 * D;
		BufferedImage seg = img(w, w);
		Graphics2D g = g(seg);
		g.setColor(Color.WHITE);
		Path2D p = new Path2D.Double();
		p.moveTo(1.5 * D, 0);
		p.lineTo(w, 0);
		p.lineTo(w - 1.5 * D, w);
		p.lineTo(0, w);
		p.closePath();
		g.fill(p);
		g.dispose();
		save(seg, "seg", true, true);

		// The perk medallion: a diamond, its edge, its edge in dashes (empty).
		int d = 16 * D;
		Path2D dia = new Path2D.Double();
		double m = 0.75 * D;
		dia.moveTo(d / 2.0, m);
		dia.lineTo(d - m, d / 2.0);
		dia.lineTo(d / 2.0, d - m);
		dia.lineTo(m, d / 2.0);
		dia.closePath();
		BufferedImage df = img(d, d);
		g = g(df);
		g.setColor(Color.WHITE);
		g.fill(dia);
		g.dispose();
		innerShadow(df, (x, y) -> (d / 2.0 - m - Math.abs(x - d / 2.0) - Math.abs(y - d / 2.0)) / Math.sqrt(2), 1.6 * D, 0.5);
		save(df, "diamond_fill", true, true);
		BufferedImage dl = img(d, d);
		g = g(dl);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(0.9f * D));
		g.draw(dia);
		// And a smaller one inside, as on a seal.
		Path2D inner = new Path2D.Double();
		double n = 4.2 * D;
		inner.moveTo(d / 2.0, n);
		inner.lineTo(d - n, d / 2.0);
		inner.lineTo(d / 2.0, d - n);
		inner.lineTo(n, d / 2.0);
		inner.closePath();
		g.setStroke(new BasicStroke(0.6f * D));
		g.draw(inner);
		g.dispose();
		save(dl, "diamond_line", true, true);
		BufferedImage dd = img(d, d);
		g = g(dd);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(0.75f * D, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{2f * D, 1.4f * D}, 0f));
		g.draw(dia);
		g.setStroke(new BasicStroke(0.8f * D));
		g.draw(new Line2D.Double(d / 2.0, d / 2.0 - 2.5 * D, d / 2.0, d / 2.0 + 2.5 * D));
		g.draw(new Line2D.Double(d / 2.0 - 2.5 * D, d / 2.0, d / 2.0 + 2.5 * D, d / 2.0));
		g.dispose();
		save(dd, "diamond_empty", true, true);

		// The portrait's frame: 14 by 14, an octagon ring round a 10 by 10 face.
		int f = 14 * D;
		BufferedImage fr = img(f, f);
		g = g(fr);
		g.setColor(Color.WHITE);
		Area ring = new Area(octagon(f, f, 3.2 * D, 0));
		ring.subtract(new Area(octagon(f, f, 2.4 * D, 2 * D)));
		g.fill(ring);
		g.dispose();
		save(fr, "frame", true, true);

		// A padlock, 7 by 9.
		BufferedImage lock = img(7 * D, 9 * D);
		g = g(lock);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(1.3f * D));
		g.draw(new Arc2D.Double(1.4 * D, 0.65 * D, 4.2 * D, 5.2 * D, 0, 180, Arc2D.OPEN));
		g.draw(new Line2D.Double(1.4 * D, 3.2 * D, 1.4 * D, 4.4 * D));
		g.draw(new Line2D.Double(5.6 * D, 3.2 * D, 5.6 * D, 4.4 * D));
		Area body = new Area(new RoundRectangle2D.Double(0, 4 * D, 7 * D, 5 * D, 1.2 * D, 1.2 * D));
		body.subtract(new Area(new Ellipse2D.Double(2.6 * D, 5.2 * D, 1.8 * D, 1.8 * D)));
		body.subtract(new Area(new Rectangle2D.Double(3.1 * D, 6.4 * D, 0.8 * D, 1.6 * D)));
		g.fill(body);
		g.dispose();
		save(lock, "lock", true, true);

		// The arrows either side of the hero: 8 by 14 chevrons.
		for (int side = 0; side < 2; side++) {
			BufferedImage ch = img(8 * D, 14 * D);
			g = g(ch);
			g.setColor(Color.WHITE);
			g.setStroke(new BasicStroke(1.4f * D, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
			Path2D v = new Path2D.Double();
			double x0 = side == 0 ? 6.2 * D : 1.8 * D;
			double x1 = side == 0 ? 1.8 * D : 6.2 * D;
			v.moveTo(x0, 1.5 * D);
			v.lineTo(x1, 7 * D);
			v.lineTo(x0, 12.5 * D);
			g.draw(v);
			g.dispose();
			save(ch, side == 0 ? "chev_l" : "chev_r", true, true);
		}
	}

	// ------------------------------------------------------------ light

	static void glows() throws Exception {
		// A soft round glow, white with its strength in the alpha.
		int s = 128;
		BufferedImage glow = img(s, s);
		for (int y = 0; y < s; y++) {
			for (int x = 0; x < s; x++) {
				double dx = (x + 0.5) / s * 2 - 1;
				double dy = (y + 0.5) / s * 2 - 1;
				double r = Math.sqrt(dx * dx + dy * dy);
				double a = r >= 1 ? 0 : Math.pow(1 - r, 2.2) * (1 - r * r);
				glow.setRGB(x, y, argb(a, 1, 1, 1));
			}
		}
		save(glow, "glow", true, true);

		// A line of light, fading to either end: under the name, along the header.
		BufferedImage line = img(256, 16);
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 256; x++) {
				double u = Math.abs((x + 0.5) / 256 * 2 - 1);
				double v = Math.abs((y + 0.5) / 16 * 2 - 1);
				double along = Math.pow(clamp(1 - u), 1.6);
				double across = Math.exp(-v * v * 9) * 0.55 + (v < 0.14 ? 0.45 : 0);
				line.setRGB(x, y, argb(along * across, 1, 1, 1));
			}
		}
		save(line, "glow_line", true, true);

		// The light falling on them from above: a cone, strongest at the top, gone by the floor.
		BufferedImage beam = img(128, 256);
		for (int y = 0; y < 256; y++) {
			double t = (y + 0.5) / 256;
			double half = 0.16 + 0.34 * t;
			for (int x = 0; x < 128; x++) {
				double u = Math.abs((x + 0.5) / 128 * 2 - 1) / (half * 2);
				double a = clamp(1 - u) * clamp(1 - u) * (0.25 + 0.75 * (1 - t)) * Math.min(1, t * 6);
				beam.setRGB(x, y, argb(a, 1, 1, 1));
			}
		}
		save(beam, "beam", true, true);
	}

	// ------------------------------------------------------------ the pedestal under the hero

	static void pedestal() throws Exception {
		// 120 by 30 GUI pixels: a low stone drum, the top an ellipse; the rim light separate (tinted).
		int w = 120 * D;
		int h = 30 * D;
		double rx = w / 2.0 - 2 * D;
		double ry = 9 * D;
		double cy = ry + 2 * D;
		double side = 6 * D;
		BufferedImage stone = img(w, h);
		Graphics2D g = g(stone);
		// The side: an ellipse's lower half dropped by its thickness, darker to the edges.
		Area drum = new Area(new Ellipse2D.Double(w / 2.0 - rx, cy - ry + side, rx * 2, ry * 2));
		drum.add(new Area(new Rectangle2D.Double(w / 2.0 - rx, cy, rx * 2, side)));
		g.setPaint(new LinearGradientPaint((float) (w / 2.0 - rx), 0, (float) (w / 2.0 + rx), 0, new float[]{0f, 0.5f, 1f},
			new Color[]{new Color(0x0A0B10), new Color(0x1C1F28), new Color(0x0A0B10)}));
		g.fill(drum);
		// The top: worn stone, lit a little from the middle.
		g.setPaint(new RadialGradientPaint(new Point2D.Double(w / 2.0, cy - ry * 0.2), (float) rx, new float[]{0f, 0.7f, 1f},
			new Color[]{new Color(0x30333E), new Color(0x1E212A), new Color(0x14161D)}));
		g.fill(new Ellipse2D.Double(w / 2.0 - rx, cy - ry, rx * 2, ry * 2));
		// Rings cut in it.
		g.setStroke(new BasicStroke(0.5f * D));
		g.setColor(new Color(0, 0, 0, 90));
		for (double k : new double[]{0.82, 0.55}) {
			g.draw(new Ellipse2D.Double(w / 2.0 - rx * k, cy - ry * k, rx * 2 * k, ry * 2 * k));
		}
		g.dispose();
		// A little grit.
		Random rnd = new Random(31);
		for (int i = 0; i < 9000; i++) {
			int x = rnd.nextInt(w);
			int y = rnd.nextInt(h);
			int p = stone.getRGB(x, y);
			if (p >>> 24 > 200) {
				double f = 0.85 + rnd.nextDouble() * 0.3;
				stone.setRGB(x, y, argb(1, ((p >> 16) & 255) / 255.0 * f, ((p >> 8) & 255) / 255.0 * f, (p & 255) / 255.0 * f));
			}
		}
		save(stone, "pedestal", true, true);

		BufferedImage rim = img(w, h);
		g = g(rim);
		// The rim of the top, bright along the front, a glow under it.
		for (int i = 6; i >= 1; i--) {
			g.setStroke(new BasicStroke(i * 0.9f * D));
			g.setColor(new Color(1f, 1f, 1f, 0.05f));
			g.draw(new Ellipse2D.Double(w / 2.0 - rx, cy - ry, rx * 2, ry * 2));
		}
		g.setStroke(new BasicStroke(0.6f * D));
		g.setPaint(new GradientPaint(0, (float) (cy - ry), new Color(1f, 1f, 1f, 0.25f), 0, (float) (cy + ry), new Color(1f, 1f, 1f, 1f)));
		g.draw(new Ellipse2D.Double(w / 2.0 - rx, cy - ry, rx * 2, ry * 2));
		g.dispose();
		save(rim, "pedestal_rim", true, true);
	}

	// ------------------------------------------------------------ the street behind

	static void backdrop() throws Exception {
		int w = 1024;
		int h = 512;
		int horizon = 330;
		BufferedImage b = img(w, h);
		Graphics2D g = g(b);
		Random rnd = new Random(1031);
		// The sky, the glow of the town low on it.
		g.setPaint(new LinearGradientPaint(0, 0, 0, horizon, new float[]{0f, 0.75f, 1f},
			new Color[]{new Color(0x080B16), new Color(0x182036), new Color(0x2C2E3E)}));
		g.fillRect(0, 0, w, horizon);
		// A hazy moon up on the left.
		for (int i = 40; i >= 0; i--) {
			float a = i == 0 ? 0.5f : 0.006f;
			double r = 22 + i * 2.2;
			g.setColor(new Color(0.75f, 0.78f, 0.85f, a));
			g.fill(new Ellipse2D.Double(190 - r, 92 - r, r * 2, r * 2));
		}
		// Far trees: bare, against the sky.
		g.setColor(new Color(0x090B12));
		for (int i = 0; i < 9; i++) {
			double x = 30 + i * 118 + rnd.nextInt(50);
			tree(g, rnd, x, horizon - 40, -Math.PI / 2, 70 + rnd.nextInt(40), 7, 6);
		}
		// The houses along the street: gables, a porch light, a window or two lit.
		int x = -20;
		while (x < w) {
			int hw = 110 + rnd.nextInt(70);
			int wallH = 70 + rnd.nextInt(30);
			int roofH = 40 + rnd.nextInt(25);
			int base = horizon + 12;
			boolean middle = x + hw > w * 0.36 && x < w * 0.64;
			Color wall = new Color(middle ? 0x0B0D15 : 0x0C0E16);
			g.setColor(wall);
			g.fillRect(x, base - wallH, hw, wallH);
			Path2D roof = new Path2D.Double();
			roof.moveTo(x - 8, base - wallH);
			roof.lineTo(x + hw / 2.0, base - wallH - roofH);
			roof.lineTo(x + hw + 8, base - wallH);
			roof.closePath();
			g.fill(roof);
			// A chimney sometimes.
			if (rnd.nextBoolean()) {
				g.fillRect(x + hw * 3 / 4 - 6, base - wallH - roofH + 6, 12, roofH);
			}
			// Windows: most dark, some lit warm (fewer behind the hero).
			for (int wy = 0; wy < 2; wy++) {
				for (int wx = 0; wx < 3; wx++) {
					int px = x + 16 + wx * (hw - 32) / 3 + 4;
					int py = base - wallH + 12 + wy * 30;
					boolean lit = rnd.nextDouble() < (middle ? 0.22 : 0.34);
					if (lit) {
						glow(g, px + 7, py + 9, 26, new Color(1f, 0.62f, 0.25f), 0.10f);
						g.setColor(new Color(0xE89A48));
					} else {
						g.setColor(new Color(0x10131C));
					}
					g.fillRect(px, py, 14, 18);
					g.setColor(wall);
					g.fillRect(px + 6, py, 2, 18);
					g.fillRect(px, py + 8, 14, 2);
				}
			}
			// A jack-o'-lantern on the step.
			if (rnd.nextDouble() < 0.7) {
				int px = x + hw / 2 + rnd.nextInt(30) - 15;
				pumpkin(g, px, base + 6, 9 + rnd.nextInt(4));
			}
			x += hw + 26 + rnd.nextInt(30);
		}
		// The ground: the pavement, the road, darker nearer.
		g.setPaint(new LinearGradientPaint(0, horizon + 12, 0, h, new float[]{0f, 1f}, new Color[]{new Color(0x161925), new Color(0x06070B)}));
		g.fillRect(0, horizon + 12, w, h - horizon - 12);
		// A picket fence along the pavement.
		g.setColor(new Color(0x0B0D14));
		for (int px = 0; px < w; px += 13) {
			if (px > w * 0.4 && px < w * 0.6) {
				continue;
			}
			Path2D picket = new Path2D.Double();
			picket.moveTo(px, horizon + 46);
			picket.lineTo(px, horizon + 22);
			picket.lineTo(px + 4, horizon + 16);
			picket.lineTo(px + 8, horizon + 22);
			picket.lineTo(px + 8, horizon + 46);
			picket.closePath();
			g.fill(picket);
		}
		g.fillRect(0, horizon + 26, (int) (w * 0.4), 3);
		g.fillRect((int) (w * 0.6), horizon + 26, w, 3);
		// Street lamps, one each side, their light on the ground.
		for (int lx : new int[]{110, 905}) {
			glow(g, lx, horizon - 120, 110, new Color(1f, 0.7f, 0.35f), 0.022f);
			glow(g, lx, horizon + 60, 160, new Color(1f, 0.65f, 0.3f), 0.014f);
			g.setColor(new Color(0x08090E));
			g.fillRect(lx - 3, horizon - 118, 6, 190);
			g.fillRect(lx - 9, horizon - 124, 18, 6);
			g.setColor(new Color(0xFFD08A));
			g.fill(new Ellipse2D.Double(lx - 6, horizon - 118, 12, 9));
		}
		// Pumpkins nearer, either side, and fallen leaves.
		pumpkin(g, 60, h - 70, 26);
		pumpkin(g, 120, h - 52, 18);
		pumpkin(g, 960, h - 66, 24);
		for (int i = 0; i < 700; i++) {
			double lx = rnd.nextDouble() * w;
			double ly = horizon + 40 + Math.pow(rnd.nextDouble(), 0.6) * (h - horizon - 40);
			double s = 1.5 + (ly - horizon) / 60.0 * rnd.nextDouble();
			float[] hue = {0.04f + rnd.nextFloat() * 0.06f, 0.7f, 0.18f + rnd.nextFloat() * 0.22f};
			Color c = Color.getHSBColor(hue[0], hue[1], hue[2]);
			g.setColor(c);
			g.fill(new Ellipse2D.Double(lx, ly, s * 1.6, s));
		}
		g.dispose();
		// Far behind the hero: out of focus.
		blur(b, 5);
		blur(b, 5);
		blur(b, 5);
		save(b, "backdrop", true, true);
	}

	static void tree(Graphics2D g, Random rnd, double x, double y, double angle, double len, double width, int depth) {
		if (depth == 0 || len < 4) {
			return;
		}
		double x1 = x + Math.cos(angle) * len;
		double y1 = y + Math.sin(angle) * len;
		g.setStroke(new BasicStroke((float) width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(new Line2D.Double(x, y, x1, y1));
		int n = 2 + (rnd.nextDouble() < 0.3 ? 1 : 0);
		for (int i = 0; i < n; i++) {
			double a = angle + (rnd.nextDouble() - 0.5) * 1.4;
			tree(g, rnd, x1, y1, a, len * (0.6 + rnd.nextDouble() * 0.2), width * 0.66, depth - 1);
		}
	}

	static void glow(Graphics2D g, double x, double y, double r, Color c, float a) {
		for (int i = 10; i >= 1; i--) {
			double rr = r * i / 10;
			g.setColor(new Color(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, a));
			g.fill(new Ellipse2D.Double(x - rr, y - rr, rr * 2, rr * 2));
		}
	}

	static void pumpkin(Graphics2D g, double x, double y, double s) {
		glow(g, x, y - s * 0.4, s * 3, new Color(1f, 0.5f, 0.12f), 0.05f);
		g.setColor(new Color(0x7A3608));
		for (int i = -1; i <= 1; i++) {
			g.fill(new Ellipse2D.Double(x - s * 0.55 + i * s * 0.35, y - s * 0.8, s * 1.1, s * 0.8));
		}
		g.setColor(new Color(0x1C140A));
		g.fillRect((int) (x - s * 0.08), (int) (y - s * 0.98), (int) Math.max(2, s * 0.16), (int) (s * 0.22));
		// The face, lit from inside.
		g.setColor(new Color(0xFFC060));
		Path2D eye = new Path2D.Double();
		for (int side = -1; side <= 1; side += 2) {
			eye.moveTo(x + side * s * 0.32, y - s * 0.6);
			eye.lineTo(x + side * s * 0.18, y - s * 0.46);
			eye.lineTo(x + side * s * 0.44, y - s * 0.46);
			eye.closePath();
		}
		g.fill(eye);
		g.fill(new Arc2D.Double(x - s * 0.36, y - s * 0.52, s * 0.72, s * 0.34, 180, 180, Arc2D.CHORD));
	}

	/** A box blur of radius r, opaque images. */
	static void blur(BufferedImage b, int r) {
		int w = b.getWidth();
		int h = b.getHeight();
		int[] px = b.getRGB(0, 0, w, h, null, 0, w);
		int[] out = new int[px.length];
		for (int pass = 0; pass < 2; pass++) {
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int rs = 0;
					int gs = 0;
					int bs = 0;
					int n = 0;
					for (int k = -r; k <= r; k++) {
						int xx = pass == 0 ? Math.max(0, Math.min(w - 1, x + k)) : x;
						int yy = pass == 1 ? Math.max(0, Math.min(h - 1, y + k)) : y;
						int p = px[yy * w + xx];
						rs += (p >> 16) & 255;
						gs += (p >> 8) & 255;
						bs += p & 255;
						n++;
					}
					out[y * w + x] = 0xFF000000 | (rs / n) << 16 | (gs / n) << 8 | (bs / n);
				}
			}
			System.arraycopy(out, 0, px, 0, px.length);
		}
		b.setRGB(0, 0, w, h, px, 0, w);
	}

	// ------------------------------------------------------------ air: fog, grain, the dark at the edges

	/** Value noise that wraps every period cells. */
	static double noise(double x, double y, int period, long seed) {
		int x0 = (int) Math.floor(x);
		int y0 = (int) Math.floor(y);
		double fx = x - x0;
		double fy = y - y0;
		fx = fx * fx * (3 - 2 * fx);
		fy = fy * fy * (3 - 2 * fy);
		double a = hash(Math.floorMod(x0, period), y0, seed);
		double b = hash(Math.floorMod(x0 + 1, period), y0, seed);
		double c = hash(Math.floorMod(x0, period), y0 + 1, seed);
		double d = hash(Math.floorMod(x0 + 1, period), y0 + 1, seed);
		return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
	}

	static double hash(int x, int y, long seed) {
		long h = x * 374761393L + y * 668265263L + seed * 982451653L;
		h = (h ^ (h >>> 13)) * 1274126177L;
		return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0;
	}

	static void fog() throws Exception {
		// Wisps, thicker low down; it wraps left to right so it can drift.
		int w = 512;
		int h = 128;
		BufferedImage f = img(w, h);
		for (int y = 0; y < h; y++) {
			double v = (y + 0.5) / h;
			double low = Math.pow(v, 1.4) * Math.min(1, (1 - v) * 4 + 0.6);
			for (int x = 0; x < w; x++) {
				double n = 0;
				double amp = 0.55;
				int period = 4;
				double fx = x / (double) w;
				for (int o = 0; o < 4; o++) {
					n += amp * noise(fx * period, y / (double) h * period * 0.5, period, o);
					amp *= 0.5;
					period *= 2;
				}
				double a = clamp((n - 0.35) * 1.6) * low;
				f.setRGB(x, y, argb(a, 1, 1, 1));
			}
		}
		save(f, "fog", true, false);
	}

	static void grain() throws Exception {
		int s = 128;
		BufferedImage gr = img(s, s);
		Random rnd = new Random(7);
		for (int y = 0; y < s; y++) {
			for (int x = 0; x < s; x++) {
				double v = rnd.nextDouble();
				gr.setRGB(x, y, argb(Math.abs(v - 0.5) * 2 * 0.9, v > 0.5 ? 1 : 0, v > 0.5 ? 1 : 0, v > 0.5 ? 1 : 0));
			}
		}
		save(gr, "grain", false, false);
	}

	static void vignette() throws Exception {
		int w = 256;
		int h = 144;
		BufferedImage v = img(w, h);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				double dx = ((x + 0.5) / w * 2 - 1);
				double dy = ((y + 0.5) / h * 2 - 1);
				double r = Math.sqrt(dx * dx * 0.85 + dy * dy * 1.1);
				double a = clamp((r - 0.45) / 0.75);
				v.setRGB(x, y, argb(a * a * (3 - 2 * a), 0, 0, 0));
			}
		}
		save(v, "vignette", true, true);
	}
}
