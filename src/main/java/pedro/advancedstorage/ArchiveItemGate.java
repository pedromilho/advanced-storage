package pedro.advancedstorage;

import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.joml.Vector3i;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Settings live on the block entity. Filter entries are item IDs, never stacks. */
public final class ArchiveItemGate implements Component<ChunkStore> {
    static final String ITEM_ID="AdvancedStorage_Archive_Item_Gate";
    private static final String TRANSFER_ENDPOINT_TAG = "AdvancedStorageTransferEndpoint";
    private static final Map<String, String[]> TRANSFER_ENDPOINT_TAGS = Map.of(TRANSFER_ENDPOINT_TAG, new String[0]);
    private static ComponentType<ChunkStore,ArchiveItemGate> type;
    static final BuilderCodec<ArchiveItemGate> CODEC=BuilderCodec.builder(ArchiveItemGate.class,ArchiveItemGate::new)
        .append(new KeyedCodec<>("Receive",Codec.BOOLEAN),(gate,value)->gate.settings.receive=value,gate->gate.settings.receive).add()
        .append(new KeyedCodec<>("Send",Codec.BOOLEAN),(gate,value)->gate.settings.send=value,gate->gate.settings.send).add()
        .append(new KeyedCodec<>("SendAll",Codec.BOOLEAN),(gate,value)->gate.settings.sendAll=value,gate->gate.settings.sendAll).add()
        .append(new KeyedCodec<>("ReceiveFilters",Codec.STRING_ARRAY),(gate,value)->gate.settings.setFilters(false,value),gate->gate.settings.filters(false)).add()
        .append(new KeyedCodec<>("SendFilters",Codec.STRING_ARRAY),(gate,value)->gate.settings.setFilters(true,value),gate->gate.settings.filters(true)).add()
        .build();

    ItemTransferSettings settings=new ItemTransferSettings();
    private float transferTimer;

    static void register(ComponentRegistryProxy<ChunkStore> registry) {
        type=registry.registerComponent(ArchiveItemGate.class,"AdvancedStorageItemGate",CODEC);
        registry.registerSystem(new TransferSystem());
    }

    static void tagTransferEndpoint(BlockType block) {
        var data = block.getData();
        if (data == null || data.getRawTags().containsKey("AdvancedStorageRole")
            || data.getRawTags().containsKey(TRANSFER_ENDPOINT_TAG)) return;
        var components = block.getBlockEntity();
        if (components != null && (components.getComponent(ItemContainerBlock.getComponentType()) != null
            || (isFurnace(block) && components.getComponent(ProcessingBenchBlock.getComponentType()) != null))) {
            data.putTags(TRANSFER_ENDPOINT_TAGS);
        }
    }

    static boolean isTaggedEndpoint(BlockType block) {
        return block != null && block.getData() != null && block.getData().getRawTags().containsKey(TRANSFER_ENDPOINT_TAG);
    }

    private static boolean isFurnace(BlockType block) {
        return block != null && block.getBench() != null && "Furnace".equals(block.getBench().getId());
    }

    static ArchiveItemGate at(World world,StorageNetwork.Pos pos) {
        return component(world,pos,type);
    }

    static <T extends Component<ChunkStore>> T component(World world,StorageNetwork.Pos pos,ComponentType<ChunkStore,T> componentType) {
        var chunks=world.getChunkStore();
        var store=chunks.getStore();
        var ref=BlockModule.getBlockEntity(world,pos.x(),pos.y(),pos.z());
        if(ref!=null && ref.isValid()) return store.getComponent(ref,componentType);
        var sectionRef=chunks.getChunkSectionReferenceAtBlock(pos.x(),pos.y(),pos.z());
        if(sectionRef==null || !sectionRef.isValid()) return null;
        var section=store.getComponent(sectionRef,BlockComponentSection.getComponentType());
        var holder=section==null?null:section.getBlockHolder(ChunkUtil.indexBlock(pos.x(),pos.y(),pos.z()));
        return holder==null?null:holder.getComponent(componentType);
    }

    @Override public ArchiveItemGate clone() {
        var clone=new ArchiveItemGate();
        clone.settings=settings.copy();
        return clone;
    }

    record Endpoint(StorageNetwork.Pos pos,ItemContainer inventory) {}

    void transfer(World world,StorageNetwork.Pos origin) {
        if(settings.receive) receive(world, origin);
        if(settings.canSend()) send(world, origin, endpoints(world, List.of(origin), false));
    }

    /** Imports can reach through conduits and take finished Furnace output. Exports target adjacent storage only. */
    static List<Endpoint> endpoints(World world, List<StorageNetwork.Pos> origins, boolean importing) {
        var endpoints = new ArrayList<Endpoint>();
        var pending = new ArrayDeque<>(origins);
        var visited = new HashSet<>(origins);
        var checked = new HashSet<StorageNetwork.Pos>();
        var checkedBases = new HashSet<StorageNetwork.Pos>();
        while (!pending.isEmpty()) {
            var pos = pending.remove();
            var node = StorageNetwork.node(world, pos);
            if (node == null) continue;
            for (int face = 0; face < StorageNetwork.DIR.length; face++) {
                var neighbor = pos.add(StorageNetwork.DIR[face]);
                var next = StorageNetwork.node(world, neighbor);
                if (next == null) {
                    if (checked.add(neighbor)) {
                        var endpoint = endpointAt(world, neighbor, checkedBases, importing);
                        if (endpoint != null) endpoints.add(endpoint);
                    }
                } else if (importing && "Conduit".equals(next.role())
                    && (node.ports() & (1 << face)) != 0
                    && (next.ports() & (1 << StorageNetwork.OPP[face])) != 0
                    && visited.size() < StorageNetwork.MAX_NODES && visited.add(neighbor)) {
                    pending.add(neighbor);
                }
            }
        }
        return endpoints;
    }

    private static Endpoint endpointAt(World world, StorageNetwork.Pos neighbor,
                                       Set<StorageNetwork.Pos> checkedBases, boolean importing) {
        if (neighbor.y() < 0 || neighbor.y() >= 320) return null;
        var chunks = world.getChunkStore();
        var section = chunks.getChunkSectionReferenceAtBlock(neighbor.x(), neighbor.y(), neighbor.z());
        if (section == null || !section.isValid()) return null;
        var blocks = chunks.getStore().getComponent(section, BlockSection.getComponentType());
        if (blocks == null || blocks.get(neighbor.x(), neighbor.y(), neighbor.z()) == BlockType.EMPTY_ID) return null;
        var base = BlockOperations.resolveBaseBlockPosition(chunks, new BlockPosition(neighbor.x(), neighbor.y(), neighbor.z()));
        var pos = new StorageNetwork.Pos(base.x, base.y, base.z);
        if (!checkedBases.add(pos) || (!pos.equals(neighbor) && StorageNetwork.node(world, pos) != null)) return null;

        var storage = component(world, pos, ItemContainerBlock.getComponentType());
        if (storage != null && storage.getItemContainer() != null) return new Endpoint(pos, storage.getItemContainer());
        if (!importing) return null;

        var furnace = component(world, pos, ProcessingBenchBlock.getComponentType());
        if (furnace == null || furnace.getOutputContainer() == null) return null;
        if (!pos.equals(neighbor)) {
            var baseSection = chunks.getChunkSectionReferenceAtBlock(pos.x(), pos.y(), pos.z());
            if (baseSection == null || !baseSection.isValid()) return null;
            blocks = chunks.getStore().getComponent(baseSection, BlockSection.getComponentType());
        }
        var block = blocks == null ? null : BlockType.getAssetMap().getAsset(blocks.get(pos.x(), pos.y(), pos.z()));
        if (!isFurnace(block)) return null;
        return new Endpoint(pos, furnace.getOutputContainer());
    }

    private void receive(World world,StorageNetwork.Pos origin) {
        var network=StorageNetwork.scan(world,origin);
        if(!network.active()) return;
        var endpoints = endpoints(world, List.of(origin), true);
        for(var endpoint:endpoints) for(short slot=0;slot<endpoint.inventory.getCapacity();slot++) {
            var item=endpoint.inventory.getItemStack(slot);
            if(ItemStack.isEmpty(item) || SealData.capacity(item)>0) continue;
            if(!settings.receives(item.getItemId())) continue;
            if(network.deposit(endpoint.inventory,slot)>0) StorageNetwork.dirty(world,endpoint.pos);
            return;
        }
    }

    private void send(World world,StorageNetwork.Pos origin,List<Endpoint> endpoints) {
        var network=StorageNetwork.scan(world,origin);
        if(!network.active()) return;
        for(var entry:network.contents()) {
            if(!settings.sends(entry.template.getItemId())) continue;
            int amount=(int)Math.min(entry.count,entry.template.getItem().getMaxStack());
            for(var endpoint:endpoints) {
                if(network.withdraw(endpoint.inventory,entry.template,amount)>0
                    || (amount>1 && network.withdraw(endpoint.inventory,entry.template,1)>0)) {
                    StorageNetwork.dirty(world,endpoint.pos);
                    return;
                }
            }
        }
    }

    private static final class TransferSystem extends EntityTickingSystem<ChunkStore> {
        @Override public Query<ChunkStore> getQuery() {
            return Query.and(type,BlockModule.BlockStateInfo.getComponentType());
        }

        @Override public void tick(float dt,int index,ArchetypeChunk<ChunkStore> chunk,Store<ChunkStore> store,CommandBuffer<ChunkStore> commands) {
            var gate=chunk.getComponent(index,type);
            if(!gate.settings.receive && !gate.settings.send) return;
            gate.transferTimer+=dt;
            if(gate.transferTimer<1) return;
            gate.transferTimer=0;
            var point=new Vector3i();
            if(!chunk.getComponent(index,BlockModule.BlockStateInfo.getComponentType()).fillWorldPos(commands,point)) return;
            var world=store.getExternalData().getWorld();
            var pos=new StorageNetwork.Pos(point.x,point.y,point.z);
            var node=StorageNetwork.node(world,pos);
            if(node==null || !node.role().equals("ItemGate")) return;
            try {
                gate.transfer(world,pos);
            } catch(RuntimeException error) {
                world.getLogger().atWarning().withCause(error).log("Archive Item Gate transfer failed at %s",pos);
            }
        }
    }
}
