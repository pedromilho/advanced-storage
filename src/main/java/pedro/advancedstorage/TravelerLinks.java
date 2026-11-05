package pedro.advancedstorage;

import com.google.gson.*;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** World-owned links are keyed by the physical tablet, never the player's life/entity. */
public final class TravelerLinks {
    public static final String ITEM="AdvancedStorage_Travelers_Catalog", KEY="AdvancedStorageTabletId";
    private static final Map<World,TravelerLinks> WORLDS=new WeakHashMap<>();
    public static synchronized TravelerLinks get(World world) {return WORLDS.computeIfAbsent(world,TravelerLinks::new);}
    public static final class Tablet {
        public final UUID id;
        public String holder;
        public StorageNetwork.Pos transmitter;
        Tablet(UUID id,String holder) {this.id=id;this.holder=holder;}
    }
    final Path file;
    final Map<UUID,Tablet> tablets=new LinkedHashMap<>();
    private TravelerLinks(World world) {
        file=world.getSavePath().resolve("advanced-storage-tablets.json");
        if(Files.exists(file)) try {
            for(var value:JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
                var row=value.getAsJsonObject();var t=new Tablet(UUID.fromString(row.get("id").getAsString()),row.get("holder").getAsString());
                if(row.has("x")) t.transmitter=new StorageNetwork.Pos(row.get("x").getAsInt(),row.get("y").getAsInt(),row.get("z").getAsInt());
                tablets.put(t.id,t);
            }
        } catch(IOException | RuntimeException ex) {throw new IllegalStateException("Cannot read tablet links: "+file,ex);}
    }
    public static UUID id(ItemStack stack) {
        if(ItemStack.isEmpty(stack)||!ITEM.equals(stack.getItemId())||stack.getMetadata()==null) return null;
        var value=stack.getMetadata().get(KEY);
        try {return value!=null&&value.isString()?UUID.fromString(value.asString().getValue()):null;} catch(IllegalArgumentException ex) {return null;}
    }
    public static ItemStack identify(ItemStack stack) {
        if(ItemStack.isEmpty(stack)||!ITEM.equals(stack.getItemId())||id(stack)!=null) return stack;
        var meta=stack.getMetadata()==null?new BsonDocument():stack.getMetadata().clone();
        meta.put(KEY,new BsonString(UUID.randomUUID().toString()));return stack.withMetadata(meta);
    }
    void remember(ItemStack item,String holder) {
        var id=id(item);if(id==null)return;
        var t=tablets.computeIfAbsent(id,k->new Tablet(k,holder));t.holder=holder;
    }
    private void inspect(ItemContainer c,String holder) {
        if(c==null)return;
        for(short i=0;i<c.getCapacity();i++) {
            var old=c.getItemStack(i);var item=identify(old);
            if(item!=old)c.setItemStackForSlot(i,item);
            remember(item,holder);
        }
    }
    public void discover(World world) {
        for(var player:world.getPlayerRefs()) {
            var ref=player.getReference();if(ref==null||!ref.isValid())continue;
            var store=ref.getStore();
            for(var type:InventoryComponent.EVERYTHING) {
                var inventory=store.getComponent(ref,type);
                if(inventory!=null)inspect(inventory.getInventory(),player.getUsername());
            }
        }
        world.getEntityStore().getStore().forEachChunk(ItemComponent.getComponentType(),(java.util.function.BiConsumer<com.hypixel.hytale.component.ArchetypeChunk<EntityStore>,com.hypixel.hytale.component.CommandBuffer<EntityStore>>)(chunk,commands)->{
            for(int i=0;i<chunk.size();i++) {
                var component=chunk.getComponent(i,ItemComponent.getComponentType());var old=component.getItemStack();var item=identify(old);
                if(item!=old)component.setItemStack(item);remember(item,"Dropped tablet");
            }
        });
        var chunks=world.getChunkStore();
        chunks.getStore().forEachChunk(ItemContainerBlock.getComponentType(),(java.util.function.BiConsumer<com.hypixel.hytale.component.ArchetypeChunk<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore>,com.hypixel.hytale.component.CommandBuffer<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore>>)(chunk,commands)->{
            for(int i=0;i<chunk.size();i++)inspect(chunk.getComponent(i,ItemContainerBlock.getComponentType()).getItemContainer(),"Stored tablet");
        });
    }
    public void link(UUID id,StorageNetwork.Pos pos) {
        var t=tablets.get(id);if(t==null)throw new IllegalArgumentException("Unknown tablet");
        var previous=t.transmitter;t.transmitter=pos;
        try {save();}catch(RuntimeException ex){t.transmitter=previous;throw ex;}
    }
    void save() {
        var rows=new JsonArray();
        for(var t:tablets.values()) {
            var row=new JsonObject();row.addProperty("id",t.id.toString());row.addProperty("holder",t.holder);
            if(t.transmitter!=null){row.addProperty("x",t.transmitter.x());row.addProperty("y",t.transmitter.y());row.addProperty("z",t.transmitter.z());}
            rows.add(row);
        }
        try {
            Files.createDirectories(file.getParent());var pending=file.resolveSibling(file.getFileName()+".pending");
            Files.writeString(pending,new GsonBuilder().setPrettyPrinting().create().toJson(rows));
            try {Files.move(pending,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException ex){Files.move(pending,file,StandardCopyOption.REPLACE_EXISTING);}
        }catch(IOException ex){throw new IllegalStateException("Cannot save tablet links",ex);}
    }
    public static boolean owns(Ref<EntityStore> ref,World world,UUID id) {
        if(ref==null||!ref.isValid()||ref.getStore().getExternalData().getWorld()!=world)return false;
        var store=ref.getStore();if(store.getComponent(ref,DeathComponent.getComponentType())!=null)return false;
        for(var type:InventoryComponent.EVERYTHING) {
            var component=store.getComponent(ref,type);if(component==null)continue;var c=component.getInventory();
            if(c!=null)for(short i=0;i<c.getCapacity();i++)if(id.equals(id(c.getItemStack(i))))return true;
        }
        return false;
    }
    public record Session(World world,UUID tablet,StorageNetwork.Pos origin) {
        public boolean canDeposit(ItemStack item) {return !tablet.equals(id(item));}
        public boolean valid(Ref<EntityStore> ref) {
            var t=get(world).tablets.get(tablet);
            return owns(ref,world,tablet)&&t!=null&&Objects.equals(t.transmitter,origin);
        }
        public boolean connected() {
            var t=get(world).tablets.get(tablet);
            if(t==null||origin==null||!origin.equals(t.transmitter))return false;
            var n=StorageNetwork.node(world,origin);return n!=null&&n.role().equals("Transmitter");
        }
        public String status() {
            if(origin==null)return "Disconnected - connect this tablet at an Archive Transmitter";
            if(!connected())return "Transmitter unavailable";
            return "Remote connection - Transmitter "+origin.x()+", "+origin.y()+", "+origin.z();
        }
    }
}
