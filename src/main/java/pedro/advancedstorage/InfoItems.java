package pedro.advancedstorage;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;

final class InfoItems {
    private InfoItems() {}
    static ItemStack make(String id,long count,String title,String detail) {
        return new ItemStack(id,(int)Math.clamp(count,1,Integer.MAX_VALUE))
            .withMetadata(ItemDisplayMetadata.KEYED_CODEC,new ItemDisplayMetadata(Message.raw(title),Message.raw(detail)));
    }
}
