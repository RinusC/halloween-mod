package dev.halloween.client;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.game.FocusPoints;
import dev.halloween.net.HwNet;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Match HUD, laid out like the original: Bloodthirst ring bottom-left, weapon and ability diamonds
 * bottom-right, XP feed on the left, objectives top-left, world markers projected on screen.
 * Civilians get health and their side of the grab check.
 */
public final class HwHud {
	private static final int TEXT = 0xFFE8E2D6;
	private static final int DIM = 0xFF8A847A;
	private static final int BLOOD = 0xFFB3121B;
	private static final int RED_FILL = 0xE0701015;
	private static final int DARK_FILL = 0xC0181818;
	private static final int GREEN = 0xFF3FE0A0;
	private static final int YELLOW = 0xFFFFD24A;
	private static final int ORANGE = 0xFFFF9A2E;

	private HwHud() {
	}

	public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		Role role = ClientState.myRole();
		int w = g.guiWidth();
		int h = g.guiHeight();
		float partial = delta.getGameTimeDeltaPartialTick(false);
		if (role == Role.SHAPE) {
			HwNet.ShapeHud hud = ClientState.hud;
			if (hud == null) {
				return;
			}
			beams(g, hud, w, h);
			impulseSpots(g, mc, w, h, partial);
			if (hud.sense()) {
				victimMarkers(g, mc, w, h, partial);
			}
			reticle(g, mc, hud, w, h, partial);
			if (ClientState.qte.active() && ClientState.qte.forShape()) {
				shapeGrab(g, mc, w, h, partial);
			}
			objectives(g, mc.font, hud);
			feed(g, mc.font, h);
			bloodthirstRing(g, mc.font, hud, h);
			abilities(g, mc, hud, w, h);
		} else if (role == Role.CIVILIAN) {
			if (mc.player.isSpectator()) {
				g.centeredText(mc.font, Component.translatable("halloween.hud.dead"), w / 2, h - 40, BLOOD);
				return;
			}
			healthBar(g, mc, w, h);
			if (ClientState.qte.active() && !ClientState.qte.forShape()) {
				victimQte(g, mc, w, h, partial);
			}
		}
	}

	// ---------------------------------------------------------------- world markers

	/** Shape Jump: tall light beams rising over the victims. */
	private static void beams(GuiGraphicsExtractor g, HwNet.ShapeHud hud, int w, int h) {
		for (Vec3 base : hud.beams()) {
			Projection.Point bottom = Projection.toScreen(base, w, h);
			Projection.Point top = Projection.toScreen(base.add(0, 80, 0), w, h);
			if (bottom == null || top == null) {
				continue;
			}
			int half = (int) Mth.clamp(60 / Math.max(4, bottom.distance()), 2, 10);
			int x = (int) bottom.x();
			int y0 = (int) Math.max(-10, top.y());
			int y1 = (int) Math.min(h + 10, bottom.y());
			if (y1 <= y0) {
				continue;
			}
			g.fillGradient(x - half, y0, x + half, y1, 0x00FFD24A, 0xA0FFD24A);
			g.fillGradient(x - half / 3 - 1, y0, x + half / 3 + 1, y1, 0x00FFF2C0, 0xD0FFF2C0);
		}
	}

	/** Detection Impulse: indoor victims only give away a fuzzy spot on their building. */
	private static void impulseSpots(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		if (!ClientState.impulseActive()) {
			return;
		}
		float age = mc.level.getGameTime() - ClientState.impulseStart + partial;
		float life = Math.max(1, ClientState.impulseUntil - ClientState.impulseStart);
		int alpha = (int) (255 * Mth.clamp(1 - age / life, 0, 1));
		for (Vec3 spot : ClientState.impulseSpots) {
			Projection.Point p = Projection.toScreen(spot.add(0, 1.5, 0), w, h);
			if (p == null) {
				continue;
			}
			int r = (int) Mth.clamp(240 / Math.max(6, p.distance()), 6, 30);
			int color = (alpha << 24) | (ORANGE & 0xFFFFFF);
			ring(g, p.x(), p.y(), r + (age % 20) * 0.6f, 2, 0f, 1f, color);
			ring(g, p.x(), p.y(), r * 0.5f, 2, 0f, 1f, color);
		}
	}

	/** Killer Sense: focus points on each victim, stalk level pips and name above the head. */
	private static void victimMarkers(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		for (HwNet.VictimInfo info : ClientState.VICTIMS.values()) {
			Entity entity = mc.level.getEntity(info.entityId());
			if (!(entity instanceof LivingEntity living) || !ClientState.VISIBLE.contains(info.entityId())) {
				continue;
			}
			if (info.stalk() < 3f) {
				for (int i = 0; i < FocusPoints.COUNT; i++) {
					Projection.Point p = Projection.toScreen(FocusPoints.get(living, i, partial), w, h);
					if (p != null) {
						focusIcon(g, i, p.x(), p.y());
					}
				}
			}
			Vec3 head = living.getPosition(partial).add(0, living.getBbHeight() + 0.45, 0);
			Projection.Point p = Projection.toScreen(head, w, h);
			if (p == null) {
				continue;
			}
			int x = (int) p.x();
			int y = (int) p.y();
			g.centeredText(mc.font, info.name(), x, y - 14, info.marked() ? 0xFFE0261F : 0xFFDDDDDD);
			int level = (int) info.stalk();
			float partialLevel = info.stalk() - level;
			for (int i = 0; i < 3; i++) {
				int cx = x - 9 + i * 9;
				int fillColor = i < level ? (info.marked() ? 0xFFE0261F : 0xFFF2F2F2) : 0x80303030;
				diamond(g, cx, y - 2, 3, fillColor);
				if (i == level && partialLevel > 0) {
					diamond(g, cx, y - 2, Math.max(1, (int) (3 * partialLevel)), 0xFFB0B0B0);
				}
			}
		}
	}

	private static void focusIcon(GuiGraphicsExtractor g, int index, float fx, float fy) {
		int x = (int) fx;
		int y = (int) fy;
		int c = 0xF0F4F1E8;
		switch (index) {
			case 0 -> {
				diamond(g, x, y, 4, c);
				diamond(g, x, y, 2, 0xF0202020);
			}
			case 1 -> ring(g, x, y, 3.5f, 1, 0f, 1f, 0xF09CC8FF);
			case 2 -> g.fill(x - 2, y - 2, x + 2, y + 2, 0xF0101010);
			case 3 -> ring(g, x, y, 2.5f, 1, 0f, 1f, c);
			default -> {
				g.fill(x - 2, y - 2, x, y, c);
				g.fill(x, y, x + 2, y + 2, c);
				g.fill(x - 2, y + 2, x, y + 4, c);
			}
		}
	}

	/** Centre reticle: a small dot normally, a ring in Killer Sense that turns green on a focus point. */
	private static void reticle(GuiGraphicsExtractor g, Minecraft mc, HwNet.ShapeHud hud, int w, int h, float partial) {
		// Everything the Shape does follows the ray through the screen centre, so the reticle lives there.
		int cx = w / 2;
		int cy = h / 2;
		if (!hud.sense()) {
			g.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xB0FFFFFF);
			return;
		}
		boolean onPoint = false;
		Vec3 eye = CameraAim.origin();
		Vec3 look = CameraAim.direction();
		float limit = HwConfig.get().reticleFocusAngleDeg;
		for (HwNet.VictimInfo info : ClientState.VICTIMS.values()) {
			if (mc.level.getEntity(info.entityId()) instanceof LivingEntity living && info.stalk() < 3f && ClientState.VISIBLE.contains(info.entityId())) {
				for (int i = 0; i < FocusPoints.COUNT && !onPoint; i++) {
					Vec3 dir = FocusPoints.get(living, i, partial).subtract(eye).normalize();
					onPoint = Math.toDegrees(Math.acos(Mth.clamp(look.dot(dir), -1, 1))) <= limit;
				}
			}
		}
		ring(g, cx, cy, 8, 2, 0f, 1f, onPoint ? GREEN : 0xC0E8E8E8);
		g.centeredText(mc.font, Component.translatable("halloween.hud.focus", mc.options.keyUse.getTranslatedKeyMessage()), cx, cy + 16, 0xA0E8E2D6);
	}

	// ---------------------------------------------------------------- grab

	/** The Shape's side of a grab: a ring with a rotating needle and a target arc. */
	private static void shapeGrab(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		HwNet.Qte qte = ClientState.qte;
		int cx = w / 2;
		int cy = h / 2 + 40;
		float r = 22;
		ring(g, cx, cy, r, 5, 0f, 1f, 0xC0303030);
		ring(g, cx, cy, r, 5, qte.zoneStart(), qte.zoneStart() + qte.zoneWidth(), 0xFFC9C25A);
		double elapsed = Math.max(0, mc.level.getGameTime() - qte.roundStart() + partial);
		float needle = HwNet.needle(elapsed, qte.period());
		double a = needle * Math.PI * 2 - Math.PI / 2;
		for (float t = 0; t <= r + 4; t += 1f) {
			int x = (int) (cx + Math.cos(a) * t);
			int y = (int) (cy + Math.sin(a) * t);
			g.fill(x, y, x + 2, y + 2, 0xFFFFFFFF);
		}
		g.centeredText(mc.font, mc.options.keyAttack.getTranslatedKeyMessage(), cx, cy - 4, TEXT);
		pips(g, cx, cy + (int) r + 10, qte.points(), qte.needed(), BLOOD);
		pips(g, cx, cy + (int) r + 18, qte.enemyPoints(), qte.enemyNeeded(), 0xFFF2F2F2);
		int y = cy + (int) r + 26;
		if (qte.window()) {
			g.centeredText(mc.font, Component.translatable("halloween.hud.grab.window"), cx, y, TEXT);
			y += 10;
		}
		if (qte.bloody()) {
			g.centeredText(mc.font, Component.translatable("halloween.hud.grab.bloody"), cx, y, BLOOD);
		}
	}

	private static void victimQte(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		HwNet.Qte qte = ClientState.qte;
		int barW = 180;
		int x = w / 2 - barW / 2;
		int y = (int) (h * 0.62);
		g.fill(x - 6, y - 22, x + barW + 6, y + 34, 0xB0000000);
		g.centeredText(mc.font, Component.translatable("halloween.hud.qte", mc.options.keyJump.getTranslatedKeyMessage()), w / 2, y - 16, TEXT);

		g.fill(x, y, x + barW, y + 10, 0xFF1A1A1A);
		int z0 = x + (int) (barW * qte.zoneStart());
		int z1 = x + (int) (barW * (qte.zoneStart() + qte.zoneWidth()));
		g.fill(z0, y, z1, y + 10, 0xFF3FA34D);

		double elapsed = mc.level.getGameTime() - qte.roundStart() + partial;
		float cursor = HwNet.qteCursor(Math.max(0, elapsed), qte.period());
		int cx = x + (int) (barW * cursor);
		g.fill(cx - 1, y - 3, cx + 1, y + 13, 0xFFFFFFFF);

		float timeLeft = 1f - (float) Mth.clamp(elapsed / qte.roundTicks(), 0, 1);
		bar(g, x, y + 13, barW, 2, timeLeft, 0xFF8A847A);

		pips(g, w / 2, y + 20, qte.points(), qte.needed(), 0xFF3FA34D);
		pips(g, w / 2, y + 28, qte.enemyPoints(), qte.enemyNeeded(), BLOOD);
	}

	private static void pips(GuiGraphicsExtractor g, int cx, int y, int filled, int total, int color) {
		for (int i = 0; i < total; i++) {
			int px = cx - total * 6 + i * 12 + 2;
			g.fill(px, y, px + 8, y + 4, i < filled ? color : 0xFF3A3A3A);
		}
	}

	// ---------------------------------------------------------------- panels

	private static void objectives(GuiGraphicsExtractor g, Font font, HwNet.ShapeHud hud) {
		int y = 10;
		boolean first = true;
		for (Component line : hud.objectives()) {
			diamond(g, 14, y + 3, 3, first ? 0xFFD8D2C4 : 0xFF8FA0C8);
			g.text(font, line, 22, y, first ? TEXT : 0xFFB8C2DA, true);
			y += 12;
			first = false;
		}
	}

	/** XP feed on the left: "+075 XP" and the reasons, fading out after a few seconds. */
	private static void feed(GuiGraphicsExtractor g, Font font, int h) {
		long now = System.currentTimeMillis();
		int y = h / 2 - 50;
		for (ClientState.FeedEntry entry : ClientState.FEED) {
			long age = now - entry.time();
			if (age > 5000) {
				continue;
			}
			int alpha = (int) (255 * Mth.clamp((5000 - age) / 800f, 0, 1));
			if (alpha < 8) {
				continue;
			}
			int color = (alpha << 24) | (TEXT & 0xFFFFFF);
			g.pose().pushMatrix();
			g.pose().translate(10, y);
			g.pose().scale(1.6f, 1.6f);
			g.text(font, String.format("+%03d", entry.points()), 0, 0, color, true);
			g.pose().popMatrix();
			g.text(font, "XP", 10 + (int) (font.width("+000") * 1.6f) + 3, y + 5, color, true);
			y += 16;
			for (Component line : entry.lines()) {
				g.text(font, line, 10, y, color, true);
				y += 10;
			}
			y += 6;
		}
	}

	/** Three-segment Bloodthirst ring with blood drops in the middle, like the original. */
	private static void bloodthirstRing(GuiGraphicsExtractor g, Font font, HwNet.ShapeHud hud, int h) {
		int cx = 34;
		int cy = h - 34;
		float r = 20;
		float value = hud.bloodthirst();
		int level = Math.min(3, (int) (value / 100f) + 1);
		for (int i = 0; i < 3; i++) {
			float from = i / 3f + 0.015f;
			float to = (i + 1) / 3f - 0.015f;
			ring(g, cx, cy, r, 5, from, to, 0xC0303030);
			float segment = Mth.clamp((value - i * 100f) / 100f, 0, 1);
			if (segment >= 1f) {
				ring(g, cx, cy, r, 5, from, to, BLOOD);
			} else if (segment > 0f) {
				ring(g, cx, cy, r, 5, from, from + (to - from) * segment, 0xFFE8E2D6);
			}
		}
		for (int i = 0; i < level; i++) {
			drop(g, cx - (level - 1) * 4 + i * 8, cy - 4, level == 3 ? BLOOD : 0xFFE8E2D6);
		}
	}

	/** Weapon, ability diamonds and small actions in the bottom-right corner. */
	private static void abilities(GuiGraphicsExtractor g, Minecraft mc, HwNet.ShapeHud hud, int w, int h) {
		Font font = mc.font;
		int baseY = h - 28;

		// Killer Sense diamond.
		int senseX = w - 26;
		boolean senseTired = hud.fatigue() > 0.6f;
		abilityDiamond(g, font, senseX, baseY, "◉", HalloweenClient.KEY_SENSE,
			hud.sense() ? 0xE0D8D2C4 : DARK_FILL, 1f - hud.fatigue(), senseTired ? 0xFFC77B2A : 0xFF606060, hud.sense() ? 0xFF101010 : TEXT);

		// Shape Jump diamond: red while unavailable.
		int jumpX = w - 62;
		boolean jumpReady = hud.jumpBlock() == 0;
		boolean inShadow = hud.shadowTicks() > 0;
		float jumpProgress = !inShadow && hud.jumpBlock() == 3 ? 1f - (float) hud.jumpCooldown() / Math.max(1, hud.jumpCooldownMax()) : 1f;
		abilityDiamond(g, font, jumpX, baseY, "»", HalloweenClient.KEY_SHAPE_JUMP,
			inShadow ? (jumpReady ? 0xE07A5CC8 : 0xE0602A6A) : jumpReady ? DARK_FILL : RED_FILL, jumpProgress, 0xFF606060, TEXT);
		if (inShadow && hud.jumpBlock() != 0) {
			// Leaving the shadow is refused here: say why.
			g.centeredText(font, Component.translatable(hud.jumpBlock() == 1 ? "halloween.hud.jump.light" : "halloween.hud.jump.seen"), jumpX, baseY - 26, 0xFFE07070);
		} else if (!inShadow && hud.jumpBlock() == 3) {
			g.centeredText(font, seconds(hud.jumpCooldown()), jumpX, baseY - 26, DIM);
		} else if (!inShadow && (hud.jumpBlock() == 1 || hud.jumpBlock() == 2)) {
			g.centeredText(font, Component.translatable(hud.jumpBlock() == 1 ? "halloween.hud.jump.light" : "halloween.hud.jump.seen"), jumpX, baseY - 26, DIM);
		}

		// Detection Impulse diamond, above the other two.
		int impulseX = w - 44;
		int impulseY = baseY - 36;
		boolean impulseReady = hud.impulseCooldown() == 0;
		float impulseProgress = impulseReady ? 1f : 1f - (float) hud.impulseCooldown() / Math.max(1, hud.impulseCooldownMax());
		abilityDiamond(g, font, impulseX, impulseY, "◎", HalloweenClient.KEY_IMPULSE,
			impulseReady ? DARK_FILL : RED_FILL, impulseProgress, 0xFF606060, TEXT);

		// Weapon.
		int weaponX = w - 112;
		ItemStack held = mc.player.getMainHandItem();
		if (!held.isEmpty()) {
			g.item(held, weaponX, baseY - 8);
		}
		g.centeredText(font, mc.options.keyAttack.getTranslatedKeyMessage(), weaponX + 8, baseY + 12, DIM);

		// Small actions: push, grab, dash, right-aligned to the left of the weapon.
		int sx = weaponX - 10;
		int sy = baseY - 30;
		smallAction(g, font, sx, sy, Component.translatable("halloween.hud.push"), HalloweenClient.KEY_PUSH.getTranslatedKeyMessage(), hud.pushCooldown());
		smallAction(g, font, sx, sy + 11, Component.translatable("halloween.hud.grab"), mc.options.keyUse.getTranslatedKeyMessage(), hud.grabCooldown());
		smallAction(g, font, sx, sy + 22, Component.translatable("halloween.hud.dash"), mc.options.keySprint.getTranslatedKeyMessage(),
			hud.dashTicks() > 0 ? 0 : hud.dashCooldown());
	}

	private static void abilityDiamond(GuiGraphicsExtractor g, Font font, int cx, int cy, String glyph, KeyMapping key,
		int fill, float progress, int progressColor, int glyphColor) {
		diamond(g, cx, cy, 13, 0xFF0C0C0C);
		diamond(g, cx, cy, 12, fill);
		if (progress < 1f) {
			int size = (int) (11 * Mth.clamp(progress, 0, 1));
			if (size > 0) {
				diamond(g, cx, cy, size, progressColor);
			}
		}
		g.centeredText(font, glyph, cx, cy - 4, glyphColor);
		Component label = key.getTranslatedKeyMessage();
		int lw = font.width(label) + 6;
		g.fill(cx - lw / 2, cy + 15, cx + lw / 2, cy + 25, 0xE0E8E2D6);
		g.centeredText(font, label, cx, cy + 16, 0xFF101010);
	}

	private static void smallAction(GuiGraphicsExtractor g, Font font, int x, int y, Component name, Component key, int cooldown) {
		int color = cooldown > 0 ? DIM : TEXT;
		Component text = cooldown > 0
			? Component.translatable("halloween.hud.small.cooldown", name, seconds(cooldown))
			: Component.translatable("halloween.hud.small.ready", name, key);
		g.text(font, text, x - font.width(text), y, color, true);
	}

	// ---------------------------------------------------------------- Civilian

	private static void healthBar(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		int x = w - 110;
		int y = h - 22;
		g.fill(x - 4, y - 14, x + 106, h - 6, 0x99000000);
		g.text(mc.font, Component.translatable("halloween.hud.health"), x, y - 10, TEXT, true);
		float frac = mc.player.getHealth() / Math.max(1f, mc.player.getMaxHealth());
		bar(g, x, y, 100, 6, frac, frac < 0.35f ? BLOOD : 0xFFD8D2C4);
	}

	// ---------------------------------------------------------------- drawing helpers

	private static void bar(GuiGraphicsExtractor g, int x, int y, int width, int height, float frac, int color) {
		g.fill(x, y, x + width, y + height, 0xFF1A1A1A);
		g.fill(x, y, x + (int) (width * Mth.clamp(frac, 0, 1)), y + height, color);
	}

	/** Filled diamond (square rotated 45 degrees) with half-diagonal {@code size}. */
	private static void diamond(GuiGraphicsExtractor g, int cx, int cy, int size, int color) {
		for (int dy = -size; dy <= size; dy++) {
			int half = size - Math.abs(dy);
			g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
		}
	}

	/** Arc of a ring from {@code from} to {@code to} (fractions of a turn, clockwise from the top). */
	private static void ring(GuiGraphicsExtractor g, float cx, float cy, float r, int thickness, float from, float to, int color) {
		int steps = Math.max(8, (int) (r * 7 * Math.abs(to - from)));
		int half = Math.max(1, thickness / 2);
		for (int i = 0; i <= steps; i++) {
			double a = (from + (to - from) * i / (double) steps) * Math.PI * 2 - Math.PI / 2;
			int x = (int) (cx + Math.cos(a) * r);
			int y = (int) (cy + Math.sin(a) * r);
			g.fill(x - half, y - half, x + half + (thickness % 2), y + half + (thickness % 2), color);
		}
	}

	private static final String[] DROP = {
		"..#..",
		"..#..",
		".###.",
		"#####",
		"#####",
		".###.",
	};

	private static void drop(GuiGraphicsExtractor g, int cx, int top, int color) {
		for (int row = 0; row < DROP.length; row++) {
			for (int col = 0; col < 5; col++) {
				if (DROP[row].charAt(col) == '#') {
					g.fill(cx - 2 + col, top + row, cx - 1 + col, top + row + 1, color);
				}
			}
		}
	}

	private static String seconds(int ticks) {
		return String.valueOf((ticks + 19) / 20);
	}
}
