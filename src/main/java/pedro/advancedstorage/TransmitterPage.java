package pedro.advancedstorage;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import java.util.concurrent.*;

/** Native-style transmitter management; every action revalidates physical access. */
public final class TransmitterPage extends InteractiveCustomUIPage<CatalogInput> {
    static final Map<UUID,TransmitterPage> OPEN=new ConcurrentHashMap<>();
    final World world;final StorageNetwork.Pos pos;final String session=UUID.randomUUID().toString();
    boolean active;int offset;String mounted="",notice="";
    public TransmitterPage(PlayerRef player,World world,StorageNetwork.Pos pos) {
        super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,CatalogSettingsPage.CODEC);this.world=world;this.pos=pos;
    }
    void bind(UIEventBuilder events,String selector,String action) {
        events.addEventBinding(CustomUIEventBindingType.Activating,selector,EventData.of("Action",action).append("Session",session),false);
    }
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder commands,UIEventBuilder events,Store<EntityStore> store) {
        commands.append("AdvancedStorage/Transmitter.ui");bind(events,"#Previous","previous");bind(events,"#Next","next");render(commands,events);
        active=true;OPEN.put(playerRef.getUuid(),this);poll();
    }
    void render(UICommandBuilder commands,UIEventBuilder events) {
        var links=TravelerLinks.get(world);links.discover(world);
        var tablets=new ArrayList<>(links.tablets.values());
        tablets.sort(Comparator.comparing((TravelerLinks.Tablet t)->t.holder).thenComparing(t->t.id));
        offset=Math.max(0,Math.min(offset,Math.max(0,((tablets.size()-1)/6)*6)));
        var net=StorageNetwork.scan(world,pos);
        commands.set("#State.Text",(net.active()?"Network online":"Network offline - connect an Arcane Core")+"  |  "+tablets.size()+" tablets");
        commands.set("#Notice.Text",notice);
        commands.set("#Previous.Disabled",offset==0);commands.set("#Next.Disabled",offset+6>=tablets.size());
        String fingerprint=offset+"/"+tablets.stream().map(t->t.id+"/"+t.holder+"/"+t.transmitter).toList();
        if(fingerprint.equals(mounted))return;mounted=fingerprint;
        commands.clear("#Tablets");
        for(int i=offset;i<Math.min(offset+6,tablets.size());i++) {
            var t=tablets.get(i);String row="#Tablets["+(i-offset)+"]";
            commands.append("#Tablets","AdvancedStorage/TransmitterTablet.ui");
            commands.set(row+" #Name.Text","Traveler's Catalog - "+t.id.toString().substring(0,8));
            boolean here=pos.equals(t.transmitter);
            commands.set(row+" #Status.Text",t.holder+"  |  "+(here?"Connected to this network":t.transmitter==null?"Disconnected":"Connected to another Transmitter"));
            commands.set(row+" #Connect.Disabled",here);commands.set(row+" #Disconnect.Disabled",!here);
            bind(events,row+" #Connect","connect/"+t.id);bind(events,row+" #Disconnect","disconnect/"+t.id);
        }
        commands.set("#Empty.Visible",tablets.isEmpty());
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,String raw) {
        if(!active)return;
        if(!NativeStorageWindows.canAccess(ref,world,pos,"Transmitter")){close();return;}
        var input=CatalogInput.parse(raw);if(!session.equals(input.session))return;
        notice="";
        if(input.action.equals("next"))offset+=6;
        else if(input.action.equals("previous"))offset-=6;
        else {
            var parts=input.action.split("/",2);if(parts.length!=2)return;
            UUID id;try{id=UUID.fromString(parts[1]);}catch(IllegalArgumentException ex){return;}
            var links=TravelerLinks.get(world);var tablet=links.tablets.get(id);if(tablet==null)return;
            try {
                if(parts[0].equals("connect"))links.link(id,pos);
                else if(parts[0].equals("disconnect")&&pos.equals(tablet.transmitter))links.link(id,null);
                else return;
            }catch(IllegalStateException ex){notice="Could not save the connection. Please try again.";world.getLogger().atSevere().withCause(ex).log("Tablet connection save failed");}
        }
        var commands=new UICommandBuilder();var events=new UIEventBuilder();render(commands,events);sendUpdate(commands,events,false);
    }
    void poll() {
        CompletableFuture.delayedExecutor(1,TimeUnit.SECONDS,world).execute(()->{
            if(!active)return;var ref=playerRef.getReference();if(ref==null||!ref.isValid())return;
            var player=ref.getStore().getComponent(ref,Player.getComponentType());
            if(player==null||player.getPageManager().getCustomPage()!=this)return;
            if(!NativeStorageWindows.canAccess(ref,world,pos,"Transmitter")){close();return;}
            var commands=new UICommandBuilder();var events=new UIEventBuilder();render(commands,events);sendUpdate(commands,events,false);poll();
        });
    }
    public void dismiss() {close();}
    @Override public void onDismiss(Ref<EntityStore> ref,Store<EntityStore> store){active=false;OPEN.remove(playerRef.getUuid(),this);}
}
