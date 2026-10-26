package dev.halloween.client;

import dev.halloween.game.Errands;
import dev.halloween.game.HwItems;
import dev.halloween.game.Inventory;
import dev.halloween.net.HwNet;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Townsfolk's favours as a survivor sees them (game.Errands): a small mark over whoever has one to ask, the talk menu's
 * fourth choice, and, for the one taken on, where to go (a mark there, an arrow at the edge) and a line on the goal list.
 */
public final class ClientErrands {
	/** Asking townsfolk are marked only this close (no clutter from across town). */
	private static final double OFFER_RANGE = 40;
	private static final int GOLD = 0xFFF0C860;

	private static List<HwNet.Errands.Entry> list = List.of();

	private ClientErrands() {
	}

	static void register() {
		ClientPlayNetworking.registerGlobalReceiver(HwNet.Errands.TYPE, (payload, ctx) -> list = payload.list());
	}

	public static void reset() {
		list = List.of();
	}

	private static int me() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? -1 : mc.player.getId();
	}

	/** The favour this townsperson has for me: one nobody took yet, or the one I took on. */
	static HwNet.Errands.Entry forNpc(int npcId) {
		for (HwNet.Errands.Entry e : list) {
			if (e.npc() == npcId && (e.taker() < 0 || e.taker() == me())) {
				return e;
			}
		}
		return null;
	}

	/** The broken radio of a favour I took on (held use opens its wires). */
	public static boolean isRadio(BlockPos pos) {
		for (HwNet.Errands.Entry e : list) {
			if (e.kind() == Errands.RADIO && e.taker() == me() && BlockPos.containing(e.target()).equals(pos)) {
				return true;
			}
		}
		return false;
	}

	/** The dog of a favour, still lost (use to take it home). */
	public static boolean isLostDog(Entity entity) {
		for (HwNet.Errands.Entry e : list) {
			if (e.kind() == Errands.DOG && !e.found() && e.targetId() == entity.getId()) {
				return true;
			}
		}
		return false;
	}

	/** Medicine in the pockets, to hand over. */
	static boolean hasMedicine(Minecraft mc) {
		var inv = mc.player.getInventory();
		for (int i = 0; i < Inventory.SLOTS; i++) {
			if (HwItems.is(inv.getItem(i), HwItems.Kind.MED_KIT) || HwItems.is(inv.getItem(i), HwItems.Kind.HEALING_SPRAY)) {
				return true;
			}
		}
		return false;
	}

	/** The talk menu's fourth choice for this favour, and whether it can be picked now. */
	static Component menuLabel(HwNet.Errands.Entry e) {
		if (e.taker() < 0) {
			return Component.translatable("halloween.menu.errand." + e.kind());
		}
		return Component.translatable(e.kind() == Errands.MEDICINE ? "halloween.menu.errand.give" : "halloween.menu.errand.taken");
	}

	static boolean menuEnabled(HwNet.Errands.Entry e) {
		return e.taker() < 0 || e.kind() == Errands.MEDICINE && hasMedicine(Minecraft.getInstance());
	}

	/** Lines for the goal list: the favour I took on. */
	static java.util.List<Component> goalLines() {
		java.util.List<Component> lines = new java.util.ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		for (HwNet.Errands.Entry e : list) {
			if (e.taker() == me()) {
				String step = switch (e.kind()) {
					case Errands.MEDICINE -> hasMedicine(mc) ? "give" : "find";
					case Errands.DOG -> e.found() ? "back" : "find";
					default -> "go";
				};
				lines.add(Component.translatable("halloween.goal.errand." + e.kind() + "." + step, e.name()));
			}
		}
		return lines;
	}

	/** The mark over someone asking: a "!" in a dark diamond with a gold rim. */
	private static void badge(GuiGraphicsExtractor g, Font font, int x, int y) {
		diamond(g, x, y, 8, 0xFFC89A38);
		diamond(g, x, y, 7, 0xE8161310);
		g.pose().pushMatrix();
		g.pose().translate(x + 0.5f, y + 0.5f);
		g.pose().scale(1.25f, 1.25f);
		g.centeredText(font, Component.literal("!").withStyle(net.minecraft.ChatFormatting.BOLD), 0, -4, GOLD);
		g.pose().popMatrix();
	}

	private static void diamond(GuiGraphicsExtractor g, int x, int y, int r, int color) {
		for (int dy = -r; dy <= r; dy++) {
			int half = r - Math.abs(dy);
			g.fill(x - half, y + dy, x + half + 1, y + dy + 1, color);
		}
	}

	static void hud(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		Font font = mc.font;
		for (HwNet.Errands.Entry e : list) {
			if (e.taker() < 0) {
				// "!" over whoever has something to ask.
				Entity npc = mc.level.getEntity(e.npc());
				if (npc == null || npc.position().distanceTo(mc.player.position()) > OFFER_RANGE) {
					continue;
				}
				Vec3 at = npc.getPosition(partial).add(0, npc.getBbHeight() + 0.55, 0);
				float bob = Mth.sin((mc.level.getGameTime() + partial) * 0.12f) * 0.06f;
				at = at.add(0, bob, 0);
				if (ClientVr.active()) {
					VrMarks.add(at, "!", GOLD, 2f);
					continue;
				}
				Projection.Point p = Projection.toScreen(at, w, h);
				if (p != null && p.x() >= 0 && p.x() <= w && p.y() >= 0 && p.y() <= h) {
					badge(g, font, (int) p.x(), (int) p.y());
					if (p.distance() < 10) {
						Component what = Component.translatable("halloween.errand.mark." + e.kind());
						int tw = font.width(what);
						int tx = (int) p.x() - tw / 2;
						int ty = (int) p.y() + 12;
						g.fill(tx - 3, ty - 2, tx + tw + 3, ty + 10, 0xB0141210);
						g.text(font, what, tx, ty, 0xFFE8DCB8, true);
					}
				}
			} else if (e.taker() == me()) {
				if (e.kind() == Errands.MEDICINE && !hasMedicine(mc)) {
					// Nowhere to point to yet: medicine is found where it is found.
					continue;
				}
				Entity follow = e.targetId() < 0 ? null : mc.level.getEntity(e.targetId());
				Vec3 at = (follow != null ? follow.getPosition(partial).add(0, follow.getBbHeight() + 0.5, 0) : e.target().add(0, 1.2, 0));
				int dist = (int) Math.round(mc.player.position().distanceTo(at));
				Component label = Component.translatable("halloween.errand.where." + e.kind() + (e.found() ? ".back" : ""), e.name(), dist);
				if (ClientVr.active()) {
					VrMarks.add(at, "◆", GOLD, 2f);
					VrMarks.add(at.add(0, -0.4, 0), label, GOLD, 1f);
					continue;
				}
				Projection.Point p = Projection.toScreen(at, w, h);
				if (p != null && p.x() >= 0 && p.x() <= w && p.y() >= 0 && p.y() <= h) {
					g.pose().pushMatrix();
					g.pose().translate(p.x(), p.y() - 6);
					g.pose().scale(1.5f, 1.5f);
					g.centeredText(font, Component.literal("◆"), 0, -4, GOLD);
					g.pose().popMatrix();
					g.centeredText(font, label, (int) p.x(), (int) p.y() + 6, GOLD);
					continue;
				}
				Vec3 to = at.subtract(mc.player.getEyePosition(partial));
				Vec3 look = mc.player.getViewVector(partial);
				boolean right = look.x * to.z - look.z * to.x > 0;
				Component side = right ? label.copy().append(" ▶") : Component.literal("◀ ").append(label);
				g.centeredText(font, side, right ? w - 70 : 70, h / 2 - 28, GOLD);
			}
		}
	}
}
