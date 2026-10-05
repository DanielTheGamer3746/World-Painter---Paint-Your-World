package com.daniel.worldpainter.mixin;

import net.minecraft.world.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.io.IOException;

/**
 * Removes a chunk from a region file the running game has open (live changes): the game's own
 * region file object clears the chunk's entry, so it generates again when the player gets there.
 */
@Mixin(RegionFile.class)
public interface RegionFileAccessor {
	/** The chunk's entry in the region header (0 = not saved). */
	@Invoker("getChunkBlockInfo")
	int worldpainter$location(int x, int z);

	@Invoker("writeChunkBlockInfo")
	void worldpainter$setLocation(int x, int z, int location) throws IOException;
}
