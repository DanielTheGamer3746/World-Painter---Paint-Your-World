package com.daniel.worldpainter.editor;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.storage.IoUtil;
import com.daniel.worldpainter.storage.WorldPaths;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * While the 3D editor is open the player flies through blocks as a spectator. The game mode and
 * position from before are remembered (also on disk, in case the game closes while editing) and
 * restored when the editor closes, when the player leaves, or on the next join.
 */
public final class EditorSessions {
	private static final String FILE = "editor_session.json";

	private record Saved(String gameMode, double x, double y, double z) {
	}

	private static final Map<UUID, Saved> ACTIVE = new ConcurrentHashMap<>();

	private EditorSessions() {
	}

	public static void register() {
		ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> {
			ServerPlayer player = listener.getPlayer();
			if (ACTIVE.containsKey(player.getUUID())) {
				end(server, player, true);
			}
		});
		ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> restoreAfterCrash(server, listener.getPlayer()));
	}

	public static boolean isEditing(ServerPlayer player) {
		return ACTIVE.containsKey(player.getUUID());
	}

	/** Server thread. Switches the player to spectator for flying through the structure. */
	public static void begin(MinecraftServer server, ServerPlayer player) {
		if (ACTIVE.containsKey(player.getUUID())) {
			return;
		}
		Saved saved = new Saved(player.gameMode.getGameModeForPlayer().name(), player.getX(), player.getY(), player.getZ());
		ACTIVE.put(player.getUUID(), saved);
		writeFile(server);
		player.setGameMode(GameType.SPECTATOR);
	}

	/** Server thread. Restores the game mode; optionally puts the player back where they started. */
	public static void end(MinecraftServer server, ServerPlayer player, boolean returnToStart) {
		Saved saved = ACTIVE.remove(player.getUUID());
		if (saved == null) {
			return;
		}
		restore(player, saved, returnToStart);
		writeFile(server);
	}

	private static void restore(ServerPlayer player, Saved saved, boolean returnToStart) {
		GameType mode;
		try {
			mode = GameType.valueOf(saved.gameMode());
		} catch (IllegalArgumentException e) {
			mode = GameType.CREATIVE;
		}
		player.setGameMode(mode);
		// "Stay here" inside a wall would trap the player: go back to the start instead.
		if (returnToStart || !player.level().noCollision(player)) {
			player.teleportTo(saved.x(), saved.y(), saved.z());
		}
	}

	private static void restoreAfterCrash(MinecraftServer server, ServerPlayer player) {
		Path file = file(server);
		if (!Files.isRegularFile(file)) {
			return;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			String key = player.getUUID().toString();
			if (!root.has(key)) {
				return;
			}
			JsonObject o = root.get(key).getAsJsonObject();
			Saved saved = new Saved(o.get("mode").getAsString(), o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble());
			restore(player, saved, true);
			WorldPainter.LOGGER.info("Restored {}'s game mode after the structure editor was left open", player.getName().getString());
			ACTIVE.remove(player.getUUID());
			writeFile(server);
		} catch (IOException | RuntimeException e) {
			WorldPainter.LOGGER.warn("Could not read {}", file, e);
		}
	}

	private static Path file(MinecraftServer server) {
		return WorldPaths.paintDir(WorldPainter.worldRoot(server)).resolve(FILE);
	}

	private static void writeFile(MinecraftServer server) {
		Path file = file(server);
		try {
			if (ACTIVE.isEmpty()) {
				Files.deleteIfExists(file);
				return;
			}
			JsonObject root = new JsonObject();
			for (Map.Entry<UUID, Saved> e : ACTIVE.entrySet()) {
				JsonObject o = new JsonObject();
				o.addProperty("mode", e.getValue().gameMode());
				o.addProperty("x", e.getValue().x());
				o.addProperty("y", e.getValue().y());
				o.addProperty("z", e.getValue().z());
				root.add(e.getKey().toString(), o);
			}
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(FILE + ".tmp");
			Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
			IoUtil.replace(tmp, file);
		} catch (IOException e) {
			WorldPainter.LOGGER.warn("Could not write {}", file, e);
		}
	}
}
