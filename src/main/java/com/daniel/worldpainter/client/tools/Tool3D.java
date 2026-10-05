package com.daniel.worldpainter.client.tools;

import com.daniel.worldpainter.client.edit.PainterState;

/**
 * A tool that works on blocks in the 3D view (not only on map columns). Other tools are used in the
 * 3D view on the column of the block under the mouse.
 */
public interface Tool3D {
	void press3d(PainterState s, Hit3D hit, boolean alt);

	/** The mouse moved with the button held; {@code hit} is null when it is over the sky. */
	void drag3d(PainterState s, Hit3D hit, boolean alt);

	/** Called every tick (20 per second) while the button is held; {@code hit} may be null. */
	void hold3d(PainterState s, Hit3D hit, boolean alt);

	void release3d(PainterState s, boolean alt);

	/** Where the tool would act for this hit: {x, y, z} of the ball center (or island top center). */
	double[] target(PainterState s, Hit3D hit, boolean alt);
}
