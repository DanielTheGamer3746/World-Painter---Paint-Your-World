package com.daniel.worldpainter.mixin;

import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The world's chunk cache. Read from the field: Beta's client jar only keeps the methods the client
 * itself calls, and the getter is not one of them.
 */
@Mixin(World.class)
public interface WorldAccessor {
	@Accessor("chunkSource")
	ChunkSource worldpainter$chunkSource();
}
