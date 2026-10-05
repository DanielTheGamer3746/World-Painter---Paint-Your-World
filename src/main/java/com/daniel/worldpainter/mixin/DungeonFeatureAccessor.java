package com.daniel.worldpainter.mixin;

import net.minecraft.item.ItemStack;
import net.minecraft.world.gen.feature.DungeonFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Random;

/** Beta's own dungeon loot and spawner mob choice, for dungeons the player placed. */
@Mixin(DungeonFeature.class)
public interface DungeonFeatureAccessor {
	@Invoker("getRandomChestItem")
	ItemStack worldpainter$chestItem(Random random);

	@Invoker("getRandomEntity")
	String worldpainter$mob(Random random);
}
