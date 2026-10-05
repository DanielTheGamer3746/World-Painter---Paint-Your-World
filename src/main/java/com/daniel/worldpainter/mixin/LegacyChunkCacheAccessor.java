package com.daniel.worldpainter.mixin;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSource;
import net.minecraft.world.chunk.LegacyChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The singleplayer world's chunk cache: its generator regenerates chunks for live changes, and a
 * chunk that is still in memory but out of reach is dropped before it is removed from the save.
 */
@Mixin(LegacyChunkCache.class)
public interface LegacyChunkCacheAccessor {
	@Accessor("generator")
	ChunkSource worldpainter$generator();

	/** The chunks in memory: a 32 x 32 ring indexed {@code (x & 31) + (z & 31) * 32}. */
	@Accessor("chunks")
	Chunk[] worldpainter$chunks();

	@Accessor("cachedChunk")
	Chunk worldpainter$cachedChunk();

	@Accessor("cachedChunk")
	void worldpainter$setCachedChunk(Chunk chunk);
}
