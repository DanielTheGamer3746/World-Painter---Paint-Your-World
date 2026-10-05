package com.daniel.worldpainter.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Live changes run the chunk tasks of a temporary dimension themselves (it is not ticked by the server). */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
	@Accessor("mainThreadExecutor")
	BlockableEventLoop<Runnable> worldpainter$mainThreadExecutor();
}
