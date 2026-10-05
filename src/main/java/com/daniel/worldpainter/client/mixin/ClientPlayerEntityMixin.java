package com.daniel.worldpainter.client.mixin;

import com.daniel.worldpainter.client.WorldPainterClient;
import net.minecraft.entity.player.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Beta hands every key press made while playing (no screen open) to the player: the painter's key is seen here. */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {
	@Inject(method = "updateKey", at = @At("HEAD"))
	private void worldpainter$key(int key, boolean pressed, CallbackInfo ci) {
		if (pressed) {
			WorldPainterClient.keyPressed(key);
		}
	}
}
