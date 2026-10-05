package com.daniel.worldpainter.data;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.storage.IoUtil;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToIntFunction;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * All painted data of one world (or of the draft of a world that is not created yet).
 * Tiles are loaded from disk when first needed and can be unloaded again, so painted areas
 * far bigger than memory are fine. Thread safe for readers (world generation threads).
 */
public final class PaintWorld {
	/** Blocks from -30,000,000 up to (but not including) +30,000,000 on both axes: 60,000,000 wide. */
	public static final int WORLD_LIMIT = 30_000_000;
	public static final int MIN_TILE = -WORLD_LIMIT >> PaintTile.SHIFT;
	public static final int MAX_TILE = (WORLD_LIMIT - 1) >> PaintTile.SHIFT;

	private static final int INDEX_MAGIC = 0x57504958; // "WPIX"
	private static final String TILES = "tiles";
	private static final String INDEX = "index.bin";
	public static final String PENDING_REGEN = "pending_regen.bin";

	private final Path dir;
	private final Path tilesDir;
	private final ConcurrentHashMap<Long, PaintTile> loaded = new ConcurrentHashMap<>();
	private final Set<Long> onDisk = ConcurrentHashMap.newKeySet();
	private final Set<Long> modified = ConcurrentHashMap.newKeySet();
	private final ConcurrentHashMap<Long, Integer> summaries = new ConcurrentHashMap<>();
	private final DirtyChunks regen = new DirtyChunks();
	/** Chunks changed since live changes last picked them up (painter thread only). */
	private final DirtyChunks liveChanged = new DirtyChunks();
	private final AtomicLong clock = new AtomicLong();
	private final int maxLoaded;
	private volatile int loadsSinceEvict;
	private volatile StructurePlan structures = new StructurePlan().index();
	private volatile boolean structuresModified;

	private PaintWorld(Path dir, int maxLoaded) {
		this.dir = dir;
		this.tilesDir = dir.resolve(TILES);
		this.maxLoaded = maxLoaded;
	}

	/** Opens (or prepares to create) the paint data in {@code dir}. Nothing is written until {@link #save}. */
	public static PaintWorld open(Path dir, int maxLoadedTiles) {
		PaintWorld w = new PaintWorld(dir, maxLoadedTiles);
		w.rescan();
		return w;
	}

	public static boolean hasPaint(Path dir) {
		if (Files.isRegularFile(dir.resolve(StructurePlan.FILE))) {
			return true;
		}
		Path tiles = dir.resolve(TILES);
		if (!Files.isDirectory(tiles)) {
			return false;
		}
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(tiles, "*.wpt")) {
			return ds.iterator().hasNext();
		} catch (IOException e) {
			return false;
		}
	}

	/** Re-reads which tiles exist on disk and drops everything cached (keeps nothing unsaved). */
	public synchronized void rescan() {
		loaded.clear();
		onDisk.clear();
		modified.clear();
		summaries.clear();
		if (Files.isDirectory(tilesDir)) {
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(tilesDir, "*.wpt")) {
				for (Path p : ds) {
					Long key = TileIO.parseFileName(p.getFileName().toString());
					if (key != null) {
						onDisk.add(key);
					}
				}
			} catch (IOException e) {
				WorldPainter.LOGGER.error("Could not list painted tiles in {}", tilesDir, e);
			}
		}
		loadIndex();
		try {
			structures = StructurePlan.load(dir).index();
		} catch (IOException | RuntimeException e) {
			WorldPainter.LOGGER.error("Could not read {}", dir.resolve(StructurePlan.FILE), e);
			structures = new StructurePlan().index();
		}
		structuresModified = false;
	}

	/** The structure part of the design (placed / removed structures, no-structure zones). */
	public StructurePlan structures() {
		return structures;
	}

	/** Replaces the structure plan (editor). */
	public void setStructures(StructurePlan plan) {
		structures = plan.index();
		structuresModified = true;
	}

	public Path dir() {
		return dir;
	}

	public static boolean inWorld(int blockX, int blockZ) {
		return blockX >= -WORLD_LIMIT && blockX < WORLD_LIMIT && blockZ >= -WORLD_LIMIT && blockZ < WORLD_LIMIT;
	}

	public static boolean tileInWorld(int tx, int tz) {
		return tx >= MIN_TILE && tx <= MAX_TILE && tz >= MIN_TILE && tz <= MAX_TILE;
	}

	/** True if the tile has any data, loaded or on disk (does not load it). */
	public boolean exists(long key) {
		return loaded.containsKey(key) || onDisk.contains(key);
	}

	public boolean isLoaded(long key) {
		return loaded.containsKey(key);
	}

	/** Returns the tile or null if nothing was painted there. Loads it from disk when needed. */
	public PaintTile get(int tx, int tz) {
		long key = TileKey.of(tx, tz);
		PaintTile t = loaded.get(key);
		if (t == null) {
			if (!onDisk.contains(key)) {
				return null;
			}
			t = loaded.computeIfAbsent(key, this::loadTile);
			if (t == null) {
				return null;
			}
			if (++loadsSinceEvict > 128) {
				loadsSinceEvict = 0;
				evictIfNeeded();
			}
		}
		t.lastUse = clock.incrementAndGet();
		return t;
	}

	public PaintTile getForBlock(int x, int z) {
		return get(x >> PaintTile.SHIFT, z >> PaintTile.SHIFT);
	}

	/** Editor: returns the tile, creating an empty one if needed. */
	public PaintTile getOrCreate(int tx, int tz) {
		PaintTile t = get(tx, tz);
		if (t == null) {
			t = loaded.computeIfAbsent(TileKey.of(tx, tz), k -> new PaintTile(tx, tz));
			t.lastUse = clock.incrementAndGet();
		}
		return t;
	}

	private PaintTile loadTile(long key) {
		Path file = tilesDir.resolve(TileIO.fileName(TileKey.x(key), TileKey.z(key)));
		try {
			PaintTile t = TileIO.read(file);
			if (t.tx != TileKey.x(key) || t.tz != TileKey.z(key)) {
				throw new IOException("Tile file position does not match its name");
			}
			return t;
		} catch (IOException e) {
			WorldPainter.LOGGER.error("Could not read painted tile {}", file, e);
			onDisk.remove(key);
			return null;
		}
	}

	public void markModified(PaintTile t) {
		t.touch();
		long key = TileKey.of(t.tx, t.tz);
		modified.add(key);
		loaded.putIfAbsent(key, t);
	}

	public boolean hasUnsavedChanges() {
		return !modified.isEmpty() || structuresModified || !regen.isEmpty();
	}

	/** Chunks painted over during this editing session (only used for existing worlds). */
	public DirtyChunks regen() {
		return regen;
	}

	/** Marks a painted chunk: in the save's regeneration list, and for live changes. */
	public void markRegen(int cx, int cz) {
		regen.markChunk(cx, cz);
		liveChanged.markChunk(cx, cz);
	}

	/** Like {@link #markRegen} for every chunk under a block rectangle (inclusive). */
	public void markRegenRect(int minX, int minZ, int maxX, int maxZ) {
		regen.markBlockRect(minX, minZ, maxX, maxZ);
		liveChanged.markBlockRect(minX, minZ, maxX, maxZ);
	}

	/** The chunks painted since the last call, for live changes (they regenerate right away). */
	public DirtyChunks takeLiveChanges() {
		if (liveChanged.isEmpty()) {
			return null;
		}
		DirtyChunks d = liveChanged.copy();
		liveChanged.clear();
		return d;
	}

	public Integer storedSummary(long key) {
		return summaries.get(key);
	}

	public void putSummary(long key, int color) {
		summaries.put(key, color);
	}

	/** Every tile that has data, loaded or not. */
	public Set<Long> allTileKeys() {
		Set<Long> s = new HashSet<>(onDisk);
		s.addAll(loaded.keySet());
		return s;
	}

	public int loadedCount() {
		return loaded.size();
	}

	public long loadedMemoryBytes() {
		long b = 0;
		for (PaintTile t : loaded.values()) {
			b += t.memoryBytes();
		}
		return b;
	}

	/** Writes changed tiles, the zoomed-out color index, and (optionally) the list of chunks to regenerate. */
	public synchronized void save(ToIntFunction<PaintTile> summarizer, boolean writeRegen) throws IOException {
		Files.createDirectories(tilesDir);
		for (Long key : new ArrayList<>(modified)) {
			PaintTile t = loaded.get(key);
			Path file = tilesDir.resolve(TileIO.fileName(TileKey.x(key), TileKey.z(key)));
			if (t != null) {
				t.compact();
			}
			if (t == null || t.isEmpty()) {
				Files.deleteIfExists(file);
				onDisk.remove(key);
				loaded.remove(key);
				summaries.remove(key);
			} else {
				TileIO.write(t, file);
				onDisk.add(key);
				if (summarizer != null) {
					summaries.put(key, summarizer.applyAsInt(t));
				}
			}
			modified.remove(key);
		}
		saveIndex();
		if (structuresModified) {
			structures.save(dir);
			structuresModified = false;
		}
		Files.writeString(dir.resolve("worldpainter.properties"), "format=1\n");
		if (writeRegen && !regen.isEmpty()) {
			Path file = dir.resolve(PENDING_REGEN);
			synchronized (DirtyChunks.FILE_LOCK) {
				DirtyChunks all = DirtyChunks.load(file);
				all.addAll(regen);
				all.save(file);
			}
			regen.clear();
		}
		evictIfNeeded();
	}

	/** Unloads tiles that are saved and have not been used recently. */
	public void evictIfNeeded() {
		int size = loaded.size();
		if (size <= maxLoaded) {
			return;
		}
		List<PaintTile> clean = new ArrayList<>();
		for (Map.Entry<Long, PaintTile> e : loaded.entrySet()) {
			if (!modified.contains(e.getKey()) && onDisk.contains(e.getKey())) {
				clean.add(e.getValue());
			}
		}
		clean.sort((a, b) -> Long.compare(a.lastUse, b.lastUse));
		int toRemove = size - (maxLoaded * 3 / 4);
		for (int i = 0; i < clean.size() && i < toRemove; i++) {
			PaintTile t = clean.get(i);
			loaded.remove(TileKey.of(t.tx, t.tz), t);
		}
	}

	/**
	 * Deletes all painted data of this design (used by "Clear All" on a draft). Other dimensions'
	 * designs (in {@code dimensions/}) and backups are kept.
	 */
	public synchronized void deleteEverything() throws IOException {
		IoUtil.deleteRecursively(tilesDir);
		for (String file : new String[]{INDEX, StructurePlan.FILE, PENDING_REGEN, "worldpainter.properties"}) {
			Files.deleteIfExists(dir.resolve(file));
		}
		loaded.clear();
		onDisk.clear();
		modified.clear();
		summaries.clear();
		regen.clear();
		structures = new StructurePlan().index();
		structuresModified = false;
	}

	private void loadIndex() {
		Path file = dir.resolve(INDEX);
		if (!Files.isRegularFile(file)) {
			return;
		}
		try (InputStream raw = Files.newInputStream(file);
			 DataInputStream in = new DataInputStream(new GZIPInputStream(raw))) {
			if (in.readInt() != INDEX_MAGIC) {
				return;
			}
			in.readInt();
			int n = in.readInt();
			for (int i = 0; i < n; i++) {
				long key = in.readLong();
				int color = in.readInt();
				if (onDisk.contains(key)) {
					summaries.put(key, color);
				}
			}
		} catch (IOException e) {
			WorldPainter.LOGGER.warn("Could not read {}; zoomed-out colors will be rebuilt", file, e);
		}
	}

	private void saveIndex() throws IOException {
		Path file = dir.resolve(INDEX);
		Path tmp = dir.resolve(INDEX + ".tmp");
		try (OutputStream raw = Files.newOutputStream(tmp);
			 DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
			out.writeInt(INDEX_MAGIC);
			out.writeInt(1);
			List<Map.Entry<Long, Integer>> entries = new ArrayList<>(summaries.entrySet());
			out.writeInt(entries.size());
			for (Map.Entry<Long, Integer> e : entries) {
				out.writeLong(e.getKey());
				out.writeInt(e.getValue());
			}
		}
		IoUtil.replace(tmp, file);
	}
}
