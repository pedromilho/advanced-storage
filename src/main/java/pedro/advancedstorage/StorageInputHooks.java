package pedro.advancedstorage;

import com.hypixel.hytale.server.core.io.handlers.*;
import com.hypixel.hytale.server.core.io.handlers.game.GamePacketHandler;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.hypixel.hytale.protocol.packets.window.ClientOpenWindow;
import com.hypixel.hytale.protocol.packets.window.WindowType;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageEvent;

/** Uses the game connection directly, including single-player Quiche connections. */
final class StorageInputHooks implements SubPacketHandler {
    private final IPacketHandler handler;
    StorageInputHooks(IPacketHandler handler) {this.handler=handler;}
    public void registerHandlers() {
        if(!(handler instanceof GamePacketHandler game)) return;
        game.registerHandler(CustomPageEvent.PACKET_ID, packet->{
            var event=(CustomPageEvent)packet;
            var page=CatalogSettingsPage.OPEN.get(game.getPlayerRef().getUuid());
            if(page!=null && page.observedPackets.add("NativeCustomPage/"+event.type)) CatalogSettingsPage.LOGGER.atInfo().log("[AdvancedStorage/Input] NativeCustomPage/%s",event.type);
            game.handle(event);
        });
        IWorldPacketHandler.<ClientOpenWindow>registerHandler(game,ClientOpenWindow.PACKET_ID,(packet,playerRef,ref,world,store)->{
            var player=store.getComponent(ref,Player.getComponentType());
            if(player==null) return;
            if(player.getPageManager().getCustomPage() instanceof CatalogSettingsPage page) {
                CatalogSettingsPage.LOGGER.atInfo().log("[AdvancedStorage/Input] NativeOpenWindow/%s",packet.type);
                if(packet.type==WindowType.PocketCrafting) {page.close();return;}
            }
            var prevented=com.hypixel.hytale.server.core.modules.entity.EntityModule.get().getPreventInventoryAccessComponentType();
            if(prevented!=null && store.getArchetype(ref).contains(prevented)) return;
            var supplier=Window.CLIENT_REQUESTABLE_WINDOW_TYPES.get(packet.type);
            if(supplier==null) return;
            var update=player.getWindowManager().clientOpenWindow(ref,supplier.get(),store);
            if(update!=null) game.writeNoCache(update);
        });
    }
}
