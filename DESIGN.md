# Advanced Storage

## Confirmed decisions

- Target game: **Hytale**, not Minecraft. Refined Storage is the inspiration.
- Theme: medieval wizard library, dark timber, aged bronze and turquoise runes.
- Develop with English names. Portuguese localization comes later.
- Inventory thumbnails must be 64 × 64 RGBA PNGs, matching the installed
  game's native item-icon atlas. Keep larger previews outside the asset pack.
- Preserve the user's 32-unit Hytale scale. The current Seal Archive model is
  32 × 33 × 32 including the upper frame.
- **Seal Archive has exactly 4 slots in a horizontal row.** Updated by the
  user on 2026-10-31. Do not replace this with a conventional chest capacity.
- The native item container persists exactly four seals. A Java 25 runtime
  provides network discovery, portable contents and Archive/Catalog/Core UI.
- All pages use Hytale's Common.ui containers, labels, fields and buttons.
- Archive: exactly four horizontal writable seal slots on the left. Usage,
  capacity, installed seal count and network state appear as text on the right.
  There are no spacer slots or informational item stacks.
- Core: a live functional network schematic, with Core -> Conduit bus -> Archive
  and Catalog branches, thumbnails and reachable counts. No item grids or transfers.
- Catalog: search, Alphabetical/Quantity controls, network storage, player inventory,
  hotbar and optional backpack share one Common.ui page. No Open Storage button or
  intermediate settings screen. Search filters the actual storage grid immediately.
- The Catalog registers a native window as the authoritative network inventory,
  and maps explicit ItemGrid pick/release/drop/cancel events to native InventoryUtils
  transactions. Each ItemGrid.InventorySectionId must use its real native inventory
  ID (registered Catalog window, -2 storage, -1 hotbar, -9 backpack). Logical keys
  belong only in the event envelope; assigning them to client grids breaks drags.
  Source metadata can contain native IDs and is checked against the captured source.
  A complete Dropped envelope may arrive without SlotClicking: resolve its native
  source against the displayed snapshot and revalidate the actual stack before moving.
  Ignore immediately repeated terminal callbacks; permit subsequent independent drags.
  Catalog snapshots refresh once per grid instead of rescanning for every slot.
  Idle page reconciliation runs every 75 ms; transfers themselves run on the event.
- Selection snapshots retain metadata/durability/quality and never remove items
  into a temporary cursor container. Reject stale sources, changed destinations,
  invalid indices and repeated terminal callbacks. Closing cancels selection safely.
- Do not replace mounted Slots during a drag. Idle updates send changed grids only.
  Search and sorting cancel selection before rebuilding the grid. Validate Catalog
  presence, player world and distance before commands and periodic refreshes.
- Alphabetical uses English display names; Quantity uses aggregate counts,
  descending. Remember each player's search and sorting selection. Stored totals
  are shown by the page-owned StorageTooltip, authorized by the user to replace
  the sticky native tooltip only inside this mod's interfaces. ItemGrid InfoDisplay
  is None only in the mod UI files. Reuse native tooltip textures, type sizes,
  quality colors and spacing. The tooltip is a child of StorageRoot; dismissing
  the page destroys it. Never patch the game's global tooltips or native files.
  Archive dimensions equal its visible rows plus the native title and padding;
  reserve a notice row only for actual errors, and backpack space only when equipped.
  Update dimensions using setObject(selector + ".Anchor", Anchor), preserving width.
  Nested selectors such as .Anchor.Height are rejected by the installed client.
- Interaction hints use `server.interactionHints.openDoor`, matching native chests
  and including the user's actual interaction key (normally F).
- Old eight-slot containers migrate on open. Preserve all populated seals;
  return excess seals to the player's inventory, or wait if it cannot hold them.
- The Seal Archive must not require a supporting block below it. Removing the
  floor must leave the archive and its contents in place; free placement is allowed.
- Export rotates the prop 180° around Y so the drawer side faces the player
  on placement. The original Blockbench source remains in its authored orientation.

## Blocks and items

| English name | Future Portuguese localization | Role |
| --- | --- | --- |
| Arcane Core | Núcleo Arcano | Activates and coordinates the storage network |
| Seal Archive | Arquivo de Selos | Holds storage components; 4 horizontal slots |
| Runic Catalog | Catálogo Rúnico | Deposit, withdraw and search items |
| Runic Conduit | Condutor Rúnico | Connects network blocks |
| Archive Anchor | Âncora do Acervo | Links the library to remote access |
| Traveler's Sigil | Sigilo do Viajante | Portable access to the linked archive |

## Storage Seals

- Confirmed capacities count **individual items**, not stacks or item types:

| Tier | Item capacity | Rune color |
| --- | ---: | --- |
| 1 | 1,000 | Turquoise |
| 2 | 4,000 | Green |
| 3 | 16,000 | Blue |
| 4 | 64,000 | Violet |
| 5 | 128,000 | Gold |

- The initial appearance stage is complete. The user now requested the main
  functionality: seals store real items in versioned BSON ItemStack metadata.
- Exactly four seal slots per Seal Archive. Seals have MaxStack=1, so each
  component occupies one slot. Insertion accepts seals only; nested seals are rejected.
- Source of truth: `config/storage_seals.json`. Capacity/tier tags and tooltips
  are generated from it with `tools/prepare_storage_seals.py`.
- Appearance: parchment scroll, bronze clasps, colored rune and one to five
  readable tier marks; editable `.bbmodel` files live under `modelos/seals/`.
- No crafting recipes or upgrades are defined yet; do not invent resource costs.

The remote-access upgrade uses ancient magical ruins and restored relics.
Bronze repairs and a recurring rune connect those relics visually to the library.

## Arcane Core prototype

- Source: `modelos/corrected/ArcaneCore - Textured.bbmodel`, preserving all 136
  authored cuboids, pivots and groups. Physical dimensions are 32 × 54 × 32.
- Stone base, bronze cage/supports, copper sockets and a fullbright turquoise crystal.
- In-game ID: `AdvancedStorage_Arcane_Core`; inventory icon is RGBA 64 × 64.
- Four horizontal conduit sockets at Y=16, recognized via the namespaced block
  tag `AdvancedStorageRole=Core` and `AdvancedStorage_Core` TagPattern.
- No top/bottom socket: the tall crystal does not represent a vertical port.
- At least one connected Core activates a network. No fuel cost is defined.

## Runic Catalog prototype

- Source: `modelos/corrected/RunicCatalog - Textured.bbmodel`, preserving all 46
  authored pieces and the inclined book. Eight pieces are zero-thickness planes.
- Export planes as native Hytale quads, with two-dimensional size, a normal,
  front texture layout and double-sided rendering; do not add artificial thickness.
- Dark timber, bronze fittings, leather cover, parchment pages and navy cloth
  with turquoise runes. Inventory icon is RGBA 64 × 64.
- In-game ID: `AdvancedStorage_Runic_Catalog`. It is a freely placeable visual
  block with an integrated inventory/search page using the game's Common.ui styles.
- The user's update adds 15 socket pieces on local north, east and west, centered
  at Y=16 and reaching the block boundaries. The banner's south face is not a port.
- Preserve the existing book, banner and wood texture exactly. Append copper
  socket textures to the atlas with `tools/update_catalog_sockets.py`.
- Conduits recognize those three ports via `AdvancedStorageRole=Catalog` and
  the `AdvancedStorage_Catalog` TagPattern; native checks cover all four yaws
  and both placement orders, including rejection of the banner face.

## Runic Conduit prototype

- Copper fittings and glass reveal turquoise energy inside the pipe.
- Preserve the authored mounting height: the straight pipe occupies Y=12..20
  and is centered at Y=16, matching the Seal Archive's side sockets.
- The in-game conduit automatically selects and rotates models across all six
  directions, including vertical elbows and non-planar junctions.
- Glass uses alpha blending. The energy texture is static and fullbright;
  network transfer is functional, while animated pulses remain future work.
- Both glass and energy faces must export `textureLayout.*.transparent=true`;
  PNG alpha and the block's `RequiresAlphaBlending` flag alone are insufficient.

### Connection design

- Reuse the conduit geometry as a center junction plus six optional arms.
- Connections depend on compatible neighboring conduits and network sockets,
  including the Seal Archive. Never connect indiscriminately to arbitrary blocks.
- Generate visual variants from shared segments (straight, elbow, T, crossings,
  vertical and terminal shapes), rather than requiring manually authored models
  for every combination of the six directions.
- Hytale's native `Patterned` connected-block assets now choose the six-direction
  visual shape. The server traverses reciprocal physical sockets for item transfers.
- Isolated and single-neighbor conduits use the full straight model.
- Eight conduit IDs advertise a namespaced compatibility tag on all six
  faces, independently of displayed shape, to avoid update oscillation.
- Only compatible mod blocks connect; vanilla pipes and ordinary solid blocks
  are excluded. The Seal Archive is recognized on all six faces.
- Identify archives through the namespaced block tag `AdvancedStorageRole=Archive`
  and the `AdvancedStorage_Archive` TagPattern, not an exact base block ID. Opening
  and closing the container creates generated state IDs that must remain compatible.
- Native regression coverage includes all three archive interaction states,
  both placement orders, all six sides and a physical floor-removal check.

### Vertical models

- Preserve all three user-authored sources. Correct their UVs and materials in
  `modelos/corrected/` without altering geometry or pivots.
- `T-cima` has four physical exits (east, south, west, up); `T-cima-baixo`
  has five (east, south, west, up, down); `all` has all six.
- Rotate the authored planar models for vertical straights, elbows, planar Ts
  and planar crosses. Rotate the new models for non-planar four/five/six-way joints.
- Derive a three-axis corner (south, west, up) from `T-cima`, removing its east
  arm and adding a matching closed copper wall. This is a generated additional
  variant, not an edit to any of the user's originals.
- Native integration checks cover all 64 neighbor combinations, all 24 cube
  orientations, compatible archives, incompatible blocks and live neighbor updates.

### Visual testing before further model edits

- Add the user's corrected curve, T and four-connection models as separate,
  manually placed test items. Do not change the authored geometry or textures.
- Item IDs: `AdvancedStorage_Runic_Conduit_Curve`,
  `AdvancedStorage_Runic_Conduit_T`, `AdvancedStorage_Runic_Conduit_Cross`.
- The user approved the textures and requested automatic connections. Keep the
  approved copper covering and models intact. The existing test IDs now also
  auto-connect and remain compatible with already placed blocks.

## Archive Transmitter prototype

- `AdvancedStorage_Archive_Transmitter` uses the authored 174-element Hytale-textured model, its native scale, model collision boxes and a 64 × 64 inventory thumbnail.
- Ports are on North, East, South and West only. The conduit patterned rule set recognizes `AdvancedStorageRole=Transmitter`; runtime network discovery uses the same four-port mask and preserves the Transmitter identity in connected block counts.
- This stage provides placement and network connections for in-world testing. No remote-access interaction or crafting recipe has been added yet.
- Transmitter collision refinement: base footprint is exactly 1 × 1 block, height 27/32; mast uses its stepped dimensions with a half-unit horizontal margin. Each of the eight radial arms uses segments no longer than four model units: nine-unit shaft thickness and eleven-unit crystal-tip thickness. This gives a small selection margin without the broad empty diagonal boxes produced by the original per-piece bounds. Total: 104 boxes.
- Interaction/selection revision: `InteractionHitboxType` now references `AdvancedStorage_Archive_Transmitter_Interaction`, a base-only 1 × 1 footprint, height 27/32. Physical collision retains the 104 fitted boxes, including padded antenna arms. This avoids using the full antenna span as the interaction target; antenna tips are deliberately outside the selectable region. Client outline behavior still needs in-game confirmation.
- In-game result: the base-only InteractionHitboxType did not change the visible black selection outline. The client log confirms that the updated pack was loaded at 20:02 on 2026-11-02, after the 20:01 installation. Do not treat InteractionHitboxType as a verified outline control. User preference is pending between a multipart block implementation and base-only physical collision; do not silently remove antenna collision.

## Multipart Transmitter (current implementation)

The previous InteractionHitboxType-only experiment is superseded. The exporter retains the authored Blockbench source and splits its root groups into three linked block assets: base at y, mast at y+1, antenna assembly at y+2. Root node translations compensate for these offsets, preserving all 174 authored elements and their world transforms exactly.

- Base collision/outline extent: 1 × 0.84375 × 1 blocks.
- Mast collision/outline extent: 0.53125 × 2.3125 × 0.53125 blocks.
- Antenna collision/outline extent: 3 × 0.34375 × 3 blocks, located at antenna height.
- Native placement consumes the single visible base item. Placement checks loaded, unoccupied cells for the mast, antenna and padded filler footprint first. Queued assembly verifies successful native base placement; failed assembly removes its owned parts and refunds a consumed item.
- Technical helper items are hidden from normal categories and cannot be independently placed. Helper IDs and fixed offsets persist ownership without a transient lookup table.
- Player breaks and environment breaks remove only the structure's owned parts/fillers and spawn one base item. Concurrent break requests are deduplicated. Native drop lists are Empty to avoid duplicate drops.
- Only the base is a network node, with four horizontal conduit ports. Helpers do not add storage/network nodes.
- Existing old single-model Transmitters must be broken and placed again after upgrading. Their owned filler cells are cleared during dismantling.

Validation: 49 checks passed inside an isolated Hytale 0.7.5 server, covering four yaw rotations, assembly/dismantling, occupied-space rejection, preservation of foreign blocks, filler cleanup, simultaneous part breaks yielding one spawned item, environment removal and saved structure ownership after server restart. Exported part roots reconstruct the complete source exactly. The installed JAR matches the verified build byte for byte. Native placement gestures and visual contours still require confirmation in the game client.

## Astrolabe antenna redesign (current)

The active source is now `modelos/ArchiveTransmitter - Astrolabe.bbmodel` (58 elements). The eight radial arms are replaced by two crossed octagonal bronze rings, cardinal fastenings and a suspended faceted turquoise crystal. The original base, sockets and stepped mast foot are retained; the upper stem and mount are shorter. Bronze samples come from the native Metal_Bronze texture and the crystal uses the previously approved turquoise material. Existing base UV pixels are retained in the new atlas, with fresh material patches appended.

The existing multipart export and lifecycle remain active. Mast and antenna hitboxes and the inventory thumbnail follow the redesigned geometry. Antenna collision width is 1.03125 blocks, down from 3 blocks. The new JAR passed the same 49 server checks and was installed; in-client appearance and contours still require visual confirmation. Replacing existing Transmitters clears their old filler footprint.

## Metallic reading refinement after user edits

The user's revised Astrolabe source contains 50 elements and no inner ring. Those element fields, transforms, group hierarchy and outliner are preserved. Connection groups, antena-base and antena-halo-outer now have independent UV islands with painted metal bevels, short reflection bands and corner highlights. Colors are sampled from native Metal_Copper_Decorative and Metal_Bronze_Ornate palettes. Original atlas pixels remain intact, preserving stone and crystal UV regions. The embedded texture has a fresh UUID/name (Metal v3) and uses a 512 × 512 atlas. Source PNGs and embedded image agree.

The inventory thumbnail and collision boxes were regenerated from the user's 50-element geometry. Build and installed-JAR integrity checks passed; the metallic appearance is an offline preview pending user review.

## Unified Transmitter and geometry cleanup (current)

The Transmitter is again exported as one complete model with one physical/selection box, X/Z 0..1 and Y 0..3.375 (32 × 108 × 32 model units). Multipart placement and split-model export have been removed. Saved legacy helper IDs remain as invisible technical assets, and the existing removal handlers clear owned old parts/fillers safely when breaking a Transmitter. Replacing an old Transmitter is recommended to remove its former helper cells.

The active Blockbench model was optimized conservatively: one zero-depth duplicate base plane was removed after verifying that its texture UV matches the underlying solid face. Another 51 faces fully covered by opaque solid cuboids were disabled. The editor model goes from 50 to 49 elements and 296 to 243 enabled faces. All remaining element transforms, visible UVs and embedded texture data are unchanged. Hytale's native block models also omit hidden faces from textureLayout, so the exporter retains this optimization.

Validation passed in an isolated Hytale 0.7.5 server: one hitbox, exact block-width footprint, native filler-based placement without technical helper assembly, four lateral network ports, simultaneous breaking producing one item, complete cleanup of native fillers, and cleanup of old multipart saves without duplicate drops (262 assertions including empty-cell checks). The tested JAR was installed and matched byte for byte. Client contour remains to be visually checked after re-entering and replacing an old Transmitter.

## Traveler's Catalog in-game visual prototype

`AdvancedStorage_Travelers_Catalog` is now a handheld item in the asset pack. The build exports its authored Blockbench source under Items/AdvancedStorage, including the 24 × 24 transparent fullbright spiral. It has a 64 × 64 RGBA icon and a native blue item light (Color #26e, Radius 2). The prototype uses the native Item animation set, stacks to one and is available under Upgrade in Creative. No recipe or remote network interaction is defined yet.

Validation: the isolated Hytale 0.7.5 server loaded the item, correct model path and native ColorLight. Model, atlas and icon paths resolve in the JAR; the emissive glyph is 24 × 24; icon dimensions and archive integrity passed. The installed JAR matches the validated build. First-person grip and lighting appearance remain to be checked in the client.


## Traveler remote storage (current)

Traveler's Catalog now opens CatalogSettingsPage and CatalogContainer, sharing the Runic Catalog UI, sorting/search preferences and native inventory gestures. Only the title and connection status differ. Secondary use opens the tablet. Use on any native filler cell of the Transmitter resolves to its base.

Archive Transmitter opens a Common.ui management page listing discovered tablets in this world, with explicit Connect and Disconnect actions and six entries per page. Discovery includes player inventories, loaded dropped items and loaded block containers. Previously linked tablets remain in the saved list when their holder is absent. No automatic nearest-network binding is performed.

Each tablet receives a UUID in native ItemStack BSON metadata. Per-world links are written atomically to advanced-storage-tablets.json under the world's save directory. Death does not clear links, and a new player entity can use the same recovered tablet. Ordinary item loss rules remain in force. Links are confined to their world and survive server restarts.

Remote windows revalidate physical tablet possession and the saved link. The existing catalog projection rejects transfers when the tablet is disconnected, its Transmitter is missing, or the network is offline. Disconnection/rebinding invalidates existing sessions. A section lease loads the linked topology on open and releases keep-loaded references on close or failed opening. Physical catalogs retain their normal distance validation.

The open Traveler's Catalog rejects deposits of its own tablet UUID through the native catalog slot validation. Other tablet UUIDs are unaffected, and physical Runic Catalogs retain normal tablet storage/withdrawal. The isolated-server suite passed 37 assertions, including rejected self-deposit, unchanged inventory/storage totals, and a successful physical-catalog tablet round trip. The tested JAR was installed byte for byte.


## Core schematic and installed crafting tables (current)

The Core topology now tracks Catalog and Transmitter positions. Its centered schematic adds actual 64px Transmitter/Traveler thumbnails, the count of tablets linked to reachable Transmitters and an installed-table branch with the native bench icon.

Catalog.ui keeps the original 1120 x 700 panel and player inventory placement. A sidebar extends 236 units to its left. Ten native inventory sockets sit beneath the player's hotbar. Only the ten usable crafting tables in Hytale 0.7.5 are accepted: Alchemy, Arcane, Armour, Builders, Cooking, Farming, Furniture, Weapon, Workbench and GoblinLab Tinkering. Processing stations and unfinished Armory/Loom/Trough prototypes are excluded. Both physical and remote Catalogs use the same page and show installed tables from all reachable Catalogs. Tablet installation sockets refer to the first reachable physical Catalog.

Selecting a table replaces only the left network inventory area with a searchable native-style recipe grid, requirements and craft/upgrade controls. Network storage restores the item grid. Sidebar names/icons use native item assets. All inventory gestures still use real native window section IDs; bench installation transfers one table, and shift-removing a table returns it to the player inventory.

ItemContainerBlock persists the ten installed table items. Older Catalogs gain that component on first open. Each selected table retains its native BenchBlock tier state in item BSON metadata. Crafting validates native bench requirements, knowledge and world Memories, applies native recipe times/tier modifiers, takes inputs from seals, and places outputs into the player's inventory. Plans and capacity checks precede seal writes; failed writes restore output inventory and all seals. Closing the page cancels unfinished crafting without consuming ingredients. Upgrade costs are committed at completion, so removing a table while upgrading does not spend materials. No processing implementation is included after the user's crafting-only scope correction.

Validation: 54 isolated-server assertions cover all ten table types, rejecting excluded items, saved tiers, actual network ingredient consumption, exact quantities, failed-output rollback, timed crafting, native upgrade costs, sidebar event generation, recipe rendering and the expanded Core schematic. The 37 remote-storage assertions also passed against this package. Client layout and drag interactions remain to be visually checked in-game.


## Catalog layout and hit-test repair

The outer frame is now 1592 units wide, with the existing 1120-unit panel centered inside it. The sidebar starts at zero within that frame, preserving its screen position while placing its buttons inside ancestor hit bounds. Sidebar icon/label children are input-transparent so activation reaches the button. The storage/recipe modes now overlay inside a single flex region with Full:0 anchors, preventing hidden mode elements from dividing grid height. Tooltip rectangles are bounded at both top and bottom of the page.

Build and layout invariants passed: unchanged main-panel center and inventory anchors, sidebar containment, full-area mode anchors, tooltip bounds and installed-JAR byte equality. The initial artifact text comparison differed only in Windows line endings and passed after normalization. Hytale window capture failed with FrameArrived/window-capture timeouts; these changes have not been visually confirmed in the client.


## Scroll-safe tooltip placement (current)

A real client diagnostic confirmed SlotMouseEntered sends Action, GridVersion, Section, Session and SlotIndex only. It supplies no scroll displacement or pixel coordinates. Index-derived page-overlay placement cannot track the native ItemGrid scroll position correctly.

Scrollable mod grids (network storage, recipes and backpack) now publish TooltipTextSpans with a mod-local TextTooltipStyle. The client text-tooltip engine owns placement; ItemGrid.InfoDisplay remains None, avoiding the problematic global item-tooltip layer. The formatter preserves name, quality, ID, descriptions/stored counts and stats, using existing quality frames and text colors. Native text layout uses its own wrapping and a uniform 16px base font rather than the overlay's separately sized labels. Non-scrolling player/Archive grids retain the existing overlay. Server-initiated close clears active text-tooltip spans before unmounting. Other game interfaces are unaffected.

Build, formatted-message encoding, content checks, style asset resolution and archive integrity passed. The temporary hover-payload logger was removed. Installation is pending because the game holds the existing JAR open; scroll placement and text-tooltip teardown await client visual confirmation.

The scroll-safe tooltip build was subsequently installed after the world closed; installed JAR bytes match the verified build. Client visual confirmation remains pending.

The direct ItemGrid text-tooltip attempt did not display in the client. Scrollable tooltip text/style now targets the enclosing Group hosts (StoredHost, RecipesHost and BackpackHost), with both plain and formatted content supplied. Recipe grid gained a same-size flex host. Host mapping, native command encoding, build and installed-JAR equality passed; display/scrolling remains subject to client confirmation.


## Native item tooltip restoration and staged close (current)

Neither the ItemGrid text-tooltip path nor the parent-Group text-tooltip path displayed in the user's client. Network storage and recipe grids now use native InfoDisplay: Tooltip, including the Stored grid reset fragment. The custom overlay skips those sections. Other player/Archive grids retain their existing tooltip path.

Physical and remote Catalog pages use CantClose plus the existing root Dismissing binding to route Escape through the server (Tab already routes through StorageInputHooks). Close first disables native info display, empties the two displayed grids and hides them, then waits for a client UI acknowledgement followed by 120ms before unmounting; a 1500ms fallback prevents an indefinitely open page. This only clears client UI projections, not stored items. Acknowledgements are handled on the owning world thread and final close checks that this is still the current page.

Compilation, archive integrity, native tooltip mode/reset consistency and close-path ordering checks passed. Native Hytale window capture again failed with FrameArrived timeout, so display, scroll tracking and Escape/Tab teardown are not client-confirmed. Installation is pending because the world still holds the existing mod JAR open.

The staged-close/native-item-tooltip build was installed after the user left the world. Installed JAR bytes match the verified build. Client checks remain pending.


## Regression rollback: restore close controls and page-owned tooltip (current)

The user confirmed the native item tooltip still persisted after close, and CantClose removed Escape/F dismissal. That approach has been reverted. All Catalog/recipe grid definitions again use InfoDisplay None. StorageTooltip renders its page-owned overlay for every mod grid, retaining the established name/body font sizes and removing both unsuccessful native text-tooltip implementations. Catalog pages again use CanDismissOrCloseThroughInteraction, with existing Tab handling retained. The acknowledgement/delayed-close implementation and related styles/helpers were removed.

The resulting build was installed and byte-matched. Close-lifetime, grid mode, Tab handler and archive integrity checks passed. The original scroll-offset positioning problem remains unresolved; this rollback restores the last functional tooltip lifetime and close behavior and must not be presented as a scroll alignment fix.


## Native UI-state reset on storage-page dismissal (current)

Network and recipe grids again use the standard item tooltip. Catalog pages keep CanDismissOrCloseThroughInteraction and the existing Tab hook. There is no CantClose override, delayed-close gate, inventory flash or replacement UI library.

Investigation of the installed server JAR found HudManager.resetUserInterface(PlayerRef), which sends the dedicated ResetUserInterfaceState client packet. CatalogSettingsPage.onDismiss queues that native operation after its own window cleanup. The queued task checks the player reference and skips the reset if another custom page is active. This reset is triggered by our storage-page dismissal only; global UI assets/configuration are unchanged.

Compilation, tooltip grid/reset-fragment consistency, close-lifetime invariants, reset-call placement, JAR integrity and installed-byte equality passed. The actual client effect of ResetUserInterfaceState on the stuck item tooltip remains to be confirmed in-game; no claim of visual resolution is made from these checks alone.


## Remove UI-resource reset after client crash (current)

The user's client log records a NullReferenceException while rendering the reopened Catalog after ResetUserInterfaceState. The tooltip background disappeared but its text remained, so the global reset was not a valid tooltip cleanup. That call has been removed completely.

Cleanup now queues a batch containing the normal native Inventory -> None page transitions, only after our custom page/window teardown and only if no replacement custom/server-side page or window is active. This uses ordinary inventory lifecycle cleanup and does not invalidate shared UI resources. It may briefly instantiate the native inventory in the client; actual visual behavior is not confirmed.

The same log also showed a late duplicate CloseWindow packet for the bench socket window. Recently closed IDs from our page are retained for five seconds, allowing StorageInputHooks to ignore only a duplicate close for an already absent mod window. All other close packets continue through the game's original handler. Native Escape/F lifetime and Tab handling are retained.

Build, native SetPage packet codec round-trip, cleanup ordering, absence of global reset calls, archive integrity and installed-byte equality passed. The previous client's crashed session must be restarted before assessing reopening and tooltip cleanup. Client visual confirmation is still pending.


## Internal UI library and controlled visible ranges (current)

The user authorized building an internal UI library after both native tooltip cleanup paths failed. The first implementation is in pedro.advancedstorage.ui (GridViewport and TooltipPlacement), with Common.ui-themed RowScroll and RecipeGrid templates. See docs/ui-library.md for the API, input boundary and migration scope.

Catalog and Traveler network inventories now expose 54 visible data cells plus nine reserved deposit cells in a fixed native container. The server owns the first visible row. Recipes expose four visible rows with logical-index mapping. Native hidden scrolling is disabled for both grids. A row slider and Up/Down buttons own navigation; mouse-wheel integration is explicitly not implemented. Search resets the active range, count changes clamp it, active selections disable navigation, and grid versions/remounting reject stale events.

The tooltip is again entirely page-owned with its previous quality textures, fonts and arrow. It uses visible cell coordinates through TooltipPlacement, so changing the logical range does not change the tooltip anchor of a cell on screen. There is no global UI reset, native Inventory->None cleanup cycle, CantClose override or native item tooltip in the migrated grids. Main-panel/player-inventory anchors and existing Escape/F/Tab routes are preserved. Backpack/Archive native scrolling has not yet migrated to the viewport component.

Validation passed: pure Java viewport/tooltip geometry checks, 36 real-server inventory assertions across a 160-type list, plus the 54 crafting and 37 remote-storage assertions. Deposits on full data pages, correct item identity/quantities after scrolling, filter reset, fixed container capacity and reserved empty cells were verified. The library build was installed and byte-matched. Client visual, slider and gesture confirmation remains pending; tests do not establish client rendering correctness.
