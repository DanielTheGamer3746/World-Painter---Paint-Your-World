package com.daniel.worldpainter.client.mixin;

import com.daniel.worldpainter.client.DraftSession;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Moves a design painted on the Create World screen into the world folder as the world is created. */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {
	@Inject(method = "createLevelFromExistingSettings", at = @At("HEAD"))
	private void worldpainter$attachDraft(CallbackInfo ci, @Local(argsOnly = true) LevelStorageSource.LevelStorageAccess access) {
		DraftSession.attachTo(access.getLevelPath(LevelResource.ROOT).toAbsolutePath().normalize());
	}
}
