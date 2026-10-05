package com.daniel.worldpainter.client.editor;

import com.daniel.worldpainter.data.BetaBlocks;
import net.minecraft.world.World;

/** Finds the first block along a ray (voxel walk), with the face it was hit on. */
public final class BlockRay {
	/** A block that was hit; (nx, ny, nz) points out of the face that was hit. */
	public record Hit(int x, int y, int z, int nx, int ny, int nz) {
		public int placeX() {
			return x + nx;
		}

		public int placeY() {
			return y + ny;
		}

		public int placeZ() {
			return z + nz;
		}
	}

	private BlockRay() {
	}

	/** Plants, torches and the like are looked through; water and lava too unless {@code hitFluids}. */
	public static Hit cast(World world, V3 origin, V3 dir, double maxDistance, boolean hitFluids) {
		return cast(world, origin, dir, maxDistance, hitFluids, false);
	}

	/** {@code hitSmall}: also stop at plants, torches, rails and the like (the structure editor edits them). */
	public static Hit cast(World world, V3 origin, V3 dir, double maxDistance, boolean hitFluids, boolean hitSmall) {
		int x = (int) Math.floor(origin.x()), y = (int) Math.floor(origin.y()), z = (int) Math.floor(origin.z());
		int stepX = dir.x() > 0 ? 1 : -1, stepY = dir.y() > 0 ? 1 : -1, stepZ = dir.z() > 0 ? 1 : -1;
		double tDeltaX = dir.x() == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.x());
		double tDeltaY = dir.y() == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.y());
		double tDeltaZ = dir.z() == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.z());
		double tMaxX = dir.x() == 0 ? Double.MAX_VALUE : ((stepX > 0 ? (x + 1 - origin.x()) : (origin.x() - x)) * tDeltaX);
		double tMaxY = dir.y() == 0 ? Double.MAX_VALUE : ((stepY > 0 ? (y + 1 - origin.y()) : (origin.y() - y)) * tDeltaY);
		double tMaxZ = dir.z() == 0 ? Double.MAX_VALUE : ((stepZ > 0 ? (z + 1 - origin.z()) : (origin.z() - z)) * tDeltaZ);
		double t = 0;
		// The camera may start inside a block (it flies through walls): the starting block is ignored.
		while (t <= maxDistance) {
			int nx = 0, ny = 0, nz = 0;
			if (tMaxX < tMaxY && tMaxX < tMaxZ) {
				x += stepX;
				t = tMaxX;
				tMaxX += tDeltaX;
				nx = -stepX;
			} else if (tMaxY < tMaxZ) {
				y += stepY;
				t = tMaxY;
				tMaxY += tDeltaY;
				ny = -stepY;
			} else {
				z += stepZ;
				t = tMaxZ;
				tMaxZ += tDeltaZ;
				nz = -stepZ;
			}
			if (y < 0 || y > 127) {
				if ((stepY > 0 && y > 127) || (stepY < 0 && y < 0)) {
					return null;
				}
				continue;
			}
			int id = world.getBlockId(x, y, z);
			if (id != 0 && (hitSmall || !BetaBlocks.seeThrough(id)) && (hitFluids || !BetaBlocks.isFluid(id))) {
				return new Hit(x, y, z, nx, ny, nz);
			}
		}
		return null;
	}
}
