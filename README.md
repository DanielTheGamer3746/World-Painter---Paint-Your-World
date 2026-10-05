# World Painter

**Paint your Minecraft world the way you want it — before you create it, or after.**

World Painter gives you a map of the entire 60,000,000 × 60,000,000 block world where every pixel is one block. Scroll, zoom, and paint biomes, terrain height, surface blocks, water and lava. Then switch to 3D and fly through the real world to paint and sculpt caves, overhangs and floating islands. When you create the world (or load it again), Minecraft generates your design as real terrain, and in a world you're already playing, **painted chunks regenerate in real time while you paint**.

---

## 🎨 Paint the world

- **Biomes:** paint any biome, including biomes from datapacks and mods. Colors on the map match how each biome looks in game (green plains, orange dappled forest, pink cherry grove, sandy desert, red crimson forest...).
- **Terrain height:** raise mountains, carve valleys, and flatten building areas
- **Surface blocks:** choose the top block (sand, snow, grass, stone...)
- **Water and lava:** set lakes, seas and lava pools at any level
- **Templates:** start from a ready-made layout, or save your own and reuse it in every world
- **Edge smoothing:** blend biome borders and height changes so they look natural

### Tools
Brush, eraser, height sculpt, smooth, fill, rectangle, color picker, selection, stamp and 3D sculpt. Brush size and shape are adjustable, and everything has undo and redo.

---

## ⚡ Live changes: real-time chunk regeneration

Paint in a world you're playing and watch it change. With **Live changes** on (the default):

- The world generates from your design **as you paint it**: no saving, no reloading, no leaving the world
- Chunks regenerate while you paint, starting with the ones you're looking at, complete with biomes, terrain, trees, ores and structures
- Undo regenerates the chunks again
- The design saves itself, so there are no Save buttons, just **Done**
- Painted chunks far away regenerate when you get near them

Builds inside painted chunks are replaced by what your design generates there.

---

## ⚙️ Settings

The **gear** button in the top bar opens the settings, where you can turn **Live changes** on or off. With it off, the painter works the classic way: changes stay in the design until you press **Save**, and land that already exists regenerates after **Save & Reload** (with a backup).

The painter's interface follows your game's **GUI scale**: every scale setting in Minecraft gives the same size in World Painter, so buttons and text always match the rest of your game.

---

## 🌋 Overworld, Nether and End

Each dimension has its own design, and one click switches between them. In game, the painter opens on the dimension you're standing in.

| Dimension | What you can paint |
|---|---|
| Overworld | Biomes, height, surface blocks, water and lava, structures, 3D sculpting |
| Nether | Biomes, structures, 3D sculpting (caverns, pillars, floating islands) |
| End | Biomes, surface blocks, structures, 3D sculpting (floating islands in the void) |

---

## 🧭 3D world view

Press **F5** in the painter to switch from the map to the **real world in 3D**. Fly around with a free camera and paint right on the terrain:

- Orbit, pan and zoom, move with WASD, and get top, front and side views
- A small map in the corner shows where you are; click it to fly anywhere
- Every tool works on the block you point at, with its outline drawn on the terrain
- With live changes on, you watch the land change as you paint it

---

## ⛰️ 3D sculpting

Shape the world in 3D, not just from above:

- **Add:** grow overhangs, arches, pillars and bridges out of any surface
- **Carve:** dig caves, tunnels, holes and cliffs
- **Floating islands:** click the ground to place an island above it, every one shaped differently
- **Restore:** undo the 3D shaping and go back to the painted terrain

In the 3D view, sculpting changes the world instantly. When the land regenerates, it gets grass, trees and ores like any other terrain.

---

## 🏰 Structures

- **Place** any structure exactly where you want it: villages, outposts, temples, strongholds, ancient cities, trial chambers, ruined portals, Nether fortresses, bastions, End cities and more. It generates there no matter what the biome is.
- **Remove** structures you don't want, including ones that have already generated in an existing world
- **No-structure zones:** draw areas where vanilla structures can't spawn
- **Vanilla structures on/off:** turn them off to keep only the structures you placed
- Painting biomes **doesn't move or delete structures**. A village stays a village even if you paint a forest over it.

---

## 🧊 3D structure editor

Edit structures right in your world, with a camera that feels like a 3D program such as Blender:

- Orbit, pan and zoom around the structure, with front, side and top views
- Select, place, break and pick blocks, with undo and redo
- **Chest loot:** see and edit the contents, or reroll them from any chest loot table
- **Mob spawners:** change which mob they spawn
- **Stronghold portals:** add or remove eyes of ender. All 12 opens the portal.
- **Ruined portals:** add or remove obsidian, fix crying obsidian, complete the frame, or light it

Press **K** in game while looking at a structure, or open it from the painter's map.

---

## 🌍 Works on new *and* existing worlds

| Where | How |
|---|---|
| Create World screen | **World Painter** button |
| Singleplayer world list | Select a world, then **World Painter** |
| In game | Press **O** to paint (F5 for 3D), **K** for the structure editor |

In an existing world, the areas you paint are **regenerated** with your design: right away with live changes on, or on the next load with them off (after making a **backup** of the affected region files).

---

## 🪨 Beta 1.7.3 (Babric)

World Painter is also available for **Beta 1.7.3** with Babric, adapted to Beta's biomes and its 128-block-high world. It works with or without StationAPI. It has the map, the 3D world view, live changes and a 3D structure editor where you can edit chest items and spawner mobs. Beta's structures are dungeons and lakes: place them anywhere, or find dungeons hidden underground with the editor's **Find a dungeon nearby** and **See in the dark**.

---

## 📋 Good to know

- Painting works in the Overworld, Nether and End of normal (noise-based) worlds, not superflat or dimensions added by mods.
- Minecraft stores biomes in 4×4×4 cells, so biome borders follow a 4-block grid.
- The 3D view and live changes need the world open in singleplayer.
- You can paint and edit in game in singleplayer. Server owners can paint a copy of the world folder from their own game.
- Structures you place need "Generate Structures" turned on.

---

| Mod Loader | Available Versions | Upcoming Versions |
|---|---|---|
| Fabric | 26.3 | 1.21.11 |
| NeoForge | 26.3 | 1.21.1 |
| Forge | 26.3 | 1.20.1 |
| Babric | b1.7.3 | X |

Disclaimer: This mod is an independent fan creation and is not affiliated with, authorized, or endorsed by WorldPainter.