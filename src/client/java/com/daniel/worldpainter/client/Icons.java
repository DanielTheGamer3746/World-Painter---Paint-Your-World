package com.daniel.worldpainter.client;

import com.daniel.worldpainter.WorldPainter;
import net.minecraft.resources.Identifier;

/** The 16x16 icons in textures/gui/tools.png (drawn by tools-src/make_icons.py). */
public final class Icons {
	public static final Identifier TEXTURE = WorldPainter.id("textures/gui/tools.png");
	public static final int COUNT = 15;
	public static final int SHEET_W = COUNT * 16;
	public static final int SHEET_H = 16;

	public static final int PICK = 6;
	public static final int SELECT = 7;
	public static final int STRUCTURE = 9;
	public static final int PLACE = 10;
	public static final int BREAK = 11;
	public static final int SCULPT_3D = 12;
	public static final int CUBE = 13;
	public static final int GEAR = 14;

	private Icons() {
	}
}
