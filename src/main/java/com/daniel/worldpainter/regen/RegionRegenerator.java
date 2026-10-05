package com.daniel.worldpainter.regen;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.data.DirtyChunks;
import com.daniel.worldpainter.data.PaintWorld;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes painted-over chunks of an existing world generate again. Before a world loads, every
 * affected region file is copied to {@code worldpainter/backups/<time>/} and the painted chunks are
 * removed from it (their entries in the region header are cleared). Minecraft then generates those
 * chunks fresh, using the painted design.
 */
public final class RegionRegenerator {
	private static final List<String> STORAGE_FOLDERS = List.of("region", "entities", "poi");

	private RegionRegenerator() {
	}

	/**
	 * Clears the chunks listed in a dimension's {@code pending_regen.bin} from that dimension's region
	 * files. Returns how many chunks were removed. Deletes the pending list when done.
	 */
	public static int processPending(Path worldRoot, Path paintDir, ResourceKey<Level> dimension) throws IOException {
		Path pendingFile = paintDir.resolve(PaintWorld.PENDING_REGEN);
		if (!Files.isRegularFile(pendingFile)) {
			return 0;
		}
		DirtyChunks dirty = DirtyChunks.load(pendingFile);
		Path dimensionDir = DimensionType.getStorageFolder(dimension, worldRoot);
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
		Path backupRoot = paintDir.resolve("backups").resolve(stamp);

		// Group by region file: 32x32 chunks, one bit per chunk.
		Map<Long, long[]> byRegion = new HashMap<>();
		dirty.forEach((cx, cz) -> {
			long key = ((long) (cx >> 5) << 32) | ((cz >> 5) & 0xFFFFFFFFL);
			long[] bits = byRegion.computeIfAbsent(key, k -> new long[16]);
			int idx = (cx & 31) + ((cz & 31) << 5);
			bits[idx >> 6] |= 1L << (idx & 63);
		});

		int removed = 0;
		for (String folder : STORAGE_FOLDERS) {
			Path dir = dimensionDir.resolve(folder);
			if (!Files.isDirectory(dir)) {
				continue;
			}
			for (Map.Entry<Long, long[]> e : byRegion.entrySet()) {
				int rx = (int) (e.getKey() >> 32);
				int rz = (int) (long) e.getKey();
				Path file = dir.resolve("r." + rx + "." + rz + ".mca");
				int n = clearChunks(file, rx, rz, e.getValue(), backupRoot.resolve(folder));
				if (folder.equals("region")) {
					removed += n;
				}
			}
		}
		Files.delete(pendingFile);
		if (removed > 0) {
			WorldPainter.LOGGER.info("Backed up changed region files to {}", backupRoot);
		}
		return removed;
	}

	private static int clearChunks(Path file, int rx, int rz, long[] bits, Path backupDir) throws IOException {
		if (!Files.isRegularFile(file) || Files.size(file) < 8192) {
			return 0;
		}
		int cleared = 0;
		boolean backedUp = false;
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
			for (int idx = 0; idx < 1024; idx++) {
				if ((bits[idx >> 6] & (1L << (idx & 63))) == 0) {
					continue;
				}
				raf.seek(idx * 4L);
				int location = raf.readInt();
				if (location == 0) {
					continue;
				}
				if (!backedUp) {
					Files.createDirectories(backupDir);
					Files.copy(file, backupDir.resolve(file.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
					backedUp = true;
				}
				raf.seek(idx * 4L);
				raf.writeInt(0);
				raf.seek(4096 + idx * 4L);
				raf.writeInt(0);
				cleared++;
				// Oversized chunks are stored next to the region file.
				int cx = (rx << 5) + (idx & 31);
				int cz = (rz << 5) + (idx >> 5);
				Files.deleteIfExists(file.resolveSibling("c." + cx + "." + cz + ".mcc"));
			}
		}
		return cleared;
	}
}
