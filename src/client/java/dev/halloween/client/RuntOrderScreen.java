package dev.halloween.client;

import dev.halloween.net.HwNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Michael's order wheel for his Runt (when a bot plays it), held open with its key: search there, go for that one,
 * play tricks there, come to heel. "There" and "that one" are what he was looking at when he opened it. Pick a
 * sector with the mouse, keys 1-4, or let go of the key over one.
 */
public final class RuntOrderScreen extends Screen {
	private static final int SECTORS = 4;
	private static final int INNER = 22;
	private static final int OUTER = 70;
	private static final int[] KINDS = {HwNet.RuntOrder.SEARCH, HwNet.RuntOrder.ATTACK, HwNet.RuntOrder.TRICKS, HwNet.RuntOrder.HEEL};
	private final int targetId;
	private final BlockPos spot;
	private int hovered = -1;

	public RuntOrderScreen(int targetId, BlockPos spot) {
		super(Component.translatable("halloween.menu.runt"));
		this.targetId = targetId;
		this.spot = spot;
	}

	private Component label(int sector) {
		return Component.translatable("halloween.menu.runt." + KINDS[sector]);
	}

	/** Searching and tricks need a place, going for someone a target or a place. */
	private boolean enabled(int sector) {
		return KINDS[sector] == HwNet.RuntOrder.HEEL || this.spot != null || KINDS[sector] == HwNet.RuntOrder.ATTACK && this.targetId >= 0;
	}

	private int sectorAt(double mx, double my) {
		double dx = mx - this.width / 2.0;
		double dy = my - this.height / 2.0;
		if (dx * dx + dy * dy < INNER * INNER) {
			return -1;
		}
		double angle = Math.atan2(dx, -dy);
		if (angle < 0) {
			angle += Math.PI * 2;
		}
		return (int) ((angle + Math.PI / SECTORS) / (Math.PI * 2 / SECTORS)) % SECTORS;
	}

	private static int ringSector(int dx, int dy) {
		int d2 = dx * dx + dy * dy;
		if (d2 < INNER * INNER || d2 > OUTER * OUTER) {
			return -1;
		}
		double step = Math.PI * 2 / SECTORS;
		double angle = Math.atan2(dx, -dy);
		if (angle < 0) {
			angle += Math.PI * 2;
		}
		int s = (int) ((angle + step / 2) / step) % SECTORS;
		double off = Math.abs(Math.IEEEremainder(angle - s * step, Math.PI * 2));
		return off > step / 2 - 0.05 ? -1 : s;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		// The world stays in view around the ring.
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		int cx = this.width / 2;
		int cy = this.height / 2;
		this.hovered = this.sectorAt(mouseX, mouseY);
		double step = Math.PI * 2 / SECTORS;
		for (int dy = -OUTER; dy <= OUTER; dy++) {
			int runStart = 0;
			int runSector = -1;
			for (int dx = -OUTER; dx <= OUTER + 1; dx++) {
				int s = dx > OUTER ? -1 : ringSector(dx, dy);
				if (s != runSector) {
					if (runSector >= 0) {
						g.fill(cx + runStart, cy + dy, cx + dx, cy + dy + 1,
							!this.enabled(runSector) ? 0x70151515 : runSector == this.hovered ? 0xD06A3FA8 : 0xA0151515);
					}
					runSector = s;
					runStart = dx;
				}
			}
		}
		for (int s = 0; s < SECTORS; s++) {
			double mid = s * step;
			int lx = cx + (int) Math.round(Math.sin(mid) * (OUTER + 16));
			int ly = cy - (int) Math.round(Math.cos(mid) * (OUTER + 16));
			Component text = Component.literal((s + 1) + ". ").append(this.label(s));
			g.centeredText(this.font, text, lx, ly - 4, !this.enabled(s) ? 0xFF6A665E : s == this.hovered ? 0xFFFFFFFF : 0xFFD8D2C4);
		}
		g.centeredText(this.font, this.getTitle(), cx, cy - 4, 0xFFB98CFF);
		g.centeredText(this.font, Component.translatable("halloween.menu.runt.hint"), cx, cy + OUTER + 30, 0xFF8A847A);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		// Let go of the key over a sector: that order (in the middle: nothing, the wheel stays for a click).
		if (HalloweenClient.KEY_RUNT_ORDER.matches(event) && this.hovered >= 0) {
			this.choose(this.hovered);
			return true;
		}
		return super.keyReleased(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int s = this.sectorAt(event.x(), event.y());
		if (s >= 0) {
			this.choose(s);
		} else {
			this.onClose();
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key >= GLFW.GLFW_KEY_1 && key < GLFW.GLFW_KEY_1 + SECTORS) {
			this.choose(key - GLFW.GLFW_KEY_1);
			return true;
		}
		return super.keyPressed(event);
	}

	/** Sends the order in that sector (also used by tests). */
	public void choose(int sector) {
		if (!this.enabled(sector)) {
			return;
		}
		int kind = KINDS[sector];
		ClientPlayNetworking.send(new HwNet.RuntOrder(kind, kind == HwNet.RuntOrder.ATTACK ? this.targetId : -1, this.spot == null ? BlockPos.ZERO : this.spot));
		this.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
