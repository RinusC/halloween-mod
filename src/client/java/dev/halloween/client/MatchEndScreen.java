package dev.halloween.client;

import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/**
 * The match is over: on the left the tally (who won, who got out and which way, who died, points, the night's
 * length and weather, the best moment), on the right the night's chronicle, minute by minute (scrolls with the
 * wheel). The game goes on behind it; Esc or the button closes it.
 */
public final class MatchEndScreen extends Screen {
	private static final int LINE = 11;
	private final HwNet.MatchEnd end;
	private final List<FormattedCharSequence> left = new ArrayList<>();
	private final List<FormattedCharSequence> right = new ArrayList<>();
	private int scroll;
	private int x0;
	private int y0;
	private int x1;
	private int y1;

	public MatchEndScreen(HwNet.MatchEnd end) {
		super(Component.translatable("halloween.hud.end_title"));
		this.end = end;
	}

	public static void open(HwNet.MatchEnd end) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.gui.setScreen(new MatchEndScreen(end)));
	}

	@Override
	protected void init() {
		int w = Math.min(this.width - 20, 520);
		int h = Math.min(this.height - 20, 300);
		this.x0 = (this.width - w) / 2;
		this.y0 = (this.height - h) / 2;
		this.x1 = this.x0 + w;
		this.y1 = this.y0 + h;
		int col = (w - 30) / 2;
		this.left.clear();
		this.right.clear();
		for (Component line : this.end.lines().subList(Math.min(1, this.end.lines().size()), this.end.lines().size())) {
			this.left.addAll(line.getString().isEmpty() ? List.of(FormattedCharSequence.EMPTY) : this.font.split(line, col));
		}
		for (Component line : this.end.chronicle()) {
			this.right.addAll(this.font.split(line, col));
		}
		this.addRenderableWidget(Button.builder(Component.translatable("halloween.end.close"), b -> this.onClose())
			.bounds(this.width / 2 - 50, this.y1 - 26, 100, 20).build());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		g.fill(0, 0, this.width, this.height, 0x60000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		g.fill(this.x0, this.y0, this.x1, this.y1, 0xE0100C0A);
		g.fill(this.x0, this.y0, this.x1, this.y0 + 2, this.end.shapeWon() ? 0xFFA01818 : 0xFF3A8A3A);
		int cx = this.width / 2;
		Component verdict = this.end.lines().isEmpty() ? this.title : this.end.lines().get(0);
		g.centeredText(this.font, verdict, cx, this.y0 + 10, 0xFFFFFFFF);
		int top = this.y0 + 30;
		int bottom = this.y1 - 32;
		int lx = this.x0 + 10;
		int rx = cx + 5;
		g.text(this.font, Component.translatable("halloween.end.summary"), lx, top, 0xFFB8A888);
		g.text(this.font, Component.translatable("halloween.end.chronicle"), rx, top, 0xFFB8A888);
		g.fill(cx, top, cx + 1, bottom, 0x40FFFFFF);
		int y = top + 14;
		for (FormattedCharSequence line : this.left) {
			if (y + LINE > bottom) {
				break;
			}
			g.text(this.font, line, lx, y, 0xFFE8E2D4);
			y += LINE;
		}
		if (this.right.isEmpty()) {
			g.text(this.font, Component.translatable("halloween.end.empty"), rx, top + 14, 0xFF6A665E);
		} else {
			int visible = Math.max(1, (bottom - top - 14) / LINE);
			this.scroll = Mth.clamp(this.scroll, 0, Math.max(0, this.right.size() - visible));
			g.enableScissor(rx, top + 14, this.x1 - 6, bottom);
			y = top + 14;
			for (int i = this.scroll; i < this.right.size() && i < this.scroll + visible; i++) {
				g.text(this.font, this.right.get(i), rx, y, 0xFFE8E2D4);
				y += LINE;
			}
			g.disableScissor();
			if (this.right.size() > visible) {
				int track = bottom - top - 14;
				int thumb = Math.max(10, track * visible / this.right.size());
				int at = top + 14 + (track - thumb) * this.scroll / Math.max(1, this.right.size() - visible);
				g.fill(this.x1 - 4, at, this.x1 - 2, at + thumb, 0x80FFFFFF);
			}
		}
		super.extractRenderState(g, mouseX, mouseY, partial);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		this.scroll -= (int) Math.signum(scrollY) * 3;
		return true;
	}
}
