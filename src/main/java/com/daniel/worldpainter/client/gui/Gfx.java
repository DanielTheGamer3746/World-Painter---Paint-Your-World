package com.daniel.worldpainter.client.gui;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.client.Icons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.platform.Lighting;
import net.minecraft.client.util.ScreenScaler;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Drawing for the painter's screens on Beta 1.7.3: filled rectangles, outlines, text, icons, a
 * texture and clipping, in GUI coordinates (the same units Beta's screens use).
 */
public final class Gfx {
	private final Minecraft mc;
	private final TextRenderer font;
	private final Deque<int[]> scissors = new ArrayDeque<>();
	private int scale = 1;
	private static int iconTexture = -1;
	private static boolean itemsBroken;

	public Gfx(Minecraft mc) {
		this.mc = mc;
		this.font = mc.textRenderer;
	}

	/** Call at the start of every frame (the GUI scale may have changed). */
	public void begin() {
		scale = Math.max(1, new ScreenScaler(mc.options, mc.displayWidth, mc.displayHeight).scaleFactor);
		scissors.clear();
	}

	/** Screen pixels per GUI pixel. */
	public int scale() {
		return scale;
	}

	public TextRenderer font() {
		return font;
	}

	public int width(String text) {
		return font.getWidth(text);
	}

	public void fill(int x0, int y0, int x1, int y1, int argb) {
		if (x1 <= x0 || y1 <= y0) {
			return;
		}
		float a = (argb >>> 24) / 255f, r = (argb >> 16 & 255) / 255f, g = (argb >> 8 & 255) / 255f, b = (argb & 255) / 255f;
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GL11.glColor4f(r, g, b, a);
		Tessellator t = Tessellator.INSTANCE;
		t.startQuads();
		t.vertex(x0, y1, 0);
		t.vertex(x1, y1, 0);
		t.vertex(x1, y0, 0);
		t.vertex(x0, y0, 0);
		t.draw();
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glDisable(GL11.GL_BLEND);
		GL11.glColor4f(1f, 1f, 1f, 1f);
	}

	/** A thin line between two GUI points (sub-pixel positions are fine). */
	public void line(double x0, double y0, double x1, double y1, int argb) {
		float a = (argb >>> 24) / 255f, r = (argb >> 16 & 255) / 255f, g = (argb >> 8 & 255) / 255f, b = (argb & 255) / 255f;
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GL11.glLineWidth(Math.max(1f, scale * 0.75f));
		GL11.glColor4f(r, g, b, a);
		Tessellator t = Tessellator.INSTANCE;
		t.start(GL11.GL_LINES);
		t.vertex(x0, y0, 0);
		t.vertex(x1, y1, 0);
		t.draw();
		GL11.glLineWidth(1f);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glDisable(GL11.GL_BLEND);
		GL11.glColor4f(1f, 1f, 1f, 1f);
	}

	public void outline(int x, int y, int w, int h, int argb) {
		fill(x, y, x + w, y + 1, argb);
		fill(x, y + h - 1, x + w, y + h, argb);
		fill(x, y + 1, x + 1, y + h - 1, argb);
		fill(x + w - 1, y + 1, x + w, y + h - 1, argb);
	}

	public void text(String text, int x, int y, int argb, boolean shadow) {
		if (shadow) {
			font.drawWithShadow(text, x, y, argb);
		} else {
			font.draw(text, x, y, argb);
		}
		GL11.glColor4f(1f, 1f, 1f, 1f);
	}

	public void centeredText(String text, int cx, int y, int argb) {
		text(text, cx - width(text) / 2, y, argb, true);
	}

	/** Draws a whole texture (by GL id) into a rectangle. */
	public void texture(int glId, int x, int y, int w, int h) {
		texture(glId, x, y, w, h, 0, 0, 1, 1, false);
	}

	private void texture(int glId, int x, int y, int w, int h, double u0, double v0, double u1, double v1, boolean blend) {
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
		if (blend) {
			GL11.glEnable(GL11.GL_BLEND);
			GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		}
		GL11.glColor4f(1f, 1f, 1f, 1f);
		Tessellator t = Tessellator.INSTANCE;
		t.startQuads();
		t.vertex(x, y + h, 0, u0, v1);
		t.vertex(x + w, y + h, 0, u1, v1);
		t.vertex(x + w, y, 0, u1, v0);
		t.vertex(x, y, 0, u0, v0);
		t.draw();
		if (blend) {
			GL11.glDisable(GL11.GL_BLEND);
		}
	}

	/** One 16 x 16 icon of the tool sheet ({@link Icons}). */
	public void icon(int index, int x, int y) {
		int tex = iconTexture();
		if (tex < 0) {
			return;
		}
		double u0 = index * 16.0 / Icons.SHEET_W, u1 = (index + 1) * 16.0 / Icons.SHEET_W;
		texture(tex, x, y, 16, 16, u0, 0, u1, 1, true);
	}

	private int iconTexture() {
		if (iconTexture == -1) {
			iconTexture = -2;
			try (InputStream in = Gfx.class.getResourceAsStream(Icons.TEXTURE)) {
				BufferedImage image = in == null ? null : ImageIO.read(in);
				if (image != null) {
					iconTexture = mc.textureManager.load(image);
				}
			} catch (Exception e) {
				WorldPainter.LOGGER.warn("Could not load the tool icons", e);
			}
		}
		return iconTexture;
	}

	/**
	 * An item (or block) icon with its count, drawn by the game like in a chest. If the game's item
	 * drawing is not available, a colored square stands in.
	 */
	public void item(ItemStack stack, int x, int y) {
		if (stack == null) {
			return;
		}
		if (!itemsBroken) {
			try {
				if (EntityRenderDispatcher.INSTANCE.get(ItemEntity.class) instanceof ItemRenderer items) {
					GL11.glPushMatrix();
					GL11.glRotatef(120f, 1f, 0f, 0f);
					Lighting.turnOn();
					GL11.glPopMatrix();
					GL11.glEnable(GL_RESCALE_NORMAL);
					GL11.glEnable(GL11.GL_DEPTH_TEST);
					GL11.glColor4f(1f, 1f, 1f, 1f);
					items.renderGuiItem(font, mc.textureManager, stack, x, y);
					items.renderGuiItemDecoration(font, mc.textureManager, stack, x, y);
					GL11.glDisable(GL_RESCALE_NORMAL);
					Lighting.turnOff();
					GL11.glDisable(GL11.GL_LIGHTING);
					GL11.glDisable(GL11.GL_DEPTH_TEST);
					GL11.glColor4f(1f, 1f, 1f, 1f);
					return;
				}
			} catch (LinkageError e) {
				itemsBroken = true;
				WorldPainter.LOGGER.warn("Item icons are not available; showing colors instead", e);
				itemDrawn();
			} catch (RuntimeException e) {
				// This one item cannot be drawn (a mod's item, say): a color stands in for it.
				if (!itemWarned) {
					itemWarned = true;
					WorldPainter.LOGGER.warn("Could not draw item {}", stack.itemId, e);
				}
				itemDrawn();
			}
		}
		int c = 0xFF000000 | (stack.itemId * 0x9E3779B1 >>> 8);
		fill(x + 2, y + 2, x + 14, y + 14, c);
		if (stack.count > 1) {
			String n = String.valueOf(stack.count);
			text(n, x + 17 - width(n), y + 9, 0xFFFFFFFF, true);
		}
	}

	private static final int GL_RESCALE_NORMAL = 0x803A;
	private static boolean itemWarned;

	/** Puts the drawing state back after an item that failed half way. */
	private static void itemDrawn() {
		try {
			Lighting.turnOff();
		} catch (RuntimeException | LinkageError ignored) {
			// Nothing to undo.
		}
		GL11.glDisable(GL_RESCALE_NORMAL);
		GL11.glDisable(GL11.GL_LIGHTING);
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glColor4f(1f, 1f, 1f, 1f);
	}

	/** Only draws inside this rectangle until {@link #disableScissor()}; nests. */
	public void enableScissor(int x0, int y0, int x1, int y1) {
		int[] r = {x0, y0, x1, y1};
		if (!scissors.isEmpty()) {
			int[] o = scissors.peek();
			r = new int[]{Math.max(x0, o[0]), Math.max(y0, o[1]), Math.min(x1, o[2]), Math.min(y1, o[3])};
		}
		scissors.push(r);
		apply(r);
	}

	public void disableScissor() {
		if (!scissors.isEmpty()) {
			scissors.pop();
		}
		if (scissors.isEmpty()) {
			GL11.glDisable(GL11.GL_SCISSOR_TEST);
		} else {
			apply(scissors.peek());
		}
	}

	private void apply(int[] r) {
		int w = Math.max(0, r[2] - r[0]), h = Math.max(0, r[3] - r[1]);
		GL11.glEnable(GL11.GL_SCISSOR_TEST);
		GL11.glScissor(r[0] * scale, mc.displayHeight - (r[1] + h) * scale, w * scale, h * scale);
	}

	/** A tooltip box near the mouse, kept on screen. */
	public void tooltip(java.util.List<String> lines, int mx, int my, int screenW, int screenH) {
		if (lines.isEmpty()) {
			return;
		}
		int w = 0;
		for (String l : lines) {
			w = Math.max(w, width(l));
		}
		int h = lines.size() * 10;
		int x = mx + 12, y = my - 12;
		if (x + w + 4 > screenW) {
			x = Math.max(2, mx - 16 - w);
		}
		if (y + h + 4 > screenH) {
			y = Math.max(2, screenH - h - 4);
		}
		y = Math.max(4, y);
		fill(x - 3, y - 4, x + w + 3, y + h + 2, 0xF0100010);
		outline(x - 3, y - 4, w + 6, h + 6, 0xFF3E2A7A);
		for (int i = 0; i < lines.size(); i++) {
			text(lines.get(i), x, y + i * 10, 0xFFFFFFFF, true);
		}
	}

	/** Splits text into lines that fit a width. */
	public java.util.List<String> wrap(String text, int w) {
		java.util.List<String> lines = new java.util.ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String next = line.length() == 0 ? word : line + " " + word;
			if (width(next) > w && line.length() > 0) {
				lines.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(next);
			}
		}
		if (line.length() > 0) {
			lines.add(line.toString());
		}
		return lines;
	}
}
