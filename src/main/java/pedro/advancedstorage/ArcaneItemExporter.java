package pedro.advancedstorage;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class ArcaneItemExporter implements Component<ChunkStore> {
    static final String ITEM_ID = "AdvancedStorage_Arcane_Item_Exporter";
    static final String ROLE = "Exporter";
    static final int MAX_CHANNELS = 64;
    private static ComponentType<ChunkStore, ArcaneItemExporter> type;
    private static final BuilderCodec<ArcaneItemExporter> CODEC =
        BuilderCodec.builder(ArcaneItemExporter.class, ArcaneItemExporter::new)
            .append(new KeyedCodec<>("Name", Codec.STRING),
                (block, value) -> block.rename(value), block -> block.name).add()
            .append(new KeyedCodec<>("Channels", ArrayCodec.ofBuilderCodec(ItemTransferSettings.CODEC, ItemTransferSettings[]::new)),
                (block, value) -> block.channels = normalize(value), block -> block.channels).add()
            .build();

    // Only positions in ticking chunks can send or receive. No chunk tickets are needed.
    private static final Map<World, Set<StorageNetwork.Pos>> KNOWN = Collections.synchronizedMap(new WeakHashMap<>());
    private String name = "";
    private ItemTransferSettings[] channels = {new ItemTransferSettings()};
    private float elapsed;

    static boolean isExporterBlock(String id) {
        return id.equals(ITEM_ID) || id.equals(ITEM_ID + "_Wall") || id.equals(ITEM_ID + "_Ceiling");
    }

    private static ItemTransferSettings[] normalize(ItemTransferSettings[] saved) {
        if (saved == null || saved.length == 0) return new ItemTransferSettings[]{new ItemTransferSettings()};
        var result = Arrays.copyOf(saved, Math.min(MAX_CHANNELS, saved.length));
        for (int i = 0; i < result.length; i++) if (result[i] == null) result[i] = new ItemTransferSettings();
        return result;
    }

    static void register(ComponentRegistryProxy<ChunkStore> registry) {
        type = registry.registerComponent(ArcaneItemExporter.class, "AdvancedStorageItemExporter", CODEC);
        registry.registerSystem(new TransferSystem());
    }

    static ArcaneItemExporter at(World world, StorageNetwork.Pos pos) {
        var node = StorageNetwork.node(world, pos);
        return node == null || !ROLE.equals(node.role()) ? null : ArchiveItemGate.component(world, pos, type);
    }

    int channelCount() { return channels.length; }

    String name() { return name; }

    boolean rename(String value) {
        var clean = new StringBuilder();
        if (value != null) value.codePoints()
            .filter(cp -> !Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT
                && Character.getType(cp) != Character.LINE_SEPARATOR && Character.getType(cp) != Character.PARAGRAPH_SEPARATOR)
            .limit(32).forEach(clean::appendCodePoint);
        String newName = clean.toString().strip();
        if (newName.equals(name)) return false;
        name = newName;
        return true;
    }

    record Peer(String name, StorageNetwork.Pos position) {}

    Peer peer(StorageNetwork.Pos pos) {
        return new Peer(name.isEmpty() ? "Exporter" : name, pos);
    }

    static List<Peer> peers(World world, StorageNetwork.Pos current, int channel) {
        var candidates = KNOWN.get(world);
        if (candidates == null) return List.of();
        var peers = new ArrayList<Peer>();
        var positions = candidates.iterator();
        while (positions.hasNext()) {
            var pos = positions.next();
            if (current.equals(pos)) continue;
            var target = at(world, pos);
            if (target == null) positions.remove();
            else if (target.channel(channel) != null) peers.add(target.peer(pos));
        }
        peers.sort(Comparator.comparing(Peer::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Peer::name));
        return peers;
    }

    ItemTransferSettings channel(int number) {
        return number >= 1 && number <= channels.length ? channels[number-1] : null;
    }

    int addChannel() {
        if (channels.length >= MAX_CHANNELS) return 0;
        channels = Arrays.copyOf(channels, channels.length + 1);
        channels[channels.length - 1] = new ItemTransferSettings();
        return channels.length;
    }

    @Override public ArcaneItemExporter clone() {
        var copy = new ArcaneItemExporter();
        copy.name = name;
        copy.channels = Arrays.stream(channels).map(ItemTransferSettings::copy).toArray(ItemTransferSettings[]::new);
        return copy;
    }

    private record Receiver(StorageNetwork.Pos position, ArcaneItemExporter exporter) {}

    void transfer(World world, StorageNetwork.Pos source, Set<StorageNetwork.Pos> candidates) {
        if (Arrays.stream(channels).noneMatch(ItemTransferSettings::canSend)) return;
        var receivers = new ArrayList<Receiver>();
        var positions = candidates.iterator();
        while (positions.hasNext()) {
            var pos = positions.next();
            if (source.equals(pos)) continue;
            var target = at(world, pos);
            if (target == null) positions.remove();
            else if (Arrays.stream(target.channels).anyMatch(settings -> settings.receive)) receivers.add(new Receiver(pos, target));
        }
        if (receivers.isEmpty()) return;
        for (int number = 1; number <= channels.length; number++) {
            var send = channel(number);
            if (send.canSend()) transferChannel(world, source, receivers, number, send);
        }
    }

    private void transferChannel(World world, StorageNetwork.Pos source, List<Receiver> receivers, int number, ItemTransferSettings send) {
        var network = StorageNetwork.scan(world, source);
        boolean sourceOnNetwork = network.active();
        var sources = sourceOnNetwork ? List.<ArchiveItemGate.Endpoint>of() : remoteEndpoints(world, source, network, true);
        if (!sourceOnNetwork && sources.isEmpty()) return;
        var contents = sourceOnNetwork ? network.contents() : List.<StorageNetwork.StoredItem>of();
        for (var receiver : receivers) {
            var receive = receiver.exporter().channel(number);
            if (receive == null || !receive.receive) continue;
            var targetNetwork = StorageNetwork.scan(world, receiver.position());
            if (sourceOnNetwork) {
                var targetChests = remoteEndpoints(world, receiver.position(), targetNetwork, false);
                if (!targetChests.isEmpty() && sendToChests(world, source, targetChests, contents, send, receive)) return;
            } else if (targetNetwork.active() && targetNetwork.used() < targetNetwork.capacity()
                && sendToNetwork(world, sources, targetNetwork, send, receive)) {
                return;
            }
        }
    }

    private static List<ArchiveItemGate.Endpoint> remoteEndpoints(World world, StorageNetwork.Pos exporter,
                                                                  StorageNetwork network, boolean importing) {
        if (network.cores > 0 || network.invalid || network.truncated) return List.of();
        var origins = new ArrayList<StorageNetwork.Pos>();
        origins.add(exporter);
        // On the remote side a Gate acts as an adapter. The Exporter channel controls the transfer.
        origins.addAll(network.itemGates);
        return ArchiveItemGate.endpoints(world, origins, importing);
    }

    private static boolean sendToNetwork(World world, List<ArchiveItemGate.Endpoint> chests, StorageNetwork target,
                                         ItemTransferSettings send, ItemTransferSettings receive) {
        for (var chest : chests) {
            var inventory = chest.inventory();
            for (short slot = 0; slot < inventory.getCapacity(); slot++) {
                var item = inventory.getItemStack(slot);
                if (ItemStack.isEmpty(item) || SealData.capacity(item) > 0) continue;
                String id = item.getItemId();
                if (!send.sends(id) || !receive.receives(id)) continue;
                if (target.deposit(inventory, slot) == 0) return false;
                StorageNetwork.dirty(world, chest.pos());
                return true;
            }
        }
        return false;
    }

    private static boolean sendToChests(World world, StorageNetwork.Pos source, List<ArchiveItemGate.Endpoint> chests,
                                        List<StorageNetwork.StoredItem> contents, ItemTransferSettings send, ItemTransferSettings receive) {
        for (var entry : contents) {
            String id = entry.template.getItemId();
            if (!send.sends(id) || !receive.receives(id)) continue;
            int amount = (int)Math.min(entry.count, entry.template.getItem().getMaxStack());
            for (var chest : chests) {
                var inventory = chest.inventory();
                int accepted = inventory.canAddItemStack(entry.template.withQuantity(amount), true, true) ? amount : 1;
                if (!inventory.canAddItemStack(entry.template.withQuantity(accepted), true, true)) continue;
                if (StorageNetwork.scan(world, source).withdraw(inventory, entry.template, accepted) == 0) return false;
                StorageNetwork.dirty(world, chest.pos());
                return true;
            }
        }
        return false;
    }

    private static final class TransferSystem extends EntityTickingSystem<ChunkStore> {
        @Override public Query<ChunkStore> getQuery() {
            return Query.and(type, BlockModule.BlockStateInfo.getComponentType());
        }

        @Override public void tick(float dt, int index, ArchetypeChunk<ChunkStore> chunk, Store<ChunkStore> store, CommandBuffer<ChunkStore> commands) {
            var exporter = chunk.getComponent(index, type);
            exporter.elapsed += dt;
            if (exporter.elapsed < 1) return;
            exporter.elapsed = 0;
            var point = new Vector3i();
            if (!chunk.getComponent(index, BlockModule.BlockStateInfo.getComponentType()).fillWorldPos(commands, point)) return;
            var world = store.getExternalData().getWorld();
            var source = new StorageNetwork.Pos(point.x, point.y, point.z);
            var positions = KNOWN.computeIfAbsent(world, key -> new LinkedHashSet<>());
            positions.add(source);
            try {
                exporter.transfer(world, source, positions);
            } catch (RuntimeException error) {
                world.getLogger().atWarning().withCause(error).log("Arcane Item Exporter transfer failed at %s", source);
            }
        }
    }
}
