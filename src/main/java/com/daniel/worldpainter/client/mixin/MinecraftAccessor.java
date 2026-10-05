package com.daniel.worldpainter.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.io.File;

/** The game folder (.minecraft) the game keeps its saves in. */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
	@Accessor("runDirectory")
	File worldpainter$runDirectory();
}
