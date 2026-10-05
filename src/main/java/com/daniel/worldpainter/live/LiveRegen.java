package com.daniel.worldpainter.live;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.data.DirtyChunks;
import com.daniel.worldpainter.data.PaintDimension;
import com.daniel.worldpainter.data.PaintWorld;
import com.daniel.worldpainter.gen.ChunkBiomes;
import com.daniel.worldpainter.gen.Dimensions;
import com.daniel.worldpainter.mixin.ChunkMapAccessor;
import com.daniel.worldpainter.storage.IoUtil;
import com.daniel.worldpainter.storage.WorldPaths;
import com.google.common.collect.ImmutableList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * "Live changes": painted chunks of the running world are regenerated in place, without leaving the
 * world. Server thread only.
 *
 * <p>Chunks are generated the normal way (biomes, terrain, caves, decorations, structures, with the
 * design applied by the usual hooks) in a temporary copy of the dimension that uses the same chunk
 * generator, in a scratch folder. The result is then copied block by block into the real chunks, with
 * biomes and the contents of chests and spawners. The work is spread over ticks so the game keeps
 * running. Chunks that are not loaded wait: they regenerate when a player comes near, or (if the game
 * is closed first) from the pending list the next time the world loads, as without live changes.
 */
public final class LiveRegen {
	/** Chunks regenerated together in one temporary dimension. */
	private static final int MAX_BATCH = 16;
	/** Time each step may take on the server thread. */
	private static final long STEP_NANOS = 12_000_000L;
	/** Two steps closer than this are one (the game client and the server tick both drive the work). */
	private static final long MIN_STEP_GAP_NANOS = 45_000_000L;
	private static final long JOB_TIMEOUT_NANOS = 120_000_000_000L;
	/** Block changes are sent to players, without updating neighbors (no sand falling, no water spreading). */
	private static final int FLAGS = 2 | 16;

	private static volatile boolean enabled = true;
	private static volatile String status = "";
	private static volatile String failure;
	private static volatile int waitingCount;

	private static final Map<ResourceKey<Level>, LinkedHashSet<Long>> WAITING = new HashMap<>();
	private static final Set<ResourceKey<Level>> ADOPTED = new HashSet<>();
	private static final List<Path> TRASH = new ArrayList<>();
	private static Job job;
	private static long lastStep;
	private static long lastScan;

	private LiveRegen() {
	}

	// ------------------------------------------------------------------ settings and status (any thread)

	public static void setEnabled(boolean on) {
		enabled = on;
	}

	public static boolean enabled() {
		return enabled;
	}

	/** One line for the painter's status bar, or "" when there is nothing to tell. */
	public static String status() {
		String f = failure;
		return f != null ? f : status;
	}

	/** Set when regenerating failed; painted chunks then regenerate the next time the world loads. */
	public static boolean failed() {
		return failure != null;
	}

	public static int waitingCount() {
		return waitingCount;
	}

	// ------------------------------------------------------------------ requests (server thread)

	/**
	 * The painter saved a change: these chunks of {@code dimension} regenerate (the loaded ones in a
	 * moment, the others when they load). The first request of a dimension also picks up chunks that
	 * were painted earlier and are still waiting in the design's pending list.
	 */
	public static void request(MinecraftServer server, ResourceKey<Level> dimension, DirtyChunks chunks) {
		LinkedHashSet<Long> waiting = WAITING.computeIfAbsent(dimension, k -> new LinkedHashSet<>());
		if (ADOPTED.add(dimension)) {
			Path file = pendingFile(server, dimension);
			if (file != null) {
				synchronized (DirtyChunks.FILE_LOCK) {
					try {
						DirtyChunks.load(file).forEach((cx, cz) -> waiting.add(key(cx, cz)));
					} catch (IOException e) {
						WorldPainter.LOGGER.warn("Could not read {}", file, e);
					}
				}
			}
		}
		chunks.forEach((cx, cz) -> {
			long k = key(cx, cz);
			waiting.add(k);
			if (job != null && job.dimension.equals(dimension) && job.chunks.contains(k)) {
				// Changed again while it regenerates: it regenerates once more afterwards.
				job.again.add(k);
			}
		});
		lastScan = 0;
		updateCounts();
	}

	// ------------------------------------------------------------------ the work (server thread)

	/** Does a slice of the work. Called every server tick, and by the painter while the game is paused. */
	public static void step(MinecraftServer server) {
		long now = System.nanoTime();
		if (now - lastStep < MIN_STEP_GAP_NANOS) {
			return;
		}
		lastStep = now;
		emptyTrash();
		if (job == null && (!enabled || failure != null || WAITING.isEmpty())) {
			return;
		}
		long deadline = now + STEP_NANOS;
		try {
			if (job == null) {
				startJob(server, now);
			}
			if (job != null) {
				runJob(server, deadline);
			}
		} catch (Throwable t) {
			WorldPainter.LOGGER.error("World Painter could not regenerate painted chunks live", t);
			failure = "Live changes failed (see the log). Painted chunks regenerate the next time the world loads.";
			abortJob();
		}
	}

	/** The server stops: everything temporary is closed and deleted. */
	public static void stop() {
		abortJob();
		WAITING.clear();
		ADOPTED.clear();
		failure = null;
		status = "";
		waitingCount = 0;
		emptyTrash();
	}

	private static void startJob(MinecraftServer server, long now) throws IOException {
		if (now - lastScan < 500_000_000L) {
			return;
		}
		lastScan = now;
		for (Map.Entry<ResourceKey<Level>, LinkedHashSet<Long>> e : WAITING.entrySet()) {
			ServerLevel level = server.getLevel(e.getKey());
			if (level == null || e.getValue().isEmpty()) {
				continue;
			}
			List<Long> batch = loadedWaiting(level, e.getValue());
			if (batch.isEmpty()) {
				continue;
			}
			batch.forEach(e.getValue()::remove);
			job = Job.start(server, level, batch);
			updateCounts();
			return;
		}
	}

	/** Up to {@link #MAX_BATCH} waiting chunks that are loaded now, nearest to players first. */
	private static List<Long> loadedWaiting(ServerLevel level, LinkedHashSet<Long> waiting) {
		List<Long> batch = new ArrayList<>();
		List<ServerPlayer> players = level.players();
		int view = level.getServer().getPlayerList().getViewDistance() + 1;
		for (ServerPlayer p : players) {
			int pcx = (int) Math.floor(p.getX()) >> 4, pcz = (int) Math.floor(p.getZ()) >> 4;
			for (int r = 0; r <= view && batch.size() < MAX_BATCH; r++) {
				for (int dz = -r; dz <= r && batch.size() < MAX_BATCH; dz++) {
					for (int dx = -r; dx <= r && batch.size() < MAX_BATCH; dx++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
							continue;
						}
						long k = key(pcx + dx, pcz + dz);
						if (waiting.contains(k) && !batch.contains(k) && loaded(level, pcx + dx, pcz + dz) != null) {
							batch.add(k);
						}
					}
				}
			}
		}
		if (batch.size() < MAX_BATCH && waiting.size() <= 4096) {
			// Chunks kept loaded without a player nearby (spawn chunks, force-loaded chunks).
			for (Iterator<Long> it = waiting.iterator(); it.hasNext() && batch.size() < MAX_BATCH; ) {
				long k = it.next();
				if (!batch.contains(k) && loaded(level, cx(k), cz(k)) != null) {
					batch.add(k);
				}
			}
		}
		return batch;
	}

	private static void runJob(MinecraftServer server, long deadline) throws IOException {
		Job j = job;
		if (System.nanoTime() - j.started > JOB_TIMEOUT_NANOS) {
			throw new IllegalStateException("Regenerating " + j.chunks.size() + " chunks took too long");
		}
		if (!j.generated()) {
			// Let the temporary dimension's chunk tasks run until they are done or the time is up.
			((ChunkMapAccessor) j.temp.getChunkSource().chunkMap).worldpainter$mainThreadExecutor()
					.managedBlock(() -> j.generated() || System.nanoTime() > deadline);
			if (!j.generated()) {
				status = "Live changes: generating " + j.chunks.size() + " chunk" + (j.chunks.size() == 1 ? "" : "s") + "...";
				return;
			}
		}
		while (j.next < j.chunks.size() && System.nanoTime() < deadline) {
			long k = j.chunks.get(j.next++);
			ChunkAccess fresh = j.result(k);
			LevelChunk live = loaded(j.level, cx(k), cz(k));
			if (fresh != null && live != null) {
				copy(j.level, fresh, live);
			} else if (fresh == null) {
				throw new IllegalStateException("A chunk could not be generated");
			} else {
				// Unloaded meanwhile: it regenerates when it loads again.
				j.again.add(k);
			}
		}
		if (j.next < j.chunks.size()) {
			status = "Live changes: updating chunks...";
			return;
		}
		finish(server, j);
	}

	private static void finish(MinecraftServer server, Job j) throws IOException {
		job = null;
		try {
			List<Long> done = new ArrayList<>();
			LinkedHashSet<Long> waiting = WAITING.computeIfAbsent(j.dimension, k -> new LinkedHashSet<>());
			for (long k : j.chunks) {
				if (j.again.contains(k)) {
					waiting.add(k);
				} else {
					done.add(k);
				}
			}
			// Regenerated: they no longer need regenerating when the world loads next time.
			Path file = pendingFile(server, j.dimension);
			if (file != null && !done.isEmpty()) {
				synchronized (DirtyChunks.FILE_LOCK) {
					if (Files.isRegularFile(file)) {
						DirtyChunks pending = DirtyChunks.load(file);
						for (long k : done) {
							pending.removeChunk(cx(k), cz(k));
						}
						if (pending.isEmpty()) {
							Files.deleteIfExists(file);
						} else {
							pending.save(file);
						}
					}
				}
			}
		} finally {
			j.close();
			updateCounts();
		}
	}

	private static void abortJob() {
		Job j = job;
		job = null;
		if (j != null) {
			WAITING.computeIfAbsent(j.dimension, k -> new LinkedHashSet<>()).addAll(j.chunks);
			j.close();
		}
	}

	/** Makes the real chunk like the freshly generated one: blocks, chests and spawners, biomes. */
	private static void copy(ServerLevel level, ChunkAccess fresh, LevelChunk live) {
		int minX = fresh.getPos().getMinBlockX(), minZ = fresh.getPos().getMinBlockZ();
		int minY = live.getMinY();
		int maxY = minY + live.getHeight() - 1;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		List<BlockPos> withData = new ArrayList<>();
		for (int y0 = minY; y0 <= maxY; y0 += 16) {
			LevelChunkSection from = fresh.getSection(fresh.getSectionIndex(y0));
			LevelChunkSection to = live.getSection(live.getSectionIndex(y0));
			if (from.hasOnlyAir() && to.hasOnlyAir()) {
				continue;
			}
			for (int ly = 0; ly < 16; ly++) {
				for (int lz = 0; lz < 16; lz++) {
					for (int lx = 0; lx < 16; lx++) {
						BlockState want = from.getBlockState(lx, ly, lz);
						BlockState have = to.getBlockState(lx, ly, lz);
						pos.set(minX + lx, y0 + ly, minZ + lz);
						if (want != have) {
							level.setBlock(pos, want, FLAGS);
						}
						if (want.hasBlockEntity()) {
							withData.add(pos.immutable());
						}
					}
				}
			}
		}
		for (BlockPos p : withData) {
			copyBlockEntity(level, fresh, p);
		}
		BiomeResolverCopy biomes = new BiomeResolverCopy(fresh);
		if (ChunkBiomes.fill(live, biomes::get)) {
			live.markUnsaved();
			level.getChunkSource().chunkMap.resendBiomesForChunks(List.of(live));
		}
	}

	/** Loot chests, spawners and the like get the contents the generated chunk gave them. */
	private static void copyBlockEntity(ServerLevel level, ChunkAccess fresh, BlockPos p) {
		BlockEntity from = fresh.getBlockEntity(p);
		BlockEntity to = level.getBlockEntity(p);
		if (from == null || to == null) {
			return;
		}
		try {
			TagValueOutput out = TagValueOutput.createWithContext(new ProblemReporter.Collector(), level.registryAccess());
			from.saveWithId(out);
			CompoundTag tag = out.buildResult();
			to.loadWithComponents(TagValueInput.create(new ProblemReporter.Collector(), level.registryAccess(), tag));
			to.setChanged();
		} catch (RuntimeException e) {
			WorldPainter.LOGGER.debug("Could not copy the contents of a block at {}", p, e);
		}
	}

	private record BiomeResolverCopy(ChunkAccess from) {
		net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> get(int qx, int qy, int qz) {
			return ChunkBiomes.read(from, qx, qy, qz);
		}
	}

	/** The loaded, fully generated chunk at a position, or null. */
	private static LevelChunk loaded(ServerLevel level, int cx, int cz) {
		return level.getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk c ? c : null;
	}

	private static Path pendingFile(MinecraftServer server, ResourceKey<Level> dimension) {
		PaintDimension d = Dimensions.of(dimension);
		if (d == null) {
			return null;
		}
		return d.dir(WorldPaths.paintDir(WorldPainter.worldRoot(server))).resolve(PaintWorld.PENDING_REGEN);
	}

	private static void updateCounts() {
		int n = 0;
		for (LinkedHashSet<Long> s : WAITING.values()) {
			n += s.size();
		}
		waitingCount = n;
		Job j = job;
		if (j != null) {
			status = "Live changes: generating " + j.chunks.size() + " chunk" + (j.chunks.size() == 1 ? "" : "s") + "...";
		} else if (n > 0) {
			status = n + " painted chunk" + (n == 1 ? "" : "s") + " regenerate when you get near";
		} else {
			status = "";
		}
	}

	private static void emptyTrash() {
		for (Iterator<Path> it = TRASH.iterator(); it.hasNext(); ) {
			try {
				IoUtil.deleteRecursively(it.next());
				it.remove();
			} catch (IOException e) {
				// Files still in use: tried again next time.
			}
		}
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

	/** One batch of chunks generated in a temporary copy of the dimension. */
	private static final class Job {
		final ServerLevel level;
		final ResourceKey<Level> dimension;
		final List<Long> chunks;
		final Set<Long> again = new HashSet<>();
		final long started = System.nanoTime();
		final Path dir;
		final LevelStorageSource.LevelStorageAccess access;
		final ServerLevel temp;
		final Map<Long, CompletableFuture<ChunkAccess>> futures = new HashMap<>();
		int next;

		private Job(ServerLevel level, List<Long> chunks, Path dir, LevelStorageSource.LevelStorageAccess access, ServerLevel temp) {
			this.level = level;
			this.dimension = level.dimension();
			this.chunks = chunks;
			this.dir = dir;
			this.access = access;
			this.temp = temp;
		}

		static Job start(MinecraftServer server, ServerLevel level, List<Long> chunks) throws IOException {
			Path dir = Files.createTempDirectory("worldpainter-live");
			LevelStorageSource.LevelStorageAccess access = null;
			try {
				access = LevelStorageSource.createDefault(dir).createAccess("regen");
				// Same generator object as the real dimension, so the painted design applies to it.
				ServerLevel temp = new ServerLevel(server, Util.backgroundExecutor(), access, (ServerLevelData) level.getLevelData(),
						level.dimension(), new LevelStem(level.dimensionTypeRegistration(), level.getChunkSource().getGenerator()),
						level.isDebug(), level.getSeed(), ImmutableList.of(), false);
				Job j = new Job(level, chunks, dir, access, temp);
				for (long k : chunks) {
					j.futures.put(k, temp.getChunkSource().getChunkFuture(cx(k), cz(k), ChunkStatus.FEATURES, true)
							.thenApply(r -> r.orElse(null)));
				}
				return j;
			} catch (Exception e) {
				if (access != null) {
					try {
						access.close();
					} catch (IOException ignored) {
						// Deleted with the folder.
					}
				}
				TRASH.add(dir);
				throw e instanceof IOException io ? io : new IOException("Could not create the temporary dimension", e);
			}
		}

		boolean generated() {
			for (CompletableFuture<ChunkAccess> f : futures.values()) {
				if (!f.isDone()) {
					return false;
				}
			}
			return true;
		}

		ChunkAccess result(long k) {
			CompletableFuture<ChunkAccess> f = futures.get(k);
			return f == null || f.isCompletedExceptionally() ? null : f.getNow(null);
		}

		void close() {
			try {
				temp.close();
			} catch (Exception e) {
				WorldPainter.LOGGER.debug("Closing the temporary dimension", e);
			}
			try {
				access.close();
			} catch (IOException e) {
				WorldPainter.LOGGER.debug("Closing the temporary folder", e);
			}
			TRASH.add(dir);
		}
	}
}
