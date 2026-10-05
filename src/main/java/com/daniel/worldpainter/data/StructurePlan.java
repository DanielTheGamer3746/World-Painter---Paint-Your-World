package com.daniel.worldpainter.data;

import com.daniel.worldpainter.storage.IoUtil;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The structure part of a design: structures the player placed, generated structures the player
 * removed, and zones where vanilla structures may not start. Saved as {@code structures.json}.
 */
public final class StructurePlan {
	public static final String FILE = "structures.json";

	/** A structure forced to generate with its start in the chunk containing (x, z). */
	public record Placed(String structure, int x, int z) {
		public int chunkX() {
			return x >> 4;
		}

		public int chunkZ() {
			return z >> 4;
		}
	}

	/** A generated structure the player removed: no structure may start in that chunk again. */
	public record Removed(String structure, int chunkX, int chunkZ) {
	}

	/** Inclusive block rectangle where vanilla structures may not start. */
	public record Zone(int minX, int minZ, int maxX, int maxZ) {
		public boolean intersectsChunk(int cx, int cz) {
			return (cx << 4) <= maxX && (cx << 4) + 15 >= minX && (cz << 4) <= maxZ && (cz << 4) + 15 >= minZ;
		}

		public boolean contains(int x, int z) {
			return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
		}
	}

	public boolean vanillaStructures = true;
	public final List<Placed> placed = new ArrayList<>();
	public final List<Removed> removed = new ArrayList<>();
	public final List<Zone> zones = new ArrayList<>();

	// Lookup tables for world generation (built by index()).
	private Map<Long, List<Placed>> placedByChunk;
	private Set<Long> removedChunks;

	public boolean isDefault() {
		return vanillaStructures && placed.isEmpty() && removed.isEmpty() && zones.isEmpty();
	}

	public StructurePlan copy() {
		StructurePlan p = new StructurePlan();
		p.vanillaStructures = vanillaStructures;
		p.placed.addAll(placed);
		p.removed.addAll(removed);
		p.zones.addAll(zones);
		return p;
	}

	private static long key(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
	}

	/** Builds the lookup tables. Call once before using the plan from generation threads. */
	public StructurePlan index() {
		Map<Long, List<Placed>> byChunk = new HashMap<>();
		for (Placed p : placed) {
			byChunk.computeIfAbsent(key(p.chunkX(), p.chunkZ()), k -> new ArrayList<>()).add(p);
		}
		Set<Long> rem = new HashSet<>();
		for (Removed r : removed) {
			rem.add(key(r.chunkX(), r.chunkZ()));
		}
		placedByChunk = byChunk;
		removedChunks = rem;
		return this;
	}

	public List<Placed> placedInChunk(int cx, int cz) {
		List<Placed> l = placedByChunk.get(key(cx, cz));
		return l == null ? List.of() : l;
	}

	/** True if vanilla structures must not start in this chunk. */
	public boolean blocksVanilla(int cx, int cz) {
		if (!vanillaStructures || removedChunks.contains(key(cx, cz))) {
			return true;
		}
		for (Zone z : zones) {
			if (z.intersectsChunk(cx, cz)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * How many chunks around a structure's start chunk must be regenerated in an existing world so the
	 * whole structure appears or disappears. Large jigsaw structures reach far from their start.
	 */
	public static int regenRadiusChunks(String id) {
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		if (path.startsWith("village") || path.equals("pillager_outpost") || path.equals("bastion_remnant")
				|| path.equals("ancient_city") || path.equals("trial_chambers") || path.equals("trail_ruins")
				|| path.equals("stronghold") || path.startsWith("mineshaft") || path.equals("fortress") || path.equals("end_city")) {
			return 8;
		}
		if (path.equals("mansion")) {
			return 6;
		}
		if (path.equals("monument")) {
			return 4;
		}
		if (path.startsWith("abandoned_camp")) {
			return 3;
		}
		return 2;
	}

	// ---- files ----

	public static StructurePlan load(Path dir) throws IOException {
		Path file = dir.resolve(FILE);
		StructurePlan p = new StructurePlan();
		if (!Files.isRegularFile(file)) {
			return p;
		}
		JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
		if (root.has("vanilla_structures")) {
			p.vanillaStructures = root.get("vanilla_structures").getAsBoolean();
		}
		if (root.has("placed")) {
			for (JsonElement e : root.getAsJsonArray("placed")) {
				JsonObject o = e.getAsJsonObject();
				p.placed.add(new Placed(o.get("structure").getAsString(), o.get("x").getAsInt(), o.get("z").getAsInt()));
			}
		}
		if (root.has("removed")) {
			for (JsonElement e : root.getAsJsonArray("removed")) {
				JsonObject o = e.getAsJsonObject();
				p.removed.add(new Removed(o.get("structure").getAsString(), o.get("chunk_x").getAsInt(), o.get("chunk_z").getAsInt()));
			}
		}
		if (root.has("zones")) {
			for (JsonElement e : root.getAsJsonArray("zones")) {
				JsonObject o = e.getAsJsonObject();
				p.zones.add(new Zone(o.get("min_x").getAsInt(), o.get("min_z").getAsInt(), o.get("max_x").getAsInt(), o.get("max_z").getAsInt()));
			}
		}
		return p;
	}

	public void save(Path dir) throws IOException {
		Path file = dir.resolve(FILE);
		if (isDefault()) {
			Files.deleteIfExists(file);
			return;
		}
		JsonObject root = new JsonObject();
		root.addProperty("format", 1);
		root.addProperty("vanilla_structures", vanillaStructures);
		JsonArray pl = new JsonArray();
		for (Placed p : placed) {
			JsonObject o = new JsonObject();
			o.addProperty("structure", p.structure());
			o.addProperty("x", p.x());
			o.addProperty("z", p.z());
			pl.add(o);
		}
		root.add("placed", pl);
		JsonArray rm = new JsonArray();
		for (Removed r : removed) {
			JsonObject o = new JsonObject();
			o.addProperty("structure", r.structure());
			o.addProperty("chunk_x", r.chunkX());
			o.addProperty("chunk_z", r.chunkZ());
			rm.add(o);
		}
		root.add("removed", rm);
		JsonArray zs = new JsonArray();
		for (Zone z : zones) {
			JsonObject o = new JsonObject();
			o.addProperty("min_x", z.minX());
			o.addProperty("min_z", z.minZ());
			o.addProperty("max_x", z.maxX());
			o.addProperty("max_z", z.maxZ());
			zs.add(o);
		}
		root.add("zones", zs);
		Files.createDirectories(dir);
		Path tmp = dir.resolve(FILE + ".tmp");
		Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
		IoUtil.replace(tmp, file);
	}
}
