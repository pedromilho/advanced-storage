package pedro.advancedstorage;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Load only the connected topology, retaining its sections while the tablet is open. */
final class RemoteNetworkLease implements AutoCloseable {
    record Step(StorageNetwork.Pos pos,int incoming) {}
    final World world;
    final Map<String,Ref<ChunkStore>> pins=new HashMap<>();
    final Set<StorageNetwork.Pos> visited=new HashSet<>();
    final ArrayDeque<Step> pending=new ArrayDeque<>();
    final CompletableFuture<RemoteNetworkLease> ready=new CompletableFuture<>();
    final TravelerLinks.Session session;final Ref<EntityStore> player;
    boolean closed;
    private RemoteNetworkLease(TravelerLinks.Session session,Ref<EntityStore> player) {this.world=session.world();this.session=session;this.player=player;}
    static CompletableFuture<RemoteNetworkLease> prepare(TravelerLinks.Session session,Ref<EntityStore> player) {
        var lease=new RemoteNetworkLease(session,player);
        if(session.origin()!=null)lease.pending.add(new Step(session.origin(),0));
        lease.next();return lease.ready;
    }
    void next() {
        if(closed||!session.valid(player)){close();ready.complete(this);return;}
        if(pending.isEmpty()||visited.size()>=4096){ready.complete(this);return;}
        var step=pending.removeFirst();var p=step.pos();
        if(p.y()<0||p.y()>=320||visited.contains(p)){world.execute(this::next);return;}
        String key=(p.x()>>5)+":"+(p.y()>>5)+":"+(p.z()>>5);
        var existing=pins.get(key);
        if(existing!=null){visit(step);world.execute(this::next);return;}
        world.getChunkStore().getChunkSectionReferenceAtBlockAsync(p.x(),p.y(),p.z()).whenComplete((ref,error)->world.execute(()->{
            if(closed)return;
            if(error!=null){close();ready.completeExceptionally(error);return;}
            if(ref!=null&&ref.isValid()) {
                var section=world.getChunkStore().getStore().getComponent(ref,ChunkSection.getComponentType());
                if(section!=null){section.addKeepLoaded();pins.put(key,ref);visit(step);}
            }
            next();
        }));
    }
    void visit(Step step) {
        var p=step.pos();var node=StorageNetwork.node(world,p);
        if(node==null||(step.incoming()!=0&&(node.ports()&step.incoming())==0)||!visited.add(p))return;
        for(int i=0;i<6;i++)if((node.ports()&(1<<i))!=0)pending.add(new Step(p.add(StorageNetwork.DIR[i]),1<<StorageNetwork.OPP[i]));
    }
    @Override public void close() {
        if(closed)return;closed=true;
        for(var ref:pins.values())if(ref.isValid()) {
            var section=world.getChunkStore().getStore().getComponent(ref,ChunkSection.getComponentType());
            if(section!=null)section.removeKeepLoaded();
        }
        pins.clear();
    }
}
