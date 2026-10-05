package dev.halloween.client;

import dev.halloween.HwSounds;
import dev.halloween.net.HwNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The gun case's combination lock: a keypad of 0-9, C (clear) and OK. Four digits, then OK; the lock
 * buzzes on a wrong code. Mouse or the number keys, Backspace and Enter.
 */
public final class KeypadScreen extends MinigameScreen {
	public static final int DIGITS = 4;
	private static final int KEY = 26;
	private static final String[] LABELS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "OK"};

	private String entered = "";
	private int wrongFrames;
	private int frame;

	public KeypadScreen(BlockPos pos, long seed) {
		super(Component.translatable("halloween.minigame.keypad"), HwNet.Minigame.KEYPAD, pos, seed);
	}

	public String entered() {
		return this.entered;
	}

	private int keyX(int i) {
		return this.left() + W / 2 - (KEY * 3 + 8) / 2 + (i % 3) * (KEY + 4);
	}

	private int keyY(int i) {
		return this.top() + 58 + (i / 3) * (KEY + 2);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
		this.frame++;
		this.panel(g, Component.translatable("halloween.minigame.keypad.hint"));
		// The little display.
		int dx = this.left() + W / 2 - 40;
		int dy = this.top() + 32;
		boolean wrong = this.frame < this.wrongFrames;
		g.fill(dx, dy, dx + 80, dy + 20, 0xFF0C140C);
		StringBuilder shown = new StringBuilder();
		for (int i = 0; i < DIGITS; i++) {
			shown.append(i < this.entered.length() ? this.entered.charAt(i) : '_').append(i < DIGITS - 1 ? " " : "");
		}
		Component text = wrong ? Component.translatable("halloween.minigame.keypad.wrong") : Component.literal(shown.toString());
		g.centeredText(this.font, text, dx + 40, dy + 6, wrong ? 0xFFE05050 : 0xFF7FE07F);
		for (int i = 0; i < LABELS.length; i++) {
			int x = this.keyX(i);
			int y = this.keyY(i);
			boolean hover = mx >= x && mx < x + KEY && my >= y && my < y + KEY;
			g.fill(x, y, x + KEY, y + KEY, hover ? 0xFF6A665E : 0xFF3A3632);
			g.fill(x + 1, y + 1, x + KEY - 1, y + KEY - 1, hover ? 0xFF4A4640 : 0xFF2A2622);
			g.centeredText(this.font, Component.literal(LABELS[i]), x + KEY / 2, y + KEY / 2 - 4, TEXT);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int i = 0; i < LABELS.length; i++) {
			int x = this.keyX(i);
			int y = this.keyY(i);
			if (event.x() >= x && event.x() < x + KEY && event.y() >= y && event.y() < y + KEY) {
				this.press(LABELS[i]);
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) {
			this.press(String.valueOf(key - GLFW.GLFW_KEY_0));
			return true;
		}
		if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) {
			this.press(String.valueOf(key - GLFW.GLFW_KEY_KP_0));
			return true;
		}
		if (key == GLFW.GLFW_KEY_BACKSPACE) {
			this.press("C");
			return true;
		}
		if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
			this.press("OK");
			return true;
		}
		return super.keyPressed(event);
	}

	/** A key of the pad (also used by tests). */
	public void press(String label) {
		sound(HwSounds.CASE_KEY, 1f);
		switch (label) {
			case "C" -> this.entered = "";
			case "OK" -> {
				if (this.entered.length() == DIGITS) {
					ClientPlayNetworking.send(new HwNet.MinigameResult(this.kind, this.pos, HwNet.MinigameResult.CODE + Integer.parseInt(this.entered)));
				}
			}
			default -> {
				if (this.entered.length() < DIGITS) {
					this.entered += label;
				}
			}
		}
	}

	/** The server said no. */
	public void wrong() {
		this.wrongFrames = this.frame + 40;
		this.entered = "";
	}
}
