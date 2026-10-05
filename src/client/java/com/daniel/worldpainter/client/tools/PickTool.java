package com.daniel.worldpainter.client.tools;

import com.daniel.worldpainter.client.edit.PainterState;
import com.daniel.worldpainter.client.map.BiomeColors;
import com.daniel.worldpainter.data.FluidType;

/** Eyedropper: takes the value under the cursor for the chosen layer, then switches back to the brush. */
public final class PickTool implements Tool {
	@Override
	public String name() {
		return "Pick";
	}

	@Override
	public char hotkey() {
		return 'I';
	}

	@Override
	public String help() {
		return "Click to take the biome / height / surface / fluid under the cursor.";
	}

	@Override
	public boolean usesBrush() {
		return false;
	}

	@Override
	public void press(PainterState s, int x, int z, boolean alt) {
		switch (s.layer) {
			case BIOME -> {
				String b = s.paintedBiome(x, z);
				if (b == null) {
					b = s.existingBiome(x, z);
				}
				if (b == null) {
					s.say("Nothing to pick here.");
					return;
				}
				s.biome = b;
				s.say("Picked " + BiomeColors.pretty(b));
			}
			case SURFACE -> {
				String b = s.paintedSurface(x, z);
				if (b == null) {
					s.say("No painted surface block here.");
					return;
				}
				s.surface = b;
				s.say("Picked " + b);
			}
			case HEIGHT -> {
				s.height = s.effectiveHeight(x, z);
				s.say("Picked height " + s.height);
			}
			case FLUID -> {
				short f = s.paintedFluid(x, z);
				if (f == FluidType.NONE) {
					s.say("No painted water/lava here.");
					return;
				}
				s.fluidType = FluidType.type(f);
				s.fluidLevel = FluidType.level(f);
				s.say("Picked " + s.fluidType.name().toLowerCase() + " at Y " + s.fluidLevel);
			}
		}
		s.tool = Tools.BRUSH;
	}
}
