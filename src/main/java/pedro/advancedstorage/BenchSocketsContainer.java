package pedro.advancedstorage;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.*;
import java.util.function.*;

/** Installed workbenches use native inventory gestures and block-container persistence. */
public final class BenchSocketsContainer extends SafeItemContainer {
    final World world;
    final StorageNetwork.Pos origin;
    ItemStack[] baseline;
    com.hypixel.hytale.server.core.inventory.container.ItemContainer backing;
    int writing;
    boolean refreshing;
    public BenchSocketsContainer(World world,StorageNetwork.Pos origin) {
        super(NetworkBenches.SLOTS);this.world=world;this.origin=origin;refresh();
    }
    public boolean refresh() {
        if(writing>0 || refreshing) return false;
        refreshing=true;
        try {
            var previous=items.clone();
            backing=NetworkBenches.inventory(world,origin,true);
            Arrays.fill(items,null);
            for(short slot=0;slot<NetworkBenches.SLOTS;slot++) items[slot]=backing==null?null:backing.getItemStack(slot);
            itemsCount=(int)Arrays.stream(items).filter(s->!ItemStack.isEmpty(s)).count();
            return !Arrays.equals(previous,items);
        } finally {refreshing=false;}
    }
    void begin() {
        super.lockForWrite();if(writing==0){refresh();baseline=Arrays.copyOf(items,NetworkBenches.SLOTS);}writing++;
    }
    void end() {
        try {
            if(--writing==0 && backing!=null) {
                for(short slot=0;slot<NetworkBenches.SLOTS;slot++) if(!Objects.equals(baseline[slot],backing.getItemStack(slot)))
                    throw new IllegalStateException("Installed benches changed during transfer");
                for(short slot=0;slot<NetworkBenches.SLOTS;slot++) if(!Objects.equals(items[slot],baseline[slot])) {
                    if(!backing.setItemStackForSlot(slot,items[slot]).succeeded()) throw new IllegalStateException("Bench slot update rejected");
                    StorageNetwork.dirty(world,origin);
                }
            }
        } finally {super.unlockForWrite();}
    }
    @Override protected <V> V writeAction(Supplier<V> action) {begin();try{return action.get();}finally{end();}}
    @Override protected <X,V> V writeAction(Function<X,V> action,X value) {return writeAction(()->action.apply(value));}
    @Override protected void lockForWrite() {begin();}
    @Override protected void unlockForWrite() {end();}
    @Override protected <V> V readAction(Supplier<V> action) {refresh();return super.readAction(action);}
    @Override protected <X,V> V readAction(Function<X,V> action,X value) {refresh();return super.readAction(action,value);}
    @Override public ItemStack getItemStack(short slot) {refresh();return super.getItemStack(slot);}
    @Override public SimpleItemContainer clone() {refresh();return new SimpleItemContainer(this);}
    @Override protected boolean cantAddToSlot(short slot,ItemStack item,ItemStack existing) {
        return slot>=NetworkBenches.SLOTS || backing==null || (!ItemStack.isEmpty(item) && (NetworkBenches.type(item)==null || item.getQuantity()!=1)) || super.cantAddToSlot(slot,item,existing);
    }
    @Override protected boolean cantRemoveFromSlot(short slot) {return slot>=NetworkBenches.SLOTS || backing==null || super.cantRemoveFromSlot(slot);}
    @Override protected boolean cantDropFromSlot(short slot) {return slot>=NetworkBenches.SLOTS || backing==null || super.cantDropFromSlot(slot);}
    public void sortBenches() {
        if(backing!=null) {backing.sortItems(com.hypixel.hytale.server.core.inventory.container.SortType.TYPE);StorageNetwork.dirty(world,origin);refresh();}
    }
}
