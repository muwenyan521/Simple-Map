# Simple Map

Simple Map focuses on readable terrain, fast client-side exploration, practical navigation tools, and an optional Map Book system for sharing explored regions without turning the interface into a separate game.

> **Development status:** active development. The current codebase is a major rewrite of the original public version, with a new chunk-stream scanner, cave-layer mapping, texture-aware colour processing, and a reworked rendering pipeline.

> **Architecture checkpoint:** V17.8 / M4 Region LOD authority. Surface far-zoom coverage can now be projected directly from region source data, published through a durable 8×8 hierarchy, and rendered as a coarse underlay before exact pages finish. The older factor-2 LOD tree remains temporarily as a quality/refinement adapter until the M5 page-table renderer replaces both paths.

> **PASS99 loaded/loading checkpoint:** minimap Surface/Cave now keep retained root coverage behind refinement and gate front/back FBO handoff like Xaero's loaded/loading grids. Cave branch work uses foreground/background dirty lanes rather than viewport queue sorting, and projection/page styling removes several high-rate transient allocations.

---

## Highlights

- Full-screen world map with smooth pan, zoom-to-cursor, pins, waypoints, block information, biome information, and optional teleport tools.
- Square or circular minimap with rotatable map/player modes, configurable compass letters, coordinates, pointer styles, colours, size, zoom, and screen position.
- Surface and cave mapping with automatic roof detection, manual Top-Y layers, and a full cave-network view when enabled by the server.
- Two block-colour pipelines: **Accurate** texture/tint-aware rendering and **Vanilla** map colours.
- Optional 2D/3D terrain relief, biome tinting, water/depth shading, colour profiles, and per-block colour overrides.
- Chunk-priority scanning, asynchronous region loading, background texture preparation, bounded GPU uploads, and LRU caches.
- Map Books can save, update, copy, merge, and transfer explored map regions between players.

---

## World Map

Press `M` to open the map. The key can be changed in Minecraft's Controls menu.

### Navigation and map interaction

- Click and drag to pan.
- Scroll to zoom toward the mouse cursor.
- Left-click to place or remove the active navigation pin.
- Right-click to open the context menu:
  - add or delete a waypoint;
  - follow or stop following a waypoint;
  - teleport to the selected position when the player has permission;
  - override the displayed colour of the selected block.
- Hover the map to inspect coordinates, block height, block identity, and optionally the biome.
- Click the player-coordinate panel in the bottom-right to copy a message such as `I am at coordinates [X, Y, Z]` and open chat with it pre-filled.
- Shift-click the refresh icon to re-centre the map on the player and restore `1.0x` zoom.

### Map toolbar

The compact left toolbar provides:

- waypoint visibility;
- refresh / rescan;
- surface night or cave-light mode;
- map and minimap settings.

The cave control appears separately at the bottom-left when cave mapping is available.

---

## Minimap

The minimap is rendered from the same explored region data as the full-screen map.

- Square and circular shapes.
- Rotating-map or rotating-player-pointer modes.
- Configurable size from 16 to 150 GUI pixels.
- Independent minimap zoom.
- Draggable, anchor-based placement that remains stable across resolution and GUI-scale changes.
- Optional coordinates with independent scale and position.
- Arrow-only or skin-plus-arrow player marker.
- Configurable player pointer, pin, coordinate text, compass letters, and minimap ring colours.
- Optional compass letters: `N`, `E`, `S`, and `W`.
- Optional rendering over inventory, pause, chat, and container screens.
- Navigation pins clamp to the minimap edge and continue showing distance to the destination.
- Pins may clear automatically when the player comes within five blocks of the target.

---

## Cave Mapping

Cave mapping is controlled by the server because it can reveal underground terrain.

| Server mode | Behaviour |
|---|---|
| `OFF` | Surface map only. |
| `AUTO` | Automatically switches to a layered cave map when a solid roof is detected above the player, including ceiling dimensions such as the Nether. |
| `ON` | Enables automatic detection plus player controls for `OFF`, `LAYERED`, `FULL`, and manual Top-Y selection. |

### Layered mode

- Scans a bounded vertical band below the selected Top-Y value.
- Automatic Top-Y follows the player's elevation in stable bands.
- Manual Top-Y can be selected with the map slider when the server uses `ON` mode.
- Layer changes are debounced to avoid rapid switching while moving on stairs or near a layer boundary.

### Full cave view

- Builds a wider underground projection from discovered cave columns.
- Keeps surface and cave data separate.
- During layer changes, the previous cave layer remains visible until replacement regions are ready; otherwise an unloaded region stays black instead of briefly flashing the surface map.

The light button works independently in cave mode:

- `OFF`: full brightness;
- `AUTO`: readable darkening, including the Nether;
- `ON`: stronger cave darkening.

---

## Colour and Terrain Rendering

### Colour modes

| Mode | Description |
|---|---|
| `ACCURATE` | Samples block/model textures and applies biome-aware tint policies where appropriate. Intended for detailed vanilla and modded terrain. |
| `VANILLA` | Uses Minecraft's built-in map-colour system for a simpler, familiar result. |

Additional rendering options include:

- biome-aware grass, foliage, and water tinting;
- transparent-leaf and fluid handling;
- terrain slopes: `OFF`, `2D`, or `3D`;
- map profiles: `Balanced`, `Vibrant`, `Natural`, and `Contrast`;
- optional flower/detail display;
- saved custom colour palette;
- per-block colour overrides from the map context menu.

The override system is also a fallback for blocks whose model or tint behaviour cannot be inferred reliably.

---

## Waypoints and Pins

- Right-click an empty map position to add a waypoint.
- Choose waypoint icons from categorized vanilla item groups or the searchable A-Z browser.
- Right-click an existing waypoint to follow it, delete it, or stop following it.
- Left-click anywhere to create a temporary navigation pin.
- The full map and minimap both display direction and live distance.
- Waypoint visibility, waypoint scale, pin scale, and automatic pin clearing are configurable.

---

## Map Book System

Map Books provide an optional progression and multiplayer sharing layer.

### Items

| Item | Purpose |
|---|---|
| **Empty Map Book** | Saves the player's currently explored region files into a new written Map Book. |
| **Map Book** | Stores a server-side map ID, owner information, and references to saved explored regions. |

The Empty Map Book recipe is shapeless:

- Book
- Compass
- Empty Map

### Workflow

1. **Create:** Right-click an Empty Map Book to save explored regions and create an owned Map Book.
2. **Open:** The owner can right-click the written book to open the map.
3. **Update:** The owner can Shift + right-click to upload newly explored regions to the same book.
4. **Learn:** A non-owner can right-click a shared copy to merge its regions into local map data. Learning is a one-time use and consumes that copy.
5. **Copy:** Craft one written Map Book with one Empty Map Book to produce two copies of the written book.
6. **Merge:** Craft two valid written Map Books together to create a new merged Map Book containing regions from both sources.

When `requireMapBook` is enabled, a player must have learned map data and carry a valid Map Book to scan or open the map.

---

## Configuration

### Client configuration

Client settings are stored in:

```text
.minecraft/simplemap/config.json
```

The in-map settings screen is divided into four tabs:

- **Map:** minimap state, size, shape, zoom, rotation, pointer style, pointer scale, pin scale, and display in other screens.
- **Coords:** coordinate visibility and scale, plus cursor-biome information.
- **Colors:** UI colour targets, presets, custom hexadecimal colours, saved palette entries, and map colour profile.
- **System:** chunk-stream mapping, scan budget, always-rescan mode, pin behaviour, Accurate/Vanilla colour mode, flowers, and terrain relief.

`Scan: AUTO` is recommended for modern systems. Manual values represent an approximate loaded-chunk budget per client tick rather than random dots.

### Server configuration

Server settings are stored in:

```text
config/simplemap-server.json
```

```json
{
  "requireMapBook": false,
  "caveMapMode": 0
}
```

- `requireMapBook`: requires learned map data and a valid written Map Book in the player's inventory.
- `caveMapMode`: `0 = OFF`, `1 = AUTO`, `2 = ON`.

In singleplayer or an integrated server, these settings can also be edited through the mod's Config screen. On a remote server, clients only see the server-selected values.

---

## Controls

| Action | Default input |
|---|---|
| Open world map | `M` |
| Pan map | Left-click and drag |
| Zoom | Mouse wheel |
| Place or remove navigation pin | Left-click without dragging |
| Open map context menu | Right-click |
| Refresh visible loaded chunks | Refresh toolbar button |
| Reset centre and zoom | Shift + refresh toolbar button |
| Cycle night / cave light | Sun/moon toolbar button |
| Cycle cave view | Cave button, when server mode is `ON` |
| Select automatic or manual Top-Y | Cave-layer slider |
| Open settings | Gear toolbar button |
| Save/update Map Book | Right-click / Shift + right-click the corresponding book |

---

## Performance Architecture

Simple Map performs world sampling on the client and uses the server only for policy synchronization and Map Book transfer/storage.

The current mapping pipeline includes:

- loaded-chunk priority queues instead of the old random-dot scanner;
- immediate handling of newly streamed chunks near the player;
- centre-first viewport scanning while the full map is open;
- separate surface, layered-cave, full-cave, and light-region caches;
- asynchronous region-file loading and saving;
- background CPU texture construction;
- coalesced dirty-region revisions to prevent redundant rebuilds;
- bounded texture publication per frame to reduce upload spikes;
- batched region rendering with a single flush per map pass;
- LRU limits for CPU region caches and GPU textures;
- cached data preloading when panning across previously explored regions.

### Performance settings

- Keep **Always Rescan** disabled unless frequently changing terrain must update continuously.
- Use **Scan: AUTO** for the fastest deadline-bounded scan rate.
- Lower the scan budget on slower CPUs if exploration causes frame-time spikes.
- Full cave mapping is more expensive than surface or layered cave mapping because it projects a larger vertical volume.

---

## Installation

1. Install Minecraft `1.21.1`.
2. Install a compatible NeoForge `21.1.x` build.
3. Place the Simple Map `.jar` file in the Minecraft `mods` directory.
4. Launch the game and bind the map key if `M` conflicts with another mod.
5. For multiplayer, install the mod on both the server and participating clients when using Map Books or server-controlled cave mapping.

Java 21 is required by Minecraft 1.21.1 and the development toolchain.

---

## Building from Source

```bash
./gradlew build
```

On Windows:

```powershell
.\gradlew.bat build
```

The project uses:

- Java 21;
- NeoForge ModDevGradle;
- Parchment mappings for Minecraft 1.21.1.

Development run configurations:

```bash
./gradlew runClient
./gradlew runServer
```

---

## Notes and Limitations

- The map can only sample brand-new terrain after the Minecraft client has received the corresponding chunks.
- Previously stored region data may still be displayed while those chunks are no longer loaded.
- Cave mapping can expose underground structures and should be enabled according to the server's intended gameplay rules.
- Complex modded renderers, generated models, or unusual tint systems may require a manual block-colour override.
- The project is still being profiled and refined; performance reports should include the dimension, map mode, scan setting, render distance, and relevant crash report.

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

Copyright © 2026 Velorise.

