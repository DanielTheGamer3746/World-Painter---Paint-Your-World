package com.daniel.worldpainter.client.mixin;

import com.daniel.worldpainter.client.WorldPainterClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	/**
	 * Every singleplayer world starts here (from the world list, or created on the Create World
	 * screen), before its folder is opened: the moment to attach a draft design and to remove
	 * painted-over chunks so they regenerate.
	 */
	@Inject(method = "startGame", at = @At("HEAD"))
	private void worldpainter$beforeWorldStarts(String folderName, String worldName, long seed, CallbackInfo ci) {
		WorldPainterClient.beforeWorldStarts(folderName);
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void worldpainter$tick(CallbackInfo ci) {
		WorldPainterClient.tick((Minecraft) (Object) this);
	}
}
