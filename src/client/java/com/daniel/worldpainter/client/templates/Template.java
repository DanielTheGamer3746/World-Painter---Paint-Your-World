package com.daniel.worldpainter.client.templates;

/** Something that can be stamped: a built-in generator or a saved user template. */
public interface Template {
	String name();

	/** Short description shown in the side panel. */
	String description();

	/** Whether the size slider applies (built-in generators) or the size is fixed (saved templates). */
	boolean resizable();

	/** Produces the pixels to stamp. {@code size} is only used when {@link #resizable()}. */
	TemplateData create(int size, long seed);

	/** Size without generating (for the preview outline). */
	int width(int size);

	int depth(int size);
}
