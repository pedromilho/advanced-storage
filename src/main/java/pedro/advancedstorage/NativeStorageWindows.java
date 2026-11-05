package pedro.advancedstorage;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerBlockWindow;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import java.util.concurrent.*;

public final class NativeStorageWindows {
    private NativeStorageWindows() {}
    public static boolean canAccess(Ref<EntityStore> ref,World world,StorageNetwork.Pos pos,String role) {
        if(ref==null || !ref.isValid() || ref.getStore().getExternalData().getWorld()!=world) return false;
        var transform=ref.getStore().getComponent(ref,TransformComponent.getComponentType());
        var node=StorageNetwork.node(world,pos);
        return transform!=null && node!=null && node.role().equals(role) && transform.getPosition().distanceSquared(pos.x()+.5,pos.y()+.5,pos.z()+.5)<=100;
    }
    public static void open(Ref<EntityStore> ref,World world,StorageNetwork.Pos pos) {
        var node=StorageNetwork.node(world,pos);
        if(node!=null && node.role().equals(ArcaneItemExporter.ROLE) && canAccess(ref,world,pos,ArcaneItemExporter.ROLE)) {
            var store=ref.getStore();
            var player=store.getComponent(ref,Player.getComponentType());
            var playerRef=store.getComponent(ref,PlayerRef.getComponentType());
            var exporter=ArcaneItemExporter.at(world,pos);
            if(player!=null && playerRef!=null && exporter!=null)
                player.getPageManager().openCustomPage(ref,store,new ArchiveItemGatePage(playerRef,world,pos,exporter));
            return;
        }
        if(node!=null && node.role().equals("ItemGate") && canAccess(ref,world,pos,"ItemGate")) {
            var store=ref.getStore();
            var player=store.getComponent(ref,Player.getComponentType());
            var playerRef=store.getComponent(ref,PlayerRef.getComponentType());
            var gate=ArchiveItemGate.at(world,pos);
            if(player!=null && playerRef!=null && gate!=null) {
                player.getPageManager().openCustomPage(ref,store,new ArchiveItemGatePage(playerRef,world,pos,gate));
            }
            return;
        }
        if(node!=null && node.role().equals("Transmitter") && canAccess(ref,world,pos,"Transmitter")) {
            var store=ref.getStore();var player=store.getComponent(ref,Player.getComponentType());var playerRef=store.getComponent(ref,PlayerRef.getComponentType());
            if(player!=null&&playerRef!=null)player.getPageManager().openCustomPage(ref,store,new TransmitterPage(playerRef,world,pos));
            return;
        }
        if(node!=null && node.role().equals("Catalog") && canAccess(ref,world,pos,"Catalog")) {
            var player=ref.getStore().getComponent(ref,Player.getComponentType());
            var playerRef=ref.getStore().getComponent(ref,PlayerRef.getComponentType());
            var store=ref.getStore();
            var inventory=store.getComponent(ref,InventoryComponent.Storage.getComponentType());
            var hotbar=store.getComponent(ref,InventoryComponent.Hotbar.getComponentType());
            var backpack=store.getComponent(ref,InventoryComponent.Backpack.getComponentType());
            if(player==null || playerRef==null || inventory==null || hotbar==null) return;
            var chunks=world.getChunkStore();
            var section=chunks.getStore().getComponent(chunks.getChunkSectionReferenceAtBlock(pos.x(),pos.y(),pos.z()),BlockSection.getComponentType());
            var type=BlockType.getAssetMap().getAsset(section.get(pos.x(),pos.y(),pos.z()));
            var window=new ContainerBlockWindow(pos.x(),pos.y(),pos.z(),section.getRotationIndex(pos.x(),pos.y(),pos.z()),type,
                new CatalogContainer(world,pos,CatalogSettingsPage.preference(playerRef)));
            var page=new CatalogSettingsPage(playerRef,world,pos,window,inventory.getInventory(),hotbar.getInventory(),backpack==null?null:backpack.getInventory());
            player.getPageManager().openCustomPageWithWindows(ref,store,page,page.windows());return;
        }
        if(node!=null && node.role().equals("Core") && canAccess(ref,world,pos,"Core")) {
            var store=ref.getStore();var player=store.getComponent(ref,Player.getComponentType());
            var playerRef=store.getComponent(ref,PlayerRef.getComponentType());
            if(player!=null && playerRef!=null) player.getPageManager().openCustomPage(ref,store,new CoreNetworkPage(playerRef,world,pos));
            return;
        }
        openInventory(ref,world,pos,CatalogView.DEFAULT);
    }
    public static void openInventory(Ref<EntityStore> ref,World world,StorageNetwork.Pos pos,CatalogView view) {
        if(!ref.isValid() || ref.getStore().getExternalData().getWorld()!=world) return;
        var store=ref.getStore();
        var player=store.getComponent(ref,Player.getComponentType());
        var playerRef=store.getComponent(ref,PlayerRef.getComponentType());
        var transform=store.getComponent(ref,TransformComponent.getComponentType());
        if(player==null || playerRef==null || transform==null || transform.getPosition().distanceSquared(pos.x()+.5,pos.y()+.5,pos.z()+.5)>100) return;
        var node=StorageNetwork.node(world,pos);
        if(node==null) return;
        ItemContainer container;
        if(node.role().equals("Core")) {
            status(playerRef,StorageNetwork.scan(world,pos));container=new CoreContainer(world,pos);
        } else if(node.role().equals("Archive")) {
            var inventory=InventoryComponent.getCombined(store,ref,InventoryComponent.Storage.getComponentType(),InventoryComponent.Hotbar.getComponentType(),InventoryComponent.Backpack.getComponentType());
            if(!StorageNetwork.resizeArchive(world,pos,inventory)) {
                playerRef.sendMessage(Message.raw("Free inventory space to receive the extra seals from this old Archive."));return;
            }
            container=new ArchiveContainer(world,pos);
        } else if(node.role().equals("Catalog")) {
            var net=StorageNetwork.scan(world,pos);status(playerRef,net);
            if(!net.active() || net.seals.isEmpty()) return;
            container=new CatalogContainer(world,pos,view);
        } else return;
        if(container==null) return;
        var chunks=world.getChunkStore();
        var sectionRef=chunks.getChunkSectionReferenceAtBlock(pos.x(),pos.y(),pos.z());
        var section=chunks.getStore().getComponent(sectionRef,BlockSection.getComponentType());
        var type=BlockType.getAssetMap().getAsset(section.get(pos.x(),pos.y(),pos.z()));
        var window=new StorageWindow(world,pos,section.getRotationIndex(pos.x(),pos.y(),pos.z()),type,container);
        if(node.role().equals("Archive")) {
            var inventory=store.getComponent(ref,InventoryComponent.Storage.getComponentType());
            var hotbar=store.getComponent(ref,InventoryComponent.Hotbar.getComponentType());
            var backpack=store.getComponent(ref,InventoryComponent.Backpack.getComponentType());
            if(inventory!=null && hotbar!=null) player.getPageManager().openCustomPageWithWindows(ref,store,
                new CatalogSettingsPage(playerRef,world,pos,window,inventory.getInventory(),hotbar.getInventory(),backpack==null?null:backpack.getInventory()),window);
        } else player.getPageManager().setPageWithWindows(ref,store,Page.Bench,true,window);
    }
    public static void openRemote(Ref<EntityStore> ref,World world,TravelerLinks.Session session) {
        if(!session.valid(ref))return;
        RemoteNetworkLease.prepare(session,ref).whenComplete((lease,error)->world.execute(()->{
            if(error!=null){world.getLogger().atWarning().withCause(error).log("Could not load tablet network");return;}
            if(!session.valid(ref)){lease.close();return;}
            try {openRemoteLoaded(ref,world,session,lease);}catch(RuntimeException ex){lease.close();throw ex;}
        }));
    }
    private static void openRemoteLoaded(Ref<EntityStore> ref,World world,TravelerLinks.Session session,RemoteNetworkLease lease) {
        var store=ref.getStore();var player=store.getComponent(ref,Player.getComponentType());var playerRef=store.getComponent(ref,PlayerRef.getComponentType());
        var inventory=store.getComponent(ref,InventoryComponent.Storage.getComponentType());var hotbar=store.getComponent(ref,InventoryComponent.Hotbar.getComponentType());var backpack=store.getComponent(ref,InventoryComponent.Backpack.getComponentType());
        if(player==null||playerRef==null||inventory==null||hotbar==null){lease.close();return;}
        var origin=session.origin()==null?new StorageNetwork.Pos(0,-1,0):session.origin();
        var container=new CatalogContainer(world,origin,CatalogSettingsPage.preference(playerRef),()->session.valid(ref)&&session.connected(),session::canDeposit);
        var window=new ContainerBlockWindow(origin.x(),origin.y(),origin.z(),0,BlockType.getAssetMap().getAsset(TransmitterStructure.BASE),container) {
            @Override public boolean validate(Ref<EntityStore> actor,ComponentAccessor<EntityStore> accessor){return session.valid(actor);}
            @Override public void onClose0(Ref<EntityStore> actor,ComponentAccessor<EntityStore> accessor){try{super.onClose0(actor,accessor);}finally{lease.close();}}
        };
        var page=new CatalogSettingsPage(playerRef,world,origin,window,inventory.getInventory(),hotbar.getInventory(),backpack==null?null:backpack.getInventory(),session);
        if(!player.getPageManager().openCustomPageWithWindows(ref,store,page,page.windows()))lease.close();
    }
    static void status(PlayerRef player,StorageNetwork net) {
        String state=net.truncated?"Network too large":net.invalid?"Unreadable storage seal":net.cores==0?"Offline - connect an Arcane Core":net.seals.isEmpty()?"Insert storage seals into an Archive":"Network online";
        player.sendMessage(Message.raw(String.format(Locale.US,"%s | %,d / %,d items | %d seals | %d archives",state,net.used(),net.capacity(),net.seals.size(),net.archives.size())));
    }
    public static final class StorageWindow extends ContainerBlockWindow {
        final World world;
        final StorageNetwork.Pos pos;
        boolean open;
        com.hypixel.hytale.event.EventRegistration<?,?> changes;
        public StorageWindow(World world,StorageNetwork.Pos pos,int rotation,BlockType block,ItemContainer container) {
            super(pos.x(),pos.y(),pos.z(),rotation,block,container);this.world=world;this.pos=pos;
        }
        @Override public boolean onOpen0(Ref<EntityStore> ref,Store<EntityStore> store) {
            open=true;
            if(getItemContainer() instanceof CatalogContainer || getItemContainer() instanceof CoreContainer || getItemContainer() instanceof ArchiveContainer) poll();
            else changes=getItemContainer().registerChangeEvent(event->StorageNetwork.dirty(world,pos));
            return true;
        }
        void poll() {
            if(!open) return;
            boolean changed=getItemContainer() instanceof CatalogContainer catalog?catalog.refresh():getItemContainer() instanceof ArchiveContainer archive?archive.refresh():((CoreContainer)getItemContainer()).refresh();
            if(changed) invalidate();
            CompletableFuture.delayedExecutor(250,TimeUnit.MILLISECONDS,world).execute(this::poll);
        }
        @Override public void onClose0(Ref<EntityStore> ref,ComponentAccessor<EntityStore> accessor) {
            open=false;
            if(changes!=null) changes.unregister();
            StorageNetwork.dirty(world,pos);
        }
        @Override public void handleAction(Ref<EntityStore> ref,Store<EntityStore> store,com.hypixel.hytale.protocol.packets.window.WindowAction action) {
            if(getItemContainer() instanceof CoreContainer) return;
            if(getItemContainer() instanceof CatalogContainer catalog && action instanceof com.hypixel.hytale.protocol.packets.window.SortItemsAction) {
                var order=catalog.view.order()==CatalogView.Order.QUANTITY?CatalogView.Order.ALPHABETICAL:CatalogView.Order.QUANTITY;
                var view=new CatalogView(catalog.view.search(),order);catalog.setView(view);
                var player=store.getComponent(ref,PlayerRef.getComponentType());
                if(player!=null) {
                    AdvancedStoragePlugin.catalogPreferences.remember(player.getUuid(), view);
                    player.sendMessage(Message.raw("Sort: "+(order==CatalogView.Order.QUANTITY?"Quantity":"Alphabetical")));
                }
                invalidate();return;
            }
            if(getItemContainer() instanceof ArchiveContainer archive && action instanceof com.hypixel.hytale.protocol.packets.window.SortItemsAction) {
                archive.sortSeals();invalidate();return;
            }
            super.handleAction(ref,store,action);
        }
    }
}
