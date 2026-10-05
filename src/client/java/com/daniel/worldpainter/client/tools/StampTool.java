package com.daniel.worldpainter.client.tools;

import com.daniel.worldpainter.client.edit.PainterState;
import com.daniel.worldpainter.client.map.MapDraw;
import com.daniel.worldpainter.client.map.MapView;
import com.daniel.worldpainter.client.templates.Template;
import com.daniel.worldpainter.client.templates.TemplateData;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Places the chosen template (island, continent, saved selection...) centered where you click. */
public final class StampTool implements Tool {
	@Override
	public String name() {
		return "Stamp";
	}

	@Override
	public char hotkey() {
		return 'T';
	}

	@Override
	public String help() {
		return "Click to place the chosen template. Q rotates it. Built-in templates change shape every time.";
	}

	@Override
	public boolean usesBrush() {
		return false;
	}

	@Override
	public void press(PainterState s, int x, int z, boolean alt) {
		Template t = s.template;
		if (t == null) {
			s.say("Pick a template in the side panel first.");
			return;
		}
		if (alt && t.resizable()) {
			s.templateSeed = System.nanoTime();
		}
		TemplateData data = t.create(s.templateSize, s.templateSeed);
		if (data == null) {
			s.say("That template could not be loaded.");
			return;
		}
		data.stamp(s, x, z, s.templateRotation);
		if (t.resizable()) {
			// a new random variation for the next stamp
			s.templateSeed = System.nanoTime();
		}
		s.say("Placed " + t.name() + " (" + data.rotatedWidth(s.templateRotation) + " x " + data.rotatedDepth(s.templateRotation) + ")");
	}

	@Override
	public void drawOverlay(PainterState s, GuiGraphicsExtractor g, MapView view, double mouseX, double mouseY) {
		Template t = s.template;
		if (t == null || !view.contains(mouseX, mouseY)) {
			return;
		}
		int w = t.width(s.templateSize), d = t.depth(s.templateSize);
		if ((s.templateRotation & 1) == 1) {
			int tmp = w;
			w = d;
			d = tmp;
		}
		int cx = (int) Math.floor(view.toWorldX(mouseX));
		int cz = (int) Math.floor(view.toWorldZ(mouseY));
		int x0 = cx - w / 2, z0 = cz - d / 2;
		MapDraw.rect(g, view, x0, z0, x0 + w - 1, z0 + d - 1, 0xFF7FFFFF);
		MapDraw.cross(g, view, cx + 0.5, cz + 0.5, 0xFF7FFFFF);
	}
}
