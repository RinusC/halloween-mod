package dev.halloween.client;

import dev.halloween.net.HwNet;
import java.util.Random;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

/**
 * The Runt on your back: its hands at your throat. Drag each one down off your neck, past the line; let one go
 * too soon and it creeps back up. All of them off (five seconds or so, if you are quick) and you throw it off;
 * till then it rides you.
 */
public final class HandsScreen extends MinigameScreen {
	public static final int HANDS = 4;
	private static final int SKIN = 0xFF9AA08A;
	private static final int SKIN_DARK = 0xFF6C7258;
	private static final int NAIL = 0xFF1A1614;
	private static final int HW = 22;
	private static final int HH = 26;

	private final float[] x = new float[HANDS];
	private final float[] y = new float[HANDS];
	private final float[] homeY = new float[HANDS];
	private final boolean[] off = new boolean[HANDS];
	private final float[] fall = new float[HANDS];
	private int dragging = -1;
	private double grabDy;
	private int ticks;
	private final Random random;

	public HandsScreen(BlockPos pos, long seed) {
		super(Component.translatable("halloween.minigame.hands"), HwNet.Minigame.HANDS, pos, seed);
		this.random = new Random(seed);
	}

	@Override
	protected void init() {
		super.init();
		int cx = this.left() + W / 2;
		for (int i = 0; i < HANDS; i++) {
			// Round the neck: two on either side, at slightly different heights.
			this.x[i] = cx + (i - 1.5f) * 30 - HW / 2f + (this.random.nextFloat() - 0.5f) * 6;
			this.homeY[i] = this.top() + 48 + (i % 2) * 8 + this.random.nextInt(5);
			this.y[i] = this.homeY[i];
		}
	}

	private float line() {
		return this.top() + H * 0.76f;
	}

	@Override
	public void tick() {
		super.tick();
		this.ticks++;
		for (int i = 0; i < HANDS; i++) {
			if (this.off[i]) {
				this.fall[i] += 3;
				continue;
			}
			if (i != this.dragging && this.y[i] > this.homeY[i]) {
				// Let go of too soon: it creeps back up to the throat.
				this.y[i] = Math.max(this.homeY[i], this.y[i] - 2.2f);
			}
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
		this.panel(g, Component.translatable("halloween.minigame.hands.hint"));
		int cx = this.left() + W / 2;
		// Your neck and collar.
		g.fill(cx - 18, this.top() + 36, cx + 18, this.top() + 92, 0xFFC69472);
		g.fill(cx - 46, this.top() + 88, cx + 46, this.top() + 100, 0xFF3A4A6A);
		// The line to drag them past.
		int line = (int) this.line();
		for (int lx = this.left() + 16; lx < this.left() + W - 16; lx += 8) {
			g.fill(lx, line, lx + 4, line + 1, 0x80E8E2D6);
		}
		for (int i = 0; i < HANDS; i++) {
			if (this.off[i]) {
				if (this.fall[i] < 40) {
					this.hand(g, (int) this.x[i], (int) (this.y[i] + this.fall[i]), 0x60);
				}
				continue;
			}
			this.hand(g, (int) this.x[i], (int) this.y[i], 0xFF);
		}
	}

	/** A grey bony hand gripping, fingers up, black nails; {@code alpha} fades a thrown-off one. */
	private void hand(GuiGraphicsExtractor g, int hx, int hy, int alpha) {
		int a = alpha << 24;
		int skin = a | (SKIN & 0xFFFFFF);
		int dark = a | (SKIN_DARK & 0xFFFFFF);
		int nail = a | (NAIL & 0xFFFFFF);
		g.fill(hx + 2, hy + 12, hx + HW - 2, hy + HH, skin);
		g.fill(hx + 2, hy + HH - 3, hx + HW - 2, hy + HH, dark);
		for (int f = 0; f < 4; f++) {
			int fx = hx + 2 + f * 5;
			int top = hy + (f == 1 || f == 2 ? 0 : 3);
			g.fill(fx, top, fx + 4, hy + 13, skin);
			g.fill(fx, top, fx + 4, top + 2, nail);
			g.fill(fx + 3, top + 2, fx + 4, hy + 12, dark);
		}
		g.fill(hx - 3, hy + 14, hx + 3, hy + 18, skin);
		g.fill(hx - 3, hy + 14, hx - 1, hy + 16, nail);
	}

	private int handAt(double mx, double my) {
		for (int i = HANDS - 1; i >= 0; i--) {
			if (!this.off[i] && mx >= this.x[i] - 4 && mx <= this.x[i] + HW + 2 && my >= this.y[i] - 2 && my <= this.y[i] + HH + 2) {
				return i;
			}
		}
		return -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int i = this.handAt(event.x(), event.y());
		if (i >= 0) {
			this.dragging = i;
			this.grabDy = event.y() - this.y[i];
			sound(SoundEvents.SLIME_SQUISH_SMALL, 0.7f);
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (this.dragging < 0) {
			return super.mouseDragged(event, dx, dy);
		}
		// Only down (and back up): it doesn't let you pull it sideways.
		this.y[this.dragging] = (float) Math.max(this.homeY[this.dragging], event.y() - this.grabDy);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (this.dragging < 0) {
			return super.mouseReleased(event);
		}
		int i = this.dragging;
		this.dragging = -1;
		if (this.y[i] + HH / 2f >= this.line()) {
			this.throwOff(i);
		}
		return true;
	}

	/** Where hand {@code i} is now (for tests). */
	public double[] handCenter(int i) {
		return new double[]{this.x[i] + HW / 2.0, this.y[i] + HH / 2.0};
	}

	public double lineY() {
		return this.line();
	}

	/** Pulls hand {@code i} off for good (also used by tests). */
	public void throwOff(int i) {
		if (this.off[i]) {
			return;
		}
		this.off[i] = true;
		sound(SoundEvents.PLAYER_ATTACK_WEAK, 0.8f);
		for (boolean b : this.off) {
			if (!b) {
				return;
			}
		}
		this.finish();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		// Nowhere to walk away to: it is on your back.
		return false;
	}
}
