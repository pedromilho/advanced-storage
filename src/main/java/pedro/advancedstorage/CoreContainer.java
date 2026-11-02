package pedro.advancedstorage;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterType;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.*;

/** Standard chest display of connected blocks; thumbnails are informational, never real items. */
public final class CoreContainer extends SafeItemContainer {
    final World world;
    final StorageNetwork.Pos origin;
    public CoreContainer(World world,StorageNetwork.Pos origin) {
        super((short)4);this.world=world;this.origin=origin;setGlobalFilter(FilterType.DENY_ALL);refresh();
    }
    public boolean refresh() {
        var previous=items.clone();
        var network=StorageNetwork.scan(world,origin);
        Arrays.fill(items,null);itemsCount=0;
        String[] ids={"AdvancedStorage_Arcane_Core","AdvancedStorage_Seal_Archive","AdvancedStorage_Runic_Catalog","AdvancedStorage_Runic_Conduit"};
        for(String id:ids) {
            int count=network.connectedBlocks.getOrDefault(id,0);
            if(count>0) {
                var template=new ItemStack(id,1);
                items[itemsCount++]=InfoItems.make(id,count,CatalogView.name(template)+": "+count,"Connected blocks in this network. Read-only; these thumbnails are not stored items.");
            }
        }
        return !Arrays.equals(previous,items);
    }
}
