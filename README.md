# Advanced Storage

Create magical storage networks, access your items from a single interface, and keep your resources organized without the need of chests. Advanced Storage is a Refined Storage-inspired mod, themed to fit Hytale's fantasy setting.

## Installation

Use Advanced Storage **1.0.1** with Hytale **0.7.6**. Place `advanced-storage-1.0.1.jar` in `UserData/Mods` and enable **AdvancedStorage** in your world. Replace the previous JAR when upgrading. Published builds are listed on the [releases page](https://github.com/pedromilho/advanced-storage/releases).

## Setting up the network

Connect **Seal Archive → Runic Conduit → Arcane Core → Runic Conduit → Runic Catalog**.

- **Arcane Core:** activates the network and displays its connected blocks.
- **Seal Archive:** holds five Storage Seals in horizontal slots.
- **Runic Conduit:** automatically connects blocks in all six directions.
- **Runic Catalog:** lets you search, sort, deposit, and withdraw items.
- **Archive Transmitter:** connects Traveler's Catalogs to its network through its tablet list.
- **Traveler's Catalog:** opens the same storage interface remotely in the same world. Right-click to open it.
- **Archive Item Gate:** moves items between nearby inventories and the network, with separate send and receive filters. It can also collect finished Furnace items through connected conduits.
- **Arcane Item Exporter:** sends items between paired Exporters on the same channel. Name each Exporter to identify it in the channel list.
- **Blank Seal** and **Runic Book:** crafting components.

Storage Seals retain their contents when removed from an Archive. They cannot be stored inside the network.

For remote access, connect an Archive Transmitter to the network, open it, and select **Connect** beside your tablet. **Disconnect** revokes that tablet's access. The link belongs to the tablet and survives death and server restarts; normal item loss rules still apply. The network must have an Arcane Core to transfer items.

| Seal | Capacity in individual items |
| --- | ---: |
| Tier 1 | 1,000 |
| Tier 2 | 4,000 |
| Tier 3 | 16,000 |
| Tier 4 | 64,000 |
| Tier 5 | 128,000 |

## Network crafting

Install crafting tables in the ten slots below the player inventory in a Runic Catalog. Select a table using its icon and name in the left sidebar. Recipes replace the network item grid while the player inventory stays in place; **Network storage** switches back.

Crafting uses ingredients stored in the connected seals and delivers finished items to the player inventory. The Salvager's Workbench returns its outputs directly to the network. Recipe times, table tiers, unlocks and world Memories requirements are checked. Installed table upgrades remain on their items when removed and reinstalled. Traveler's Catalog can use the crafting tables installed on the same network.

## Crafting

All fifteen mod recipes are available at the **Arcane Workbench → Misc**.

## License

The original Advanced Storage code and assets are proprietary. Official releases may be installed and played in Hytale. See [LICENSE](LICENSE) for the terms.

The Catalog UI now uses the internal [UI component library](docs/ui-library.md). Network and recipe lists use the library’s vertical navigation track and a tooltip anchored to the visible slot.
