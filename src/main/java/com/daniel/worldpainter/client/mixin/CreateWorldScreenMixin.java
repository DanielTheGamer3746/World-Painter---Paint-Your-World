package com.daniel.worldpainter.client.mixin;

import com.daniel.worldpainter.client.WorldPainterClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A "World Painter" button on the Create World screen: paint the world before it exists. */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin extends Screen {
	@Unique
	private static final int WORLDPAINTER_BUTTON = 4291;

	@Shadow
	private TextFieldWidget worldNameField;
	@Shadow
	private TextFieldWidget seedField;

	/** The typed name and seed, put back when the screen comes back from the painter. */
	@Unique
	private String worldpainter$name;
	@Unique
	private String worldpainter$seed;

	@SuppressWarnings("unchecked")
	@Inject(method = "init()V", at = @At("TAIL"))
	private void worldpainter$addButton(CallbackInfo ci) {
		buttons.add(new ButtonWidget(WORLDPAINTER_BUTTON, 4, height - 24, 96, 20, "World Painter"));
		if (worldpainter$name != null) {
			worldNameField.setText(worldpainter$name);
			seedField.setText(worldpainter$seed);
			worldpainter$name = null;
			worldpainter$seed = null;
		}
	}

	@Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true)
	private void worldpainter$click(ButtonWidget button, CallbackInfo ci) {
		if (button.id == WORLDPAINTER_BUTTON) {
			ci.cancel();
			worldpainter$name = worldNameField.getText();
			worldpainter$seed = seedField.getText();
			WorldPainterClient.openForNewWorld(minecraft, this);
		}
	}
}
