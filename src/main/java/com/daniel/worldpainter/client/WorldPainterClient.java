package com.daniel.worldpainter.client;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.client.mixin.MinecraftAccessor;
import com.daniel.worldpainter.client.screen.InfoScreen;
import com.daniel.worldpainter.client.screen.PainterScreen;
import com.daniel.worldpainter.client.screen.StructureEditorScreen;
import com.daniel.worldpainter.data.PaintDimension;
import com.daniel.worldpainter.live.LiveRegen;
import com.daniel.worldpainter.storage.WorldPaths;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public class WorldPainterClient implements ClientModInitializer {
	/** Opens the painter in game (O by default, changeable in Controls). */
	public static final KeyBinding OPEN_KEY = new KeyBinding("World Painter", Keyboard.KEY_O);
	/** Opens the structure editor where you look (K by default). */
	public static final KeyBinding EDITOR_KEY = new KeyBinding("Structure Editor", Keyboard.KEY_K);
	/** Time per game tick for chunks still waiting to regenerate after live painting. */
	private static final long TICK_BUDGET = 8_000_000L;
	private static boolean openRequested;
	private static boolean editorRequested;

	@Override
	public void onInitializeClient() {
		// Reads the settings, so live changes start in the right state.
		Settings.liveChanges();
	}

	public static Minecraft mc() {
		return (Minecraft) FabricLoader.getInstance().getGameInstance();
	}

	/** The game folder (.minecraft), where the game keeps its saves. */
	public static Path gameDir() {
		File dir = ((MinecraftAccessor) mc()).worldpainter$runDirectory();
		Path path = dir != null ? dir.toPath() : FabricLoader.getInstance().getGameDir();
		return path.toAbsolutePath().normalize();
	}

	/** The folder of a singleplayer world by its folder name. */
	public static Path worldFolder(String folderName) {
		return gameDir().resolve("saves").resolve(folderName).toAbsolutePath().normalize();
	}

	/** A key went down while playing (no screen open). */
	public static void keyPressed(int key) {
		if (key == OPEN_KEY.code) {
			openRequested = true;
		} else if (key == EDITOR_KEY.code) {
			editorRequested = true;
		}
	}

	/** End of every game tick (also while a screen pauses the game). */
	public static void tick(Minecraft mc) {
		if (openRequested) {
			openRequested = false;
			if (mc.currentScreen == null && mc.world != null) {
				openForRunningWorld(mc);
			}
		}
		if (editorRequested) {
			editorRequested = false;
			if (mc.currentScreen == null && mc.world != null && mc.player != null) {
				openEditor(mc);
			}
		}
		if (mc.world != null && mc.world.dimension != null && mc.world.dimension.id == 0 && LiveRegen.waitingCount() > 0) {
			// Chunks painted live that still wait (the painter was closed meanwhile) regenerate near the player first.
			if (!(mc.currentScreen instanceof PainterScreen) && mc.player != null) {
				LiveRegen.setFocus(mc.player.x, mc.player.z);
			}
			LiveRegen.work(mc.world, mc.player, TICK_BUDGET);
		}
	}

	/** A singleplayer world is about to load (a new one is created first): attach a draft, regenerate painted chunks. */
	public static void beforeWorldStarts(String folderName) {
		Path root = worldFolder(folderName);
		if (!Files.isRegularFile(root.resolve("level.dat"))) {
			DraftSession.attachTo(root);
		}
		LiveRegen.reset();
		WorldPainter.beforeWorldLoads(root);
	}

	public static void openForNewWorld(Minecraft mc, Screen createWorldScreen) {
		mc.setScreen(new PainterScreen(new PainterTarget(PainterTarget.Kind.NEW_WORLD, DraftSession.draftDir(),
				PaintDimension.OVERWORLD, null, null, "New world", createWorldScreen)));
	}

	public static void openForSavedWorld(Minecraft mc, Screen worldList, String folderName, String displayName) {
		Path root = worldFolder(folderName);
		mc.setScreen(new PainterScreen(new PainterTarget(PainterTarget.Kind.SAVED_WORLD, WorldPaths.paintDir(root),
				PaintDimension.OVERWORLD, root, folderName, displayName, worldList)));
	}

	/** The structure editor in the world you are playing, looking where you look. */
	public static void openEditor(Minecraft mc) {
		if (mc.world.isRemote) {
			mc.setScreen(new InfoScreen(null, "Structure Editor",
					"The structure editor can only change singleplayer worlds."));
			return;
		}
		mc.setScreen(new StructureEditorScreen(null, null, StructureEditorScreen.fromPlayer(mc)));
	}

	public static void openForRunningWorld(Minecraft mc) {
		if (mc.world == null || mc.world.isRemote) {
			mc.setScreen(new InfoScreen(null, "World Painter",
					"World Painter can only edit singleplayer worlds.",
					"On a server, the owner can paint the world folder from their own game."));
			return;
		}
		Path root = WorldPainter.worldRoot(mc.world);
		if (root == null) {
			mc.setScreen(new InfoScreen(null, "World Painter", "This world has no folder to keep a design in."));
			return;
		}
		String folderName = root.getFileName().toString();
		String name = folderName;
		mc.setScreen(new PainterScreen(new PainterTarget(PainterTarget.Kind.RUNNING_WORLD, WorldPaths.paintDir(root),
				PaintDimension.OVERWORLD, root, folderName, name, null)));
	}
}
