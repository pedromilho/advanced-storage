package pedro.advancedstorage;

import com.google.gson.*;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.bson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Portable storage. Native ItemStack codecs retain durability, quality and all item metadata. */
public final class SealData {
    public static final String PREFIX="AdvancedStorage_Storage_Seal_Tier_";
    private static final String KEY="AdvancedStorageContents";
    private static final Map<String,Integer> CAPACITIES=new HashMap<>();
    public static final short ARCHIVE_SLOTS;
    static {
        try(var in=Objects.requireNonNull(SealData.class.getResourceAsStream("/storage_seals.json"))) {
            var config=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
            ARCHIVE_SLOTS=config.get("archive_slots").getAsShort();
            for(var t:config.getAsJsonArray("tiers")) {
                var tier=t.getAsJsonObject();
                CAPACITIES.put(PREFIX+tier.get("tier").getAsInt(),tier.get("capacity").getAsInt());
            }
        } catch(IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
    public static int capacity(ItemStack seal) { return ItemStack.isEmpty(seal)?0:CAPACITIES.getOrDefault(seal.getItemId(),0); }
    public static final class Entry {
        public final ItemStack template;
        public int count;
        Entry(ItemStack template,int count) { this.template=template.withQuantity(1); this.count=count; }
    }
    public final int capacity;
    public final List<Entry> entries=new ArrayList<>();
    private SealData(int capacity) { this.capacity=capacity; }
    public int used() { return entries.stream().mapToInt(e->e.count).sum(); }
    public static SealData read(ItemStack seal) {
        int cap=capacity(seal);
        if(cap==0 || seal.getQuantity()!=1) throw new IllegalArgumentException("Invalid storage seal");
        var data=new SealData(cap);
        var meta=seal.getMetadata();
        if(meta==null || !meta.containsKey(KEY)) return data;
        var root=meta.getDocument(KEY);
        if(root.getInt32("Version").getValue()!=1) throw new IllegalArgumentException("Unknown seal format");
        long total=0;
        for(var value:root.getArray("Items")) {
            var row=value.asDocument();
            var item=ItemStack.CODEC.decode(row.get("Stack"));
            int count=row.getInt32("Count").getValue();
            if(ItemStack.isEmpty(item) || capacity(item)>0 || count<=0 || (total+=count)>cap)
                throw new IllegalArgumentException("Invalid seal contents");
            data.entries.add(new Entry(item,count));
        }
        return data;
    }
    public int insert(ItemStack item,int requested) {
        if(ItemStack.isEmpty(item) || capacity(item)>0 || requested<=0) return 0;
        int amount=Math.min(requested,capacity-used());
        if(amount<=0) return 0;
        for(var entry:entries) if(entry.template.isStackableWith(item)) { entry.count+=amount; return amount; }
        entries.add(new Entry(item,amount));
        return amount;
    }
    public int remove(ItemStack item,int requested) {
        int removed=0;
        for(var it=entries.iterator();it.hasNext() && removed<requested;) {
            var entry=it.next();
            if(!entry.template.isStackableWith(item)) continue;
            int n=Math.min(entry.count,requested-removed);
            entry.count-=n; removed+=n;
            if(entry.count==0) it.remove();
        }
        return removed;
    }
    public ItemStack write(ItemStack seal) {
        // Native recipes match metadata exactly. A fully emptied seal must become craftable again.
        if(entries.isEmpty()) {
            var metadata=seal.getMetadata();
            if(metadata==null) return seal;
            metadata=metadata.clone();
            metadata.remove(KEY);
            return seal.withMetadata(metadata.isEmpty()?null:metadata);
        }
        var items=new BsonArray();
        for(var entry:entries) items.add(new BsonDocument("Stack",ItemStack.CODEC.encode(entry.template))
            .append("Count",new BsonInt32(entry.count)));
        return seal.withMetadata(KEY,new BsonDocument("Version",new BsonInt32(1)).append("Items",items));
    }
}
