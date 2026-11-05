package pedro.advancedstorage;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.*;
import java.util.function.*;

/** Native chest slots backed by seals. Visible stacks refill from the network after transfers. */
public final class CatalogContainer extends SafeItemContainer {
    final World world;
    final StorageNetwork.Pos origin;
    ItemStack[] identities;
    ItemStack[] baseline;
    StorageNetwork network;
    int writing;
    boolean refreshing;
    private long networkScans;
    private int projectionReads;
    CatalogView view;
    final BooleanSupplier access;
    final Predicate<ItemStack> depositAllowed;
    public pedro.advancedstorage.ui.GridViewport viewport;
    public void enableViewport(int rows){
        if(writing!=0)throw new IllegalStateException("Transfer in progress");
        viewport=new pedro.advancedstorage.ui.GridViewport(9,rows);capacity=(short)(viewport.capacity()+1);
        items=new ItemStack[capacity];identities=new ItemStack[capacity];refresh();
    }
    public boolean scrollTo(int row){
        if(writing!=0)throw new IllegalStateException("Transfer in progress");
        if(viewport==null||!viewport.setFirstRow(row))return false;
        Arrays.fill(identities,null);refresh();return true;
    }
    ItemStack[] snapshot() {refresh();return items.clone();}
    /** Repack the visible list between gestures; native transfers retain stable source slots. */
    public boolean refreshOrder() {
        if(writing!=0) return false;
        Arrays.fill(identities,null);
        return refresh();
    }
    public void setView(CatalogView view) {
        if(writing!=0) throw new IllegalStateException("Cannot reorder during a transfer");
        if(viewport!=null)viewport.setFirstRow(0);
        this.view=view;Arrays.fill(identities,null);refresh();
    }
    public CatalogContainer(World world,StorageNetwork.Pos origin) {
        this(world,origin,CatalogView.DEFAULT);
    }
    public CatalogContainer(World world,StorageNetwork.Pos origin,CatalogView view) {
        this(world,origin,view,()->true);
    }
    public CatalogContainer(World world,StorageNetwork.Pos origin,CatalogView view,BooleanSupplier access) {
        this(world,origin,view,access,item->true);
    }
    public CatalogContainer(World world,StorageNetwork.Pos origin,CatalogView view,BooleanSupplier access,Predicate<ItemStack> depositAllowed) {
        super((short)63);
        this.world=world;this.origin=origin;this.view=view;this.access=access;this.depositAllowed=depositAllowed;identities=new ItemStack[63];refresh();
    }
    public boolean refresh() {
        if(world==null || writing>0 || refreshing || projectionReads>0) return false;
        refreshing=true;
        try {
            networkScans++;
            network=StorageNetwork.scan(world,origin);
            if(!access.getAsBoolean())network.invalid=true;
            var old=items.clone();
            var entries=network.active()?view.select(network.contents()):new ArrayList<StorageNetwork.StoredItem>();
            if(viewport!=null){
                viewport.setItemCount(entries.size());int start=Math.min(entries.size(),viewport.start());
                entries=new ArrayList<>(entries.subList(start,Math.min(entries.size(),start+viewport.capacity())));
            }
            int needed=viewport==null?Math.min(32760,Math.max(63,((entries.size()+17)/9)*9)):viewport.capacity()+1;
            if(needed>capacity) {
                capacity=(short)needed;items=Arrays.copyOf(items,needed);identities=Arrays.copyOf(identities,needed);
            }
            // Keep the last viewport slot free for deposits, even if the item is already visible.
            int visibleSlots=viewport==null?capacity:viewport.capacity();
            var remaining=new long[entries.size()];
            for(int i=0;i<entries.size();i++) remaining[i]=entries.get(i).count;
            Arrays.fill(items,null);
            // Keep existing slot identities stable while other players use the same network.
            for(int slot=0;slot<visibleSlots;slot++) if(identities[slot]!=null) {
                for(int i=0;i<entries.size();i++) if(remaining[i]>0 && entries.get(i).template.isStackableWith(identities[slot])) {
                    int quantity=(int)Math.min(remaining[i],Math.max(1,identities[slot].getItem().getMaxStack()));
                    items[slot]=identities[slot].withQuantity(quantity);remaining[i]-=quantity;break;
                }
            }
            for(int i=0;i<entries.size();i++) {
                var entry=entries.get(i);
                if(remaining[i]==0 || Arrays.stream(items).anyMatch(s->!ItemStack.isEmpty(s) && s.isStackableWith(entry.template))) continue;
                for(int slot=0;slot<visibleSlots;slot++) if(ItemStack.isEmpty(items[slot])) {
                    identities[slot]=entry.template;
                    items[slot]=entry.template.withQuantity((int)Math.min(remaining[i],Math.max(1,entry.template.getItem().getMaxStack())));
                    break;
                }
            }
            itemsCount=(int)Arrays.stream(items).filter(s->!ItemStack.isEmpty(s)).count();
            return !Arrays.equals(old,items);
        } finally {refreshing=false;}
    }
    private void begin() {
        super.lockForWrite();
        if(writing==0) {refresh();baseline=items.clone();}
        writing++;
    }
    private void end() {
        try {
            if(--writing==0) {
                if(!Arrays.equals(baseline,items)) network.commitProjection(baseline,items);
                int visibleSlots=viewport==null?capacity:viewport.capacity();
                for(int i=0;i<visibleSlots;i++) if(!ItemStack.isEmpty(items[i])) identities[i]=items[i].withQuantity(1);
            }
        } finally {super.unlockForWrite();}
    }
    @Override protected <V> V writeAction(Supplier<V> action) {
        begin();
        try {return action.get();} finally {end();}
    }
    @Override protected <X,V> V writeAction(Function<X,V> action,X value) {return writeAction(()->action.apply(value));}
    @Override protected void lockForWrite() {begin();}
    @Override protected void unlockForWrite() {end();}
    @Override protected <V> V readAction(Supplier<V> action) {refresh();return super.readAction(action);}
    @Override protected <X,V> V readAction(Function<X,V> action,X value) {refresh();return super.readAction(action,value);}
    @Override public Map<Integer,com.hypixel.hytale.protocol.ItemWithAllMetadata> toProtocolMap() {
        // Native serialization loops through every slot. Scan once for the complete packet,
        // then let each slot read the same projection. No snapshot survives this call.
        refresh();projectionReads++;
        try {return super.toProtocolMap();} finally {projectionReads--;}
    }
    @Override public ItemStack getItemStack(short slot) {refresh();return super.getItemStack(slot);}
    @Override public SimpleItemContainer clone() {refresh();return new SimpleItemContainer(this);}
    @Override protected boolean cantAddToSlot(short slot,ItemStack incoming,ItemStack existing) {
        if(ItemStack.isEmpty(incoming)) return false;
        if(!depositAllowed.test(incoming))return true;
        if(network==null || !network.active() || SealData.capacity(incoming)>0) return true;
        long pending=0;
        if(baseline!=null && writing>0) {
            for(var item:items) if(!ItemStack.isEmpty(item)) pending+=item.getQuantity();
            for(var item:baseline) if(!ItemStack.isEmpty(item)) pending-=item.getQuantity();
        }
        long replaced=!ItemStack.isEmpty(existing) && !existing.isStackableWith(incoming)?existing.getQuantity():0;
        return incoming.getQuantity()>network.capacity()-network.used()-pending+replaced || super.cantAddToSlot(slot,incoming,existing);
    }
    @Override protected boolean cantRemoveFromSlot(short slot) {return !network.active() || super.cantRemoveFromSlot(slot);}
}
