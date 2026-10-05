package com.daniel.worldpainter.client.existing;

/** A structure that already exists in a saved world (read from its start chunk). Bounds are inclusive block X/Z. */
public record ExistingStructure(String id, int chunkX, int chunkZ, int minX, int minZ, int maxX, int maxZ) {
	public boolean contains(int x, int z) {
		return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
	}
}
