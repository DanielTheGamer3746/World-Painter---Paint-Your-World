package com.daniel.worldpainter.live;

import com.daniel.worldpainter.mixin.ChunkCacheAccessor;
import com.daniel.worldpainter.mixin.LegacyChunkCacheAccessor;
import com.daniel.worldpainter.mixin.WorldAccessor;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkCache;
import net.minecraft.world.chunk.ChunkSource;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.LegacyChunkCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Where the running world keeps its loaded chunks. Beta 1.7.3's singleplayer world uses a chunk
 * cache with a map of every loaded chunk ({@link ChunkCache}); the older ring of 32 x 32 chunks
 * around the player ({@link LegacyChunkCache}) is supported too.
 */
public final class ChunkStore {
	private ChunkStore() {
	}

	public static ChunkSource source(World world) {
		return world == null ? null : ((WorldAccessor) world).worldpainter$chunkSource();
	}

	/** The world's chunk generator, or null if its chunk cache is not one Beta has. */
	public static ChunkSource generator(World world) {
		ChunkSource source = source(world);
		if (source instanceof ChunkCache) {
			return ((ChunkCacheAccessor) source).worldpainter$generator();
		}
		if (source instanceof LegacyChunkCache) {
			return ((LegacyChunkCacheAccessor) source).worldpainter$generator();
		}
		return null;
	}

	/** For the "live changes stopped" message: which chunk cache and generator the world has. */
	public static String describe(World world) {
		ChunkSource source = source(world);
		ChunkSource generator = generator(world);
		return (source == null ? "none" : source.getClass().getName()) + " / " + (generator == null ? "none" : generator.getClass().getName());
	}

	/** A loaded chunk (null if that chunk is not in memory). */
	public static Chunk loaded(World world, int cx, int cz) {
		ChunkSource source = source(world);
		if (source == null || !source.isChunkLoaded(cx, cz)) {
			return null;
		}
		Chunk c = source.getChunk(cx, cz);
		return c == null || c instanceof EmptyChunk || c.x != cx || c.z != cz ? null : c;
	}

	/** Every chunk in memory right now (a copy). */
	public static List<Chunk> all(World world) {
		ChunkSource source = source(world);
		List<Chunk> out = new ArrayList<>();
		if (source instanceof ChunkCache) {
			for (Object o : ((ChunkCacheAccessor) source).worldpainter$chunks()) {
				if (o instanceof Chunk c && !(c instanceof EmptyChunk)) {
					out.add(c);
				}
			}
		} else if (source instanceof LegacyChunkCache) {
			Chunk[] ring = ((LegacyChunkCacheAccessor) source).worldpainter$chunks();
			if (ring != null) {
				for (Chunk c : ring) {
					if (c != null && !(c instanceof EmptyChunk)) {
						out.add(c);
					}
				}
			}
		}
		return out;
	}

	/** Puts a regenerated chunk in place of the old one, so the world, the renderer and saving use it. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static boolean replace(World world, Chunk old, Chunk fresh, int cx, int cz) {
		ChunkSource source = source(world);
		if (source instanceof ChunkCache) {
			ChunkCacheAccessor cache = (ChunkCacheAccessor) source;
			Map map = cache.worldpainter$chunkByPos();
			Object key = null;
			for (Object o : map.entrySet()) {
				Map.Entry e = (Map.Entry) o;
				if (e.getValue() == old) {
					key = e.getKey();
					break;
				}
			}
			if (key == null) {
				return false;
			}
			map.put(key, fresh);
			List list = cache.worldpainter$chunks();
			int i = indexOf(list, old);
			if (i >= 0) {
				list.set(i, fresh);
			} else {
				list.add(fresh);
			}
			return true;
		}
		if (source instanceof LegacyChunkCache) {
			LegacyChunkCacheAccessor ring = (LegacyChunkCacheAccessor) source;
			Chunk[] chunks = ring.worldpainter$chunks();
			chunks[(cx & 31) + (cz & 31) * 32] = fresh;
			if (ring.worldpainter$cachedChunk() == old) {
				ring.worldpainter$setCachedChunk(fresh);
			}
			return true;
		}
		return false;
	}

	private static int indexOf(List<?> list, Object o) {
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i) == o) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Lets go of a chunk without saving it (it is about to be removed from the save and generated
	 * again). Only Beta's older ring keeps chunks that count as not loaded.
	 */
	public static Chunk dropUnloaded(World world, int cx, int cz) {
		ChunkSource source = source(world);
		if (!(source instanceof LegacyChunkCache)) {
			return null;
		}
		LegacyChunkCacheAccessor cache = (LegacyChunkCacheAccessor) source;
		Chunk[] ring = cache.worldpainter$chunks();
		if (ring == null) {
			return null;
		}
		int slot = (cx & 31) + (cz & 31) * 32;
		Chunk stale = ring[slot];
		if (stale == null || stale.x != cx || stale.z != cz) {
			return null;
		}
		ring[slot] = null;
		if (cache.worldpainter$cachedChunk() == stale) {
			cache.worldpainter$setCachedChunk(null);
		}
		return stale;
	}
}
