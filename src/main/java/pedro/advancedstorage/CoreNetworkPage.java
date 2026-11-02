package pedro.advancedstorage;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.BasicCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import java.util.concurrent.*;

/** A live functional schematic. Counts describe reachable blocks, never inventory slots. */
public final class CoreNetworkPage extends BasicCustomUIPage {
    final World world;
    final StorageNetwork.Pos origin;
    final Map<String,String> labels=new HashMap<>();
    boolean active;
    public CoreNetworkPage(PlayerRef player,World world,StorageNetwork.Pos origin) {
        super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction);
        this.world=world;this.origin=origin;
    }
    @Override public void build(UICommandBuilder commands) {
        commands.append("AdvancedStorage/Core.ui");render(commands);
        if(playerRef!=null && !active) {active=true;poll();}
    }
    void label(UICommandBuilder commands,String id,String text) {
        if(!Objects.equals(labels.put(id,text),text)) commands.set(id+".Text",text);
    }
    void render(UICommandBuilder commands) {
        var net=StorageNetwork.scan(world,origin);
        label(commands,"#State",net.invalid?"Unreadable storage seal":net.truncated?"Network too large":net.active()?"Network online":"Network offline");
        label(commands,"#CoreCount","Arcane Core x "+net.cores);
        label(commands,"#Conduits","Runic Conduit x "+net.conduits);
        label(commands,"#Archives","Seal Archive x "+net.archives.size());
        label(commands,"#Catalogs","Runic Catalog x "+net.connectedBlocks.getOrDefault("AdvancedStorage_Runic_Catalog",0));
        label(commands,"#Storage",String.format(Locale.US,"Storage: %,d / %,d items   |   %d seals",net.used(),net.capacity(),net.seals.size()));
    }
    void poll() {
        CompletableFuture.delayedExecutor(250,TimeUnit.MILLISECONDS,world).execute(()->{
            if(!active) return;
            var ref=playerRef.getReference();if(ref==null || !ref.isValid()) return;
            var player=ref.getStore().getComponent(ref,Player.getComponentType());
            if(player==null || player.getPageManager().getCustomPage()!=this) return;
            if(!NativeStorageWindows.canAccess(ref,world,origin,"Core")) {close();return;}
            var commands=new UICommandBuilder();render(commands);
            if(commands.getCommands().length>0) sendUpdate(commands,false);
            poll();
        });
    }
    @Override public void onDismiss(Ref<EntityStore> ref,Store<EntityStore> store) {active=false;}
}
