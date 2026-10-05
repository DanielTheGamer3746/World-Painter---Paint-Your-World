package com.daniel.worldpainter.live;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.data.DirtyChunks;
import com.daniel.worldpainter.data.PaintDimension;
import com.daniel.worldpainter.data.PaintWorld;
import com.daniel.worldpainter.gen.PaintBindings;
import com.daniel.worldpainter.mixin.RegionFileAccessor;
import com.daniel.worldpainter.storage.WorldPaths;
import com.daniel.worldpainter.data.BetaBlocks;
import net.minecraft.block.SandBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSource;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraft.world.chunk.storage.RegionIo;
import net.minecraft.world.gen.chunk.OverworldChunkGenerator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Live changes for Beta 1.7.3: the world follows the design while you paint.
 *
 * <p>Beta's singleplayer world runs on the game thread, so this does too, with a time budget per
 * frame. A painted chunk that is loaded is generated again by the world's own generator (with the
 * design, straight from the open painter) and replaces the old chunk in the game's chunk cache;
 * then the decoration (trees, ores, flowers, dungeons, lakes, snow) of the areas around it is done
 * again, but only allowed to change that chunk, because the neighbours' part of it is already there.
 * Replacing the whole chunk object works for Beta's chunks and for StationAPI's. A chunk that is not
 * loaded is removed from the save, so the game generates it with the design when you get there.
 */
public final class LiveRegen {
	private static final int MAX_FAILURES = 3;

	/** Gets every chunk that was regenerated (the painter's map reads it again). */
	public interface Listener {
		void regenerated(int cx, int cz);
	}

	private static boolean enabled = true;
	/** While decorating again: the chunks that may change (all others are left alone). */
	private static Set<Long> mask;
	private static long blockedAt = Long.MIN_VALUE;
	private static int blockedBlock;
	private static boolean decorateWarned;
	private static World world;
	private static Path worldRoot;
	private static final LinkedHashSet<Long> WAITING = new LinkedHashSet<>();
	private static double focusX, focusZ;
	private static boolean hasFocus;
	private static Listener listener;
	private static int done;
	private static int failures;
	private static String failed;

	private LiveRegen() {
	}

	public static void setEnabled(boolean on) {
		enabled = on;
	}

	public static void setListener(Listener l) {
		listener = l;
	}

	/** Chunks near this point regenerate first (the camera, or the middle of the map). */
	public static void setFocus(double x, double z) {
		focusX = x;
		focusZ = z;
		hasFocus = true;
	}

	private static long key(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
	}

	private static int cx(long key) {
		return (int) (key >> 32);
	}

	private static int cz(long key) {
		return (int) key;
	}

	/** True if this chunk must not change right now (see the class comment). */
	public static boolean masked(int cx, int cz) {
		Set<Long> m = mask;
		return m != null && !m.contains(key(cx, cz));
	}

	private static long blockKey(int x, int y, int z) {
		return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFF);
	}

	/** The last block a decoration could not place because its chunk is masked (see {@link #standIn}). */
	public static void blockedWrite(int x, int y, int z, int block) {
		blockedAt = blockKey(x, y, z);
		blockedBlock = block;
	}

	/**
	 * Beta's dungeon places a chest or spawner and fills it right away, without checking it is
	 * there. When that block could not be placed (it belongs to a neighbour that already has its
	 * decoration), the dungeon fills a stand-in that is not in the world instead of failing, and the
	 * rest of the decoration goes on as before. Null when the block was placed normally.
	 */
	public static BlockEntity standIn(int x, int y, int z) {
		if (mask == null || blockedAt != blockKey(x, y, z)) {
			return null;
		}
		if (blockedBlock == BetaBlocks.CHEST) {
			return new ChestBlockEntity();
		}
		if (blockedBlock == BetaBlocks.SPAWNER) {
			return new MobSpawnerBlockEntity();
		}
		return null;
	}

	public static int waitingCount() {
		return WAITING.size();
	}

	/** Short text for the painter's status line ("" when idle). */
	public static String status() {
		if (failed != null) {
			return "Live changes stopped: " + failed;
		}
		if (!WAITING.isEmpty()) {
			return "Regenerating " + WAITING.size() + " chunk" + (WAITING.size() == 1 ? "" : "s") + "...";
		}
		return done > 0 ? "World up to date" : "";
	}

	/**
	 * These chunks of the running world must follow the design now. Loaded chunks are redrawn right
	 * away (grass and leaves take the painted biome's colors at once) and queued to regenerate; the
	 * others are removed from the save.
	 */
	public static void request(World w, DirtyChunks changed) {
		if (w == null || w.isRemote || changed.isEmpty()) {
			return;
		}
		if (world != w) {
			WAITING.clear();
			world = w;
		}
		worldRoot = WorldPainter.worldRoot(w);
		failed = null;
		failures = 0;
		DirtyChunks unloaded = new DirtyChunks();
		ChunkSource source = ChunkStore.source(w);
		changed.forEach((cx, cz) -> {
			if (source.isChunkLoaded(cx, cz)) {
				WAITING.add(key(cx, cz));
				redraw(w, cx, cz);
			} else {
				dropFromMemory(w, cx, cz);
				unloaded.markChunk(cx, cz);
			}
		});
		if (worldRoot != null && !unloaded.isEmpty()) {
			removeFromSave(worldRoot, unloaded);
			forget(worldRoot, unloaded);
		}
	}

	private static void redraw(World w, int cx, int cz) {
		try {
			w.setBlocksDirty(cx << 4, 0, cz << 4, (cx << 4) + 15, 127, (cz << 4) + 15);
		} catch (LinkageError e) {
			// Only the instant recolor is lost; the chunk still regenerates.
		}
	}

	/**
	 * Regenerates waiting chunks (nearest to the focus first) for about {@code budgetNanos}, at least one.
	 * Called every frame by the painter and every game tick.
	 */
	public static void work(World w, Entity player, long budgetNanos) {
		if (!enabled || WAITING.isEmpty() || w == null) {
			return;
		}
		if (w != world) {
			// Another world (or dimension) is open: what was waiting regenerates when the world loads again.
			persistWaiting();
			WAITING.clear();
			return;
		}
		ChunkSource source = ChunkStore.source(w);
		if (!(ChunkStore.generator(w) instanceof OverworldChunkGenerator generator)) {
			WorldPainter.LOGGER.warn("Live changes: unknown chunk cache / generator {}", ChunkStore.describe(w));
			fail("this world's chunks are kept in a way World Painter does not know (see the log)");
			return;
		}
		long end = System.nanoTime() + budgetNanos;
		DirtyChunks handled = new DirtyChunks();
		do {
			long k = next(source, player);
			if (k == Long.MIN_VALUE) {
				break;
			}
			try {
				long started = System.nanoTime();
				regenerate(w, source, generator, cx(k), cz(k));
				handled.markChunk(cx(k), cz(k));
				if (done++ == 0) {
					WorldPainter.LOGGER.info("Live changes: regenerated chunk {} {} in {} ms", cx(k), cz(k), (System.nanoTime() - started) / 1_000_000);
				}
				failures = 0;
				if (listener != null) {
					listener.regenerated(cx(k), cz(k));
				}
			} catch (RuntimeException | LinkageError e) {
				// The chunk stays on the pending list: it regenerates the next time the world loads.
				WorldPainter.LOGGER.error("Live changes could not regenerate chunk {} {}", cx(k), cz(k), e);
				if (++failures >= MAX_FAILURES) {
					fail(e.toString());
				}
				break;
			} finally {
				mask = null;
			}
		} while (System.nanoTime() < end && !WAITING.isEmpty());
		liftOutOfGround(w, player);
		if (worldRoot != null && !handled.isEmpty()) {
			forget(worldRoot, handled);
		}
	}

	private static void fail(String why) {
		failed = why;
		persistWaiting();
		WAITING.clear();
	}

	/** The waiting chunk nearest to the focus (chunks that were unloaded meanwhile leave the queue). */
	private static long next(ChunkSource source, Entity player) {
		double fx = hasFocus ? focusX : player != null ? player.x : 0;
		double fz = hasFocus ? focusZ : player != null ? player.z : 0;
		int pcx = (int) Math.floor(fx) >> 4, pcz = (int) Math.floor(fz) >> 4;
		long best = Long.MIN_VALUE, bestDist = Long.MAX_VALUE;
		Iterator<Long> it = WAITING.iterator();
		while (it.hasNext()) {
			long k = it.next();
			if (!source.isChunkLoaded(cx(k), cz(k))) {
				// Out of reach meanwhile: it stays on the pending list and regenerates when the world loads again.
				it.remove();
				continue;
			}
			long dx = cx(k) - pcx, dz = cz(k) - pcz, d = dx * dx + dz * dz;
			if (d < bestDist) {
				bestDist = d;
				best = k;
			}
		}
		if (best != Long.MIN_VALUE) {
			WAITING.remove(best);
		}
		return best;
	}

	private static void regenerate(World w, ChunkSource cache, OverworldChunkGenerator generator, int cx, int cz) {
		Chunk old = ChunkStore.loaded(w, cx, cz);
		if (old == null) {
			return;
		}
		// 1) Fresh terrain: the generator makes the chunk again (with the design); it replaces the old one.
		Chunk fresh = generator.getChunk(cx, cz);
		replace(w, old, fresh, cx, cz);
		// 2) Decoration of the four areas that cover this chunk, limited to this chunk.
		boolean ownArea = false;
		mask = Set.of(key(cx, cz));
		try {
			for (int ax = cx - 1; ax <= cx; ax++) {
				for (int az = cz - 1; az <= cz; az++) {
					if (cache.isChunkLoaded(ax, az) && cache.isChunkLoaded(ax + 1, az) && cache.isChunkLoaded(ax, az + 1)
							&& cache.isChunkLoaded(ax + 1, az + 1)) {
						try {
							generator.decorate(cache, ax, az);
						} catch (RuntimeException e) {
							// One feature failing (a mod's, say) must not stop the chunk: the rest still updates.
							if (!decorateWarned) {
								decorateWarned = true;
								WorldPainter.LOGGER.warn("Live changes: decorating area {} {} failed (shown once)", ax, az, e);
							}
						}
						ownArea |= ax == cx && az == cz;
					}
				}
			}
		} finally {
			mask = null;
			blockedAt = Long.MIN_VALUE;
			// Beta turns this on while decorating and off at the end; a failure must not leave it on.
			SandBlock.fallInstantly = false;
		}
		// The chunk's own area, if it could not be decorated yet (a neighbour is not loaded), is decorated
		// by the game later, when that neighbour loads, like any new chunk.
		fresh.terrainPopulated = ownArea;
		// 3) Light and redraw.
		int x0 = cx << 4, z0 = cz << 4;
		w.queueLightUpdate(LightType.BLOCK, x0 - 1, 0, z0 - 1, x0 + 16, 127, z0 + 16);
		w.queueLightUpdate(LightType.SKY, x0 - 1, 0, z0 - 1, x0 + 16, 127, z0 + 16);
		redraw(w, cx, cz);
	}

	/**
	 * Puts the fresh chunk in the old one's place in the game's chunk cache. The old chunk's mobs and
	 * items move over; its chests, furnaces and spawners go (the fresh chunk makes its own).
	 */
	private static void replace(World w, Chunk old, Chunk fresh, int cx, int cz) {
		List<BlockEntity> blockEntities = new ArrayList<>();
		for (Object o : old.blockEntities.values()) {
			if (o instanceof BlockEntity be) {
				blockEntities.add(be);
			}
		}
		for (BlockEntity be : blockEntities) {
			w.removeBlockEntity(be.x, be.y, be.z);
		}
		fresh.entities = old.entities;
		fresh.loaded = true;
		// Not decorated again by the game while this pass decorates it (see regenerate).
		fresh.terrainPopulated = true;
		fresh.markDirty();
		if (!ChunkStore.replace(w, old, fresh, cx, cz)) {
			throw new IllegalStateException("chunk " + cx + " " + cz + " is not in the world's chunk cache");
		}
	}

	/** The ground may have risen under the player: put them on top instead of inside it. */
	private static void liftOutOfGround(World w, Entity player) {
		if (player == null || player.noClip) {
			return;
		}
		try {
			if (!player.isInsideWall()) {
				return;
			}
			int x = (int) Math.floor(player.x), z = (int) Math.floor(player.z);
			for (int y = Math.max(1, (int) Math.floor(player.y - player.standingEyeHeight)); y < 127; y++) {
				if (!w.getMaterial(x, y, z).blocksMovement() && !w.getMaterial(x, y + 1, z).blocksMovement()) {
					player.setPosition(player.x, y + player.standingEyeHeight + 0.01, player.z);
					return;
				}
			}
		} catch (LinkageError e) {
			// Not lifted: the player can dig out.
		}
	}

	/**
	 * Beta's older chunk ring keeps chunks in memory after the player walked away from them (until
	 * their place is needed), and saves them then. A chunk about to be removed from the save is let
	 * go of first, so this old copy is not written back.
	 */
	private static void dropFromMemory(World w, int cx, int cz) {
		Chunk stale = ChunkStore.dropUnloaded(w, cx, cz);
		if (stale == null) {
			return;
		}
		try {
			stale.unload();
		} catch (LinkageError e) {
			WorldPainter.LOGGER.warn("Could not unload chunk {} {} before regenerating it", cx, cz, e);
		}
	}

	/**
	 * Removes chunks from the region files through the game's own (open) region file objects, so
	 * they generate again when visited. Returns how many were removed.
	 */
	private static int removeFromSave(Path worldRoot, DirtyChunks chunks) {
		int[] removed = {0};
		chunks.forEach((cx, cz) -> {
			// The game creates missing region files when asked for one: only touch existing ones.
			if (!java.nio.file.Files.isRegularFile(worldRoot.resolve("region").resolve("r." + (cx >> 5) + "." + (cz >> 5) + ".mcr"))) {
				return;
			}
			try {
				RegionFile file = RegionIo.getRegionFile(worldRoot.toFile(), cx, cz);
				if (file == null) {
					return;
				}
				RegionFileAccessor region = (RegionFileAccessor) file;
				if (region.worldpainter$location(cx & 31, cz & 31) != 0) {
					region.worldpainter$setLocation(cx & 31, cz & 31, 0);
					removed[0]++;
				}
			} catch (IOException | RuntimeException | LinkageError e) {
				WorldPainter.LOGGER.warn("Could not remove chunk {} {} from the save", cx, cz, e);
			}
		});
		return removed[0];
	}

	/** Takes handled chunks off the world's pending list (written when the design was saved). */
	private static void forget(Path worldRoot, DirtyChunks chunks) {
		Path file = PaintDimension.OVERWORLD.dir(WorldPaths.paintDir(worldRoot)).resolve(PaintWorld.PENDING_REGEN);
		synchronized (DirtyChunks.FILE_LOCK) {
			try {
				DirtyChunks pending = DirtyChunks.load(file);
				if (pending.isEmpty()) {
					return;
				}
				chunks.forEach(pending::removeChunk);
				if (pending.isEmpty()) {
					java.nio.file.Files.deleteIfExists(file);
				} else {
					pending.save(file);
				}
			} catch (IOException e) {
				WorldPainter.LOGGER.warn("Could not update {}", file, e);
			}
		}
	}

	/** Chunks that could not be regenerated live go back on the world's pending list (they regenerate when it loads). */
	private static void persistWaiting() {
		if (worldRoot == null || WAITING.isEmpty()) {
			return;
		}
		Path file = PaintDimension.OVERWORLD.dir(WorldPaths.paintDir(worldRoot)).resolve(PaintWorld.PENDING_REGEN);
		synchronized (DirtyChunks.FILE_LOCK) {
			try {
				DirtyChunks pending = DirtyChunks.load(file);
				for (long k : WAITING) {
					pending.markChunk(cx(k), cz(k));
				}
				pending.save(file);
			} catch (IOException e) {
				WorldPainter.LOGGER.warn("Could not update {}", file, e);
			}
		}
	}

	/** A world is about to start: what was still waiting in the last one regenerates when it loads again. */
	public static void reset() {
		persistWaiting();
		WAITING.clear();
		world = null;
		worldRoot = null;
		mask = null;
		failed = null;
		hasFocus = false;
		done = 0;
	}
}
