package com.daniel.worldpainter.mixin;

import net.minecraft.world.chunk.ChunkCache;
import net.minecraft.world.chunk.ChunkSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

/**
 * Beta 1.7.3's singleplayer chunk cache: every loaded chunk by position (a map) and in a list (for
 * saving). Live changes put a regenerated chunk in place of the old one here.
 */
@Mixin(ChunkCache.class)
public interface ChunkCacheAccessor {
	@Accessor("generator")
	ChunkSource worldpainter$generator();

	/** Loaded chunks by {@code ChunkPos.hashCode(x, z)}. */
	@SuppressWarnings("rawtypes")
	@Accessor("chunkByPos")
	Map worldpainter$chunkByPos();

	@SuppressWarnings("rawtypes")
	@Accessor("chunks")
	List worldpainter$chunks();
}
