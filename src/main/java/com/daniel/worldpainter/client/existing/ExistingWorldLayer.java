package com.daniel.worldpainter.client.existing;

import com.daniel.worldpainter.WorldPainter;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shows what an existing world already looks like underneath the paint, read from its region
 * files (Beta's {@code region/r.X.Z.mcr}) in the background.
 */
public final class ExistingWorldLayer implements AutoCloseable {
	public static final short UNKNOWN = RegionReader.UNKNOWN;
	private static final int MAX_CACHED = 96;

	private final Path regionDir;
	/** The world's seed (for its natural biomes), or null if unknown. */
	private final Long seed;
	private final Set<Long> onDisk = ConcurrentHashMap.newKeySet();
	private final ConcurrentHashMap<Long, Region> cache = new ConcurrentHashMap<>();
	/** Structures found per region; kept even when the region's pixels are unloaded (they are small). */
	private final ConcurrentHashMap<Long, List<ExistingStructure>> structures = new ConcurrentHashMap<>();
	private final Set<Long> pending = ConcurrentHashMap.newKeySet();
	private final ExecutorService executor;
	private final List<String> biomeNames = new ArrayList<>();
	private final Map<String, Short> biomeIds = new HashMap<>();
	private final AtomicLong clock = new AtomicLong();
	private volatile boolean changed;
	private volatile boolean closed;

	/** One chunk read from memory (the running world): columns indexed {@code z * 16 + x}. */
	public record ChunkColumns(short[] surface, short[] floor, int[] color, String[] biome) {
	}

	/** Chunks of the running world read from memory; they win over the (older) saved copy. */
	private final Map<Long, ChunkColumns> live = new HashMap<>();

	public static final class Region {
		private final RegionReader.Raster raster;
		public volatile int averageColor;
		volatile long lastUse;

		Region(RegionReader.Raster raster, int averageColor) {
			this.raster = raster;
			this.averageColor = averageColor;
		}

		public short surface(int localX, int localZ) {
			return raster.surface[(localZ << 9) | localX];
		}

		public short floor(int localX, int localZ) {
			return raster.floor[(localZ << 9) | localX];
		}

		public short biomeIndex(int localX, int localZ) {
			return raster.biome[(localZ << 9) | localX];
		}

		/** Color of the top block seen from above (0 where nothing was generated). */
		public int color(int localX, int localZ) {
			return raster.color[(localZ << 9) | localX];
		}

		public boolean isEmpty() {
			return raster.chunks == 0;
		}
	}

	/** @param seed the world's seed (shows its natural biomes), or null */
	public ExistingWorldLayer(Path regionDir, Long seed) {
		this.regionDir = regionDir;
		this.seed = seed;
		biomeNames.add(null);
		this.executor = Executors.newFixedThreadPool(2, r -> {
			Thread t = new Thread(r, "World Painter region reader");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});
		if (Files.isDirectory(regionDir)) {
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionDir, "r.*.mcr")) {
				for (Path p : ds) {
					String[] parts = p.getFileName().toString().split("\\.");
					if (parts.length == 4) {
						try {
							onDisk.add(key(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
						} catch (NumberFormatException ignored) {
						}
					}
				}
			} catch (IOException e) {
				WorldPainter.LOGGER.warn("Could not list region files in {}", regionDir, e);
			}
		}
	}

	public static long key(int rx, int rz) {
		return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
	}

	public boolean isEmpty() {
		return onDisk.isEmpty();
	}

	public int regionCount() {
		return onDisk.size();
	}

	public boolean hasRegion(int rx, int rz) {
		return onDisk.contains(key(rx, rz));
	}

	/** Returns the region if it is loaded; otherwise null (and optionally starts loading it). */
	public Region region(int rx, int rz, boolean load) {
		long k = key(rx, rz);
		Region r = cache.get(k);
		if (r != null) {
			r.lastUse = clock.incrementAndGet();
			return r;
		}
		if (load && onDisk.contains(k) && !closed && pending.add(k)) {
			executor.execute(() -> loadRegion(rx, rz, k));
		}
		return null;
	}

	public String biomeName(short index) {
		synchronized (biomeNames) {
			return index > 0 && index < biomeNames.size() ? biomeNames.get(index) : null;
		}
	}

	/** Structures (known so far) whose start chunk lies in the given block rectangle, grown by a margin. */
	public List<ExistingStructure> structuresNear(int minX, int minZ, int maxX, int maxZ) {
		List<ExistingStructure> out = new ArrayList<>();
		int margin = 256;
		int rx0 = (minX - margin) >> 9, rx1 = (maxX + margin) >> 9;
		int rz0 = (minZ - margin) >> 9, rz1 = (maxZ + margin) >> 9;
		if ((long) (rx1 - rx0 + 1) * (rz1 - rz0 + 1) > structures.size()) {
			for (List<ExistingStructure> l : structures.values()) {
				addOverlapping(out, l, minX, minZ, maxX, maxZ);
			}
		} else {
			for (int rz = rz0; rz <= rz1; rz++) {
				for (int rx = rx0; rx <= rx1; rx++) {
					List<ExistingStructure> l = structures.get(key(rx, rz));
					if (l != null) {
						addOverlapping(out, l, minX, minZ, maxX, maxZ);
					}
				}
			}
		}
		return out;
	}

	private static void addOverlapping(List<ExistingStructure> out, List<ExistingStructure> l, int minX, int minZ, int maxX, int maxZ) {
		for (ExistingStructure s : l) {
			if (s.maxX() >= minX && s.minX() <= maxX && s.maxZ() >= minZ && s.minZ() <= maxZ) {
				out.add(s);
			}
		}
	}

	/** The smallest known existing structure covering a block, or null. */
	public ExistingStructure structureAt(int x, int z) {
		ExistingStructure best = null;
		long bestArea = Long.MAX_VALUE;
		for (ExistingStructure s : structuresNear(x, z, x, z)) {
			if (s.contains(x, z)) {
				long area = (long) (s.maxX() - s.minX() + 1) * (s.maxZ() - s.minZ() + 1);
				if (area < bestArea) {
					best = s;
					bestArea = area;
				}
			}
		}
		return best;
	}

	/** True once since the last call if new region data arrived (the map should redraw). */
	public boolean pollChanged() {
		if (changed) {
			changed = false;
			return true;
		}
		return false;
	}

	private short biomeId(String name) {
		synchronized (biomeNames) {
			Short id = biomeIds.get(name);
			if (id == null) {
				id = (short) biomeNames.size();
				biomeNames.add(name);
				biomeIds.put(name, id);
			}
			return id;
		}
	}

	private void loadRegion(int rx, int rz, long k) {
		try {
			if (closed) {
				return;
			}
			Path file = regionDir.resolve("r." + rx + "." + rz + ".mcr");
			NaturalBiomes biomes = seed == null ? null : new NaturalBiomes(seed);
			RegionReader.Raster raster = RegionReader.read(file, biomes, this::biomeId);
			Region region;
			synchronized (live) {
				for (Map.Entry<Long, ChunkColumns> e : live.entrySet()) {
					int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
					if (cx >> 5 == rx && cz >> 5 == rz) {
						write(raster, cx, cz, e.getValue());
					}
				}
				region = new Region(raster, average(raster));
				region.lastUse = clock.incrementAndGet();
				cache.put(k, region);
			}
			structures.put(k, List.copyOf(raster.structures));
			trim();
			changed = true;
		} catch (IOException | RuntimeException e) {
			WorldPainter.LOGGER.debug("Could not preview region {} {}", rx, rz, e);
		} finally {
			pending.remove(k);
		}
	}

	/**
	 * Puts a chunk of the running world (read from memory) on the map: the map then shows the world
	 * as it is now, also before the game saved it.
	 */
	public void putChunk(int cx, int cz, ChunkColumns columns) {
		if (closed) {
			return;
		}
		int rx = cx >> 5, rz = cz >> 5;
		long k = key(rx, rz);
		synchronized (live) {
			live.put(key(cx, cz), columns);
			Region region = cache.get(k);
			if (region == null && !onDisk.contains(k)) {
				// Nothing saved there yet: the region exists only in memory so far.
				region = new Region(new RegionReader.Raster(), 0);
				region.lastUse = clock.incrementAndGet();
				cache.put(k, region);
				onDisk.add(k);
			}
			if (region != null) {
				write(region.raster, cx, cz, columns);
				region.averageColor = average(region.raster);
			}
		}
		changed = true;
	}

	private void write(RegionReader.Raster raster, int cx, int cz, ChunkColumns c) {
		boolean any = false;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int i = z * 16 + x;
				int ri = ((((cz & 31) << 4) + z) << 9) | (((cx & 31) << 4) + x);
				raster.surface[ri] = c.surface()[i];
				raster.floor[ri] = c.floor()[i];
				raster.color[ri] = c.color()[i];
				String b = c.biome() == null ? null : c.biome()[i];
				raster.biome[ri] = b == null ? 0 : biomeId(b);
				any |= c.surface()[i] != UNKNOWN;
			}
		}
		if (any) {
			raster.chunks++;
		}
	}

	private int average(RegionReader.Raster raster) {
		long r = 0, g = 0, b = 0;
		int n = 0;
		for (int z = 0; z < 512; z += 16) {
			for (int x = 0; x < 512; x += 16) {
				int i = (z << 9) | x;
				if (raster.surface[i] == UNKNOWN) {
					continue;
				}
				int c = raster.color[i];
				if (c == 0) {
					c = 0xFF6A8F4A;
				}
				r += (c >> 16) & 255;
				g += (c >> 8) & 255;
				b += c & 255;
				n++;
			}
		}
		if (n == 0) {
			return 0;
		}
		return 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
	}

	private void trim() {
		if (cache.size() <= MAX_CACHED) {
			return;
		}
		List<Map.Entry<Long, Region>> entries = new ArrayList<>(cache.entrySet());
		entries.sort((a, b) -> Long.compare(a.getValue().lastUse, b.getValue().lastUse));
		for (int i = 0; i < entries.size() - MAX_CACHED; i++) {
			cache.remove(entries.get(i).getKey(), entries.get(i).getValue());
		}
	}

	@Override
	public void close() {
		closed = true;
		executor.shutdownNow();
		cache.clear();
	}
}
