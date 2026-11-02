package pedro.advancedstorage;

import com.hypixel.hytale.server.core.plugin.*;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;

public final class AdvancedStoragePlugin extends JavaPlugin {
    private com.hypixel.hytale.server.core.io.adapter.PacketFilter inventoryKey;
    public AdvancedStoragePlugin(JavaPluginInit init) { super(init); }
    @Override protected void setup() {
        getCommandRegistry().registerCommand(new IngredientsCommand());
        getCodecRegistry(Interaction.CODEC).register("AdvancedStorageOpen",NativeStorageInteraction.class,NativeStorageInteraction.CODEC);
        com.hypixel.hytale.server.core.io.ServerManager.get().registerSubPacketHandlers(StorageInputHooks::new);
        inventoryKey=com.hypixel.hytale.server.core.io.adapter.PacketAdapters.registerInbound((com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter)(player,packet)->{
            var page=CatalogSettingsPage.OPEN.get(player.getUuid());
            if(page==null) return false;
            String kind=packet.getClass().getSimpleName();
            if(packet instanceof com.hypixel.hytale.protocol.packets.interface_.CustomPageEvent event) kind+="/"+event.type;
            if(packet instanceof com.hypixel.hytale.protocol.packets.window.ClientOpenWindow open) kind+="/"+open.type;
            if(page.observedPackets.size()<40 && page.observedPackets.add(kind))
                CatalogSettingsPage.LOGGER.atInfo().log("[AdvancedStorage/Input] %s",kind);
            if(!(packet instanceof com.hypixel.hytale.protocol.packets.window.ClientOpenWindow open) || open.type!=com.hypixel.hytale.protocol.packets.window.WindowType.PocketCrafting) return false;
            page.world.execute(()->{
                var ref=player.getReference();
                if(ref==null || !ref.isValid()) return;
                var entity=ref.getStore().getComponent(ref,com.hypixel.hytale.server.core.entity.entities.Player.getComponentType());
                if(entity!=null && entity.getPageManager().getCustomPage()==page) page.close();
            });
            return true;
        });
    }
    @Override protected void shutdown() {
        if(inventoryKey!=null) com.hypixel.hytale.server.core.io.adapter.PacketAdapters.deregisterInbound(inventoryKey);
        CatalogSettingsPage.OPEN.clear();
    }
}
