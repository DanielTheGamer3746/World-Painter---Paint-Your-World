package com.daniel.worldpainter.client.tools;

import com.daniel.worldpainter.client.Icons;

import java.util.List;

public final class Tools {
	public static final Tool BRUSH = new PaintBrushTool();
	public static final Tool ERASE = new EraseTool();
	public static final Tool SCULPT = new HeightTool();
	public static final Tool SMOOTH = new SmoothEdgesTool();
	public static final Tool FILL = new FillTool();
	public static final Tool RECT = new RectTool();
	public static final Tool PICK = new PickTool();
	public static final Tool SELECT = new SelectTool();
	public static final Tool STAMP = new StampTool();
	public static final Tool STRUCTURES = new StructureTool();
	public static final Sculpt3DTool SCULPT_3D = new Sculpt3DTool();

	public static final List<Tool> ALL = List.of(BRUSH, ERASE, SCULPT, SMOOTH, FILL, RECT, PICK, SELECT, STAMP, STRUCTURES, SCULPT_3D);

	/** Icon index of a tool in the sprite sheet (textures/gui/tools.png). */
	public static int icon(Tool tool) {
		if (tool == SCULPT_3D) {
			return Icons.SCULPT_3D;
		}
		return Math.max(0, ALL.indexOf(tool));
	}

	private Tools() {
	}
}
