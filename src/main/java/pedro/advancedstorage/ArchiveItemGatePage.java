package pedro.advancedstorage;

import com.google.gson.JsonParser;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class ArchiveItemGatePage extends InteractiveCustomUIPage<CatalogInput> {
    private final World world;
    private final StorageNetwork.Pos pos;
    private final ArchiveItemGate gate;
    private final ArcaneItemExporter exporter;
    private final String session=UUID.randomUUID().toString();
    private static final int RECEIVE_GRID=-1101, SEND_GRID=-1102;
    private record Pickup(int section,int slot,String itemId) {}
    private Pickup pickup;
    private int channel = 1;
    private int controlsVersion;
    private List<ArcaneItemExporter.Peer> displayedPeers;
    private boolean pollingPeers;

    ArchiveItemGatePage(PlayerRef player,World world,StorageNetwork.Pos pos,ArchiveItemGate gate) {
        this(player,world,pos,gate,null);
    }

    ArchiveItemGatePage(PlayerRef player,World world,StorageNetwork.Pos pos,ArcaneItemExporter exporter) {
        this(player,world,pos,null,exporter);
    }

    private ArchiveItemGatePage(PlayerRef player,World world,StorageNetwork.Pos pos,ArchiveItemGate gate,ArcaneItemExporter exporter) {
        super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,CatalogSettingsPage.CODEC);
        this.world=world;
        this.pos=pos;
        this.gate=gate;
        this.exporter=exporter;
    }

    @Override public void build(Ref<EntityStore> ref,UICommandBuilder commands,UIEventBuilder events,Store<EntityStore> store) {
        commands.append("AdvancedStorage/ArchiveItemGate.ui");
        if(exporter!=null) {
            commands.set("#TransferTitle.Text","ARCANE ITEM EXPORTER");
            CatalogSettingsPage.setAnchor(commands,"#TransferFrame",1092,610);
            commands.set("#ChannelTabs.Visible",true);
            commands.set("#ExporterNameRow.Visible",true);
            commands.set("#ExporterName.Value",exporter.name());
            events.addEventBinding(CustomUIEventBindingType.Activating,"#SaveExporterName",
                EventData.of("Action","rename").append("Session",session)
                    .append("@ExporterName","#ExporterName.Value"),false);
            renderTabs(commands,events);
            pollingPeers=true;
            pollPeers();
        }
        mountControls(ref,store,commands,events);
    }

    private ItemTransferSettings settings() {
        return exporter==null?gate.settings:exporter.channel(channel);
    }

    private void mountControls(Ref<EntityStore> ref,Store<EntityStore> store,UICommandBuilder commands,UIEventBuilder events) {
        pickup=null;
        controlsVersion++;
        commands.clear("#TransferPanel");
        commands.append("#TransferPanel","AdvancedStorage/ItemTransferControls.ui");
        bind(events,CustomUIEventBindingType.ValueChanged,"#Receive","receive",null);
        bind(events,CustomUIEventBindingType.ValueChanged,"#Send","send",null);
        bind(events,CustomUIEventBindingType.ValueChanged,"#SendAll","send-all",null);
        bindFilterGrid(commands,events,"#ReceiveGrid",RECEIVE_GRID);
        bindFilterGrid(commands,events,"#SendGrid",SEND_GRID);
        mountInventory(ref,store,commands,events,InventoryComponent.STORAGE_SECTION_ID);
        mountInventory(ref,store,commands,events,InventoryComponent.HOTBAR_SECTION_ID);
        commands.set("#Receive.Value",settings().receive);
        commands.set("#Send.Value",settings().send);
        commands.set("#SendAll.Value",settings().sendAll);
        paintFilters(commands,RECEIVE_GRID);
        paintFilters(commands,SEND_GRID);
        if(exporter!=null) {
            CatalogSettingsPage.setAnchor(commands,"#TransferOptions",164,388);
            commands.set("#ExporterPeers.Visible",true);
            displayedPeers=null;
            renderPeers(commands);
        }
    }

    private void renderPeers(UICommandBuilder commands) {
        var peers = new ArrayList<ArcaneItemExporter.Peer>();
        peers.add(exporter.peer(pos));
        peers.addAll(ArcaneItemExporter.peers(world,pos,channel));
        if(peers.equals(displayedPeers)) return;
        displayedPeers = peers;
        commands.clear("#ExporterPeerList");
        for(int i=0;i<peers.size();i++) {
            var peer = peers.get(i);
            var point = peer.position();
            String row = "#ExporterPeerList[" + i + "]";
            commands.append("#ExporterPeerList","AdvancedStorage/ExporterPeerRow.ui");
            commands.set(row + " #PeerName.Text", peer.name());
            commands.set(row + " #PeerCoordinates.Text", "(" + point.x() + ", " + point.y() + ", " + point.z() + ")");
        }
    }

    private void pollPeers() {
        CompletableFuture.delayedExecutor(1,TimeUnit.SECONDS,world).execute(()->{
            if(!pollingPeers) return;
            var ref=playerRef.getReference();
            if(ref==null || !ref.isValid()) return;
            var player=ref.getStore().getComponent(ref,Player.getComponentType());
            if(player==null || player.getPageManager().getCustomPage()!=this) return;
            if(!NativeStorageWindows.canAccess(ref,world,pos,ArcaneItemExporter.ROLE)
                || ArcaneItemExporter.at(world,pos)!=exporter) { close(); return; }
            var commands=new UICommandBuilder();
            renderPeers(commands);
            if(commands.getCommands().length>0) sendUpdate(commands,false);
            pollPeers();
        });
    }

    @Override public void onDismiss(Ref<EntityStore> ref,Store<EntityStore> store) {
        pollingPeers=false;
    }

    private void bind(UIEventBuilder events,CustomUIEventBindingType type,String selector,String action,Integer section) {
        var data=EventData.of("Action",action).append("Session",session).append("GridVersion",Integer.toString(controlsVersion));
        if(section==null) data.append("@Checked",selector+".Value");
        else data.append("Section",Integer.toString(section));
        events.addEventBinding(type,selector,data,false);
    }

    private void renderTabs(UICommandBuilder commands,UIEventBuilder events) {
        commands.clear("#ChannelTabs");
        for(int number=1;number<=exporter.channelCount();number++) {
            commands.append("#ChannelTabs","AdvancedStorage/ExporterChannelTab.ui");
            String selector="#ChannelTabs["+(number-1)+"] #SelectChannel";
            commands.set(selector+".Text","Channel "+number);
            commands.set(selector+".Disabled",number==channel);
            bindTab(events,selector,"channel/"+number);
        }
        commands.append("#ChannelTabs","AdvancedStorage/ExporterAddChannel.ui");
        commands.set("#AddChannel.Disabled",exporter.channelCount()>=ArcaneItemExporter.MAX_CHANNELS);
        bindTab(events,"#AddChannel","add-channel");
    }

    private void bindTab(UIEventBuilder events,String selector,String action) {
        events.addEventBinding(CustomUIEventBindingType.Activating,selector,
            EventData.of("Action",action).append("Session",session),false);
    }

    private void switchChannel(Ref<EntityStore> ref,Store<EntityStore> store,String action) {
        int selected;
        if(action.equals("add-channel")) {
            selected=exporter.addChannel();
            if(selected==0) return;
            StorageNetwork.dirty(world,pos);
        } else {
            try {selected=Integer.parseInt(action.substring("channel/".length()));}
            catch(NumberFormatException invalid) {return;}
            if(exporter.channel(selected)==null || channel==selected) return;
        }
        channel=selected;
        var commands=new UICommandBuilder();
        var events=new UIEventBuilder();
        renderTabs(commands,events);
        mountControls(ref,store,commands,events);
        sendUpdate(commands,events,false);
    }

    private void bindFilterGrid(UICommandBuilder commands,UIEventBuilder events,String selector,int section) {
        commands.set(selector+".InventorySectionId",section);
        bind(events,CustomUIEventBindingType.SlotClicking,selector,"pick",section);
        bind(events,CustomUIEventBindingType.SlotClickPressWhileDragging,selector,"press",section);
        bind(events,CustomUIEventBindingType.Dropped,selector,"drop",section);
        bind(events,CustomUIEventBindingType.DragCancelled,selector,"cancel",section);
    }

    private void mountInventory(Ref<EntityStore> ref,Store<EntityStore> store,UICommandBuilder commands,UIEventBuilder events,int section) {
        boolean hotbar=section==InventoryComponent.HOTBAR_SECTION_ID;
        String name=hotbar?"PlayerHotbar":"PlayerItems";
        commands.clear("#"+name+"Host");
        commands.append("#"+name+"Host","AdvancedStorage/ArchiveItemGate"+name+".ui");
        String selector="#"+name;
        commands.set(selector+".InventorySectionId",section);
        bind(events,CustomUIEventBindingType.SlotClicking,selector,"pick",section);
        bind(events,CustomUIEventBindingType.SlotClickPressWhileDragging,selector,"press",section);
        bind(events,CustomUIEventBindingType.Dropped,selector,"drop",section);
        bind(events,CustomUIEventBindingType.DragCancelled,selector,"cancel",section);
        var inventory=inventory(ref,store,hotbar);
        var slots=new ItemGridSlot[inventory==null?0:inventory.getCapacity()];
        for(short i=0;i<slots.length;i++) slots[i]=slot(inventory.getItemStack(i));
        commands.set(selector+".Slots",slots);
    }

    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,String raw) {
        if(raw==null || raw.length()>4096) return;
        String role=exporter==null?"ItemGate":ArcaneItemExporter.ROLE;
        if(!NativeStorageWindows.canAccess(ref,world,pos,role)
            || (exporter==null?ArchiveItemGate.at(world,pos)!=gate:ArcaneItemExporter.at(world,pos)!=exporter)) {
            close();
            return;
        }
        CatalogInput input;
        Boolean checked=null;
        try {
            input=CatalogInput.parse(raw);
            if(input.action.equals("receive") || input.action.equals("send") || input.action.equals("send-all")) {
                var value=JsonParser.parseString(raw).getAsJsonObject().get("@Checked");
                if(value!=null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) checked=value.getAsBoolean();
                // Custom UI values can also be serialized as strings.
                else if(value!=null && value.isJsonPrimitive()) {
                    if("true".equalsIgnoreCase(value.getAsString())) checked=true;
                    else if("false".equalsIgnoreCase(value.getAsString())) checked=false;
                }
            }
        } catch(RuntimeException invalid) {
            return;
        }
        if(!session.equals(input.session)) return;
        if(exporter!=null && input.action.equals("rename")) {
            var value=JsonParser.parseString(raw).getAsJsonObject().get("@ExporterName");
            if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return;
            if(exporter.rename(value.getAsString())) StorageNetwork.dirty(world,pos);
            var commands=new UICommandBuilder();
            commands.set("#ExporterName.Value",exporter.name());
            renderPeers(commands);
            sendUpdate(commands,false);
            return;
        }
        if(exporter!=null && (input.action.equals("add-channel") || input.action.startsWith("channel/"))) {
            switchChannel(ref,store,input.action);
            return;
        }
        if(input.gridVersion==null || input.gridVersion!=controlsVersion) return;
        if(checked!=null) {
            var settings=settings();
            switch(input.action) {
                case "receive" -> settings.receive=checked;
                case "send" -> settings.send=checked;
                case "send-all" -> settings.sendAll=checked;
                default -> { return; }
            }
            StorageNetwork.dirty(world,pos);
            return;
        }
        if(input.action.equals("cancel")) {pickup=null;return;}
        if(input.section==null || input.slot==null || input.slot<0) return;
        boolean filter=input.section==RECEIVE_GRID || input.section==SEND_GRID;
        if(input.action.equals("pick") && !filter) {
            pickup=null;
            var item=sourceItem(ref,store,input.section,input.slot);
            if(!ItemStack.isEmpty(item) && matches(input.itemId,item))
                pickup=new Pickup(input.section,input.slot,item.getItemId());
            return;
        }
        if(input.action.equals("pick") && filter) {
            if(input.right && pickup==null && input.sourceSection==null && input.sourceSlot==null && input.slot<ItemTransferSettings.SLOTS) {
                updateFilter(input.section,input.slot,"");
            }
            return;
        }
        if(!input.action.equals("press") && !input.action.equals("drop")) return;
        Pickup previous=pickup;
        pickup=null;
        if((input.sourceSection==null)!=(input.sourceSlot==null)) return;
        Integer from=input.sourceSection!=null?input.sourceSection:previous==null?null:Integer.valueOf(previous.section);
        Integer sourceSlot=input.sourceSlot!=null?input.sourceSlot:previous==null?null:Integer.valueOf(previous.slot);
        if(from==null || sourceSlot==null) return;
        var item=sourceItem(ref,store,from,sourceSlot);
        if(ItemStack.isEmpty(item) || !matches(input.itemId,item)
            || input.sourceSection==null && !previous.itemId.equals(item.getItemId())) return;
        // The right pickup can also report a press on its own origin. Let the preview stay in hand.
        if(!filter && input.action.equals("press") && input.section.equals(from) && input.slot.equals(sourceSlot) && input.right) {
            pickup=new Pickup(from,sourceSlot,item.getItemId());
            return;
        }
        var commands=new UICommandBuilder();
        var events=new UIEventBuilder();
        if(filter && input.slot<ItemTransferSettings.SLOTS && setFilter(input.section,input.slot,item.getItemId())) {
            paintFilters(commands,input.section);
        }
        // End the held-item preview even when a click, rather than mouse release, completed the drag.
        if(input.action.equals("press") || !filter) mountInventory(ref,store,commands,events,from);
        if(commands.getCommands().length>0) sendUpdate(commands,events,false);
    }

    private static boolean matches(String expected,ItemStack item) {
        return expected==null || expected.equals(item.getItemId());
    }

    private void updateFilter(int section,int index,String id) {
        if(!setFilter(section,index,id)) return;
        var commands=new UICommandBuilder();
        paintFilters(commands,section);
        sendUpdate(commands,false);
    }

    private boolean setFilter(int section,int index,String id) {
        var filters=settings().filters(section==SEND_GRID);
        if(id.equals(filters[index])) return false;
        filters[index]=id;
        StorageNetwork.dirty(world,pos);
        return true;
    }

    private void paintFilters(UICommandBuilder commands,int section) {
        var filters=settings().filters(section==SEND_GRID);
        var slots=new ItemGridSlot[filters.length];
        for(int i=0;i<slots.length;i++) {
            String id=filters[i];
            slots[i]=slot(id.isEmpty() || Item.getAssetMap().getAsset(id)==null?null:new ItemStack(id,1));
        }
        commands.set((section==SEND_GRID?"#SendGrid":"#ReceiveGrid")+".Slots",slots);
    }

    private static ItemGridSlot slot(ItemStack item) {
        var slot=new ItemGridSlot(ItemStack.isEmpty(item)?null:item.withMetadata((org.bson.BsonDocument)null));
        slot.setActivatable(true);
        return slot;
    }

    private static ItemStack sourceItem(Ref<EntityStore> ref,Store<EntityStore> store,int section,int slot) {
        boolean hotbar=section==InventoryComponent.HOTBAR_SECTION_ID;
        if(!hotbar && section!=InventoryComponent.STORAGE_SECTION_ID) return null;
        var inventory=inventory(ref,store,hotbar);
        return inventory==null || slot<0 || slot>=inventory.getCapacity()?null:inventory.getItemStack((short)slot);
    }

    private static ItemContainer inventory(Ref<EntityStore> ref,Store<EntityStore> store,boolean hotbar) {
        if(hotbar) {
            var inventory=store.getComponent(ref,InventoryComponent.Hotbar.getComponentType());
            return inventory==null?null:inventory.getInventory();
        }
        var inventory=store.getComponent(ref,InventoryComponent.Storage.getComponentType());
        return inventory==null?null:inventory.getInventory();
    }
}
