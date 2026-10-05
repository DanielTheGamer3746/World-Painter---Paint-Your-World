package com.daniel.worldpainter.storage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

public final class IoUtil {
	private IoUtil() {
	}

	/** Replaces {@code target} with {@code tmp}, atomically where the file system allows it. */
	public static void replace(Path tmp, Path target) throws IOException {
		try {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public static void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	/** Copies a folder tree (used when a draft is attached to a newly created world). */
	public static void copyRecursively(Path from, Path to) throws IOException {
		try (Stream<Path> walk = Files.walk(from)) {
			for (Path src : walk.toList()) {
				Path dst = to.resolve(from.relativize(src).toString());
				if (Files.isDirectory(src)) {
					Files.createDirectories(dst);
				} else {
					Files.createDirectories(dst.getParent());
					Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}
}
