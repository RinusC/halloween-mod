package dev.halloween.client;

import dev.halloween.net.HwNet;
import java.util.Random;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * Fixing the car with a repair kit: hold the button on the start of the line and follow it strictly to
 * the end, three lines in a row. Slipping off starts the line over and makes a noise the Shape hears;
 * letting go just starts the line over.
 */
public final class CurveScreen extends MinigameScreen {
	public static final int LINES = 3;
	/** How far off the line the cursor may stray, in GUI pixels. */
	public static final double TOLERANCE = 6;
	private static final int MARGIN = 26;

	private final Random random;
	/** Lines finished so far (kept if you walk away and come back). */
	private final int[] done;
	private double[] shape;
	private boolean tracing;
	/** Furthest point reached along the current line, as an x offset from its start. */
	private double reached;
	private double lastX;
	private double lastY;
	private int flashUntil;
	private int frame;

	public CurveScreen(BlockPos pos, long seed) {
		super(Component.translatable("halloween.minigame.curve"), HwNet.Minigame.CURVE, pos, seed);
		this.random = new Random(seed);
		this.done = this.saved(() -> new int[1]);
		this.newLine();
	}

	/** Two sine waves with random phase: a smooth, wavy line, different for each of the three. */
	private void newLine() {
		Random r = new Random(this.random.nextLong() ^ this.done[0] * 977L);
		this.shape = new double[]{
			18 + r.nextDouble() * 14, 1.2 + r.nextDouble() * 1.3, r.nextDouble() * Math.PI * 2,
			8 + r.nextDouble() * 10, 3 + r.nextDouble() * 2.5, r.nextDouble() * Math.PI * 2};
		this.tracing = false;
		this.reached = 0;
	}

	private int startX() {
		return this.left() + MARGIN;
	}

	private int length() {
		return W - MARGIN * 2;
	}

	/** Height of the line at offset {@code dx} from its start. */
	public double lineY(double dx) {
		double u = dx / this.length() * Math.PI;
		double[] s = this.shape;
		return this.top() + H / 2.0 + 4 + s[0] * Math.sin(s[1] * u + s[2]) + s[3] * Math.sin(s[4] * u + s[5]);
	}

	/** Screen point of the line at offset {@code dx} (for tests). */
	public double[] point(double dx) {
		return new double[]{this.startX() + dx, this.lineY(dx)};
	}

	public int linesDone() {
		return this.done[0];
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
		this.frame++;
		this.panel(g, Component.translatable("halloween.minigame.curve.hint"));
		boolean flash = this.frame < this.flashUntil;
		int x0 = this.startX();
		for (int dx = 0; dx <= this.length(); dx++) {
			int y = (int) Math.round(this.lineY(dx));
			// The band you must keep to, the line itself, and the part already traced.
			g.fill(x0 + dx, y - (int) TOLERANCE, x0 + dx + 1, y + (int) TOLERANCE + 1, flash ? 0x60B0302A : 0x30E8E2D6);
			g.fill(x0 + dx, y - 1, x0 + dx + 1, y + 2, dx <= this.reached && this.tracing ? 0xFF6FCF5A : 0xFFB8B2A6);
		}
		int sy = (int) Math.round(this.lineY(0));
		g.fill(x0 - 5, sy - 5, x0 + 5, sy + 5, 0xFF6FCF5A);
		int ey = (int) Math.round(this.lineY(this.length()));
		g.fill(x0 + this.length() - 4, ey - 4, x0 + this.length() + 4, ey + 4, 0xFFD8D2C4);
		g.centeredText(this.font, Component.literal(this.done[0] + " / " + LINES), this.left() + W / 2, this.top() + H - 14, DIM);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (Math.abs(event.x() - this.startX()) <= TOLERANCE + 2 && Math.abs(event.y() - this.lineY(0)) <= TOLERANCE + 2) {
			this.tracing = true;
			this.reached = 0;
			this.lastX = event.x();
			this.lastY = event.y();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (!this.tracing) {
			return super.mouseDragged(event, dx, dy);
		}
		// Check every pixel between the last cursor position and this one, so a fast flick can't skip a bend.
		double fromX = this.lastX;
		double fromY = this.lastY;
		int steps = (int) Math.max(1, Math.ceil(Math.max(Math.abs(event.x() - fromX), Math.abs(event.y() - fromY))));
		for (int i = 1; i <= steps; i++) {
			double t = i / (double) steps;
			double x = fromX + (event.x() - fromX) * t;
			double y = fromY + (event.y() - fromY) * t;
			double off = x - this.startX();
			if (off < -TOLERANCE || off > this.length() + TOLERANCE || Math.abs(y - this.lineY(Math.clamp(off, 0, this.length()))) > TOLERANCE) {
				this.slipOff();
				return true;
			}
			this.reached = Math.max(this.reached, off);
		}
		this.lastX = event.x();
		this.lastY = event.y();
		if (this.reached >= this.length()) {
			this.lineDone();
		}
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (this.tracing) {
			// Let go halfway: this line starts over (quietly).
			this.tracing = false;
			this.reached = 0;
			return true;
		}
		return super.mouseReleased(event);
	}

	private void slipOff() {
		this.tracing = false;
		this.reached = 0;
		this.flashUntil = this.frame + 20;
		sound(SoundEvents.ANVIL_LAND, 1.5f);
		this.slip();
	}

	private void lineDone() {
		this.done[0]++;
		sound(SoundEvents.ANVIL_USE, 1.3f);
		if (this.done[0] >= LINES) {
			this.finish();
		} else {
			this.newLine();
		}
	}
}
