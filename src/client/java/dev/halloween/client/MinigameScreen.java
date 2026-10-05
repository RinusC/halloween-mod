package dev.halloween.client;

import dev.halloween.net.HwNet;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;

/**
 * A hands-on job on a panel over the world (no blur, the world stays in view). Esc walks away; how far
 * you got is kept per box or car for the rest of the match. The server closes it when you're caught.
 */
public abstract class MinigameScreen extends Screen {
	protected static final int W = 260;
	protected static final int H = 176;
	protected static final int TEXT = 0xFFE8E2D6;
	protected static final int DIM = 0xFF8A847A;

	/** Progress per job (kind, place and layout), kept while the client runs. */
	private static final Map<String, Object> SAVED = new HashMap<>();

	protected final int kind;
	protected final BlockPos pos;
	protected final long seed;
	private boolean silent;

	protected MinigameScreen(Component title, int kind, BlockPos pos, long seed) {
		super(title);
		this.kind = kind;
		this.pos = pos;
		this.seed = seed;
	}

	public static MinigameScreen create(HwNet.Minigame open) {
		return open.kind() == HwNet.Minigame.WIRES ? new WiresScreen(open.pos(), open.seed()) : new CurveScreen(open.pos(), open.seed());
	}

	public boolean shows(HwNet.Minigame open) {
		return open.kind() == this.kind && open.pos().equals(this.pos) && open.seed() == this.seed;
	}

	private String key() {
		return this.kind + ":" + this.pos.asLong() + ":" + this.seed;
	}

	@SuppressWarnings("unchecked")
	protected <T> T saved(java.util.function.Supplier<T> fresh) {
		return (T) SAVED.computeIfAbsent(this.key(), k -> fresh.get());
	}

	public static void forgetAll() {
		SAVED.clear();
	}

	protected int left() {
		return this.width / 2 - W / 2;
	}

	protected int top() {
		return this.height / 2 - H / 2;
	}

	protected void finish() {
		SAVED.remove(this.key());
		ClientPlayNetworking.send(new HwNet.MinigameResult(this.kind, this.pos, HwNet.MinigameResult.DONE));
		this.silent = true;
		this.onClose();
	}

	protected void slip() {
		ClientPlayNetworking.send(new HwNet.MinigameResult(this.kind, this.pos, HwNet.MinigameResult.SLIP));
	}

	/** Closed by the server (caught, too far, match over): nothing to report back. */
	public void closeQuietly() {
		this.silent = true;
		this.onClose();
	}

	@Override
	public void onClose() {
		if (!this.silent) {
			ClientPlayNetworking.send(new HwNet.MinigameResult(this.kind, this.pos, HwNet.MinigameResult.CLOSED));
		}
		this.silent = true;
		super.onClose();
	}

	protected static void sound(SoundEvent sound, float pitch) {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		// No blur or dimming: you still see (and hear) what is around you.
	}

	/** The panel frame with its title and a hint line under it. */
	protected void panel(GuiGraphicsExtractor g, Component hint) {
		int x = this.left();
		int y = this.top();
		g.fill(x - 2, y - 2, x + W + 2, y + H + 2, 0xFF3A3530);
		g.fill(x, y, x + W, y + H, 0xF0181614);
		g.centeredText(this.font, this.getTitle(), x + W / 2, y + 6, TEXT);
		g.centeredText(this.font, hint, x + W / 2, y + 19, DIM);
	}

	/** A thick straight line made of small squares. */
	protected static void line(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int thickness, int color) {
		double len = Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0));
		int steps = Math.max(1, (int) (len / Math.max(1, thickness - 1)));
		int half = thickness / 2;
		for (int i = 0; i <= steps; i++) {
			double t = i / (double) steps;
			int x = Mth.floor(Mth.lerp(t, x0, x1));
			int y = Mth.floor(Mth.lerp(t, y0, y1));
			g.fill(x - half, y - half, x - half + thickness, y - half + thickness, color);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
