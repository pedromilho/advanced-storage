# Crafting

All recipes use the native Arcane Workbench, Misc category. Source: `recipes/advanced-storage-crafts.json`, plus the approved Blank Seal and Runic Book recipes in `tools/crafting.py`.

| Item | Ingredients | Output | Time |
| --- | --- | --- | --- |
| AdvancedStorage_Arcane_Core | 15 × Ingredient_Bar_Copper, 1 × Ingredient_Life_Essence_Concentrated, 10 × Crystal_Shards, 4 × Any Cobble, 1 × Ingredient_Void_Essence | 1 | 1 s |
| AdvancedStorage_Arcane_Item_Exporter | 30 × Ingredient_Life_Essence, 10 × Crystal_Shards, 15 × Ingredient_Bar_Copper, 5 × Any Cobble | 1 | 1 s |
| AdvancedStorage_Archive_Item_Gate | 10 × Wood_Trunk, 15 × Ingredient_Bar_Copper, 10 × Ingredient_Life_Essence | 1 | 1 s |
| AdvancedStorage_Archive_Transmitter | 1 × Ingredient_Void_Essence, 2 × Ingredient_Life_Essence_Concentrated, 10 × Crystal_Shards, 25 × Ingredient_Bar_Copper, 10 × Any Cobble | 1 | 1 s |
| AdvancedStorage_Blank_Seal | 4 × Ingredient_Fibre, 1 × Ingredient_Tree_Sap, 4 × Ingredient_Life_Essence | 1 | 1 s |
| AdvancedStorage_Runic_Book | 3 × AdvancedStorage_Blank_Seal, 6 × Ingredient_Hide_Medium | 1 | 1 s |
| AdvancedStorage_Runic_Catalog | 1 × Bench_WorkBench, 6 × Wood_Trunk, 1 × AdvancedStorage_Runic_Book, 4 × Ingredient_Bar_Copper | 1 | 1 s |
| AdvancedStorage_Runic_Conduit | 6 × Ingredient_Bar_Copper, 3 × Crystal_Shards | 6 | 6 s |
| AdvancedStorage_Seal_Archive | 8 × Wood_Trunk, 1 × Furniture_Crude_Chest_Small, 4 × Ingredient_Bar_Copper, 10 × Ingredient_Life_Essence | 1 | 1 s |
| AdvancedStorage_Storage_Seal_Tier_1 | 1 × AdvancedStorage_Blank_Seal, 20 × Ingredient_Life_Essence | 1 | 1 s |
| AdvancedStorage_Storage_Seal_Tier_2 | 1 × AdvancedStorage_Storage_Seal_Tier_1, 50 × Ingredient_Life_Essence | 1 | 1 s |
| AdvancedStorage_Storage_Seal_Tier_3 | 1 × AdvancedStorage_Storage_Seal_Tier_2, 1 × Ingredient_Life_Essence_Concentrated | 1 | 1 s |
| AdvancedStorage_Storage_Seal_Tier_4 | 2 × Ingredient_Life_Essence_Concentrated, 1 × AdvancedStorage_Storage_Seal_Tier_3 | 1 | 1 s |
| AdvancedStorage_Storage_Seal_Tier_5 | 1 × AdvancedStorage_Storage_Seal_Tier_4, 3 × Ingredient_Life_Essence_Concentrated | 1 | 1 s |
| AdvancedStorage_Travelers_Catalog | 5 × Any Cobble, 5 × Ingredient_Bar_Copper, 5 × Crystal_Shards, 1 × Ingredient_Life_Essence_Concentrated | 1 | 1 s |

`Wood_Trunk` accepts native logs, `Crystal_Shards` accepts native crystal shards, and **Any Cobble** accepts full blocks of cobble or natural stone. Seal Archive uses one recipe with a shared small-chest resource.

Blank Seal uses the Recipe Page model, texture and inventory icon without its learn-recipe interaction. Runic Book uses Brown Grimoire visuals without its spellcasting interactions.

Seal upgrades use empty seals. Native crafting checks exact item metadata, so seals with stored contents cannot be consumed. Emptying a seal clears the storage metadata and lets it be upgraded again. Other item metadata is preserved.

Build: `python tools/build_pack.py` recompiles the recipes before packaging. The Any Small Chest icon is copied from the installed Hytale Assets.zip into the resource icon directory required by the game.
