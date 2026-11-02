# Advanced Storage

Create magical storage networks, access your items from a single interface, and keep your resources organized without the need of chests. Advanced Storage is a Refined Storage-inspired mod, themed to fit Hytale's fantasy setting.

## Installation

Download `advanced-storage-0.1.0-beta.jar` from the [releases page](https://github.com/pedromilho/advanced-storage/releases). Place the file in `UserData/Mods` and enable **AdvancedStorage** in your world.

## Setting up the network

Connect **Seal Archive → Runic Conduit → Arcane Core → Runic Conduit → Runic Catalog**.

- **Arcane Core:** activates the network and displays its connected blocks.
- **Seal Archive:** holds four Storage Seals in horizontal slots.
- **Runic Conduit:** automatically connects blocks in all six directions.
- **Runic Catalog:** lets you search, sort, deposit, and withdraw items.
- **Blank Seal** and **Runic Book:** crafting components.

Storage Seals retain their contents when removed from an Archive. You cannot storage Seals inside the network.

| Seal | Capacity in individual items |
| --- | ---: |
| Tier 1 | 1,000 |
| Tier 2 | 4,000 |
| Tier 3 | 16,000 |
| Tier 4 | 64,000 |
| Tier 5 | 128,000 |

## Crafting

All eleven recipes are available at the **Arcane Workbench → Misc**. Generic ingredients accept any log, crystal shard, brick, or small chest from the corresponding group. Seal upgrades require empty seals.

See [recipes and quantities](docs/crafting.md).

## Inventory controls

The Catalog combines your inventory, hotbar, search, and sorting by name or quantity. The network list automatically reorganizes after withdrawals.

- Drag items to move them.
- Shift + left-click to transfer quickly.
- Shift + right-click to select half a stack.
- Hold the right mouse button and drag to distribute one item per slot.
- Double left-click to combine matching stacks.
- Left-click the original slot to cancel your selection.

Catalog and Archive use page-owned tooltips styled after the game because the tooltips would stay in the screen even after closing the interface.

## License

See [LICENSE](LICENSE). Hytale and its original assets belong to their respective owners.
