package com.daniel.worldpainter.client.templates;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.storage.WorldPaths;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Templates saved by the player (from a selection). Stored in config/worldpainter/templates, shared by all worlds. */
public final class TemplateLibrary {
	private static final String EXT = ".wptemplate";

	private TemplateLibrary() {
	}

	public static Path dir(Path gameDir) {
		return WorldPaths.templatesDir(gameDir);
	}

	public static List<Template> list(Path gameDir) {
		List<Template> out = new ArrayList<>();
		Path d = dir(gameDir);
		if (!Files.isDirectory(d)) {
			return out;
		}
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "*" + EXT)) {
			for (Path p : ds) {
				String file = p.getFileName().toString();
				out.add(new Saved(file.substring(0, file.length() - EXT.length()), p));
			}
		} catch (IOException e) {
			WorldPainter.LOGGER.warn("Could not list templates in {}", d, e);
		}
		out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
		return out;
	}

	public static String sanitize(String name) {
		String s = name.trim().replaceAll("[^A-Za-z0-9 _-]", "_");
		if (s.length() > 48) {
			s = s.substring(0, 48);
		}
		return s.isEmpty() ? "template" : s;
	}

	public static Path save(Path gameDir, String name, TemplateData data) throws IOException {
		Path file = dir(gameDir).resolve(sanitize(name) + EXT);
		data.save(file);
		return file;
	}

	public static void delete(Template t) throws IOException {
		if (t instanceof Saved s) {
			Files.deleteIfExists(s.file);
		}
	}

	/** A saved template; its pixels are loaded the first time it is used. */
	public static final class Saved implements Template {
		private final String name;
		private final Path file;
		private TemplateData data;
		private boolean failed;

		Saved(String name, Path file) {
			this.name = name;
			this.file = file;
		}

		private TemplateData data() {
			if (data == null && !failed) {
				try {
					data = TemplateData.load(file);
				} catch (IOException | RuntimeException e) {
					failed = true;
					WorldPainter.LOGGER.error("Could not load template {}", file, e);
				}
			}
			return data;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public String description() {
			TemplateData d = data();
			return d == null ? "Could not be loaded" : "Saved template, " + d.width + " x " + d.depth + " blocks";
		}

		@Override
		public boolean resizable() {
			return false;
		}

		@Override
		public TemplateData create(int size, long seed) {
			return data();
		}

		@Override
		public int width(int size) {
			TemplateData d = data();
			return d == null ? 1 : d.width;
		}

		@Override
		public int depth(int size) {
			TemplateData d = data();
			return d == null ? 1 : d.depth;
		}
	}
}
