package pedro.advancedstorage;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.event.events.ecs.*;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.world.*;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.*;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.util.*;

/** Single-block breaking plus cleanup of helper IDs left in older saved worlds. */
public final class TransmitterStructure {
    public static final String BASE="AdvancedStorage_Archive_Transmitter";
    public static final String MAST=BASE+"_Mast", ANTENNA=BASE+"_Antenna";
    private static final int QUIET=SetBlockSettings.NO_DROP_ITEMS|SetBlockSettings.NO_FIRE_ON_BREAK|
        SetBlockSettings.NO_SEND_PARTICLES|SetBlockSettings.NO_SEND_AUDIO;
    private record Removal(World world,StorageNetwork.Pos base) {}
    private static final Set<Removal> pending=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private TransmitterStructure() {}
    private static BlockSection section(World w,StorageNetwork.Pos p) {
        if(p.y()<0||p.y()>=320)return null;
        var ref=w.getChunkStore().getChunkSectionReferenceAtBlock(p.x(),p.y(),p.z());
        return ref==null?null:w.getChunkStore().getStore().getComponent(ref,BlockSection.getComponentType());
    }
    public static String id(World w,StorageNetwork.Pos p) {
        var s=section(w,p);if(s==null)return null;
        var t=BlockType.getAssetMap().getAsset(s.get(p.x(),p.y(),p.z()));
        return t==null?null:t.getId();
    }
    private static StorageNetwork.Pos above(StorageNetwork.Pos p,int y) {return new StorageNetwork.Pos(p.x(),p.y()+y,p.z());}
    public static boolean part(String id) {return BASE.equals(id)||MAST.equals(id)||ANTENNA.equals(id);}
    private static StorageNetwork.Pos resolve(World w,Vector3i p) {
        var q=BlockOperations.resolveBaseBlockPosition(w.getChunkStore(),new BlockPosition(p.x,p.y,p.z));
        return new StorageNetwork.Pos(q.x,q.y,q.z);
    }
    private static StorageNetwork.Pos owner(World w,StorageNetwork.Pos p) {
        String id=id(w,p);return MAST.equals(id)?above(p,-1):ANTENNA.equals(id)?above(p,-2):p;
    }
    private static boolean put(World w,StorageNetwork.Pos p,String name,int rotation,boolean fillers) {
        var chunks=w.getChunkStore();var ref=chunks.getChunkSectionReferenceAtBlock(p.x(),p.y(),p.z());
        if(ref==null)return false;
        int index=BlockType.getAssetMap().getIndex(name);var type=BlockType.getAssetMap().getAsset(index);
        if(type==null)return false;
        BlockOperations.setBlock(chunks,ref,p.x(),p.y(),p.z(),index,type,rotation,0,
            QUIET|(fillers?0:SetBlockSettings.NO_SET_FILLER)|SetBlockSettings.NO_BREAK_FILLER);
        return name.equals(id(w,p));
    }
    /** Snapshot owned fillers before removing their anchors; never delete a neighbouring structure. */
    public static boolean dismantle(World w,StorageNetwork.Pos base) {
        Set<StorageNetwork.Pos> anchors=Set.of(base,above(base,1),above(base,2));
        var owned=new ArrayList<StorageNetwork.Pos>();
        boolean found=false;
        for(int dy=0;dy<=4;dy++)for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++) {
            var p=new StorageNetwork.Pos(base.x()+dx,base.y()+dy,base.z()+dz);
            if(!part(id(w,p)))continue;
            var resolved=resolve(w,new Vector3i(p.x(),p.y(),p.z()));
            if(!anchors.contains(resolved)||!owner(w,resolved).equals(base))continue;
            owned.add(p);found=true;
        }
        for(var p:owned)put(w,p,"Empty",0,false);
        return found;
    }
    private static void drop(World w,StorageNetwork.Pos p) {
        var store=w.getEntityStore().getStore();
        var drops=ItemComponent.generateItemDrops(store,List.of(new ItemStack(BASE,1)),
            new Vector3d(p.x()+.5,p.y()+.5,p.z()+.5),new com.hypixel.hytale.math.vector.Rotation3f());
        for(var holder:drops)store.addEntity(holder,AddReason.SPAWN);
    }
    public static final class Breaking extends EntityEventSystem<EntityStore,BreakBlockEvent> {
        public Breaking(){super(BreakBlockEvent.class);}
        @Override public Query<EntityStore> getQuery(){return Query.any();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,
                CommandBuffer<EntityStore> commands,BreakBlockEvent event) {
            if(event.isCancelled()||!part(event.getBlockType().getId()))return;
            var world=store.getExternalData().getWorld();var actual=resolve(world,event.getTargetBlock());
            var base=owner(world,actual);event.setCancelled(true);
            var key=new Removal(world,base);if(!pending.add(key))return;
            world.execute(()->{try{if(dismantle(world,base))drop(world,base);}finally{pending.remove(key);}});
        }
    }
    public static final class EnvironmentBreaking extends com.hypixel.hytale.component.system.WorldEventSystem<ChunkStore,EnvironmentBreakBlockEvent> {
        public EnvironmentBreaking(){super(EnvironmentBreakBlockEvent.class);}
        @Override public void handle(Store<ChunkStore> store,
                CommandBuffer<ChunkStore> commands,EnvironmentBreakBlockEvent event) {
            if(!part(event.getBlockType().getId()))return;
            var world=store.getExternalData().getWorld();var base=owner(world,resolve(world,event.getTargetBlock()));
            boolean hadBase=BASE.equals(id(world,base));var key=new Removal(world,base);
            if(!pending.add(key))return;
            world.execute(()->{try{boolean removed=dismantle(world,base);if(hadBase||removed)drop(world,base);}finally{pending.remove(key);}});
        }
    }
}
