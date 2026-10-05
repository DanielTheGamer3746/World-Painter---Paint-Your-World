package com.daniel.worldpainter.client.mixin;

import com.daniel.worldpainter.client.WorldPainterClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.util.Arrays;

/** Adds the "World Painter" and "Structure Editor" keys to Controls (saved in options.txt like the other keys). */
@Mixin(GameOptions.class)
public abstract class GameOptionsMixin {
	@Shadow
	public KeyBinding[] allKeys;

	@Shadow
	public abstract void load();

	@Inject(method = "<init>(Lnet/minecraft/client/Minecraft;Ljava/io/File;)V", at = @At("RETURN"))
	private void worldpainter$addKey(Minecraft minecraft, File gameDir, CallbackInfo ci) {
		boolean added = worldpainter$add(WorldPainterClient.OPEN_KEY);
		added |= worldpainter$add(WorldPainterClient.EDITOR_KEY);
		if (added) {
			// The options were read before the keys existed: read them again for their saved keys.
			load();
		}
	}

	@Unique
	private boolean worldpainter$add(KeyBinding key) {
		for (KeyBinding k : allKeys) {
			if (k == key) {
				return false;
			}
		}
		KeyBinding[] keys = Arrays.copyOf(allKeys, allKeys.length + 1);
		keys[keys.length - 1] = key;
		allKeys = keys;
		return true;
	}
}
