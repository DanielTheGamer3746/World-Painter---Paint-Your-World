package com.daniel.worldpainter.client;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.live.LiveRegen;
import com.daniel.worldpainter.storage.IoUtil;
import com.daniel.worldpainter.storage.WorldPaths;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** The painter's settings (the gear in its top bar), kept in config/worldpainter/settings.json. */
public final class Settings {
	private static final String FILE = "settings.json";

	private static boolean loaded;
	private static boolean liveChanges = true;

	private Settings() {
	}

	/**
	 * Live changes (on by default): what you paint changes the running world right away. The design
	 * saves itself and painted chunks regenerate while you play, so there are no Save buttons.
	 */
	public static boolean liveChanges() {
		load();
		return liveChanges;
	}

	public static void setLiveChanges(boolean on) {
		load();
		liveChanges = on;
		LiveRegen.setEnabled(on);
		save();
	}

	private static Path file() {
		return WorldPaths.templatesDir(Minecraft.getInstance().gameDirectory.toPath()).getParent().resolve(FILE);
	}

	private static synchronized void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		Path file = file();
		if (Files.isRegularFile(file)) {
			try {
				JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				JsonElement live = o.get("liveChanges");
				if (live != null && live.isJsonPrimitive()) {
					liveChanges = live.getAsBoolean();
				}
			} catch (IOException | RuntimeException e) {
				WorldPainter.LOGGER.warn("Could not read {}", file, e);
			}
		}
		LiveRegen.setEnabled(liveChanges);
	}

	private static void save() {
		Path file = file();
		JsonObject o = new JsonObject();
		o.addProperty("liveChanges", liveChanges);
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(FILE + ".tmp");
			Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
			IoUtil.replace(tmp, file);
		} catch (IOException e) {
			WorldPainter.LOGGER.warn("Could not write {}", file, e);
		}
	}
}
