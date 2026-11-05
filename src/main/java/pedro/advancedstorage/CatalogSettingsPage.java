package pedro.advancedstorage;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.SmartMoveType;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.entity.entities.player.windows.*;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.inventory.container.*;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import java.util.concurrent.*;

/** Common.ui styling, live inventory grids and explicit native transaction routing in one screen. */
public final class CatalogSettingsPage extends InteractiveCustomUIPage<CatalogInput> {
    static final com.hypixel.hytale.logger.HytaleLogger LOGGER=com.hypixel.hytale.logger.HytaleLogger.forEnclosingClass();
    int traceRemaining=160;
    static final Map<UUID,CatalogSettingsPage> OPEN=new ConcurrentHashMap<>();
    static final Map<UUID,Set<Integer>> RECENTLY_CLOSED=new ConcurrentHashMap<>();
    static final BuilderCodec<CatalogInput> CODEC=BuilderCodec.builder(CatalogInput.class,CatalogInput::new).build();
    final World world;
    final StorageNetwork.Pos origin;
    final ContainerBlockWindow window;
    final TravelerLinks.Session remote;
    final ContainerBlockWindow benchWindow;
    final BenchSocketsContainer benchSlots;
    final StorageNetwork.Pos benchOrigin;
    NetworkBenches.Station selectedBench;
    List<com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe> recipes=List.of();
    int selectedRecipe=-1;
    static final int SCROLL_SEGMENTS=16;
    static final int BENCH_COLUMNS=5;
    static final int BENCH_SLOT_PITCH=52;
    static final int BENCH_LEFT_INSET=104;
    private CatalogMouseControls mouseControls;
    final pedro.advancedstorage.ui.GridViewport recipeViewport=new pedro.advancedstorage.ui.GridViewport(9,4);
    int mountedRecipeRow=-1;
    String benchFingerprint="",recipeFingerprint="";
    final List<java.util.function.Consumer<UIEventBuilder>> pendingBenchBindings=new ArrayList<>();
    final CatalogContainer catalog;
    final ItemContainer stored;
    final boolean archive;
    final String role;
    final ItemContainer inventory,hotbar,backpack;
    final String session=UUID.randomUUID().toString();
    CatalogView view;
    CatalogGestures gestures;
    final StorageTooltip tooltip=new StorageTooltip();
    boolean active;
    String notice="";
    boolean closing,dismissed;
    final Set<String> observedPackets=ConcurrentHashMap.newKeySet();
    final Map<Integer,Integer> gridVersions=new HashMap<>();
    final Map<Integer,ItemStack[]> mounted=new HashMap<>();
    final Map<String,String> labels=new HashMap<>();
    long mountedTotals=Long.MIN_VALUE;
    com.hypixel.hytale.event.EventRegistration<?,?> windowClosed;
    public CatalogSettingsPage(PlayerRef player,World world,StorageNetwork.Pos origin) {
        this(player,world,origin,new ContainerBlockWindow(origin.x(),origin.y(),origin.z(),0,
            com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getAsset("AdvancedStorage_Runic_Catalog"),
            new CatalogContainer(world,origin,preference(player))),new SimpleItemContainer((short)36),new SimpleItemContainer((short)9),null);
    }
    public CatalogSettingsPage(PlayerRef player,World world,StorageNetwork.Pos origin,ContainerBlockWindow window,ItemContainer inventory,ItemContainer hotbar,ItemContainer backpack) {
        this(player,world,origin,window,inventory,hotbar,backpack,null);
    }
    public CatalogSettingsPage(PlayerRef player,World world,StorageNetwork.Pos origin,ContainerBlockWindow window,ItemContainer inventory,ItemContainer hotbar,ItemContainer backpack,TravelerLinks.Session remote) {
        super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,CODEC);
        this.remote=remote;
        this.world=world;this.origin=origin;this.window=window;this.stored=window.getItemContainer();this.catalog=stored instanceof CatalogContainer c?c:null;
        if(catalog!=null) catalog.enableViewport(7);
        this.archive=catalog==null;this.role=archive?"Archive":"Catalog";
        this.inventory=inventory;this.hotbar=hotbar;this.backpack=backpack;view=archive?CatalogView.DEFAULT:catalog.view;
        var candidates=archive?List.<StorageNetwork.Pos>of():StorageNetwork.scan(world,origin).catalogs;
        benchOrigin=archive?null:remote==null?origin:candidates.stream().findFirst().orElse(null);
        benchSlots=benchOrigin==null?null:new BenchSocketsContainer(world,benchOrigin);
        benchWindow=benchSlots==null?null:new ContainerBlockWindow(benchOrigin.x(),benchOrigin.y(),benchOrigin.z(),0,
            com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getAsset("AdvancedStorage_Runic_Catalog"),benchSlots) {
                @Override public boolean validate(Ref<EntityStore> actor,ComponentAccessor<EntityStore> accessor){return canAccess(actor)&&StorageNetwork.scan(world,origin).catalogs.contains(benchOrigin);}
            };
    }
    Window[] windows(){return benchWindow==null?new Window[]{window}:new Window[]{window,benchWindow};}
    static CatalogView preference(PlayerRef player) {
        return player == null ? CatalogView.DEFAULT : AdvancedStoragePlugin.catalogPreferences.get(player.getUuid());
    }
    void bind(UIEventBuilder events,CustomUIEventBindingType type,String selector,String action,Integer grid) {
        var data=EventData.of("Action",action).append("Session",session);
        if(grid!=null) data.append("Section",Integer.toString(grid)).append("GridVersion",Integer.toString(gridVersions.getOrDefault(grid,0)));
        if(action.equals("search")) data.append("@Search","#Search.Value");
        events.addEventBinding(type,selector,data,false);
    }
    String tooltipLanguage() {return playerRef==null?"en-US":playerRef.getLanguage();}
    void bindGrid(UIEventBuilder events,String selector,int grid) {
        bind(events,CustomUIEventBindingType.SlotClicking,selector,"pick",grid);
        bind(events,CustomUIEventBindingType.SlotDoubleClicking,selector,"double",grid);
        bind(events,CustomUIEventBindingType.SlotClickReleaseWhileDragging,selector,"release",grid);
        bind(events,CustomUIEventBindingType.SlotClickPressWhileDragging,selector,"press",grid);
        bind(events,CustomUIEventBindingType.Dropped,selector,"drop",grid);
        bind(events,CustomUIEventBindingType.DragCancelled,selector,"cancel",grid);
        bind(events,CustomUIEventBindingType.SlotMouseEntered,selector,"enter",grid);
        bind(events,CustomUIEventBindingType.SlotMouseExited,selector,"exit",grid);
    }
    void resetGrid(UICommandBuilder commands,UIEventBuilder events,int grid) {
        String name=switch(grid) {
            case CatalogGestures.NETWORK -> "Stored";
            case CatalogGestures.INVENTORY -> "Inventory";
            case CatalogGestures.HOTBAR -> "Hotbar";
            case CatalogGestures.BENCHES -> "Benches";
            default -> "Backpack";
        };
        gridVersions.merge(grid,1,Integer::sum);
        String host=!archive && grid==CatalogGestures.NETWORK?"#StoredCanvas":"#"+name+"Host";
        if(!archive && grid==CatalogGestures.NETWORK) tooltip.invalidate(commands);
        commands.clear(host);
        commands.append(host,"AdvancedStorage/"+(archive?"Archive":"Catalog")+name+"Grid.ui");
        commands.set("#"+name+".InventorySectionId",gestures.sections.get(grid).nativeId());
        if(archive && grid==CatalogGestures.INVENTORY) setAnchor(commands,"#Inventory",null,((inventory.getCapacity()+8)/9)*60);
        if(archive && grid==CatalogGestures.BACKPACK && backpack!=null) setAnchor(commands,"#Backpack",null,Math.min(180,((backpack.getCapacity()+8)/9)*60));
        bindGrid(events,"#"+name,grid);
        mounted.remove(grid);
    }
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder commands,UIEventBuilder events,Store<EntityStore> store) {
        commands.append(archive?"AdvancedStorage/Archive.ui":"AdvancedStorage/Catalog.ui");
        if(!archive && catalogHeight()>700) {
            setAnchor(commands,"#CatalogFrame",1592,catalogHeight());
            setAnchor(commands,"#CatalogPanel",1120,catalogHeight());
        }
        if(remote!=null)commands.set("#CatalogTitle.Text","TRAVELER'S CATALOG - "+remote.tablet().toString().substring(0,8));
        commands.append("#PageTooltipHost","AdvancedStorage/StorageTooltip.ui");
        if(!archive) {
            commands.set("#Search.Value",view.search());
        }
        var sections=new LinkedHashMap<Integer,CatalogGestures.Section>();
        sections.put(CatalogGestures.NETWORK,new CatalogGestures.Section(window.getId(),stored));
        sections.put(CatalogGestures.INVENTORY,new CatalogGestures.Section(InventoryComponent.STORAGE_SECTION_ID,inventory));
        sections.put(CatalogGestures.HOTBAR,new CatalogGestures.Section(InventoryComponent.HOTBAR_SECTION_ID,hotbar));
        if(backpack!=null && backpack.getCapacity()>0) sections.put(CatalogGestures.BACKPACK,new CatalogGestures.Section(InventoryComponent.BACKPACK_SECTION_ID,backpack));
        if(benchWindow!=null)sections.put(CatalogGestures.BENCHES,new CatalogGestures.Section(benchWindow.getId(),benchSlots));
        gestures=new CatalogGestures(sections,new CatalogGestures.Commands() {
            public void move(int from,int slot,int quantity,int to,int targetSlot) {
                if(catalog!=null && to==window.getId() && from!=to) {
                    targetSlot=catalog.viewport.capacity();
                }
                InventoryUtils.moveItem(ref,from,slot,quantity,to,targetSlot,store);
            }
            public void quickMove(int from,int slot,int quantity) {
                if(benchWindow!=null&&from==benchWindow.getId()) {
                    var item=benchSlots.getItemStack((short)slot);
                    for(int grid:new int[]{CatalogGestures.INVENTORY,CatalogGestures.HOTBAR,CatalogGestures.BACKPACK}){
                        var target=sections.get(grid);if(target==null)continue;
                        for(short i=0;i<target.container().getCapacity();i++){
                            var existing=target.container().getItemStack(i);
                            if(ItemStack.isEmpty(existing)||!ItemStack.isEmpty(item)&&existing.isStackableWith(item)&&existing.getQuantity()<item.getItem().getMaxStack()){
                                InventoryUtils.moveItem(ref,from,slot,1,target.nativeId(),i,store);return;
                            }
                        }
                    }
                    return;
                }
                if(benchWindow!=null&&from!=benchWindow.getId()) {
                    var source=sections.values().stream().filter(s->s.nativeId()==from).findFirst().orElse(null);
                    if(source!=null&&NetworkBenches.type(source.container().getItemStack((short)slot))!=null) {
                        for(short target=0;target<benchSlots.getCapacity();target++)if(ItemStack.isEmpty(benchSlots.getItemStack(target))){InventoryUtils.moveItem(ref,from,slot,1,benchWindow.getId(),target,store);return;}
                    }
                }
                var settings=store.getComponent(ref,PlayerSettings.getComponentType());
                InventoryUtils.smartMoveItem(ref,from,slot,quantity,SmartMoveType.PutInHotbarOrWindow,settings==null?PlayerSettings.defaults():settings,store);
            }
        });
        if(!archive) {
        commands.append("#RowControls","AdvancedStorage/Library/RowScroll.ui");
        bind(events,CustomUIEventBindingType.Activating,"#RowUp","scroll-up",null);
        bind(events,CustomUIEventBindingType.Activating,"#RowDown","scroll-down",null);
        for(int i=0;i<SCROLL_SEGMENTS;i++) {
            commands.append("#RowTrack","AdvancedStorage/Library/RowTrackSegment.ui");
            String button="#RowTrack["+i+"] #Jump";
            bind(events,CustomUIEventBindingType.Activating,button,"scroll-track/"+i,null);
            if(CatalogMouseControls.clientPid(playerRef)>0) {
                bind(events,CustomUIEventBindingType.MouseEntered,button,"pointer-track-enter/"+i,null);
                bind(events,CustomUIEventBindingType.MouseExited,button,"pointer-track-exit/"+i,null);
            }
        }
        bind(events,CustomUIEventBindingType.ValueChanged,"#Search","search",null);
        bind(events,CustomUIEventBindingType.Activating,"#Alphabetical","alphabetical",null);
        bind(events,CustomUIEventBindingType.Activating,"#Quantity","quantity",null);
        bind(events,CustomUIEventBindingType.Activating,"#StorageMode","storage-mode",null);
        bind(events,CustomUIEventBindingType.Activating,"#CraftOne","craft-one",null);
        bind(events,CustomUIEventBindingType.Activating,"#CraftTen","craft-ten",null);
        bind(events,CustomUIEventBindingType.Activating,"#StopCraft","stop-craft",null);
        bind(events,CustomUIEventBindingType.Activating,"#UpgradeBench","upgrade-bench",null);
        bind(events,CustomUIEventBindingType.SlotClicking,"#Recipes","recipe",CatalogGestures.RECIPES);
        bind(events,CustomUIEventBindingType.SlotMouseEntered,"#Recipes","enter",CatalogGestures.RECIPES);
        bind(events,CustomUIEventBindingType.SlotMouseExited,"#Recipes","exit",CatalogGestures.RECIPES);
        commands.set("#BenchesGroup.Visible",benchWindow!=null);
        if(benchWindow!=null) {
            resetGrid(commands,events,CatalogGestures.BENCHES);
            for(int i=0;i<NetworkBenches.SLOTS;i++) {
                commands.append("#BenchGhosts","AdvancedStorage/CatalogBenchGhost.ui");
                var anchor=new com.hypixel.hytale.server.core.ui.Anchor();
                anchor.setLeft(com.hypixel.hytale.server.core.ui.Value.of(i%BENCH_COLUMNS*BENCH_SLOT_PITCH+2));
                anchor.setTop(com.hypixel.hytale.server.core.ui.Value.of(i/BENCH_COLUMNS*BENCH_SLOT_PITCH+2));
                anchor.setWidth(com.hypixel.hytale.server.core.ui.Value.of(44));
                anchor.setHeight(com.hypixel.hytale.server.core.ui.Value.of(44));
                commands.setObject("#BenchGhosts["+i+"].Anchor",anchor);
            }
        }
        }
        String[] selectors={"#Stored","#Inventory","#Hotbar","#Backpack"};
        int[] grids={CatalogGestures.NETWORK,CatalogGestures.INVENTORY,CatalogGestures.HOTBAR,CatalogGestures.BACKPACK};
        for(int i=0;i<selectors.length;i++) {
            // ItemGrid's drag controller requires the real native section ID, not our event-envelope key.
            var section=sections.get(grids[i]);
            commands.set(selectors[i]+".InventorySectionId",section==null?InventoryComponent.BACKPACK_SECTION_ID:section.nativeId());
            bindGrid(events,selectors[i],grids[i]);

        }
        bind(events,CustomUIEventBindingType.Dismissing,"#StorageRoot","dismiss",null);
        if(archive) {
            setAnchor(commands,"#InventoryHost",null,((inventory.getCapacity()+8)/9)*60);
            setAnchor(commands,"#Inventory",null,((inventory.getCapacity()+8)/9)*60);
            if(sections.containsKey(CatalogGestures.BACKPACK)) setAnchor(commands,"#Backpack",null,Math.min(180,((backpack.getCapacity()+8)/9)*60));
        }
        commands.set("#BackpackGroup.Visible",sections.containsKey(CatalogGestures.BACKPACK));render(commands);
        pendingBenchBindings.forEach(binding->binding.accept(events));pendingBenchBindings.clear();
        if(playerRef!=null && !active) {
            active=true;OPEN.put(playerRef.getUuid(),this);
            if(!archive) mouseControls=CatalogMouseControls.start(this,playerRef);
            windowClosed=window.registerCloseEvent(event->world.execute(()->{if(active) close();}));
            scheduleRefresh();
        }
    }
    void render(UICommandBuilder commands) {
        if(catalog!=null) {
            if(gestures.holding()) catalog.refresh();else catalog.refreshOrder();
        } else ((ArchiveContainer)stored).refresh();
        var network=catalog!=null?catalog.network:StorageNetwork.scan(world,origin);
        if(archive) {
            long used=0,capacity=0;int count=0;
            for(short i=0;i<SealData.ARCHIVE_SLOTS;i++) {var seal=stored.getItemStack(i);if(SealData.capacity(seal)>0) {count++;capacity+=SealData.capacity(seal);try {used+=SealData.read(seal).used();} catch(RuntimeException ignored) {}}}
            label(commands,"#ArchiveInfo",String.format(Locale.US,"Storage: %,d / %,d items\nSeals: %d / %d\n%s",used,capacity,count,SealData.ARCHIVE_SLOTS,network.active()?"Network online":"Network offline"));
        } else {
        String selected=view.order().name();
        if(!selected.equals(labels.put("selected-order",selected))) {
            commands.set("#Alphabetical.Disabled",view.order()==CatalogView.Order.ALPHABETICAL);
            commands.set("#Quantity.Disabled",view.order()==CatalogView.Order.QUANTITY);
        }
        label(commands,"#Summary",String.format(Locale.US,"%,d / %,d items  |  %d item types",network.used(),network.capacity(),view.select(network.contents()).size()));
        label(commands,"#Order",(network.active()?"Network online":"Network offline")+"  |  "+(remote==null?"":remote.status()+"  |  ")+(view.order()==CatalogView.Order.QUANTITY?"Quantity (highest first)":"Alphabetical (A-Z)"));
        }
        String status=archive?notice:gestures.holding()?"Selected: "+gestures.selectionLabel():notice;
        label(commands,"#Notice",status);
        if(!archive) flag(commands,"#Notice.Visible",!status.isEmpty());
        if(archive) {
            boolean hasNotice=!notice.isEmpty();
            String layout="archive-layout:"+hasNotice;
            if(!layout.equals(labels.put("archive-layout",layout))) {
                commands.set("#Notice.Visible",hasNotice);
                int backpackHeight=backpack==null || backpack.getCapacity()==0?0:26+Math.min(180,((backpack.getCapacity()+8)/9)*60);
                setAnchor(commands,"#ArchiveFrame",630,246+((inventory.getCapacity()+8)/9)*60+backpackHeight+(hasNotice?26:0));
            }
        }
        // The native drag owns the cursor; confirmed slot values must still paint during a sweep.
        gestures.capture();
        var totals=network.contents();
        long fingerprint=1;
        for(var entry:totals) fingerprint=31*fingerprint+entry.template.hashCode()+entry.count;
        if(fingerprint!=mountedTotals) {mounted.remove(CatalogGestures.NETWORK);mountedTotals=fingerprint;}
        fill(commands,"#Stored",CatalogGestures.NETWORK,archive?null:totals);
        fill(commands,"#Inventory",CatalogGestures.INVENTORY,null);
        fill(commands,"#Hotbar",CatalogGestures.HOTBAR,null);
        fill(commands,"#Backpack",CatalogGestures.BACKPACK,null);
        if(!archive){if(benchSlots!=null)fill(commands,"#Benches",CatalogGestures.BENCHES,null);renderBenches(commands);}
        if(!archive) renderScrollbar(commands);
        tooltip.render(this,commands);
    }
    private pedro.advancedstorage.ui.GridViewport currentViewport() {
        return selectedBench==null?catalog.viewport:recipeViewport;
    }

    pedro.advancedstorage.ui.ScrollRail scrollRail() {
        var viewport=currentViewport();
        return new pedro.advancedstorage.ui.ScrollRail(viewport.capacity()/9,viewport.maxFirstRow(),viewport.firstRow());
    }

    int scrollGrid() {
        return selectedBench==null?CatalogGestures.NETWORK:CatalogGestures.RECIPES;
    }

    private void renderScrollbar(UICommandBuilder commands) {
        var rail=scrollRail();
        int max=rail.maxFirstRow();
        int row=rail.firstRow();
        String railSize=Integer.toString(rail.railHeight());
        if(!Objects.equals(labels.put("scroll-height",railSize),railSize)) {
            var anchor=new com.hypixel.hytale.server.core.ui.Anchor();
            anchor.setLeft(com.hypixel.hytale.server.core.ui.Value.of(544));
            anchor.setTop(com.hypixel.hytale.server.core.ui.Value.of(0));
            anchor.setWidth(com.hypixel.hytale.server.core.ui.Value.of(10));
            anchor.setHeight(com.hypixel.hytale.server.core.ui.Value.of(rail.railHeight()));
            commands.setObject("#RowControls.Anchor",anchor);
        }
        String thumbPosition=rail.thumbTop()+":"+rail.thumbHeight();
        if(!Objects.equals(labels.put("scroll-thumb",thumbPosition),thumbPosition)) {
            var anchor=new com.hypixel.hytale.server.core.ui.Anchor();
            anchor.setLeft(com.hypixel.hytale.server.core.ui.Value.of(1));
            anchor.setRight(com.hypixel.hytale.server.core.ui.Value.of(1));
            anchor.setTop(com.hypixel.hytale.server.core.ui.Value.of(rail.thumbTop()));
            anchor.setHeight(com.hypixel.hytale.server.core.ui.Value.of(rail.thumbHeight()));
            commands.setObject("#RowThumb.Anchor",anchor);
        }
        flag(commands,"#RowThumb.Visible",max>0);
        flag(commands,"#RowUp.Disabled",row==0);
        flag(commands,"#RowDown.Disabled",row==max);
        for(int i=0;i<SCROLL_SEGMENTS;i++) {
            flag(commands,"#RowTrack["+i+"] #Jump.Disabled",max==0);
        }
    }

    void scrollToRow(int requestedRow) {
        if(!active) return;
        var ref=playerRef.getReference();
        if(ref==null || !ref.isValid()) return;
        var player=ref.getStore().getComponent(ref,Player.getComponentType());
        if(player==null || player.getPageManager().getCustomPage()!=this || !canAccess(ref)) return;

        var viewport=currentViewport();
        int previous=viewport.firstRow();
        int row=Math.max(0,Math.min(viewport.maxFirstRow(),requestedRow));
        if(row==previous) return;
        var commands=new UICommandBuilder();
        var events=new UIEventBuilder();

        // Drag selections are snapshots. Cancel before rebinding visible indices to new items.
        var sourceGrid=gestures.cancelForScroll();
        if(sourceGrid!=null && (sourceGrid!=CatalogGestures.NETWORK || selectedBench!=null)) {
            resetGrid(commands,events,sourceGrid);
        }
        if(selectedBench!=null) {
            recipeViewport.setFirstRow(row);
            tooltip.invalidate(commands);
        } else if(catalog.scrollTo(row)) {
            resetGrid(commands,events,CatalogGestures.NETWORK);
        }
        notice="";
        render(commands);
        flush(commands,events);
        trace("scroll row="+previous+" -> "+viewport.firstRow()+" / "+viewport.maxFirstRow());
    }

    void stopMouseControls() {
        if(mouseControls!=null) {
            mouseControls.close();
            mouseControls=null;
        }
    }

    record BenchEntry(StorageNetwork.Pos pos,short slot,ItemStack item){}
    ItemStack recipeItem(int visibleSlot) {
        if(visibleSlot<0 || visibleSlot>=recipeViewport.visibleCount()) return null;
        int slot=recipeViewport.absoluteIndex(visibleSlot);
        return slot>=0 && slot<recipes.size()
            ? NetworkBenches.recipeIcon(recipes.get(slot),selectedBench!=null && selectedBench.salvager())
            : null;
    }
    List<BenchEntry> benchEntries() {
        var entries=new ArrayList<BenchEntry>();
        for(var pos:catalog.network.catalogs) {
            var container=NetworkBenches.inventory(world,pos,false);
            if(container==null) continue;
            for(short slot=0;slot<container.getCapacity();slot++) {
                var item=container.getItemStack(slot);
                if(NetworkBenches.type(item)!=null) entries.add(new BenchEntry(pos,slot,item));
            }
        }
        return entries;
    }
    void renderBenches(UICommandBuilder commands) {
        if(selectedBench!=null&&(!selectedBench.valid()||!catalog.network.catalogs.contains(selectedBench.pos))){selectedBench=null;selectedRecipe=-1;recipeFingerprint="";}
        var entries=benchEntries();String fingerprint=entries.stream().map(e->e.pos+":"+e.slot+":"+e.item.getItemId()).toList()+"/"+(selectedBench==null?"storage":selectedBench.pos+":"+selectedBench.slot);
        if(!fingerprint.equals(benchFingerprint)) {
            benchFingerprint=fingerprint;commands.clear("#BenchList");pendingBenchBindings.clear();
            for(int i=0;i<entries.size();i++){
                var e=entries.get(i);String row="#BenchList["+i+"]";
                commands.append("#BenchList","AdvancedStorage/BenchButton.ui");commands.set(row+" #Icon.ItemId",e.item.getItemId());commands.set(row+" #Name.Text",NetworkBenches.display(e.item));
                commands.set(row+" #Select.Disabled",selectedBench!=null&&selectedBench.pos.equals(e.pos)&&selectedBench.slot==e.slot);
                pendingBenchBindings.add(events->bind(events,CustomUIEventBindingType.Activating,row+" #Select","bench/"+e.pos.x()+"/"+e.pos.y()+"/"+e.pos.z()+"/"+e.slot,null));
            }
        }
        boolean crafting=selectedBench!=null;
        flag(commands,"#RecipePanel.Visible",crafting);flag(commands,"#StoredHost.Visible",!crafting);flag(commands,"#StorageMode.Disabled",!crafting);
        if(!crafting){label(commands,"#StoredLabel","Network storage");return;}
        var installed=NetworkBenches.inventory(world,selectedBench.pos,false).getItemStack(selectedBench.slot);
        boolean salvager=selectedBench.salvager();
        label(commands,"#StoredLabel",NetworkBenches.display(installed)+(salvager?"":" - Tier "+selectedBench.tier()));
        label(commands,"#CraftOne",salvager?"Salvage":"Craft");
        label(commands,"#CraftTen",salvager?"Salvage x10":"Craft x10");
        flag(commands,"#UpgradeBench.Visible",!salvager);
        String recipeKey=selectedBench.id+"/"+selectedBench.tier()+"/"+view.search();
        if(!recipeKey.equals(recipeFingerprint)){
            recipeFingerprint=recipeKey;recipes=NetworkBenches.recipes(installed,selectedBench.tier(),view.search());selectedRecipe=-1;
            recipeViewport.setItemCount(recipes.size());
            recipeViewport.setFirstRow(0);
            mountedRecipeRow=-1;
        }
        if(mountedRecipeRow!=recipeViewport.firstRow()) {
            mountedRecipeRow=recipeViewport.firstRow();
            gridVersions.merge(CatalogGestures.RECIPES,1,Integer::sum);
            tooltip.invalidate(commands);
            commands.clear("#RecipeCanvas");
            commands.append("#RecipeCanvas","AdvancedStorage/Library/RecipeGrid.ui");
            pendingBenchBindings.add(events->{
                bind(events,CustomUIEventBindingType.SlotClicking,"#Recipes","recipe",CatalogGestures.RECIPES);
                bind(events,CustomUIEventBindingType.SlotMouseEntered,"#Recipes","enter",CatalogGestures.RECIPES);
                bind(events,CustomUIEventBindingType.SlotMouseExited,"#Recipes","exit",CatalogGestures.RECIPES);
            });
            var slots=new ItemGridSlot[recipeViewport.visibleCount()];for(int i=0;i<slots.length;i++){slots[i]=new ItemGridSlot(recipeItem(i));slots[i].setActivatable(true);}
            commands.set("#Recipes.Slots",slots);
        }
        var recipe=selectedRecipe>=0&&selectedRecipe<recipes.size()?recipes.get(selectedRecipe):null;
        label(commands,"#RecipeName",recipe==null?(salvager?"Select an item to salvage":"Select a recipe")
            :(salvager?"Salvage ":"")+CatalogView.name(NetworkBenches.recipeIcon(recipe,salvager)));
        String ingredients = recipe == null || recipe.getInput() == null ? "" : Arrays.stream(recipe.getInput())
            .map(material -> material.getQuantity() + " x " + CatalogView.materialName(material))
            .collect(java.util.stream.Collectors.joining("\n"));
        if(salvager && recipe!=null) {
            var outputs=com.hypixel.hytale.builtin.crafting.component.CraftingManager.getOutputItemStacks(recipe);
            ingredients+="\nTo network: "+outputs.stream().map(item->item.getQuantity()+" x "+CatalogView.name(item))
                .collect(java.util.stream.Collectors.joining(", "));
        }
        // Leave space for the scrollbar and allow for the larger recipe font.
        var ingredientLines=StorageTooltip.wrapBody(ingredients,432);
        String ingredientText=String.join("\n",ingredientLines);
        if(!Objects.equals(labels.get("#Ingredients"),ingredientText)) {
            setAnchor(commands,"#Ingredients",null,Math.max(26,ingredientLines.size()*24+4));
            label(commands,"#Ingredients",ingredientText);
        }
        label(commands,"#BenchStatus",selectedBench.status);
        flag(commands,"#CraftOne.Disabled",recipe==null||!catalog.network.active());flag(commands,"#CraftTen.Disabled",recipe==null||!catalog.network.active());
        flag(commands,"#UpgradeBench.Disabled",selectedBench.type.getBench().getUpgradeRequirement(selectedBench.tier())==null);
    }
    void flag(UICommandBuilder commands,String selector,boolean value){String text=Boolean.toString(value);if(!Objects.equals(labels.put(selector,text),text))commands.set(selector,value);}
    void flush(UICommandBuilder commands,UIEventBuilder events) {
        if(events==null)events=new UIEventBuilder();
        for(var binding:pendingBenchBindings)binding.accept(events);pendingBenchBindings.clear();
        sendUpdate(commands,events,false);
    }
    void label(UICommandBuilder commands,String selector,String text) {
        if(!Objects.equals(labels.put(selector,text),text)) commands.set(selector+".Text",text);
    }
    int catalogHeight() {
        return benchWindow!=null && backpack!=null && backpack.getCapacity()>0 ? 700+BENCH_SLOT_PITCH : 700;
    }
    static void setAnchor(UICommandBuilder commands,String selector,Integer width,int height) {
        var anchor=new com.hypixel.hytale.server.core.ui.Anchor();
        if(width!=null) anchor.setWidth(com.hypixel.hytale.server.core.ui.Value.of(width));
        anchor.setHeight(com.hypixel.hytale.server.core.ui.Value.of(height));
        commands.setObject(selector+".Anchor",anchor);
    }
    void fill(UICommandBuilder commands,String selector,int grid,List<StorageNetwork.StoredItem> totals) {
        var snapshot=gestures.displayed(grid);
        if(!archive && grid==CatalogGestures.NETWORK) snapshot=Arrays.copyOf(snapshot,catalog.viewport.capacity());
        if(grid==CatalogGestures.BENCHES) snapshot=Arrays.copyOf(snapshot,NetworkBenches.SLOTS);
        if(Arrays.equals(mounted.get(grid),snapshot)) return;
        mounted.put(grid,snapshot);var slots=new ItemGridSlot[snapshot.length];
        for(int i=0;i<snapshot.length;i++) {
            var stack=snapshot[i];
            if(grid==CatalogGestures.BENCHES) flag(commands,"#BenchGhosts["+i+"].Visible",ItemStack.isEmpty(stack));
            var display=new ItemGridSlot();
            if(!ItemStack.isEmpty(stack)) {
                var preview=stack.withMetadata((org.bson.BsonDocument)null);
                if(totals!=null) {
                    long count=totals.stream().filter(e->e.template.isStackableWith(stack)).mapToLong(e->e.count).sum();
                    if(count>0) {
                        // The UI shows the total while the native container supplies one stack per transfer.
                        preview=preview.withQuantity((int)Math.min(count,Integer.MAX_VALUE));
                        display.setDescription(String.format(Locale.US,"%,d stored.",count));
                    }
                }
                display.setItemStack(preview);
            }
            display.setActivatable(true);
            slots[i]=display;
        }
        commands.set(selector+".Slots",slots);
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,String raw) {
        if(!active) return; // Hover events already in flight must not update a dismissed page.
        try {
            var data=CatalogInput.parse(raw);
            if(!data.action.equals("search") && !data.action.equals("enter") && !data.action.equals("exit")) {
                var keys=com.google.gson.JsonParser.parseString(raw).getAsJsonObject().keySet().stream().filter(k->k.matches("[A-Za-z0-9@_]{1,40}")).limit(30).toList();
                trace("input action="+data.action+" grid="+data.section+" slot="+data.slot+" from="+data.sourceSection+":"+data.sourceSlot+" quantity="+data.quantity+" shift="+data.shift+" clicks="+data.clickCount+" right="+data.right+" dragRight="+data.dragRight+" active="+active+" token="+session.equals(data.session)+" keys="+keys);
            }
            handleDataEvent(ref,store,data);
        }
        catch(IllegalArgumentException | IllegalStateException ex) {
            if(gestures!=null) gestures.cancel();mounted.clear();notice="Transfer stopped. Please select the item again.";
            var commands=new UICommandBuilder();if(gestures!=null) render(commands);flush(commands,null);
        }
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,CatalogInput data) {
        if(!active) return;
        if(data.section!=null && data.gridVersion!=null && data.gridVersion!=gridVersions.getOrDefault(data.section,0).intValue()) return;
        if(!session.equals(data.session)) {sendUpdate(new UICommandBuilder(),false);return;}
        if(!canAccess(ref)) {close();return;}
        if(data.action.equals("dismiss")) {trace("native dismiss requested");close();return;}
        if(mouseControls!=null && data.action.startsWith("pointer-track-")) {
            int slash=data.action.indexOf('/');
            if(slash<0) return;
            int segment=Integer.parseInt(data.action.substring(slash+1));
            if(segment>=0 && segment<SCROLL_SEGMENTS) {
                mouseControls.trackHover(segment,data.action.startsWith("pointer-track-enter/"));
            }
            return;
        }
        if(!archive && data.action.startsWith("scroll-")) {
            var rail=scrollRail();
            int row;
            if(data.action.equals("scroll-up")) row=rail.firstRow()-1;
            else if(data.action.equals("scroll-down")) row=rail.firstRow()+1;
            else if(data.action.startsWith("scroll-track/")) {
                int segment=Integer.parseInt(data.action.substring("scroll-track/".length()));
                if(segment<0 || segment>=SCROLL_SEGMENTS || mouseControls!=null && mouseControls.consumeTrackClick()) return;
                row=rail.rowForSegment(segment,SCROLL_SEGMENTS);
            } else return;
            scrollToRow(row);
            return;
        }
        if(mouseControls!=null && data.section!=null && data.slot!=null) {
            if(data.action.equals("enter")) mouseControls.gridHover(data.section,data.slot,true);
            else if(data.action.equals("exit")) mouseControls.gridHover(data.section,data.slot,false);
        }
        notice="";
        if(!archive&&(data.action.startsWith("bench/")||Set.of("storage-mode","recipe","craft-one","craft-ten","stop-craft","upgrade-bench").contains(data.action))) {
            gestures.cancel();tooltip.clear();
            if(data.action.startsWith("bench/")) {
                var parts=data.action.split("/");if(parts.length!=5)return;
                var pos=new StorageNetwork.Pos(Integer.parseInt(parts[1]),Integer.parseInt(parts[2]),Integer.parseInt(parts[3]));short slot=Short.parseShort(parts[4]);
                if(slot<0||slot>=NetworkBenches.SLOTS||!StorageNetwork.scan(world,origin).catalogs.contains(pos))return;
                selectedBench=NetworkBenches.station(world,pos,slot);selectedRecipe=-1;recipeFingerprint="";
            }else if(data.action.equals("storage-mode")){selectedBench=null;selectedRecipe=-1;recipeFingerprint="";}
            else if(data.action.equals("recipe")){if(data.slot!=null&&data.slot>=0&&data.slot<recipeViewport.visibleCount())selectedRecipe=recipeViewport.absoluteIndex(data.slot);}
            else if(selectedBench!=null&&selectedBench.valid()&&StorageNetwork.scan(world,origin).catalogs.contains(selectedBench.pos)) {
                if(data.action.equals("stop-craft"))selectedBench.stop();
                else if(data.action.equals("upgrade-bench")){if(!selectedBench.upgrade())notice="Upgrade unavailable.";}
                else if(selectedRecipe>=0&&selectedRecipe<recipes.size()) {
                    var recipe=recipes.get(selectedRecipe);var player=store.getComponent(ref,Player.getComponentType());
                    var output=recipe.getPrimaryOutput();
                    boolean known=!recipe.isKnowledgeRequired()||output!=null&&player.getPlayerConfigData().getKnownRecipes().contains(output.getItemId());
                    boolean memories=com.hypixel.hytale.builtin.adventure.memories.MemoriesPlugin.get().getMemoriesLevel(world.getGameplayConfig())>=recipe.getRequiredMemoriesLevel();
                    if(!known||!memories)notice="Recipe requirements not met.";
                    else if(!selectedBench.start(recipe,data.action.equals("craft-ten")?10:1,new CombinedItemContainer(inventory,hotbar),()->active&&canAccess(ref)))notice="Bench is busy or unavailable.";
                }
            }
            var commands=new UICommandBuilder();render(commands);flush(commands,null);return;
        }
        if(data.action.equals("enter") && data.section!=null && data.slot!=null) tooltip.hover(data.section,data.slot);
        else if(data.action.equals("exit") && data.section!=null && data.slot!=null) tooltip.exit(data.section,data.slot);
        else tooltip.clear();
        if(!archive && (data.action.equals("search") || data.action.equals("quantity") || data.action.equals("alphabetical"))) {
            gestures.cancel();
            mounted.clear();
            var order=data.action.equals("quantity")?CatalogView.Order.QUANTITY:data.action.equals("alphabetical")?CatalogView.Order.ALPHABETICAL:view.order();
            view=new CatalogView(data.search==null?view.search():data.search,order);
            AdvancedStoragePlugin.catalogPreferences.remember(playerRef.getUuid(), view);
            catalog.setView(view);
        } else {
            var result=gestures.handle(data);
            if(!data.action.equals("enter") && !data.action.equals("exit") || result==CatalogGestures.Result.MOVED)
                trace("result="+result+" action="+data.action+" holding="+gestures.holding()+" role="+role);
            // Most hover/press events carry no inventory change. Do not scan the network or send a UI patch.
            if(result==CatalogGestures.Result.IGNORED || result==CatalogGestures.Result.PICKED) {
                var hoverCommands=new UICommandBuilder();tooltip.render(this,hoverCommands);
                if(hoverCommands.getCommands().length>0) sendUpdate(hoverCommands,false);
                return;
            }
            if(result==CatalogGestures.Result.STALE) notice="Items changed. Please select the item again.";
            else if(result==CatalogGestures.Result.REJECTED) notice="Network transfer unavailable. Check inventory and seal capacity.";
        }
        var commands=new UICommandBuilder();
        var events=new UIEventBuilder();
        var reset=gestures.takeResetGrid();
        if(reset!=null) resetGrid(commands,events,reset);
        render(commands);flush(commands,events);
    }
    void trace(String text) {
        if(traceRemaining-->0) LOGGER.atInfo().log("[AdvancedStorage/Catalog] %s",text);
    }
    void scheduleRefresh() {
        CompletableFuture.delayedExecutor(75,TimeUnit.MILLISECONDS,world).execute(()->{
            if(!active) return;
            var ref=playerRef.getReference();
            if(ref==null || !ref.isValid()) return;
            var player=ref.getStore().getComponent(ref,Player.getComponentType());
            if(player==null || player.getPageManager().getCustomPage()!=this) return;
            if(!canAccess(ref)) {close();return;}
            var commands=new UICommandBuilder();render(commands);
            if(commands.getCommands().length>0) flush(commands,null);
            scheduleRefresh();
        });
    }
    boolean canAccess(Ref<EntityStore> ref) {return remote==null?NativeStorageWindows.canAccess(ref,world,origin,role):remote.valid(ref);}
    @Override protected void close() {
        if(closing || dismissed) return;
        closing=true;active=false;
        stopMouseControls();
        var commands=new UICommandBuilder();tooltip.hide(commands);
        if(gestures!=null) gestures.cancel();
        if(commands.getCommands().length>0)sendUpdate(commands,false);
        super.close();
    }
    @Override public void onDismiss(Ref<EntityStore> ref,Store<EntityStore> store) {
        stopMouseControls();
        tooltip.clear();active=false;if(playerRef!=null) OPEN.remove(playerRef.getUuid(),this);if(gestures!=null) gestures.cancel();
        if(dismissed) return;dismissed=true;
        trace("native dismissed preclean="+closing);
        mounted.clear();labels.clear();
        if(windowClosed!=null) windowClosed.unregister();
        if(playerRef!=null){
            Set<Integer> ids=benchWindow==null?Set.of(window.getId()):Set.of(window.getId(),benchWindow.getId());
            RECENTLY_CLOSED.put(playerRef.getUuid(),ids);
            CompletableFuture.delayedExecutor(5,TimeUnit.SECONDS,world).execute(()->RECENTLY_CLOSED.remove(playerRef.getUuid(),ids));
        }
        var player=store.getComponent(ref,Player.getComponentType());
        if(player!=null && player.getWindowManager().getWindow(window.getId())!=null) player.getWindowManager().closeWindow(ref,window.getId(),store);
        if(player!=null&&benchWindow!=null&&player.getWindowManager().getWindow(benchWindow.getId())!=null)player.getWindowManager().closeWindow(ref,benchWindow.getId(),store);
    }
}
