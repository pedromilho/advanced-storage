import pedro.advancedstorage.*;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import org.bson.*;
import org.joml.Vector3i;
import java.util.concurrent.*;
import java.nio.file.*;

public class StorageVerification {
    static int cases;
    static final StorageNetwork.Pos CATALOG=new StorageNetwork.Pos(8,280,8);
    static final StorageNetwork.Pos PIPE=new StorageNetwork.Pos(9,280,8);
    static final StorageNetwork.Pos ARCHIVE=new StorageNetwork.Pos(9,280,7);
    static final StorageNetwork.Pos CORE=new StorageNetwork.Pos(10,280,8);
    static void check(boolean result,String name) {cases++;if(!result)throw new AssertionError(name);}
    static void put(World w,StorageNetwork.Pos p,String id) {
        var ref=w.getChunkStore().getChunkSectionReferenceAtBlock(p.x(),p.y(),p.z());
        ConnectionVerificationPlugin.put(w.getChunkStore(),ref,new Vector3i(p.x(),p.y(),p.z()),id,0);
    }
    static void register(JavaPlugin plugin) {
        plugin.getCommandRegistry().registerCommand(new AbstractAsyncCommand("as-verify-storage","Verify functional storage") {
            @Override protected CompletableFuture<Void> executeAsync(CommandContext context) {
                return Universe.get().getUniverseReady().thenCompose(v->{
                    var world=Universe.get().getDefaultWorld();
                    return world.getChunkStore().getChunkSectionReferenceAtBlockAsync(8,280,8).thenCompose(ref->
                        CompletableFuture.runAsync(()->{
                            world.getChunkStore().getStore().removeComponent(ref,com.hypixel.hytale.server.core.universe.world.storage.ChunkStore.REGISTRY.getNonTickingComponentType());
                            put(world,CATALOG,"AdvancedStorage_Runic_Catalog");
                            put(world,PIPE,"AdvancedStorage_Runic_Conduit_All");
                            put(world,CORE,"AdvancedStorage_Arcane_Core");
                            put(world,ARCHIVE,"AdvancedStorage_Seal_Archive");
                            run(world);
                        },world));
                }).handle((v,error)->{
                    try{Files.writeString(Path.of("storage-check-result.txt"),error==null?"PASS\n":"FAIL\n"+error);}catch(Exception ex){throw new RuntimeException(ex);}
                    if(error!=null)error.printStackTrace();return null;
                });
            }
        });
    }
    static void verifyCrafts() {
        var command=new IngredientsCommand();
        check(command.getRequiredArguments().size()==1,"Ingredients command requires an item argument");
        for(String option:IngredientsCommand.OPTIONS) {
            var kit=IngredientsCommand.ingredients(option);
            check(!kit.isEmpty(),"Ingredient kit exists: "+option);
            check(IngredientsCommand.ingredients(IngredientsCommand.resolve(option)).equals(kit),"Full item ID alias: "+option);
            var inventory=new SimpleItemContainer((short)20);
            check(IngredientsCommand.give(inventory,kit),"Ingredient kit fits and is delivered: "+option);
        }
        var combined=new com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer(
            new SimpleItemContainer((short)2),new SimpleItemContainer((short)2));
        check(IngredientsCommand.give(combined,IngredientsCommand.ingredients("seal_archive")),"Delivery supports native combined player inventory");
        boolean invalid=false;
        try {IngredientsCommand.ingredients("invalid");}catch(IllegalArgumentException ex){invalid=true;}
        check(invalid,"Unknown target rejected");
        var fullInventory=new SimpleItemContainer((short)1);
        var sentinel=new ItemStack("Rock_Stone",100);
        fullInventory.setItemStackForSlot((short)0,sentinel);
        check(!IngredientsCommand.give(fullInventory,IngredientsCommand.ingredients("seal_archive")),"Full inventory rejects ingredient kit");
        check(sentinel.equals(fullInventory.getItemStack((short)0)),"Full inventory remains intact");
        var partial=new SimpleItemContainer((short)1);
        check(!IngredientsCommand.give(partial,IngredientsCommand.ingredients("seal_archive")),"Partially fitting kit is rejected");
        check(ItemStack.isEmpty(partial.getItemStack((short)0)),"No partial ingredient delivery");
        var recipes=com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe.getAssetMap();
        var items=com.hypixel.hytale.server.core.asset.type.item.config.Item.getAssetMap();
        String[] ids={"Runic_Conduit","Seal_Archive","Runic_Catalog","Arcane_Core","Blank_Seal","Runic_Book",
                      "Storage_Seal_Tier_1","Storage_Seal_Tier_2","Storage_Seal_Tier_3","Storage_Seal_Tier_4","Storage_Seal_Tier_5"};
        for(var suffix:ids) {
            var item=items.getAsset("AdvancedStorage_"+suffix);
            check(item!=null,"Craft item loaded: "+suffix);
            var generated=new java.util.ArrayList<com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe>();
            item.collectRecipesToGenerate(generated);
            check(generated.size()==1,"One primary recipe: "+suffix);
            var recipe=recipes.getAsset(generated.getFirst().getId());
            check(recipe!=null,"Recipe registered: "+suffix);
            check(recipe.getBenchRequirement()[0].id.equals("Arcanebench"),"Arcane bench: "+suffix);
            check(recipe.getOutputs()[0].getQuantity()==(suffix.equals("Runic_Conduit")?6:1),"Native output quantity: "+suffix);
            for(var input:recipe.getInput()) {
                String alternate=switch(String.valueOf(input.getResourceTypeId())) {
                    case "Wood_Trunk" -> "Wood_Oak_Trunk";
                    case "Crystal_Shards" -> "Ingredient_Crystal_Red";
                    case "Bricks" -> "Rock_Sandstone_Brick";
                    default -> null;
                };
                if(alternate!=null) {
                    var inventory=new SimpleItemContainer((short)1);
                    inventory.setItemStackForSlot((short)0,new ItemStack(alternate,input.getQuantity()));
                    check(inventory.removeMaterial(input).succeeded(),"Alternative material accepted: "+suffix+" / "+alternate);
                }
            }
        }
        check(recipes.getAsset("AdvancedStorage_Seal_Archive_Furniture_Tavern_Chest_Small")==null,
              "No duplicate small-chest recipe");
        for(var chest:new String[]{"Furniture_Crude_Chest_Small","Furniture_Tavern_Chest_Small","Furniture_Ancient_Chest_Small"}) {
            var inventory=new SimpleItemContainer((short)1);
            inventory.setItemStackForSlot((short)0,new ItemStack(chest,1));
            var cost=new com.hypixel.hytale.server.core.inventory.MaterialQuantity(null,"AdvancedStorage_Small_Chest",null,1,null);
            check(inventory.removeMaterial(cost).succeeded(),"Small chest group accepts "+chest);
        }
        var large=new SimpleItemContainer((short)1);
        large.setItemStackForSlot((short)0,new ItemStack("Furniture_Crude_Chest_Large",1));
        check(!large.removeMaterial(new com.hypixel.hytale.server.core.inventory.MaterialQuantity(null,"AdvancedStorage_Small_Chest",null,1,null)).succeeded(),"Small chest group rejects large chest");
        for(int tier=1;tier<5;tier++) {
            var original=new ItemStack(SealData.PREFIX+tier,1);
            var contents=SealData.read(original);
            contents.insert(new ItemStack("Rock_Stone",1),10);
            var full=contents.write(original);
            var inv=new SimpleItemContainer((short)1);
            inv.setItemStackForSlot((short)0,full);
            var cost=new com.hypixel.hytale.server.core.inventory.MaterialQuantity(original.getItemId(),null,null,1,null);
            check(!inv.removeMaterial(cost).succeeded(),"Native crafting rejects filled seal tier "+tier);
            check(SealData.read(inv.getItemStack((short)0)).used()==10,"Craft attempt preserves stored items");
            contents.remove(new ItemStack("Rock_Stone",1),10);
            inv.setItemStackForSlot((short)0,contents.write(full));
            check(inv.removeMaterial(cost).succeeded(),"Emptied seal can be used for upgrade");
        }
    }
    static void run(World world) {
        verifyCrafts();
        var archive=StorageNetwork.archive(world,ARCHIVE);
        check(archive!=null,"Archive native container exists");
        check(archive.getCapacity()==4,"Four native persistent slots");
        archive.clear();
        check(!archive.addItemStack(new ItemStack("Rock_Stone",1)).succeeded(),"Archive rejects ordinary items");
        var item=new ItemStack("Rock_Stone",1).withMetadata("TestRune",new BsonString("preserved"));
        int[] caps={1000,4000,16000,64000,128000};
        for(int tier=1;tier<=5;tier++) {
            var seal=new ItemStack(SealData.PREFIX+tier,1);
            var data=SealData.read(seal);
            check(data.capacity==caps[tier-1],"Tier capacity "+tier);
            check(data.insert(item,caps[tier-1]+1)==caps[tier-1],"Exact capacity "+tier);
            check(data.insert(item,1)==0,"No overfill "+tier);
            var restored=SealData.read(ItemStack.CODEC.decode(ItemStack.CODEC.encode(data.write(seal))));
            check(restored.used()==caps[tier-1],"Item metadata survives serialization "+tier);
            check(restored.entries.getFirst().template.isStackableWith(item),"Metadata retained "+tier);
            check(restored.remove(item,caps[tier-1]+10)==caps[tier-1],"Remove without negative amounts "+tier);
            check(restored.used()==0,"Empty seal "+tier);
            check(restored.insert(seal,1)==0,"No nested seals "+tier);
        }
        archive.setItemStackForSlot((short)0,new ItemStack(SealData.PREFIX+1,1));
        var net=StorageNetwork.scan(world,CATALOG);
        check(net.active() && net.cores==1 && net.archives.size()==1 && net.seals.size()==1,"Connected network");
        var inventory=new SimpleItemContainer((short)3);
        inventory.setItemStackForSlot((short)0,item.withQuantity(100));
        check(net.deposit(inventory,(short)0)==100,"Deposit 100");
        check(ItemStack.isEmpty(inventory.getItemStack((short)0)),"Inventory debited");
        net=StorageNetwork.scan(world,CATALOG);
        check(net.used()==100,"Stored count");
        check(net.withdraw(inventory,item,25)==25,"Withdraw 25");
        check(inventory.getItemStack((short)0).getQuantity()==25,"Inventory credited");
        check(StorageNetwork.scan(world,CATALOG).used()==75,"Seal debited");
        // A different metadata value is a different stored item.
        var other=new ItemStack("Rock_Stone",10).withMetadata("TestRune",new BsonString("other"));
        inventory.setItemStackForSlot((short)1,other);
        check(StorageNetwork.scan(world,CATALOG).deposit(inventory,(short)1)==10,"Deposit metadata variant");
        check(StorageNetwork.scan(world,CATALOG).contents().size()==2,"Variants remain distinct");
        inventory.setItemStackForSlot((short)0,item.withQuantity(1000));
        check(StorageNetwork.scan(world,CATALOG).deposit(inventory,(short)0)==915,"Partial deposit respects 1000 limit");
        check(inventory.getItemStack((short)0).getQuantity()==85,"Remainder retained");
        check(StorageNetwork.scan(world,CATALOG).deposit(inventory,(short)0)==0,"Full network rejects deposit");
        var full=new SimpleItemContainer((short)1);
        full.setItemStackForSlot((short)0,new ItemStack("AdvancedStorage_Arcane_Core",25));
        check(StorageNetwork.scan(world,CATALOG).withdraw(full,item,1)==0,"Full inventory rejects withdrawal");
        check(StorageNetwork.scan(world,CATALOG).used()==1000,"Failed withdrawal preserves items");
        // Move populated seal out and back, retaining its portable payload.
        var portable=archive.getItemStack((short)0);
        archive.setItemStackForSlot((short)0,null);
        check(StorageNetwork.scan(world,CATALOG).capacity()==0,"Removing seal removes capacity");
        archive.setItemStackForSlot((short)3,ItemStack.CODEC.decode(ItemStack.CODEC.encode(portable)));
        check(StorageNetwork.scan(world,CATALOG).used()==1000,"Reinsert populated seal in fourth slot");
        put(world,CORE,"Empty");
        net=StorageNetwork.scan(world,CATALOG);
        check(!net.active(),"Core removal deactivates network");
        check(net.withdraw(inventory,item,1)==0,"Offline withdrawal blocked");
        check(net.deposit(inventory,(short)0)==0,"Offline deposit blocked");
        put(world,CORE,"AdvancedStorage_Arcane_Core");
        put(world,PIPE,"Empty");
        check(StorageNetwork.scan(world,CATALOG).archives.isEmpty(),"Broken conduit disconnects archive");
        put(world,PIPE,"AdvancedStorage_Runic_Conduit_All");
        check(StorageNetwork.scan(world,CATALOG).used()==1000,"Reconnect retains contents");
        var nativeWindow=new NativeStorageWindows.StorageWindow(world,ARCHIVE,0,com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getAsset("AdvancedStorage_Seal_Archive"),archive);
        check(nativeWindow.getType()==com.hypixel.hytale.protocol.packets.window.WindowType.Container,"Archive uses the game's built-in container window");
        var catalog=new CatalogContainer(world,CATALOG);
        var transferInventory=new SimpleItemContainer((short)9);
        check(catalog.moveItemStackFromSlotToSlot((short)0,1,transferInventory,(short)0).succeeded(),"Catalog native drag withdraws");
        check(StorageNetwork.scan(world,CATALOG).used()==999,"Catalog drag debits seal contents");
        check(transferInventory.moveItemStackFromSlot((short)0,catalog).succeeded(),"Catalog native quick transfer deposits");
        check(StorageNetwork.scan(world,CATALOG).used()==1000,"Catalog deposit credits seal contents");
        check(!catalog.moveItemStackFromSlotToSlot((short)0,1,full,(short)0).succeeded(),"Full player inventory blocks native Catalog withdrawal");
        nativeRoutes(world,catalog,item,false);
        var deniedSource=new SimpleItemContainer((short)9);
        deniedSource.setItemStackForSlot((short)0,item);
        check(!deniedSource.moveItemStackFromSlotToSlot((short)0,1,catalog,(short)5).succeeded(),"Full seals reject a native Catalog deposit");
        check(deniedSource.getItemStack((short)0).getQuantity()==1,"Rejected Catalog deposit keeps original inventory item");
        var otherCatalog=new CatalogContainer(world,CATALOG);
        var otherPlayer=new SimpleItemContainer((short)9);
        check(catalog.moveItemStackFromSlotToSlot((short)0,25,otherPlayer,(short)0).succeeded(),"First Catalog session withdraws");
        check(otherCatalog.moveItemStackFromSlotToSlot((short)0,25,otherPlayer,(short)1).succeeded(),"Second Catalog session observes current contents");
        check(StorageNetwork.scan(world,CATALOG).used()==950,"Concurrent Catalog sessions do not duplicate stock");
        check(otherPlayer.moveItemStackFromSlot((short)0,catalog).succeeded(),"Return first session's items");
        check(otherPlayer.moveItemStackFromSlot((short)1,otherCatalog).succeeded(),"Return second session's items");
        check(StorageNetwork.scan(world,CATALOG).used()==1000,"Multiple native windows preserve total inventory");
        catalog.sortItems(com.hypixel.hytale.server.core.inventory.container.SortType.TYPE);
        check(StorageNetwork.scan(world,CATALOG).used()==1000,"Native sorting changes no stored quantities");
        put(world,CORE,"Empty");catalog.refresh();
        check(catalog.getItemStack((short)0)==null,"Core removal clears native Catalog projection");
        check(!deniedSource.moveItemStackFromSlotToSlot((short)0,1,catalog,(short)0).succeeded(),"Offline native Catalog rejects deposits");
        check(deniedSource.getItemStack((short)0).getQuantity()==1,"Offline deposit preserves player inventory");
        put(world,CORE,"AdvancedStorage_Arcane_Core");catalog.refresh();
        check(StorageNetwork.scan(world,CATALOG).used()==1000,"Reactivation preserves native Catalog stock");
        var coreScreen=new CoreContainer(world,CORE);
        check(coreScreen.getCapacity()==4,"Core uses four native thumbnail positions");
        check(coreScreen.getItemStack((short)0).getItemId().equals("AdvancedStorage_Arcane_Core"),"Core thumbnail exists");
        check(coreScreen.getItemStack((short)1).getItemId().equals("AdvancedStorage_Seal_Archive"),"Archive connection thumbnail exists");
        check(coreScreen.getItemStack((short)2).getItemId().equals("AdvancedStorage_Runic_Catalog"),"Catalog connection thumbnail exists");
        check(coreScreen.getItemStack((short)3).getItemId().equals("AdvancedStorage_Runic_Conduit"),"Conduit connection thumbnail exists");
        check(!coreScreen.moveItemStackFromSlot((short)0,deniedSource).succeeded(),"Core thumbnails cannot be extracted");
        check(!deniedSource.moveItemStackFromSlot((short)0,coreScreen).succeeded(),"Core screen cannot receive items");
        var coreTakeAll=new SimpleItemContainer((short)9);
        coreScreen.moveAllItemStacksTo(coreTakeAll);
        check(coreTakeAll.isEmpty(),"Native Take All cannot extract Core thumbnails");
        put(world,PIPE,"Empty");coreScreen.refresh();
        check(coreScreen.countItemStacks(s->!ItemStack.isEmpty(s))==1,"Core thumbnail display updates after disconnection");
        put(world,PIPE,"AdvancedStorage_Runic_Conduit_All");coreScreen.refresh();
        check(coreScreen.countItemStacks(s->!ItemStack.isEmpty(s))==4,"Core thumbnail display updates after reconnection");
        var savedSeal=archive.getItemStack((short)3);
        var sortSeal=new ItemStack(SealData.PREFIX+1,1);
        var sortData=SealData.read(sortSeal);
        sortData.insert(new ItemStack("Rock_Stone",1),40);
        sortData.insert(new ItemStack("Wood_Oak_Trunk",1),30);
        sortData.insert(new ItemStack("AdvancedStorage_Arcane_Core",1),2);
        archive.setItemStackForSlot((short)3,sortData.write(sortSeal));
        var autoSorted=new CatalogContainer(world,CATALOG,new CatalogView("",CatalogView.Order.QUANTITY));
        check(autoSorted.getItemStack((short)0).getItemId().equals("Rock_Stone"),"Quantity view starts with largest stored total");
        autoSorted.removeItemStackFromSlot((short)0,20);
        autoSorted.refreshOrder();
        check(autoSorted.getItemStack((short)0).getItemId().equals("Wood_Oak_Trunk") && autoSorted.getItemStack((short)1).getQuantity()==20,"Withdrawal automatically changes quantity order without a filter click");
        autoSorted.removeItemStackFromSlot((short)0,30);autoSorted.refreshOrder();
        check(autoSorted.getItemStack((short)0).getItemId().equals("Rock_Stone") && autoSorted.getItemStack((short)1).getItemId().equals("AdvancedStorage_Arcane_Core") && ItemStack.isEmpty(autoSorted.getItemStack((short)2)),"Removing a type compacts network slots without gaps");
        autoSorted.setView(CatalogView.DEFAULT);
        check(autoSorted.getItemStack((short)0).getItemId().equals("AdvancedStorage_Arcane_Core"),"Alphabetical view still sorts by item name");
        autoSorted.removeItemStackFromSlot((short)0,2);autoSorted.refreshOrder();
        check(autoSorted.getItemStack((short)0).getItemId().equals("Rock_Stone"),"Alphabetical view also compacts after withdrawal");
        check(StorageNetwork.scan(world,CATALOG).used()==20,"Automatic ordering preserves remaining stored quantities");
        archive.setItemStackForSlot((short)3,savedSeal);
        var viewData=java.util.List.of(new StorageNetwork.StoredItem(new ItemStack("Rock_Stone",1),150),new StorageNetwork.StoredItem(new ItemStack("AdvancedStorage_Arcane_Core",1),2));
        var alphabetical=new CatalogView("",CatalogView.Order.ALPHABETICAL).select(viewData);
        check(alphabetical.getFirst().template.getItemId().equals("AdvancedStorage_Arcane_Core"),"Alphabetical sort uses displayed English name");
        var byQuantity=new CatalogView("",CatalogView.Order.QUANTITY).select(viewData);
        check(byQuantity.getFirst().count==150,"Quantity sort uses aggregate stored count, highest first");
        check(new CatalogView("ARCANE",CatalogView.Order.QUANTITY).select(viewData).size()==1,"Case-insensitive Catalog search");
        check(new CatalogView("zzzzMissing",CatalogView.Order.ALPHABETICAL).select(viewData).isEmpty(),"Empty search results are safe");
        var filtered=new CatalogContainer(world,CATALOG,new CatalogView("zzzzMissing",CatalogView.Order.QUANTITY));
        check(filtered.getItemStack((short)0)==null && StorageNetwork.scan(world,CATALOG).used()==1000,"Filtering hides presentation only, never deletes stored contents");
        try {
            var scanCounter=CatalogContainer.class.getDeclaredField("networkScans");scanCounter.setAccessible(true);
            var opening=new CatalogContainer(world,CATALOG);
            long scansBefore=scanCounter.getLong(opening),start=System.nanoTime();
            var packet=opening.toPacket();
            long elapsed=System.nanoTime()-start,scans=scanCounter.getLong(opening)-scansBefore;
            check(!packet.items.isEmpty(),"Opening packet includes stored items");
            check(scans==1,"Opening packet scans network once instead of once per slot");
            var temporary=new SimpleItemContainer((short)1);
            check(StorageNetwork.scan(world,CATALOG).withdraw(temporary,other,1)==1,"Concurrent network withdrawal succeeds");
            var fresh=opening.toPacket();
            check(fresh.items.values().stream().anyMatch(value->value.quantity==9 && value.metadata!=null && value.metadata.contains("other")),"Next packet immediately observes external network changes");
            check(StorageNetwork.scan(world,CATALOG).deposit(temporary,(short)0)==1,"Packet freshness check restores withdrawn item");
            System.out.println("CATALOG_OPEN_PACKET scans="+scans+" micros="+elapsed/1000);
        } catch(ReflectiveOperationException ex) {throw new AssertionError(ex);}
        var commands=new com.hypixel.hytale.server.core.ui.builder.UICommandBuilder();
        var events=new com.hypixel.hytale.server.core.ui.builder.UIEventBuilder();
        var displayWindow=new com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerBlockWindow(CATALOG.x(),CATALOG.y(),CATALOG.z(),0,
            com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getAsset("AdvancedStorage_Runic_Catalog"),new CatalogContainer(world,CATALOG));
        displayWindow.setId(42);
        var uiInventory=new SimpleItemContainer((short)36);
        var uiHotbar=new SimpleItemContainer((short)9);
        var uiPage=new CatalogSettingsPage(null,world,CATALOG,displayWindow,uiInventory,uiHotbar,null);
        uiPage.build(null,commands,events,world.getEntityStore().getStore());
        try {
            var replace=CatalogSettingsPage.class.getDeclaredMethod("resetGrid",UICommandBuilder.class,UIEventBuilder.class,int.class);replace.setAccessible(true);
            var resetCommands=new UICommandBuilder();var resetEvents=new UIEventBuilder();
            replace.invoke(uiPage,resetCommands,resetEvents,CatalogGestures.INVENTORY);
            check(java.util.Arrays.stream(resetCommands.getCommands()).filter(c->"#InventoryHost".equals(c.selector)).count()==2,"Native drag source is disposed and reinserted inside its original host");
            check(resetEvents.getEvents().length==8 && java.util.Arrays.stream(resetEvents.getEvents()).allMatch(e->"#Inventory".equals(e.selector)),"Disposal rebinds only the replaced grid");
            var source=new ItemStack("Rock_Stone",24);
            uiInventory.setItemStackForSlot((short)0,source);
            var paint=new CatalogGestures(java.util.Map.of(CatalogGestures.INVENTORY,new CatalogGestures.Section(-2,uiInventory),CatalogGestures.HOTBAR,new CatalogGestures.Section(-1,uiHotbar)),new CatalogGestures.Commands(){
                public void quickMove(int from,int slot,int count) {}
                public void move(int from,int slot,int count,int to,int target) {uiInventory.moveItemStackFromSlotToSlot((short)slot,count,uiHotbar,(short)target);}
            });
            var field=CatalogSettingsPage.class.getDeclaredField("gestures");field.setAccessible(true);field.set(uiPage,paint);
            var pick=input("pick",CatalogGestures.INVENTORY,0,false);pick.right=true;paint.handle(pick);
            var press=input("press",CatalogGestures.HOTBAR,0,false);press.right=true;paint.handle(press);
            check(paint.holding(),"Right sweep remains active before mouse release");
            var render=CatalogSettingsPage.class.getDeclaredMethod("render",UICommandBuilder.class);render.setAccessible(true);
            var paintCommands=new UICommandBuilder();render.invoke(uiPage,paintCommands);
            check(java.util.Arrays.stream(paintCommands.getCommands()).anyMatch(c->"#Hotbar.Slots".equals(c.selector)),"Confirmed right-sweep items paint while the mouse is still held");
            paint.cancel();paint.capture();
            var hoverField=CatalogSettingsPage.class.getDeclaredField("tooltip");hoverField.setAccessible(true);
            var ownedTooltip=(StorageTooltip)hoverField.get(uiPage);
            ownedTooltip.hover(CatalogGestures.HOTBAR,0);
            var shownTooltip=new UICommandBuilder();render.invoke(uiPage,shownTooltip);
            check(java.util.Arrays.stream(shownTooltip.getCommands()).anyMatch(c->"#StorageTooltip.Visible".equals(c.selector) && org.bson.BsonDocument.parse(c.data).getBoolean("0").getValue()),"Owned tooltip shows over a populated mod inventory slot");
            ownedTooltip.exit(CatalogGestures.HOTBAR,0);
            var hiddenTooltip=new UICommandBuilder();render.invoke(uiPage,hiddenTooltip);
            check(java.util.Arrays.stream(hiddenTooltip.getCommands()).anyMatch(c->"#StorageTooltip.Visible".equals(c.selector) && !org.bson.BsonDocument.parse(c.data).getBoolean("0").getValue()),"Owned tooltip hides on mouse exit");
            var text=StorageTooltip.content(new ItemStack("Rock_Stone",1),"en-US",100L);
            check(text.description().equals("100 stored.") && text.id().equals("ID: Rock_Stone"),"Owned tooltip retains network totals and native item ID");
            var rare=StorageTooltip.content(new ItemStack(SealData.PREFIX+3,1),"en-US",null);
            check(rare.skin().equals("Rare") && rare.showQuality(),"Owned tooltip retains native rarity appearance");
            var durability=StorageTooltip.content(new ItemStack("Rock_Stone",1).withMaxDurability(100).withDurability(75),"en-US",null);
            check(durability.stats().contains("75 / 100"),"Owned tooltip retains item durability");
            uiInventory.clear();uiHotbar.clear();
        } catch(ReflectiveOperationException ex) {throw new AssertionError(ex);}
        check(java.util.Arrays.stream(commands.getCommands()).anyMatch(c->"#Alphabetical.Disabled".equals(c.selector) && org.bson.BsonDocument.parse(c.data).getBoolean("0").getValue()),"Selected alphabetical filter uses native disabled highlight");
        check(java.util.Arrays.stream(commands.getCommands()).anyMatch(c->"#Quantity.Disabled".equals(c.selector) && !org.bson.BsonDocument.parse(c.data).getBoolean("0").getValue()),"Other filter remains enabled");
        check(events.getEvents().length==36 && java.util.Arrays.stream(events.getEvents()).noneMatch(e->e.type==com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Dismissing && !"#StorageRoot".equals(e.selector)),"Integrated Catalog binds search, sorting, native inventory gestures and dismissal");
        var actualSections=new java.util.HashMap<String,Integer>();
        for(var command:commands.getCommands()) if(command.selector!=null && command.selector.endsWith(".InventorySectionId"))
            actualSections.put(command.selector,org.bson.BsonDocument.parse(command.data).getInt32("0").getValue());
        check(actualSections.get("#Stored.InventorySectionId")==42,"Client storage grid uses the registered native window ID");
        check(actualSections.get("#Inventory.InventorySectionId")==-2,"Client inventory grid uses the native storage section ID");
        check(actualSections.get("#Hotbar.InventorySectionId")==-1,"Client hotbar grid uses the native hotbar section ID");
        var searchEvent=CatalogInput.parse("{\"Action\":\"search\",\"@Search\":\"stone\"}");
        check(searchEvent.search.equals("stone"),"Search input decodes native UI value binding");
        // Front banner must not conduct, even with a valid neighbouring conduit.
        for(var yaw:Rotation.values()) {
            var rotation=RotationTuple.of(yaw,Rotation.None);
            var ref=world.getChunkStore().getChunkSectionReferenceAtBlock(8,280,8);
            ConnectionVerificationPlugin.put(world.getChunkStore(),ref,new Vector3i(8,280,8),"AdvancedStorage_Runic_Catalog",rotation.index());
            var node=StorageNetwork.node(world,CATALOG);
            var direction=new Vector3i(0,0,1);rotation.applyRotationTo(direction);
            int[] axes={direction.z==-1?1:0,direction.x==1?2:0,direction.z==1?4:0,direction.x==-1?8:0};
            int front=java.util.Arrays.stream(axes).sum();
            check(node!=null && (node.ports()&front)==0 && Integer.bitCount(node.ports())==3,"Catalog front rejected for yaw "+yaw);
        }
        var ref=com.hypixel.hytale.server.core.modules.block.BlockModule.getBlockEntity(world,ARCHIVE.x(),ARCHIVE.y(),ARCHIVE.z());
        var component=world.getChunkStore().getStore().getComponent(ref,ItemContainerBlock.getComponentType());
        var encoded=ItemContainerBlock.CODEC.encode(component);
        var copy=ItemContainerBlock.CODEC.decode(encoded);
        check(SealData.read(copy.getItemContainer().getItemStack((short)3)).used()==1000,"Native archive component save/reload preserves contents");
        archive.clear();
        var sealInventory=new SimpleItemContainer((short)9);
        for(short slot=0;slot<9;slot++) sealInventory.setItemStackForSlot(slot,new ItemStack(SealData.PREFIX+5,1));
        for(short slot=0;slot<4;slot++) check(StorageNetwork.insertSeal(world,ARCHIVE,sealInventory,slot),"Insert seal slot "+slot);
        check(!StorageNetwork.insertSeal(world,ARCHIVE,sealInventory,(short)4),"Fifth seal rejected");
        check(!ItemStack.isEmpty(sealInventory.getItemStack((short)4)),"Rejected seal remains in inventory");
        check(StorageNetwork.scan(world,ARCHIVE).capacity()==512000,"Four tier 5 seals combine to 512000 items");
        check(!StorageNetwork.removeSeal(world,ARCHIVE,(short)0,full),"Full inventory prevents seal removal");
        check(!ItemStack.isEmpty(archive.getItemStack((short)0)),"Failed seal removal preserves slot");
        check(StorageNetwork.removeSeal(world,ARCHIVE,(short)0,sealInventory),"Remove seal into available inventory slot");
        check(ItemStack.isEmpty(archive.getItemStack((short)0)),"Removed seal slot clears");
        archive.clear();
        // Native drag selects a specific slot; native quick transfer finds an available slot.
        sealInventory.clear();sealInventory.setItemStackForSlot((short)0,portable);
        check(sealInventory.moveItemStackFromSlotToSlot((short)0,1,archive,(short)2).succeeded(),"Native drag into chosen seal slot");
        check(SealData.read(archive.getItemStack((short)2)).used()==1000,"Drag retains contents");
        check(archive.moveItemStackFromSlot((short)2,sealInventory).succeeded(),"Native quick transfer out");
        check(sealInventory.moveItemStackFromSlot((short)0,archive).succeeded(),"Native quick transfer into first available slot");
        check(!ItemStack.isEmpty(archive.getItemStack((short)0)),"Quick transfer chooses first slot");
        sealInventory.setItemStackForSlot((short)1,new ItemStack("Rock_Stone",1));
        check(!sealInventory.moveItemStackFromSlotToSlot((short)1,1,archive,(short)1).succeeded(),"Native drag rejects non-seal");
        check(!sealInventory.moveItemStackFromSlot((short)1,archive).succeeded(),"Native quick transfer rejects non-seal");
        archive.clear();
        nativeRoutes(world,archive,portable,true);
        archive.clear();
        var archiveScreen=new ArchiveContainer(world,ARCHIVE);
        check(archiveScreen.getCapacity()==4 && archive.getCapacity()==4,"Archive screen contains exactly four seal slots");
        nativeRoutes(world,archiveScreen,portable,true);
        check(!ItemStack.isEmpty(archive.getItemStack((short)0)),"Native Archive display writes to the actual persistent seal container");
        check(archiveScreen.getCapacity()==4,"Archive has no informational item slots");
        var archivePageCommands=new UICommandBuilder();
        var archiveWindow=new com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerBlockWindow(ARCHIVE.x(),ARCHIVE.y(),ARCHIVE.z(),0,BlockType.getAssetMap().getAsset("AdvancedStorage_Seal_Archive"),archiveScreen);
        archiveWindow.setId(43);
        new CatalogSettingsPage(null,world,ARCHIVE,archiveWindow,new SimpleItemContainer((short)36),new SimpleItemContainer((short)9),null).build(null,archivePageCommands,new UIEventBuilder(),null);
        check(java.util.Arrays.stream(archivePageCommands.getCommands()).anyMatch(c->"#ArchiveInfo.Text".equals(c.selector) && c.data.contains("1,000")),"Archive text reports actual used storage beside the four slots");
        check(java.util.Arrays.stream(archivePageCommands.getCommands()).anyMatch(c->"#ArchivePanel.Anchor".equals(c.selector) && BsonDocument.parse(c.data).getDocument("0").getInt32("Height").getValue()==486),"Archive fits four inventory rows without a reserved blank notice or backpack region");
        check(java.util.Arrays.stream(archivePageCommands.getCommands()).anyMatch(c->"#Notice.Visible".equals(c.selector) && !BsonDocument.parse(c.data).getBoolean("0").getValue()),"Empty Archive notices consume no layout space");
        check(java.util.Arrays.stream(archivePageCommands.getCommands()).noneMatch(c->c.selector!=null && c.selector.contains(".Anchor.")),"Archive sends complete Anchor objects instead of unsupported nested property selectors");
        var coreCommands=new UICommandBuilder();new CoreNetworkPage(null,world,CORE).build(coreCommands);
        check(java.util.Arrays.stream(coreCommands.getCommands()).anyMatch(c->"#Archives.Text".equals(c.selector) && c.data.contains("1")),"Core schematic counts actual connected Archives");
        archiveScreen.sortSeals();
        check(archive.getCapacity()==4 && SealData.read(archive.getItemStack((short)0)).used()==1000,"Native sorting touches seals only, never info thumbnails");
        var archiveTakeAll=new SimpleItemContainer((short)9);
        archiveScreen.moveAllItemStacksTo(archiveTakeAll);
        check(archiveTakeAll.countItemStacks(s->!ItemStack.isEmpty(s))==1,"Native Take All transfers real seals only");
        check(archive.isEmpty(),"Native Take All updates persistent Archive slots");
        archive.clear();
        var legacy=new SimpleItemContainer((short)8);
        for(short slot=0;slot<8;slot++)legacy.setItemStackForSlot(slot,portable);
        component.setItemContainer(legacy);
        check(!StorageNetwork.resizeArchive(world,ARCHIVE,full),"Legacy conversion waits for inventory space");
        check(component.getItemContainer().getCapacity()==8 && SealData.read(legacy.getItemStack((short)7)).used()==1000,"Blocked migration preserves excess seals");
        var recovery=new SimpleItemContainer((short)4);
        check(StorageNetwork.resizeArchive(world,ARCHIVE,recovery),"Migrate populated eight-slot archive");
        check(component.getItemContainer().getCapacity()==4 && component.getCapacity()==4,"Migration updates actual and declared slot count");
        check(recovery.countItemStacks(s->!ItemStack.isEmpty(s))==4,"All four excess seals returned to player");
        check(SealData.read(recovery.getItemStack((short)3)).used()==1000,"Recovered seals retain contents");
        copy=ItemContainerBlock.CODEC.decode(ItemContainerBlock.CODEC.encode(component));
        check(copy.getCapacity()==4 && copy.getItemContainer().getCapacity()==4,"Migrated capacity survives save/reload");
        component.getItemContainer().clear();
        for(var p:new StorageNetwork.Pos[]{CATALOG,PIPE,ARCHIVE,CORE})put(world,p,"Empty");
        System.out.println("ADVANCED_STORAGE_FUNCTIONAL_CHECKS_PASS cases="+cases);
    }
    @SuppressWarnings("unchecked")
    static void nativeRoutes(World world,com.hypixel.hytale.server.core.inventory.container.ItemContainer container,ItemStack item,boolean archive) {
        // Exercise the real MoveItem and SmartMoveItemStack handlers, rather than UI callbacks.
        var player=new com.hypixel.hytale.server.core.entity.entities.Player();
        var playerRef=new com.hypixel.hytale.server.core.universe.PlayerRef(com.hypixel.hytale.server.core.universe.world.storage.EntityStore.REGISTRY.newHolder(),java.util.UUID.randomUUID(),"StorageTest","en-US",null,null);
        player.getWindowManager().init(playerRef);
        var storage=new com.hypixel.hytale.server.core.inventory.InventoryComponent.Storage((short)9);
        var hotbar=new com.hypixel.hytale.server.core.inventory.InventoryComponent.Hotbar((short)9);
        var combined=new com.hypixel.hytale.server.core.inventory.InventoryComponent.Combined();
        var components=new java.util.HashMap<Object,Object>();
        components.put(com.hypixel.hytale.server.core.entity.entities.Player.getComponentType(),player);
        components.put(com.hypixel.hytale.server.core.inventory.InventoryComponent.Storage.getComponentType(),storage);
        components.put(com.hypixel.hytale.server.core.inventory.InventoryComponent.Hotbar.getComponentType(),hotbar);
        components.put(com.hypixel.hytale.server.core.inventory.InventoryComponent.Combined.getComponentType(),combined);
        var accessor=(com.hypixel.hytale.component.ComponentAccessor<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>)java.lang.reflect.Proxy.newProxyInstance(StorageVerification.class.getClassLoader(),new Class<?>[]{com.hypixel.hytale.component.ComponentAccessor.class},(proxy,method,args)-> {
            if(method.getName().equals("getComponent"))return components.get(args[1]);
            if(method.getName().equals("getExternalData"))return world.getEntityStore();
            if(method.getName().equals("getArchetype"))return com.hypixel.hytale.component.Archetype.of(components.keySet().toArray(com.hypixel.hytale.component.ComponentType[]::new));
            throw new UnsupportedOperationException(method.getName());
        });
        var window=new com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow(container);
        var opened=player.getWindowManager().openWindow(null,window,world.getEntityStore().getStore());
        check(opened!=null && opened.windowType==com.hypixel.hytale.protocol.packets.window.WindowType.Container,"Native OpenWindow packet enables container controls");
        int windowId=window.getId();
        var settings=com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings.defaults();
        if(archive) {
            storage.getInventory().setItemStackForSlot((short)0,item);
            com.hypixel.hytale.server.core.inventory.InventoryUtils.moveItem(null,-2,0,1,windowId,2,accessor);
            check(!ItemStack.isEmpty(container.getItemStack((short)2)),"MoveItem packet routes drag into Archive chosen slot");
            com.hypixel.hytale.server.core.inventory.InventoryUtils.smartMoveItem(null,windowId,2,1,com.hypixel.hytale.protocol.SmartMoveType.PutInHotbarOrWindow,settings,accessor);
            check(ItemStack.isEmpty(container.getItemStack((short)2)),"SmartMove packet routes Shift-click out of Archive");
            var source=storage.getInventory().isEmpty()?hotbar.getInventory():storage.getInventory();
            int section=source==storage.getInventory()?-2:-1;
            com.hypixel.hytale.server.core.inventory.InventoryUtils.smartMoveItem(null,section,0,1,com.hypixel.hytale.protocol.SmartMoveType.PutInHotbarOrWindow,settings,accessor);
            check(!ItemStack.isEmpty(container.getItemStack((short)0)),"SmartMove packet routes Shift-click into first Archive slot");
            check(SealData.read(container.getItemStack((short)0)).used()==1000,"Packet transfers preserve populated seals");
        } else {
            long used=StorageNetwork.scan(world,CATALOG).used();
            com.hypixel.hytale.server.core.inventory.InventoryUtils.moveItem(null,windowId,0,1,-2,0,accessor);
            check(StorageNetwork.scan(world,CATALOG).used()==used-1,"MoveItem packet withdraws from network through native Catalog");
            com.hypixel.hytale.server.core.inventory.InventoryUtils.smartMoveItem(null,-2,0,1,com.hypixel.hytale.protocol.SmartMoveType.PutInHotbarOrWindow,settings,accessor);
            check(StorageNetwork.scan(world,CATALOG).used()==used,"SmartMove packet deposits into network through native Catalog");
            gestureChecks(world,container,storage.getInventory(),hotbar.getInventory(),windowId,accessor,settings);
        }
    }
    static CatalogInput input(String action,int section,int slot,boolean shift) {
        var input=new CatalogInput();input.action=action;input.section=section;input.slot=slot;input.shift=shift;return input;
    }
    static void gestureChecks(World world,com.hypixel.hytale.server.core.inventory.container.ItemContainer catalog,
        com.hypixel.hytale.server.core.inventory.container.ItemContainer inventory,com.hypixel.hytale.server.core.inventory.container.ItemContainer hotbar,int windowId,
        com.hypixel.hytale.component.ComponentAccessor<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> accessor,
        com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings settings) {
        var sections=java.util.Map.of(CatalogGestures.NETWORK,new CatalogGestures.Section(windowId,catalog),
            CatalogGestures.INVENTORY,new CatalogGestures.Section(-2,inventory),CatalogGestures.HOTBAR,new CatalogGestures.Section(-1,hotbar));
        int[] calls={0};
        var gestures=new CatalogGestures(sections,new CatalogGestures.Commands() {
            public void move(int from,int slot,int amount,int to,int targetSlot) {calls[0]++;com.hypixel.hytale.server.core.inventory.InventoryUtils.moveItem(null,from,slot,amount,to,targetSlot,accessor);}
            public void quickMove(int from,int slot,int amount) {calls[0]++;com.hypixel.hytale.server.core.inventory.InventoryUtils.smartMoveItem(null,from,slot,amount,com.hypixel.hytale.protocol.SmartMoveType.PutInHotbarOrWindow,settings,accessor);}
        });
        long original=StorageNetwork.scan(world,CATALOG).used();
        var displayed=catalog.getItemStack((short)0);int quantity=displayed.getQuantity();
        var shiftPick=CatalogInput.parse("{\"Action\":\"pick\",\"Section\":\"-1001\",\"SlotIndex\":0,\"ShiftHeld\":true,\"ClickMouseButton\":1}");
        check(gestures.handle(shiftPick)==CatalogGestures.Result.PICKED,"Client Shift-click envelope selects the displayed network stack");
        check(StorageNetwork.scan(world,CATALOG).used()==original && inventory.isEmpty() && hotbar.isEmpty(),"Picking never removes items into a temporary cursor");
        check(gestures.handle(input("release",CatalogGestures.NETWORK,0,true))==CatalogGestures.Result.MOVED,"Shift release invokes the native quick transfer handler");
        check(StorageNetwork.scan(world,CATALOG).used()==original-quantity,"Integrated Shift-click withdraws exactly one displayed stack");
        int playerGrid=inventory.isEmpty()?CatalogGestures.HOTBAR:CatalogGestures.INVENTORY;
        gestures.capture();gestures.handle(input("pick",playerGrid,0,true));
        check(gestures.handle(input("release",playerGrid,0,true))==CatalogGestures.Result.MOVED,"Integrated Shift-click deposits back into the network");
        check(StorageNetwork.scan(world,CATALOG).used()==original,"Shift round-trip conserves total contents");
        gestures.capture();
        var drop=input("drop",CatalogGestures.INVENTORY,4,false);drop.sourceSection=windowId;drop.sourceSlot=0;drop.quantity=3;drop.itemId=displayed.getItemId();
        check(gestures.handle(drop)==CatalogGestures.Result.MOVED,"Real client drop without a preceding pick routes a withdrawal");
        check(inventory.getItemStack((short)4).getQuantity()==3 && StorageNetwork.scan(world,CATALOG).used()==original-3,"Dragged quantity is credited and debited correctly");
        int submitted=calls[0];
        check(gestures.handle(drop)==CatalogGestures.Result.IGNORED && calls[0]==submitted,"Duplicate drop callback cannot replay a transfer");
        gestures.capture();
        int empty=0;while(!ItemStack.isEmpty(catalog.getItemStack((short)empty)))empty++;
        var put=input("drop",CatalogGestures.NETWORK,empty,false);put.sourceSection=-2;put.sourceSlot=4;put.itemId=displayed.getItemId();put.quantity=3;
        check(gestures.handle(put)==CatalogGestures.Result.MOVED && inventory.getItemStack((short)4)==null,"Drop-only client deposit commits all items to the seals");
        check(StorageNetwork.scan(world,CATALOG).used()==original,"Drag round-trip conserves contents and metadata");
        submitted=calls[0];
        check(gestures.handle(put)==CatalogGestures.Result.IGNORED && calls[0]==submitted,"Duplicate drop-only deposit cannot replay a transfer");
        gestures.capture();
        var invalidDrop=input("drop",CatalogGestures.INVENTORY,4,false);invalidDrop.sourceSection=-999;invalidDrop.sourceSlot=0;invalidDrop.itemId=displayed.getItemId();invalidDrop.quantity=3;
        check(gestures.handle(invalidDrop)==CatalogGestures.Result.STALE && calls[0]==submitted,"Drop-only envelope cannot name an unknown native source");
        gestures.capture();gestures.handle(input("pick",CatalogGestures.NETWORK,0,false));gestures.capture();
        check(gestures.holding(),"Background refresh preserves the active drag selection");
        check(gestures.handle(input("cancel",CatalogGestures.NETWORK,0,false))==CatalogGestures.Result.CANCELLED,"Cancelling a drag clears selection without moving items");
        check(StorageNetwork.scan(world,CATALOG).used()==original,"Cancelled drag retains all stored items");
        gestures.capture();gestures.handle(input("pick",CatalogGestures.NETWORK,0,false));
        var forged=input("drop",CatalogGestures.INVENTORY,4,false);forged.sourceSection=-999;forged.sourceSlot=0;
        submitted=calls[0];
        check(gestures.handle(forged)==CatalogGestures.Result.STALE && calls[0]==submitted,"Forged drag source IDs are rejected before native transfer");
        gestures.capture();
        int half=(quantity+1)/2;
        var split=CatalogInput.parse("{\"Action\":\"pick\",\"Section\":-1001,\"SlotIndex\":0,\"ShiftHeld\":true,\"PressedMouseButton\":3}");
        check(gestures.handle(split)==CatalogGestures.Result.PICKED,"Shift-right picks half instead of one");
        var splitDrop=input("drop",CatalogGestures.INVENTORY,1,false);
        splitDrop.sourceSection=windowId;splitDrop.sourceSlot=0;splitDrop.itemId=displayed.getItemId();splitDrop.quantity=half;splitDrop.dragRight=true;
        check(gestures.handle(splitDrop)==CatalogGestures.Result.MOVED,"Left placement accepts half picked with right mouse");
        check(inventory.getItemStack((short)1).getQuantity()==half && StorageNetwork.scan(world,CATALOG).used()==original-half,"Split withdrawal preserves exact amounts");
        com.hypixel.hytale.server.core.inventory.InventoryUtils.smartMoveItem(null,-2,1,half,com.hypixel.hytale.protocol.SmartMoveType.PutInHotbarOrWindow,settings,accessor);
        gestures.capture();
        var rightPick=input("pick",CatalogGestures.NETWORK,0,false);rightPick.right=true;
        check(gestures.handle(rightPick)==CatalogGestures.Result.PICKED,"Plain right pickup selects one");
        var rightPress=input("press",CatalogGestures.INVENTORY,1,false);rightPress.right=true;rightPress.explicitButton=true;
        rightPress.sourceSection=windowId;rightPress.sourceSlot=0;rightPress.itemId=displayed.getItemId();rightPress.quantity=1;
        check(gestures.handle(rightPress)==CatalogGestures.Result.MOVED,"Right press places one from original stack");
        check(gestures.holding(),"Right press retains the remaining source");
        check(gestures.handle(input("enter",CatalogGestures.INVENTORY,2,false))==CatalogGestures.Result.MOVED,"Held-right sweep places one on the next slot");
        submitted=calls[0];
        check(gestures.handle(input("enter",CatalogGestures.INVENTORY,2,false))==CatalogGestures.Result.IGNORED && calls[0]==submitted,"Sweep deduplicates a hovered slot");
        check(inventory.getItemStack((short)1).getQuantity()==1 && inventory.getItemStack((short)2).getQuantity()==1,"Sweep deposits one per slot");
        gestures.handle(input("release",CatalogGestures.INVENTORY,2,false));
        var trailing=CatalogInput.parse("{\"Action\":\"drop\",\"Section\":-1002,\"SlotIndex\":2,\"PressedMouseButton\":3}");
        check(gestures.handle(trailing)==CatalogGestures.Result.IGNORED && calls[0]==submitted,"Right release and Dropped cannot duplicate a placement");
        // The next left click may target the last right-placement slot and carry only pickup metadata.
        check(gestures.handle(input("press",CatalogGestures.INVENTORY,2,false))==CatalogGestures.Result.MOVED,"Left press places carried remainder immediately");
        var finish=input("drop",CatalogGestures.INVENTORY,2,false);finish.quantity=quantity-2;
        check(gestures.handle(finish)==CatalogGestures.Result.IGNORED,"Late drop does not repeat the left placement");
        check(StorageNetwork.scan(world,CATALOG).used()==original-quantity,"Network refill cannot expand the carried stack budget");
        for(int targetSlot:new int[]{1,2,3}) {
            var stack=inventory.getItemStack((short)targetSlot);
            if(!ItemStack.isEmpty(stack)) com.hypixel.hytale.server.core.inventory.InventoryUtils.smartMoveItem(null,-2,targetSlot,stack.getQuantity(),com.hypixel.hytale.protocol.SmartMoveType.PutInHotbarOrWindow,settings,accessor);
        }
        check(StorageNetwork.scan(world,CATALOG).used()==original,"Split and right-sweep round trips conserve all contents");
        gestures.cancel();gestures.capture();
        var sourceOnly=input("press",CatalogGestures.INVENTORY,1,false);sourceOnly.sourceSection=windowId;sourceOnly.sourceSlot=0;
        sourceOnly.itemId=displayed.getItemId();sourceOnly.quantity=half;sourceOnly.right=false;
        check(gestures.handle(sourceOnly)==CatalogGestures.Result.MOVED,"Real left press commits split without waiting for Dropped");
        gestures.handle(input("release",CatalogGestures.INVENTORY,1,false));
        var sourceOnlyDrop=input("drop",CatalogGestures.INVENTORY,1,false);sourceOnlyDrop.sourceSection=windowId;sourceOnlyDrop.sourceSlot=0;sourceOnlyDrop.itemId=displayed.getItemId();sourceOnlyDrop.quantity=half;
        check(gestures.handle(sourceOnlyDrop)==CatalogGestures.Result.IGNORED,"Late Dropped cannot replay a left press transfer");
        check(inventory.getItemStack((short)1).getQuantity()==half,"Drop-only split uses the native cursor quantity");
        com.hypixel.hytale.server.core.inventory.InventoryUtils.smartMoveItem(null,-2,1,half,com.hypixel.hytale.protocol.SmartMoveType.PutInHotbarOrWindow,settings,accessor);
        gestures.cancel();gestures.capture();
        var loose=new ItemStack("Rock_Stone",7).withMetadata("Rune",new BsonString("gather"));
        inventory.setItemStackForSlot((short)1,loose);
        inventory.setItemStackForSlot((short)2,loose.withQuantity(9));
        hotbar.setItemStackForSlot((short)0,loose.withQuantity(12));
        var different=loose.withMetadata("Rune",new BsonString("other"));
        inventory.setItemStackForSlot((short)3,different);
        gestures.cancel();gestures.capture();
        gestures.handle(input("pick",CatalogGestures.INVENTORY,1,false));
        check(gestures.handle(input("double",CatalogGestures.INVENTORY,1,false))==CatalogGestures.Result.MOVED,"Native slot double click gathers matching split stacks");
        check(inventory.getItemStack((short)1).getQuantity()==28 && ItemStack.isEmpty(inventory.getItemStack((short)2)) && hotbar.isEmpty(),"Double click combines inventory and hotbar without duplicating items");
        check(different.equals(inventory.getItemStack((short)3)),"Double click preserves distinct item metadata");
        check(gestures.handle(input("drop",CatalogGestures.INVENTORY,1,false))==CatalogGestures.Result.IGNORED,"Double click suppresses trailing drop callback");
        inventory.setItemStackForSlot((short)1,loose);
        inventory.setItemStackForSlot((short)2,loose.withQuantity(9));hotbar.setItemStackForSlot((short)0,loose.withQuantity(12));
        gestures.cancel();gestures.capture();
        var firstPress=input("press",CatalogGestures.INVENTORY,1,false);firstPress.sourceSection=-2;firstPress.sourceSlot=1;
        firstPress.itemId=loose.getItemId();firstPress.quantity=7;firstPress.clickCount=1;
        check(gestures.handle(firstPress)==CatalogGestures.Result.CANCELLED,"Single native press still cancels immediately");
        check(inventory.getItemStack((short)1).getQuantity()==7,"Single click cancellation does not gather items");
        var secondPress=input("press",CatalogGestures.INVENTORY,1,false);secondPress.sourceSection=-2;secondPress.sourceSlot=1;
        secondPress.itemId=loose.getItemId();secondPress.quantity=7;secondPress.clickCount=2;
        check(gestures.handle(secondPress)==CatalogGestures.Result.MOVED,"Native second press gathers without waiting for SlotDoubleClicking");
        check(inventory.getItemStack((short)1).getQuantity()==28 && ItemStack.isEmpty(inventory.getItemStack((short)2)) && hotbar.isEmpty(),"Double press reunites stacks after single-click cancellation");
        check(different.equals(inventory.getItemStack((short)3)),"Double press preserves incompatible metadata");
        inventory.setItemStackForSlot((short)1,null);inventory.setItemStackForSlot((short)2,null);inventory.setItemStackForSlot((short)3,null);
        gestures.cancel();gestures.capture();
        var initialRight=input("pick",CatalogGestures.NETWORK,0,false);initialRight.right=true;
        gestures.handle(initialRight);
        var splitPress=input("press",CatalogGestures.INVENTORY,1,false);splitPress.sourceSection=windowId;splitPress.sourceSlot=0;
        splitPress.itemId=displayed.getItemId();splitPress.quantity=half;splitPress.dragRight=true;
        check(gestures.handle(splitPress)==CatalogGestures.Result.MOVED,"Native half cursor quantity replaces an earlier unit pickup");
        check(inventory.getItemStack((short)1).getQuantity()==half,"Split press places the full half cursor quantity");
        com.hypixel.hytale.server.core.inventory.InventoryUtils.smartMoveItem(null,-2,1,half,com.hypixel.hytale.protocol.SmartMoveType.PutInHotbarOrWindow,settings,accessor);
        gestures.cancel();gestures.capture();
        var splitStack=new ItemStack("Rock_Stone",24).withMetadata("Rune",new BsonString("split-replay"));
        inventory.setItemStackForSlot((short)0,splitStack);
        gestures.cancel();gestures.capture();
        var terminal=input("press",CatalogGestures.INVENTORY,1,false);terminal.sourceSection=-2;terminal.sourceSlot=0;terminal.itemId="Rock_Stone";terminal.quantity=12;terminal.dragRight=true;
        check(gestures.handle(terminal)==CatalogGestures.Result.MOVED,"Real split sequence transfers twelve of twenty-four");
        check(gestures.takeResetGrid()==CatalogGestures.INVENTORY,"Terminal split requests native source grid disposal");
        var repeated=input("press",CatalogGestures.INVENTORY,2,false);repeated.sourceSection=-2;repeated.sourceSlot=0;repeated.itemId="Rock_Stone";repeated.quantity=12;repeated.dragRight=true;
        check(gestures.handle(repeated)==CatalogGestures.Result.IGNORED,"Stale native cursor cannot consume the second half into a new slot");
        check(inventory.getItemStack((short)0).getQuantity()==12 && inventory.getItemStack((short)1).getQuantity()==12 && ItemStack.isEmpty(inventory.getItemStack((short)2)),"Split replay preserves both real halves");
        gestures.handle(input("cancel",CatalogGestures.INVENTORY,0,false));
        check(gestures.handle(repeated)==CatalogGestures.Result.IGNORED,"Late native disposal callback does not reopen completed source");
        gestures.handle(input("pick",CatalogGestures.INVENTORY,0,false));
        check(gestures.handle(repeated)==CatalogGestures.Result.MOVED,"Fresh pickup can use the remaining half normally");
        inventory.setItemStackForSlot((short)0,null);inventory.setItemStackForSlot((short)1,null);inventory.setItemStackForSlot((short)2,null);
        gestures.cancel();gestures.capture();
        gestures.cancel();gestures.capture();
        gestures.handle(input("pick",CatalogGestures.NETWORK,0,false));
        submitted=calls[0];
        check(gestures.handle(input("press",CatalogGestures.NETWORK,0,false))==CatalogGestures.Result.CANCELLED,"Clicking the original slot cancels a held stack");
        check(!gestures.holding() && gestures.takeResetGrid()==CatalogGestures.NETWORK,"Origin cancellation releases the native cursor");
        check(calls[0]==submitted && StorageNetwork.scan(world,CATALOG).used()==original,"Origin cancellation never moves stored items");
        var late=input("drop",CatalogGestures.INVENTORY,4,false);late.sourceSection=windowId;late.sourceSlot=0;late.itemId=displayed.getItemId();late.quantity=quantity;
        check(gestures.handle(late)==CatalogGestures.Result.IGNORED && calls[0]==submitted,"Cancelled cursor cannot replay a late drop");
        gestures.cancel();gestures.capture();
        var splitCancel=input("pick",CatalogGestures.NETWORK,0,true);splitCancel.right=true;gestures.handle(splitCancel);
        check(gestures.handle(input("press",CatalogGestures.NETWORK,0,false))==CatalogGestures.Result.CANCELLED,"Clicking the origin also cancels a half-stack selection");
        check(calls[0]==submitted && StorageNetwork.scan(world,CATALOG).used()==original,"Cancelling half-stack retains all contents");
        gestures.cancel();gestures.capture();
        for(int carried:new int[]{1,half,quantity}) {
            gestures.cancel();gestures.capture();
            var single=input("press",CatalogGestures.NETWORK,0,false);
            single.sourceSection=windowId;single.sourceSlot=0;single.itemId=displayed.getItemId();single.quantity=carried;single.dragRight=true;single.clickCount=1;
            check(gestures.handle(single)==CatalogGestures.Result.CANCELLED,"First native left click cancels origin without a preceding pickup: "+carried);
            check(!gestures.holding() && gestures.takeResetGrid()==CatalogGestures.NETWORK && calls[0]==submitted,"Single-click cancellation releases cursor without moving items");
        }
        gestures.cancel();gestures.capture();
        var first=new ItemStack("Rock_Stone",4).withMetadata("Rune",new BsonString("original"));
        var replacement=first.withMetadata("Rune",new BsonString("replacement"));
        inventory.setItemStackForSlot((short)6,first);gestures.capture();gestures.handle(input("pick",CatalogGestures.INVENTORY,6,false));
        inventory.setItemStackForSlot((short)6,replacement);submitted=calls[0];
        check(gestures.handle(input("drop",CatalogGestures.NETWORK,empty,false))==CatalogGestures.Result.STALE && calls[0]==submitted,"Stale metadata selection cannot move a replacement item");
        check(inventory.getItemStack((short)6).equals(replacement),"Stale drag leaves the replacement item intact");inventory.setItemStackForSlot((short)6,null);
        check(gestures.handle(input("pick",-999,0,false))==CatalogGestures.Result.IGNORED,"Unknown UI inventory sections are ignored");
        var alias=CatalogInput.parse("{\"Action\":\"drop\",\"Section\":-1002,\"SlotIndex\":4,\"SourceInventorySectionId\":\"-1001\",\"SourceSlotId\":\"0\",\"ItemStackQuantity\":\"3\",\"PressedMouseButton\":\"Right\"}");
        check(alias.sourceSection==CatalogGestures.NETWORK && alias.quantity==3 && !alias.right && alias.dragRight,"Input decoder accepts supported numeric and named mouse/drag forms");
        for(short slot=0;slot<inventory.getCapacity();slot++) inventory.setItemStackForSlot(slot,new ItemStack("Rock_Stone",100).withMetadata("Rune",new BsonString("full")));
        for(short slot=0;slot<hotbar.getCapacity();slot++) hotbar.setItemStackForSlot(slot,new ItemStack("Rock_Stone",100).withMetadata("Rune",new BsonString("full")));
        gestures.capture();gestures.handle(input("pick",CatalogGestures.NETWORK,0,true));
        check(gestures.handle(input("release",CatalogGestures.NETWORK,0,true))==CatalogGestures.Result.REJECTED,"Full inventory rejects integrated Shift-click without consuming stock");
        check(StorageNetwork.scan(world,CATALOG).used()==original,"Rejected full-inventory gesture preserves all network items");
        inventory.clear();hotbar.clear();put(world,CORE,"Empty");
        inventory.setItemStackForSlot((short)1,new ItemStack("Rock_Stone",3));
        gestures.capture();gestures.handle(input("pick",CatalogGestures.INVENTORY,1,true));
        check(gestures.handle(input("release",CatalogGestures.INVENTORY,1,true))==CatalogGestures.Result.REJECTED,"Offline integrated Catalog rejects network transfer");
        check(inventory.countItemStacks(s->!ItemStack.isEmpty(s))+hotbar.countItemStacks(s->!ItemStack.isEmpty(s))==3,"Offline gesture preserves all three player items across inventory sections");
        inventory.clear();hotbar.clear();put(world,CORE,"AdvancedStorage_Arcane_Core");
        check(StorageNetwork.scan(world,CATALOG).used()==original,"Integrated gesture tests leave persistent network contents unchanged");
    }
}
