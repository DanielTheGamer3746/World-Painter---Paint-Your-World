package com.daniel.worldpainter.client.existing;

import com.daniel.worldpainter.live.ChunkStore;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Reads the chunks the running world has in memory onto the map, a few per frame: the map shows the
 * world as it is right now (also chunks the game has not saved yet, and chunks just regenerated).
 */
public final class LoadedChunks {
	/** The biome of a column for map colors (painted, else natural), or null. */
	public interface Biomes {
		String at(int x, int z);
	}

	private static final long REFRESH_MILLIS = 15_000;

	private final ExistingWorldLayer layer;
	private final Biomes biomes;
	private final ArrayDeque<Long> priority = new ArrayDeque<>();
	private final Set<Long> queued = new HashSet<>();
	/** Chunks already read (the same chunk object again is skipped until the next refresh). */
	private final Set<Chunk> seen = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
	private long lastRefresh;
	private java.util.List<Chunk> snapshot;
	private int cursor;
	private boolean broken;

	public LoadedChunks(ExistingWorldLayer layer, Biomes biomes) {
		this.layer = layer;
		this.biomes = biomes;
	}

	/** A chunk changed (regenerated, edited): it is read again first. */
	public void changed(int cx, int cz) {
		long k = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
		if (queued.add(k)) {
			priority.add(k);
		}
	}

	/** Reads chunks for at most {@code budgetNanos}. */
	public void scan(World world, long budgetNanos) {
		if (broken || world == null) {
			return;
		}
		long end = System.nanoTime() + budgetNanos;
		long now = System.currentTimeMillis();
		if (now - lastRefresh > REFRESH_MILLIS) {
			// Blocks change while playing too: read everything again now and then.
			lastRefresh = now;
			seen.clear();
		}
		try {
			while (!priority.isEmpty() && System.nanoTime() < end) {
				long k = priority.poll();
				queued.remove(k);
				Chunk c = ChunkStore.loaded(world, (int) (k >> 32), (int) k);
				if (c != null) {
					read(c);
				}
			}
			if (snapshot == null || cursor >= snapshot.size()) {
				// A new round over every chunk in memory.
				snapshot = ChunkStore.all(world);
				cursor = 0;
			}
			while (cursor < snapshot.size() && System.nanoTime() < end) {
				Chunk c = snapshot.get(cursor++);
				if (!seen.contains(c)) {
					read(c);
				}
			}
		} catch (RuntimeException | LinkageError e) {
			// The map then simply shows the saved world.
			broken = true;
			com.daniel.worldpainter.WorldPainter.LOGGER.warn("Could not read loaded chunks for the map", e);
		}
	}

	private void read(Chunk c) {
		seen.add(c);
		short[] surface = new short[256], floor = new short[256];
		int[] color = new int[256];
		String[] names = new String[256];
		Arrays.fill(surface, ExistingWorldLayer.UNKNOWN);
		Arrays.fill(floor, ExistingWorldLayer.UNKNOWN);
		ColumnScan scan = new ColumnScan();
		int bx = c.x << 4, bz = c.z << 4;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int lx = x, lz = z;
				int from = Math.min(127, height(c, lx, lz) + 1);
				String biome = biomes == null ? null : biomes.at(bx + x, bz + z);
				int i = z * 16 + x;
				names[i] = biome;
				if (scan.scan(y -> c.getBlockId(lx, y, lz), from, biome)) {
					surface[i] = (short) scan.surface;
					floor[i] = (short) scan.ground;
					color[i] = scan.color;
				}
			}
		}
		layer.putChunk(c.x, c.z, new ExistingWorldLayer.ChunkColumns(surface, floor, color, names));
	}

	private static int height(Chunk c, int x, int z) {
		int h = c.getHeight(x, z);
		return h <= 0 ? 127 : h;
	}
}
