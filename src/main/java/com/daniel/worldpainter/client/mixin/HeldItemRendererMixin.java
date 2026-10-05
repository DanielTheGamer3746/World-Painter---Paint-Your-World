package com.daniel.worldpainter.client.mixin;

import com.daniel.worldpainter.client.editor.Camera3D;
import net.minecraft.client.render.item.HeldItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the player is the camera of the 3D view or the structure editor (flying through the ground),
 * the held item and the "inside a block" / underwater overlays are not drawn: they would cover the view.
 */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
	@Inject(method = "render(F)V", at = @At("HEAD"), cancellable = true)
	private void worldpainter$hideHand(float delta, CallbackInfo ci) {
		if (Camera3D.viewing()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderScreenOverlays(F)V", at = @At("HEAD"), cancellable = true)
	private void worldpainter$hideOverlays(float delta, CallbackInfo ci) {
		if (Camera3D.viewing()) {
			ci.cancel();
		}
	}
}
