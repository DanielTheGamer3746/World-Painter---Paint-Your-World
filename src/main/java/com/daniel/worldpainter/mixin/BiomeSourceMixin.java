package com.daniel.worldpainter.mixin;

import com.daniel.worldpainter.gen.BiomePaint;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Painted biomes. Every biome lookup of a Beta world (generation, grass and leaf colors, weather,
 * mob spawning) goes through {@code getBiomesInArea}, which also leaves the area's temperature and
 * rainfall in two public arrays that the generator and the renderer read next.
 */
@Mixin(BiomeSource.class)
public abstract class BiomeSourceMixin {
	@Shadow
	public double[] temperatureMap;
	@Shadow
	public double[] downfallMap;

	/** The world this biome source belongs to (Beta does not keep it). */
	@Unique
	private World worldpainter$world;

	@Inject(method = "<init>(Lnet/minecraft/world/World;)V", at = @At("RETURN"))
	private void worldpainter$rememberWorld(World world, CallbackInfo ci) {
		worldpainter$world = world;
	}

	@Inject(method = "getBiomesInArea([Lnet/minecraft/world/biome/Biome;IIII)[Lnet/minecraft/world/biome/Biome;", at = @At("RETURN"))
	private void worldpainter$paintBiomes(Biome[] biomes, int x, int z, int width, int depth, CallbackInfoReturnable<Biome[]> cir) {
		if (worldpainter$world != null) {
			BiomePaint.apply(worldpainter$world, cir.getReturnValue(), temperatureMap, downfallMap, x, z, width, depth);
		}
	}

	@Inject(method = "create([DIIII)[D", at = @At("RETURN"))
	private void worldpainter$paintTemperatures(double[] temperatures, int x, int z, int width, int depth, CallbackInfoReturnable<double[]> cir) {
		if (worldpainter$world != null) {
			BiomePaint.applyTemperatures(worldpainter$world, cir.getReturnValue(), x, z, width, depth);
		}
	}
}
