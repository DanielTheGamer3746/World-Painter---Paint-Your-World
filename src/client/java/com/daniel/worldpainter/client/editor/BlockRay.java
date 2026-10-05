package com.daniel.worldpainter.client.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Finds the first block along a ray (voxel walk), with the face it was hit on. */
public final class BlockRay {
	public record Hit(BlockPos pos, Direction face) {
		/** Where a block placed on this face goes. */
		public BlockPos placePos() {
			return pos.relative(face);
		}
	}

	private BlockRay() {
	}

	public static Hit cast(Level level, Vec3 origin, Vec3 dir, double maxDistance, boolean hitFluids) {
		int x = (int) Math.floor(origin.x), y = (int) Math.floor(origin.y), z = (int) Math.floor(origin.z);
		int stepX = dir.x > 0 ? 1 : -1, stepY = dir.y > 0 ? 1 : -1, stepZ = dir.z > 0 ? 1 : -1;
		double tDeltaX = dir.x == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.x);
		double tDeltaY = dir.y == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.y);
		double tDeltaZ = dir.z == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.z);
		double tMaxX = dir.x == 0 ? Double.MAX_VALUE : ((stepX > 0 ? (x + 1 - origin.x) : (origin.x - x)) * tDeltaX);
		double tMaxY = dir.y == 0 ? Double.MAX_VALUE : ((stepY > 0 ? (y + 1 - origin.y) : (origin.y - y)) * tDeltaY);
		double tMaxZ = dir.z == 0 ? Double.MAX_VALUE : ((stepZ > 0 ? (z + 1 - origin.z) : (origin.z - z)) * tDeltaZ);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		double t = 0;
		// The camera may start inside a block (it flies through walls): ignore the starting block.
		while (t <= maxDistance) {
			Direction face;
			if (tMaxX < tMaxY && tMaxX < tMaxZ) {
				x += stepX;
				t = tMaxX;
				tMaxX += tDeltaX;
				face = stepX > 0 ? Direction.WEST : Direction.EAST;
			} else if (tMaxY < tMaxZ) {
				y += stepY;
				t = tMaxY;
				tMaxY += tDeltaY;
				face = stepY > 0 ? Direction.DOWN : Direction.UP;
			} else {
				z += stepZ;
				t = tMaxZ;
				tMaxZ += tDeltaZ;
				face = stepZ > 0 ? Direction.NORTH : Direction.SOUTH;
			}
			if (y < level.getMinY() || y > level.getMaxY()) {
				if ((stepY > 0 && y > level.getMaxY()) || (stepY < 0 && y < level.getMinY())) {
					return null;
				}
				continue;
			}
			pos.set(x, y, z);
			BlockState state = level.getBlockState(pos);
			if (!state.isAir() && (hitFluids || !(state.getBlock() instanceof LiquidBlock))) {
				return new Hit(pos.immutable(), face);
			}
		}
		return null;
	}
}
