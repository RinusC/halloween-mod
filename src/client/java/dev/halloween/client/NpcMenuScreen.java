package dev.halloween.client;

import dev.halloween.game.Townsfolk;
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
 * Radial menu for talking to a townsperson: follow me (or stay here), hide, call the police, and a favour it asks (if any). Pick a
 * sector with the mouse (or keys 1-4); the talk itself then runs on the server.
 */
public final class NpcMenuScreen extends Screen {
	/** Three choices; a fourth when the townsperson has a favour to ask (game.Errands). */
	private final int sectors;
	private final dev.halloween.net.HwNet.Errands.Entry errand;
	private static final int INNER = 22;
	private static final int OUTER = 70;
	private final int npcId;
	private final boolean following;
	private int hovered = -1;

	public NpcMenuScreen(int npcId, Component name, boolean following) {
		super(name);
		this.npcId = npcId;
		this.following = following;
		this.errand = ClientErrands.forNpc(npcId);
		this.sectors = this.errand == null ? 3 : 4;
	}

	private Component label(int option) {
		return switch (option) {
			case 0 -> Component.translatable(this.following ? "halloween.menu.stay" : "halloween.menu.follow");
			case 1 -> Component.translatable("halloween.menu.hide");
			case 2 -> Component.translatable(this.enabled(option) ? "halloween.menu.call" : "halloween.menu.call_no_body");
			default -> ClientErrands.menuLabel(this.errand);
		};
	}

	/** Calling the police needs a body somebody has found and nobody reported yet; a favour, see ClientErrands. */
	private boolean enabled(int option) {
		if (option == Townsfolk.ERRAND) {
			return this.errand != null && ClientErrands.menuEnabled(this.errand);
		}
		return option != 2 || ClientState.match.reportable() > 0;
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
		return (int) ((angle + Math.PI / this.sectors) / (Math.PI * 2 / this.sectors)) % this.sectors;
	}

	/** Sector of the ring at this offset from the centre; -1 in the gaps between sectors, the hole and outside. */
	private int ringSector(int dx, int dy) {
		int d2 = dx * dx + dy * dy;
		if (d2 < INNER * INNER || d2 > OUTER * OUTER) {
			return -1;
		}
		double step = Math.PI * 2 / this.sectors;
		double angle = Math.atan2(dx, -dy);
		if (angle < 0) {
			angle += Math.PI * 2;
		}
		int s = (int) ((angle + step / 2) / step) % this.sectors;
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
		double step = Math.PI * 2 / this.sectors;
		// The ring is filled row by row, one rectangle per run of pixels in the same sector: a few hundred fills a frame.
		for (int dy = -OUTER; dy <= OUTER; dy++) {
			int runStart = 0;
			int runSector = -1;
			for (int dx = -OUTER; dx <= OUTER + 1; dx++) {
				int s = dx > OUTER ? -1 : ringSector(dx, dy);
				if (s != runSector) {
					if (runSector >= 0) {
						g.fill(cx + runStart, cy + dy, cx + dx, cy + dy + 1, !this.enabled(runSector) ? 0x70151515 : runSector == this.hovered ? 0xD0B0302A : 0xA0151515);
					}
					runSector = s;
					runStart = dx;
				}
			}
		}
		for (int s = 0; s < this.sectors; s++) {
			double mid = s * step;
			int lx = cx + (int) Math.round(Math.sin(mid) * (OUTER + 14));
			int ly = cy - (int) Math.round(Math.cos(mid) * (OUTER + 14));
			Component text = Component.literal((s + 1) + ". ").append(this.label(s));
			int color = !this.enabled(s) ? 0xFF6A665E : s == this.hovered ? 0xFFFFFFFF : s == Townsfolk.ERRAND ? 0xFFF0C860 : 0xFFD8D2C4;
			// Beside the ring the words run away from it, not across it.
			double side = Math.sin(mid);
			int tw = this.font.width(text);
			int x = side > 0.3 ? lx - 6 : side < -0.3 ? lx + 6 - tw : lx - tw / 2;
			// On a dark strip: legible over the goal list and the world alike.
			g.fill(x - 3, ly - 6, x + tw + 3, ly + 6, 0xB0141210);
			g.text(this.font, text, x, ly - 4, color, true);
		}
		g.centeredText(this.font, this.getTitle(), cx, cy - 4, 0xFFFFFFFF);
		g.centeredText(this.font, Component.translatable(this.sectors == 4 ? "halloween.menu.hint4" : "halloween.menu.hint"), cx, cy + OUTER + 30, 0xFF8A847A);
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
		if (key >= GLFW.GLFW_KEY_1 && key < GLFW.GLFW_KEY_1 + this.sectors) {
			this.choose(key - GLFW.GLFW_KEY_1);
			return true;
		}
		return super.keyPressed(event);
	}

	private void choose(int option) {
		if (!this.enabled(option)) {
			return;
		}
		ClientPlayNetworking.send(new HwNet.NpcTalk(this.npcId, Mth.clamp(option, 0, this.sectors - 1)));
		this.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
