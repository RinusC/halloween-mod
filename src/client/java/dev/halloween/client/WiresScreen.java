package dev.halloween.client;

import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/** Breaker box: drag each loose wire on the left to the terminal of the same colour on the right. */
public final class WiresScreen extends MinigameScreen {
	public static final int WIRES = 4;
	private static final int[] COLORS = {0xFFD23B2E, 0xFF2F6FD6, 0xFFE8C930, 0xFFC03CC8};
	private static final int GRAB = 10;

	/** Colour of each wire, top to bottom, on either side. */
	private final int[] leftColor = new int[WIRES];
	private final int[] rightColor = new int[WIRES];
	/** Which left wires are joined (kept if you walk away and come back). */
	private final boolean[] joined;
	private int dragging = -1;
	private double mouseX;
	private double mouseY;

	public WiresScreen(BlockPos pos, long seed) {
		super(Component.translatable("halloween.minigame.wires"), HwNet.Minigame.WIRES, pos, seed);
		Random random = new Random(seed);
		List<Integer> order = new ArrayList<>(List.of(0, 1, 2, 3));
		Collections.shuffle(order, random);
		for (int i = 0; i < WIRES; i++) {
			this.leftColor[i] = order.get(i);
		}
		Collections.shuffle(order, random);
		for (int i = 0; i < WIRES; i++) {
			this.rightColor[i] = order.get(i);
		}
		this.joined = this.saved(() -> new boolean[WIRES]);
	}

	private int rowY(int i) {
		return this.top() + 34 + i * 36;
	}

	private int leftX() {
		return this.left() + 34;
	}

	private int rightX() {
		return this.left() + W - 34;
	}

	/** Right terminal of the colour of left wire {@code i}. */
	private int partner(int i) {
		for (int j = 0; j < WIRES; j++) {
			if (this.rightColor[j] == this.leftColor[i]) {
				return j;
			}
		}
		return 0;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
		this.panel(g, Component.translatable("halloween.minigame.wires.hint"));
		for (int i = 0; i < WIRES; i++) {
			int y = this.rowY(i);
			int lc = COLORS[this.leftColor[i]];
			int rc = COLORS[this.rightColor[i]];
			// Wire stubs coming out of the box walls, and the terminals.
			g.fill(this.left() + 6, y - 3, this.leftX(), y + 3, lc);
			g.fill(this.leftX() - 1, y - 5, this.leftX() + 5, y + 5, 0xFF9A948A);
			g.fill(this.rightX(), y - 3, this.left() + W - 6, y + 3, rc);
			g.fill(this.rightX() - 5, y - 5, this.rightX() + 1, y + 5, 0xFF9A948A);
			if (this.joined[i]) {
				line(g, this.leftX() + 2, y, this.rightX() - 2, this.rowY(this.partner(i)), 5, lc);
			}
		}
		if (this.dragging >= 0) {
			line(g, this.leftX() + 2, this.rowY(this.dragging), this.mouseX, this.mouseY, 5, COLORS[this.leftColor[this.dragging]]);
		}
		int done = 0;
		for (boolean b : this.joined) {
			done += b ? 1 : 0;
		}
		g.centeredText(this.font, Component.literal(done + " / " + WIRES), this.left() + W / 2, this.top() + H - 14, DIM);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int i = 0; i < WIRES; i++) {
			if (!this.joined[i] && Math.abs(event.x() - this.leftX()) <= GRAB && Math.abs(event.y() - this.rowY(i)) <= GRAB) {
				this.dragging = i;
				this.mouseX = event.x();
				this.mouseY = event.y();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		this.mouseX = event.x();
		this.mouseY = event.y();
		return this.dragging >= 0 || super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (this.dragging < 0) {
			return super.mouseReleased(event);
		}
		int i = this.dragging;
		this.dragging = -1;
		int j = this.partner(i);
		if (Math.abs(event.x() - this.rightX()) <= GRAB && Math.abs(event.y() - this.rowY(j)) <= GRAB) {
			this.join(i);
		}
		return true;
	}

	/** Where wire {@code i} can be picked up, and the terminal it belongs to (for tests). */
	public double[] leftEnd(int i) {
		return new double[]{this.leftX(), this.rowY(i)};
	}

	public double[] rightEndFor(int i) {
		return new double[]{this.rightX(), this.rowY(this.partner(i))};
	}

	/** Connects left wire {@code i} to its terminal (also used by tests). */
	public void join(int i) {
		if (this.joined[i]) {
			return;
		}
		this.joined[i] = true;
		sound(SoundEvents.COPPER_BULB_TURN_ON, 1.4f);
		for (boolean b : this.joined) {
			if (!b) {
				return;
			}
		}
		this.finish();
	}
}
