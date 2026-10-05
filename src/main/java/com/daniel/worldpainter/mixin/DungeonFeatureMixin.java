package com.daniel.worldpainter.mixin;

import com.daniel.worldpainter.live.LiveRegen;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.DungeonFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Beta's dungeon fills its chest and spawner right after placing them, without checking they are
 * there. While live changes decorate a regenerated chunk again, a chest or spawner of a neighbour
 * chunk is not placed (see {@link LiveRegen#standIn}); the dungeon then fills a stand-in instead of
 * failing.
 */
@Mixin(DungeonFeature.class)
public abstract class DungeonFeatureMixin {
	@Redirect(method = "generate", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/World;getBlockEntity(III)Lnet/minecraft/block/entity/BlockEntity;"))
	private BlockEntity worldpainter$standIn(World world, int x, int y, int z) {
		BlockEntity standIn = LiveRegen.standIn(x, y, z);
		return standIn != null ? standIn : world.getBlockEntity(x, y, z);
	}
}
