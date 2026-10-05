package com.daniel.worldpainter.gen;

import com.daniel.worldpainter.data.BetaBlocks;
import com.daniel.worldpainter.mixin.DungeonFeatureAccessor;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.block.material.Material;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.DungeonFeature;

import java.util.Random;

/**
 * Builds a Beta dungeon where the player placed one. Beta's own dungeon feature only builds next to
 * a cave (it needs one to five openings in the walls), so a placed dungeon uses the same room, walls,
 * spawner and chest loot as Beta, but is built wherever it was placed. Walls only replace solid ground:
 * a cave crossing the room still opens into it, as in Beta.
 */
public final class DungeonBuilder {
	private static final int HEIGHT = 3;

	private DungeonBuilder() {
	}

	/** Y of the highest ground block (not leaves, plants or fluids), at least 1. */
	public static int groundY(World world, int x, int z) {
		for (int y = 127; y > 0; y--) {
			int id = world.getBlockId(x, y, z);
			if (id == 0 || id == BetaBlocks.LEAVES || BetaBlocks.isFluid(id)) {
				continue;
			}
			Material m = world.getMaterial(x, y, z);
			if (m != null && m.blocksMovement()) {
				return y;
			}
		}
		return 1;
	}

	/** {@code mob} is "Zombie", "Skeleton", "Spider", or null for Beta's random choice. */
	public static void build(World world, Random random, int x, int y, int z, String mob) {
		int rx = random.nextInt(2) + 2;
		int rz = random.nextInt(2) + 2;
		// Beta's room: air inside (4 high), cobblestone walls and a mostly mossy floor where the ground
		// is solid, nothing hanging over air below; the ceiling stays natural stone.
		for (int bx = x - rx - 1; bx <= x + rx + 1; bx++) {
			for (int by = y + HEIGHT; by >= y - 1; by--) {
				for (int bz = z - rz - 1; bz <= z + rz + 1; bz++) {
					boolean inside = bx != x - rx - 1 && by != y - 1 && bz != z - rz - 1 && bx != x + rx + 1
							&& by != y + HEIGHT + 1 && bz != z + rz + 1;
					if (inside) {
						world.setBlock(bx, by, bz, 0);
					} else if (by >= 0 && !world.getMaterial(bx, by - 1, bz).isSolid()) {
						world.setBlock(bx, by, bz, 0);
					} else if (world.getMaterial(bx, by, bz).isSolid()) {
						world.setBlock(bx, by, bz, by == y - 1 && random.nextInt(4) != 0
								? BetaBlocks.MOSSY_COBBLESTONE : BetaBlocks.COBBLESTONE);
					}
				}
			}
		}

		DungeonFeature vanilla = new DungeonFeature();
		DungeonFeatureAccessor loot = (DungeonFeatureAccessor) vanilla;
		for (int chest = 0; chest < 2; chest++) {
			for (int attempt = 0; attempt < 3; attempt++) {
				int cx = x + random.nextInt(rx * 2 + 1) - rx;
				int cz = z + random.nextInt(rz * 2 + 1) - rz;
				if (world.getBlockId(cx, y, cz) != 0 || walls(world, cx, y, cz) != 1) {
					continue;
				}
				world.setBlock(cx, y, cz, BetaBlocks.CHEST);
				BlockEntity be = world.getBlockEntity(cx, y, cz);
				if (be instanceof ChestBlockEntity c) {
					for (int i = 0; i < 8; i++) {
						ItemStack item = loot.worldpainter$chestItem(random);
						if (item != null) {
							c.setStack(random.nextInt(c.size()), item);
						}
					}
				}
				break;
			}
		}

		world.setBlock(x, y, z, BetaBlocks.SPAWNER);
		BlockEntity be = world.getBlockEntity(x, y, z);
		if (be instanceof MobSpawnerBlockEntity spawner) {
			spawner.setSpawnedEntityId(mob != null ? mob : loot.worldpainter$mob(random));
		}
	}

	/** Solid blocks next to a floor block (a chest goes against exactly one wall, as in Beta). */
	private static int walls(World world, int x, int y, int z) {
		int n = 0;
		if (world.getMaterial(x - 1, y, z).isSolid()) {
			n++;
		}
		if (world.getMaterial(x + 1, y, z).isSolid()) {
			n++;
		}
		if (world.getMaterial(x, y, z - 1).isSolid()) {
			n++;
		}
		if (world.getMaterial(x, y, z + 1).isSolid()) {
			n++;
		}
		return n;
	}
}
