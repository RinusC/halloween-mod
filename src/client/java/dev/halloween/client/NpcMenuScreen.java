package dev.halloween.client;

import dev.halloween.net.HwNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * Radial menu for talking to a townsperson: follow me (or stay here), hide, call the police. Pick a
 * sector with the mouse (or keys 1-3); the talk itself then runs on the server.
 */
public final class NpcMenuScreen extends Screen {
	private static final int SECTORS = 3;
	private static final int INNER = 22;
	private static final int OUTER = 70;
	private final int npcId;
	private final boolean following;
	private int hovered = -1;

	public NpcMenuScreen(int npcId, Component name, boolean following) {
		super(name);
		this.npcId = npcId;
		this.following = following;
	}

	private Component label(int option) {
		return switch (option) {
			case 0 -> Component.translatable(this.following ? "halloween.menu.stay" : "halloween.menu.follow");
			case 1 -> Component.translatable("halloween.menu.hide");
			default -> Component.translatable("halloween.menu.call");
		};
	}

	/** Sector under the mouse: 0 at the top, then clockwise; -1 in the middle. */
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

	/** Sector of the ring at this offset from the centre; -1 in the gaps between sectors, the hole and outside. */
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
		// No blur or dimming: the world stays in view around the ring.
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		int cx = this.width / 2;
		int cy = this.height / 2;
		this.hovered = this.sectorAt(mouseX, mouseY);
		double step = Math.PI * 2 / SECTORS;
		// The ring is filled row by row, one rectangle per run of pixels in the same sector: a few hundred fills a frame.
		for (int dy = -OUTER; dy <= OUTER; dy++) {
			int runStart = 0;
			int runSector = -1;
			for (int dx = -OUTER; dx <= OUTER + 1; dx++) {
				int s = dx > OUTER ? -1 : ringSector(dx, dy);
				if (s != runSector) {
					if (runSector >= 0) {
						g.fill(cx + runStart, cy + dy, cx + dx, cy + dy + 1, runSector == this.hovered ? 0xD0B0302A : 0xA0151515);
					}
					runSector = s;
					runStart = dx;
				}
			}
		}
		for (int s = 0; s < SECTORS; s++) {
			double mid = s * step;
			int lx = cx + (int) Math.round(Math.sin(mid) * (OUTER + 14));
			int ly = cy - (int) Math.round(Math.cos(mid) * (OUTER + 14));
			Component text = Component.literal((s + 1) + ". ").append(this.label(s));
			g.centeredText(this.font, text, lx, ly - 4, s == this.hovered ? 0xFFFFFFFF : 0xFFD8D2C4);
		}
		g.centeredText(this.font, this.getTitle(), cx, cy - 4, 0xFFFFFFFF);
		g.centeredText(this.font, Component.translatable("halloween.menu.hint"), cx, cy + OUTER + 30, 0xFF8A847A);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int s = this.sectorAt(event.x(), event.y());
		if (s >= 0) {
			this.choose(s);
			return true;
		}
		this.onClose();
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

	private void choose(int option) {
		ClientPlayNetworking.send(new HwNet.NpcTalk(this.npcId, Mth.clamp(option, 0, SECTORS - 1)));
		this.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
