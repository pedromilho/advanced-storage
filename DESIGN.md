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
