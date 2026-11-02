package pedro.advancedstorage;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.*;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.math.util.ChunkUtil;
import org.joml.Vector3i;
import java.util.*;

/** Accessed only on the owning world's thread. Every operation uses a fresh topology snapshot. */
public final class StorageNetwork {
    public record Pos(int x,int y,int z) { Pos add(Vector3i v) { return new Pos(x+v.x,y+v.y,z+v.z); } }
    static final Vector3i[] DIR={new Vector3i(0,0,-1),new Vector3i(1,0,0),new Vector3i(0,0,1),new Vector3i(-1,0,0),new Vector3i(0,1,0),new Vector3i(0,-1,0)};
    static final int[] OPP={2,3,0,1,5,4};
    public record Node(String role,int ports) {}
    public record SealSlot(Pos position,ItemContainer container,short slot,ItemStack original,SealData data) {}
    public static final class StoredItem {
        public final ItemStack template;
        public long count;
        public StoredItem(ItemStack template,long count) {this.template=template;this.count=count;}
    }
    public final World world;
    public final List<Pos> archives=new ArrayList<>();
    public final List<SealSlot> seals=new ArrayList<>();
    public final Map<String,Integer> connectedBlocks=new TreeMap<>();
    public int cores, conduits;
    public boolean truncated, invalid;
    private StorageNetwork(World world) { this.world=world; }
    public static Node node(World world,Pos p) {
        var chunks=world.getChunkStore();
        if(p.y<0 || p.y>=320) return null;
        var ref=chunks.getChunkSectionReferenceAtBlock(p.x,p.y,p.z);
        if(ref==null || !ref.isValid()) return null;
        var section=chunks.getStore().getComponent(ref,BlockSection.getComponentType());
        if(section==null) return null;
        var type=BlockType.getAssetMap().getAsset(section.get(p.x,p.y,p.z));
        if(type==null) return null;
        String id=type.getId(),role;
        int mask;
        if(id.contains("AdvancedStorage_Seal_Archive")) { role="Archive"; mask=63; }
        else if(id.contains("AdvancedStorage_Arcane_Core")) { role="Core"; mask=15; }
        else if(id.contains("AdvancedStorage_Runic_Catalog")) { role="Catalog"; mask=11; }
        else if(id.startsWith("AdvancedStorage_Runic_Conduit")) {
            role="Conduit";
            mask=id.endsWith("_Curve")?12:id.endsWith("_T")?14:id.endsWith("_Cross")?15:
                id.endsWith("_Up")?30:id.endsWith("_UpDown")?62:id.endsWith("_All")?63:id.endsWith("_Corner3")?28:5;
        } else return null;
        int rotated=0;
        var rotation=RotationTuple.get(section.getRotationIndex(p.x,p.y,p.z));
        for(int i=0;i<6;i++) if((mask&(1<<i))!=0) {
            var d=new Vector3i(DIR[i]); rotation.applyRotationTo(d);
            for(int j=0;j<6;j++) if(d.equals(DIR[j])) rotated|=1<<j;
        }
        return new Node(role,rotated);
    }
    private static ItemContainerBlock archiveComponent(World world,Pos p) {
        var n=node(world,p);
        if(n==null || !n.role.equals("Archive")) return null;
        var ref=BlockModule.getBlockEntity(world,p.x,p.y,p.z);
        var chunks=world.getChunkStore();
        ItemContainerBlock block;
        if(ref!=null && ref.isValid()) block=chunks.getStore().getComponent(ref,ItemContainerBlock.getComponentType());
        else {
            var sectionRef=chunks.getChunkSectionReferenceAtBlock(p.x,p.y,p.z);
            var section=chunks.getStore().getComponent(sectionRef,BlockComponentSection.getComponentType());
            var holder=section==null?null:section.getBlockHolder(ChunkUtil.indexBlock(p.x,p.y,p.z));
            block=holder==null?null:holder.getComponent(ItemContainerBlock.getComponentType());
        }
        return block;
    }
    public static ItemContainer archive(World world,Pos p) {
        var block=archiveComponent(world,p);
        if(block==null) return null;
        var container=block.getItemContainer();
        if(container!=null) for(short slot=0;slot<container.getCapacity();slot++)
            container.setSlotFilter(FilterActionType.ADD,slot,(action,c,s,item)->ItemStack.isEmpty(item) || (s<SealData.ARCHIVE_SLOTS && SealData.capacity(item)>0 && item.getQuantity()==1));
        return container;
    }
    /** Upgrade old containers without truncating occupied slots or overwriting the player's items. */
    public static boolean resizeArchive(World world,Pos p,ItemContainer playerInventory) {
        var block=archiveComponent(world,p);
        if(block==null || block.getItemContainer()==null) return false;
        var old=block.getItemContainer();
        if(old.getCapacity()==SealData.ARCHIVE_SLOTS) return true;
        var occupied=new ArrayList<ItemStack>();
        for(short slot=0;slot<old.getCapacity();slot++) {
            var item=old.getItemStack(slot);
            if(!ItemStack.isEmpty(item)) occupied.add(item);
        }
        int retained=Math.min(SealData.ARCHIVE_SLOTS,occupied.size());
        var excess=occupied.subList(retained,occupied.size());
        if(!excess.isEmpty() && (playerInventory==null || !playerInventory.canAddItemStacks(excess,true,true))) return false;
        var replacement=new SimpleItemContainer(SealData.ARCHIVE_SLOTS);
        for(short slot=0;slot<retained;slot++) replacement.setItemStackForSlot(slot,occupied.get(slot));
        var before=playerInventory==null?null:snapshot(playerInventory);
        var originalComponent=ItemContainerBlock.CODEC.encode(block);
        try {
            for(var item:excess) {
                var tx=playerInventory.addItemStack(item,true,true,true);
                if(!tx.succeeded() || !ItemStack.isEmpty(tx.getRemainder())) throw new IllegalStateException("Inventory cannot receive legacy seals");
            }
            var resized=originalComponent.asDocument().clone();
            resized.put("Capacity",new org.bson.BsonInt32(SealData.ARCHIVE_SLOTS));
            ItemContainerBlock.CODEC.decode(resized,block,new com.hypixel.hytale.codec.ExtraInfo());
            block.setItemContainer(replacement);archive(world,p);dirty(world,p);return true;
        } catch(RuntimeException ex) {
            ItemContainerBlock.CODEC.decode(originalComponent,block,new com.hypixel.hytale.codec.ExtraInfo());
            block.setItemContainer(old);
            if(before!=null) restore(playerInventory,before);
            throw ex;
        }
    }
    public static void dirty(World world,Pos p) {
        var chunks=world.getChunkStore();
        var ref=chunks.getChunkSectionReferenceAtBlock(p.x,p.y,p.z);
        if(ref!=null) {
            var section=chunks.getStore().getComponent(ref,BlockComponentSection.getComponentType());
            if(section!=null) section.markNeedsSaving();
        }
        var chunk=chunks.getChunkComponent(ChunkUtil.indexChunkFromBlock(p.x,p.z),WorldChunk.getComponentType());
        if(chunk!=null) chunk.markNeedsSaving();
    }
    public static StorageNetwork scan(World world,Pos origin) {
        var net=new StorageNetwork(world);
        var pending=new ArrayDeque<Pos>();
        var visited=new HashSet<Pos>();
        pending.add(origin); visited.add(origin);
        while(!pending.isEmpty()) {
            Pos p=pending.remove(); var n=node(world,p);
            if(n==null) continue;
            String itemId=switch(n.role) {
                case "Core" -> "AdvancedStorage_Arcane_Core";
                case "Archive" -> "AdvancedStorage_Seal_Archive";
                case "Catalog" -> "AdvancedStorage_Runic_Catalog";
                default -> "AdvancedStorage_Runic_Conduit";
            };
            net.connectedBlocks.merge(itemId,1,Integer::sum);
            switch(n.role) {
                case "Core" -> net.cores++;
                case "Conduit" -> net.conduits++;
                case "Archive" -> {
                    net.archives.add(p);
                    var container=archive(world,p);
                    if(container!=null) for(short s=0;s<Math.min(SealData.ARCHIVE_SLOTS,container.getCapacity());s++) {
                        var seal=container.getItemStack(s);
                        if(SealData.capacity(seal)==0) continue;
                        try { net.seals.add(new SealSlot(p,container,s,seal,SealData.read(seal))); }
                        catch(RuntimeException ex) { net.invalid=true; }
                    }
                }
            }
            for(int i=0;i<6;i++) if((n.ports&(1<<i))!=0) {
                Pos q=p.add(DIR[i]);
                if(visited.contains(q)) continue;
                var next=node(world,q);
                if(next==null || (next.ports&(1<<OPP[i]))==0) continue;
                if(visited.size()>=4096) { net.truncated=true; continue; }
                visited.add(q); pending.add(q);
            }
        }
        return net;
    }
    public boolean active() { return cores>0 && !truncated && !invalid; }
    public long capacity() { return seals.stream().mapToLong(s->s.data.capacity).sum(); }
    public long used() { return seals.stream().mapToLong(s->s.data.used()).sum(); }
    /** Commit the net change made by one native inventory transaction to its visible stacks. */
    public void commitProjection(ItemStack[] before,ItemStack[] after) {
        if(!active()) throw new IllegalStateException("Network offline");
        var changes=new ArrayList<StoredItem>();
        for(int pass=0;pass<2;pass++) for(var item:pass==0?before:after) {
            if(ItemStack.isEmpty(item)) continue;
            if(SealData.capacity(item)>0) throw new IllegalArgumentException("Storage seals cannot be nested");
            var existing=changes.stream().filter(e->e.template.isStackableWith(item)).findFirst();
            long delta=(pass==0?-1L:1L)*item.getQuantity();
            if(existing.isPresent()) existing.get().count+=delta;
            else changes.add(new StoredItem(item.withQuantity(1),delta));
        }
        for(var change:changes) if(change.count<0) {
            int left=Math.toIntExact(-change.count);
            for(var seal:seals) left-=seal.data.remove(change.template,left);
            if(left!=0) throw new IllegalStateException("Stored items changed");
        }
        for(var change:changes) if(change.count>0) {
            int left=Math.toIntExact(change.count);
            for(var seal:seals) left-=seal.data.insert(change.template,left);
            if(left!=0) throw new IllegalStateException("Storage seals are full");
        }
        if(changes.stream().anyMatch(e->e.count!=0)) commit(prepare());
    }
    public List<StoredItem> contents() {
        var result=new ArrayList<StoredItem>();
        for(var seal:seals) for(var entry:seal.data.entries) {
            var same=result.stream().filter(e->e.template.isStackableWith(entry.template)).findFirst();
            if(same.isPresent()) same.get().count+=entry.count;
            else result.add(new StoredItem(entry.template,entry.count));
        }
        result.sort(Comparator.comparing(e->e.template.getItemId()));
        return result;
    }
    private List<ItemStack> prepare() {
        for(var s:seals) if(!Objects.equals(s.container.getItemStack(s.slot),s.original))
            throw new IllegalStateException("Archive changed during transfer");
        return seals.stream().map(s->s.data.write(s.original)).toList();
    }
    private void commit(List<ItemStack> updates) {
        // No yields between inventory and seal writes. Roll back every seal if a native write fails.
        try {
            for(int i=0;i<seals.size();i++) {
                var s=seals.get(i);
                if(!s.container.setItemStackForSlot(s.slot,updates.get(i)).succeeded())
                    throw new IllegalStateException("Seal update rejected");
                dirty(world,s.position);
            }
        } catch(RuntimeException ex) {
            for(var s:seals) {s.container.setItemStackForSlot(s.slot,s.original);dirty(world,s.position);}
            throw ex;
        }
    }
    private static List<ItemStack> snapshot(ItemContainer inventory) {
        var items=new ArrayList<ItemStack>();
        for(short slot=0;slot<inventory.getCapacity();slot++) items.add(inventory.getItemStack(slot));
        return items;
    }
    private static void restore(ItemContainer inventory,List<ItemStack> items) {
        for(short slot=0;slot<inventory.getCapacity();slot++) inventory.setItemStackForSlot(slot,items.get(slot));
    }
    public static boolean insertSeal(World world,Pos p,ItemContainer source,short sourceSlot) {
        var container=archive(world,p);
        var seal=source.getItemStack(sourceSlot);
        if(container==null || SealData.capacity(seal)==0 || seal.getQuantity()!=1) return false;
        SealData.read(seal);
        short empty=-1;
        for(short slot=0;slot<Math.min(SealData.ARCHIVE_SLOTS,container.getCapacity());slot++)
            if(ItemStack.isEmpty(container.getItemStack(slot))) {empty=slot;break;}
        if(empty<0) return false;
        if(!source.removeItemStackFromSlot(sourceSlot,1,true,true).succeeded()) return false;
        try {
            if(!container.setItemStackForSlot(empty,seal).succeeded()) throw new IllegalStateException("Seal insertion rejected");
            dirty(world,p);return true;
        } catch(RuntimeException ex) {container.setItemStackForSlot(empty,null);source.setItemStackForSlot(sourceSlot,seal);throw ex;}
    }
    public static boolean removeSeal(World world,Pos p,short slot,ItemContainer target) {
        var container=archive(world,p);
        if(container==null || slot<0 || slot>=SealData.ARCHIVE_SLOTS) return false;
        var seal=container.getItemStack(slot);
        if(ItemStack.isEmpty(seal) || !target.canAddItemStack(seal,true,true)) return false;
        var before=snapshot(target);
        var tx=target.addItemStack(seal,true,true,true);
        if(!tx.succeeded() || !ItemStack.isEmpty(tx.getRemainder())) {restore(target,before);return false;}
        try {
            if(!container.setItemStackForSlot(slot,null).succeeded()) throw new IllegalStateException("Seal removal rejected");
            dirty(world,p);return true;
        } catch(RuntimeException ex) {container.setItemStackForSlot(slot,seal);restore(target,before);throw ex;}
    }
    public int deposit(ItemContainer source,short slot) {
        if(!active()) return 0;
        var item=source.getItemStack(slot);
        if(ItemStack.isEmpty(item) || SealData.capacity(item)>0) return 0;
        int amount=0;
        for(var seal:seals) amount+=seal.data.insert(item,item.getQuantity()-amount);
        if(amount==0) return 0;
        var updates=prepare();
        var tx=source.removeItemStackFromSlot(slot,amount,true,true);
        if(!tx.succeeded()) return 0;
        try { commit(updates); }
        catch(RuntimeException ex) { source.setItemStackForSlot(slot,item); throw ex; }
        return amount;
    }
    public int withdraw(ItemContainer target,ItemStack template,int requested) {
        if(!active() || requested<=0) return 0;
        long available=0;
        for(var s:seals) for(var e:s.data.entries) if(e.template.isStackableWith(template)) available+=e.count;
        int amount=(int)Math.min(requested,available);
        if(amount==0) return 0;
        // All-or-nothing native insertion prevents partial insertion from duplicating stored items.
        var stack=template.withQuantity(amount);
        if(!target.canAddItemStack(stack,true,true)) return 0;
        int left=amount;
        for(var s:seals) left-=s.data.remove(template,left);
        var updates=prepare();
        var before=snapshot(target);
        var tx=target.addItemStack(stack,true,true,true);
        if(!tx.succeeded() || !ItemStack.isEmpty(tx.getRemainder())) {restore(target,before);return 0;}
        try {commit(updates);} catch(RuntimeException ex) {restore(target,before);throw ex;}
        return amount;
    }
}
