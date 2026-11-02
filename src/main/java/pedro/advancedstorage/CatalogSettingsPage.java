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
    static final Map<UUID,CatalogView> PREFERENCES=new HashMap<>();
    static final BuilderCodec<CatalogInput> CODEC=BuilderCodec.builder(CatalogInput.class,CatalogInput::new).build();
    final World world;
    final StorageNetwork.Pos origin;
    final ContainerBlockWindow window;
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
        super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,CODEC);
        this.world=world;this.origin=origin;this.window=window;this.stored=window.getItemContainer();this.catalog=stored instanceof CatalogContainer c?c:null;
        this.archive=catalog==null;this.role=archive?"Archive":"Catalog";
        this.inventory=inventory;this.hotbar=hotbar;this.backpack=backpack;view=archive?CatalogView.DEFAULT:catalog.view;
    }
    static CatalogView preference(PlayerRef player) {return player==null?CatalogView.DEFAULT:PREFERENCES.getOrDefault(player.getUuid(),CatalogView.DEFAULT);}
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
            default -> "Backpack";
        };
        gridVersions.merge(grid,1,Integer::sum);
        commands.clear("#"+name+"Host");
        commands.append("#"+name+"Host","AdvancedStorage/"+(archive?"Archive":"Catalog")+name+"Grid.ui");
        commands.set("#"+name+".InventorySectionId",gestures.sections.get(grid).nativeId());
        if(archive && grid==CatalogGestures.INVENTORY) setAnchor(commands,"#Inventory",null,((inventory.getCapacity()+8)/9)*60);
        if(archive && grid==CatalogGestures.BACKPACK && backpack!=null) setAnchor(commands,"#Backpack",null,Math.min(180,((backpack.getCapacity()+8)/9)*60));
        bindGrid(events,"#"+name,grid);
        mounted.remove(grid);
    }
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder commands,UIEventBuilder events,Store<EntityStore> store) {
        commands.append(archive?"AdvancedStorage/Archive.ui":"AdvancedStorage/Catalog.ui");
        commands.append("#StorageRoot","AdvancedStorage/StorageTooltip.ui");if(!archive) commands.set("#Search.Value",view.search());
        var sections=new LinkedHashMap<Integer,CatalogGestures.Section>();
        sections.put(CatalogGestures.NETWORK,new CatalogGestures.Section(window.getId(),stored));
        sections.put(CatalogGestures.INVENTORY,new CatalogGestures.Section(InventoryComponent.STORAGE_SECTION_ID,inventory));
        sections.put(CatalogGestures.HOTBAR,new CatalogGestures.Section(InventoryComponent.HOTBAR_SECTION_ID,hotbar));
        if(backpack!=null && backpack.getCapacity()>0) sections.put(CatalogGestures.BACKPACK,new CatalogGestures.Section(InventoryComponent.BACKPACK_SECTION_ID,backpack));
        gestures=new CatalogGestures(sections,new CatalogGestures.Commands() {
            public void move(int from,int slot,int quantity,int to,int targetSlot) {
                trace("move nativeFrom="+from+":"+slot+" nativeTo="+to+":"+targetSlot+" quantity="+quantity+" windowPresent="+(store.getComponent(ref,Player.getComponentType()).getWindowManager().getWindow(window.getId())!=null)+" windowValid="+window.validate(ref,store));
                InventoryUtils.moveItem(ref,from,slot,quantity,to,targetSlot,store);
            }
            public void quickMove(int from,int slot,int quantity) {
                var settings=store.getComponent(ref,PlayerSettings.getComponentType());
                InventoryUtils.smartMoveItem(ref,from,slot,quantity,SmartMoveType.PutInHotbarOrWindow,settings==null?PlayerSettings.defaults():settings,store);
            }
        });
        if(!archive) {
        bind(events,CustomUIEventBindingType.ValueChanged,"#Search","search",null);
        bind(events,CustomUIEventBindingType.Activating,"#Alphabetical","alphabetical",null);
        bind(events,CustomUIEventBindingType.Activating,"#Quantity","quantity",null);
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
        if(playerRef!=null && !active) {
            active=true;OPEN.put(playerRef.getUuid(),this);
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
            for(short i=0;i<4;i++) {var seal=stored.getItemStack(i);if(SealData.capacity(seal)>0) {count++;capacity+=SealData.capacity(seal);try {used+=SealData.read(seal).used();} catch(RuntimeException ignored) {}}}
            label(commands,"#ArchiveInfo",String.format(Locale.US,"Storage: %,d / %,d items\nSeals: %d / 4\n%s",used,capacity,count,network.active()?"Network online":"Network offline"));
        } else {
        String selected=view.order().name();
        if(!selected.equals(labels.put("selected-order",selected))) {
            commands.set("#Alphabetical.Disabled",view.order()==CatalogView.Order.ALPHABETICAL);
            commands.set("#Quantity.Disabled",view.order()==CatalogView.Order.QUANTITY);
        }
        label(commands,"#Summary",String.format(Locale.US,"%,d / %,d items  |  %d item types",network.used(),network.capacity(),view.select(network.contents()).size()));
        label(commands,"#Order",(network.active()?"Network online":"Network offline")+"  |  "+(view.order()==CatalogView.Order.QUANTITY?"Quantity (highest first)":"Alphabetical (A-Z)"));
        }
        label(commands,"#Notice",archive?notice:gestures.holding()?"Selected: "+gestures.selectionLabel():notice);
        if(archive) {
            boolean hasNotice=!notice.isEmpty();
            String layout="archive-layout:"+hasNotice;
            if(!layout.equals(labels.put("archive-layout",layout))) {
                commands.set("#Notice.Visible",hasNotice);
                int backpackHeight=backpack==null || backpack.getCapacity()==0?0:26+Math.min(180,((backpack.getCapacity()+8)/9)*60);
                setAnchor(commands,"#ArchivePanel",570,246+((inventory.getCapacity()+8)/9)*60+backpackHeight+(hasNotice?26:0));
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
        tooltip.render(this,commands);
    }
    void label(UICommandBuilder commands,String selector,String text) {
        if(!Objects.equals(labels.put(selector,text),text)) commands.set(selector+".Text",text);
    }
    static void setAnchor(UICommandBuilder commands,String selector,Integer width,int height) {
        var anchor=new com.hypixel.hytale.server.core.ui.Anchor();
        if(width!=null) anchor.setWidth(com.hypixel.hytale.server.core.ui.Value.of(width));
        anchor.setHeight(com.hypixel.hytale.server.core.ui.Value.of(height));
        commands.setObject(selector+".Anchor",anchor);
    }
    void fill(UICommandBuilder commands,String selector,int grid,List<StorageNetwork.StoredItem> totals) {
        var snapshot=gestures.displayed(grid);
        if(Arrays.equals(mounted.get(grid),snapshot)) return;
        mounted.put(grid,snapshot);var slots=new ItemGridSlot[snapshot.length];
        for(int i=0;i<snapshot.length;i++) {
            var stack=snapshot[i];
            var display=new ItemGridSlot(ItemStack.isEmpty(stack)?null:stack.withMetadata((org.bson.BsonDocument)null));
            display.setActivatable(true);
            if(!ItemStack.isEmpty(stack) && totals!=null) {
                long count=totals.stream().filter(e->e.template.isStackableWith(stack)).mapToLong(e->e.count).sum();
                display.setDescription(String.format(Locale.US,"%,d stored.",count));
            }
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
            var commands=new UICommandBuilder();if(gestures!=null) render(commands);sendUpdate(commands,false);
        }
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,CatalogInput data) {
        if(!active) return;
        if(data.section!=null && data.gridVersion!=null && data.gridVersion!=gridVersions.getOrDefault(data.section,0).intValue()) return;
        if(!session.equals(data.session)) {sendUpdate(new UICommandBuilder(),false);return;}
        if(!NativeStorageWindows.canAccess(ref,world,origin,role)) {close();return;}
        if(data.action.equals("dismiss")) {trace("native dismiss requested");close();return;}
        notice="";
        if(data.action.equals("enter") && data.section!=null && data.slot!=null) tooltip.hover(data.section,data.slot);
        else if(data.action.equals("exit") && data.section!=null && data.slot!=null) tooltip.exit(data.section,data.slot);
        else tooltip.clear();
        if(!archive && (data.action.equals("search") || data.action.equals("quantity") || data.action.equals("alphabetical"))) {
            gestures.cancel();
            mounted.clear();
            var order=data.action.equals("quantity")?CatalogView.Order.QUANTITY:data.action.equals("alphabetical")?CatalogView.Order.ALPHABETICAL:view.order();
            view=new CatalogView(data.search==null?view.search():data.search,order);
            PREFERENCES.put(playerRef.getUuid(),view);catalog.setView(view);
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
        render(commands);sendUpdate(commands,events,false);
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
            if(!NativeStorageWindows.canAccess(ref,world,origin,role)) {close();return;}
            var commands=new UICommandBuilder();render(commands);
            if(commands.getCommands().length>0) sendUpdate(commands,false);
            scheduleRefresh();
        });
    }
    @Override protected void close() {
        if(closing || dismissed) return;
        closing=true;active=false;tooltip.clear();
        if(gestures!=null) gestures.cancel();
        super.close();
    }
    @Override public void onDismiss(Ref<EntityStore> ref,Store<EntityStore> store) {
        tooltip.clear();active=false;if(playerRef!=null) OPEN.remove(playerRef.getUuid(),this);if(gestures!=null) gestures.cancel();
        if(dismissed) return;dismissed=true;
        trace("native dismissed preclean="+closing);
        mounted.clear();labels.clear();
        if(windowClosed!=null) windowClosed.unregister();
        var player=store.getComponent(ref,Player.getComponentType());
        if(player!=null && player.getWindowManager().getWindow(window.getId())!=null) player.getWindowManager().closeWindow(ref,window.getId(),store);
    }
}
