package pedro.advancedstorage;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.BlockTypeConnectedBlockShape;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.connectedblocks.ConnectedBlocksUtil.ConnectedBlockResult;
import com.hypixel.hytale.server.core.universe.world.connectedblocks.config.PatternedConnectedBlockRuleSet;
import com.hypixel.hytale.server.core.universe.world.connectedblocks.config.rule.ConnectedBlockContext;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.joml.Vector3i;
import org.joml.Vector3ic;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Includes occupied cells of multiblock inventories in the existing conduit patterns. */
public final class ConduitConnections extends PatternedConnectedBlockRuleSet {
    static final BuilderCodec<ConduitConnections> CODEC =
        BuilderCodec.builder(ConduitConnections.class, ConduitConnections::new, PatternedConnectedBlockRuleSet.CODEC).build();

    @Override
    public Optional<ConnectedBlockResult> getConnectedBlockType(ChunkStore chunks, Vector3ic position, BlockType block,
            int rotation, Vector3ic affected, boolean placement) {
        var fillers = new HashMap<Vector3i, BlockType>();
        for (var direction : StorageNetwork.DIR) {
            var neighbor = new Vector3i(position).add(direction);
            var section = sectionAt(chunks, neighbor.x, neighbor.y, neighbor.z);
            if (section == null || section.getFiller(neighbor.x, neighbor.y, neighbor.z) == 0) continue;
            var base = BlockOperations.resolveBaseBlockPosition(chunks, new BlockPosition(neighbor.x, neighbor.y, neighbor.z));
            var baseSection = sectionAt(chunks, base.x, base.y, base.z);
            if (baseSection == null) continue;
            var endpoint = BlockType.getAssetMap().getAsset(baseSection.get(base.x, base.y, base.z));
            if (ArchiveItemGate.isTaggedEndpoint(endpoint)) fillers.put(direction, endpoint);
        }
        if (fillers.isEmpty()) return super.getConnectedBlockType(chunks, position, block, rotation, affected, placement);

        var rules = getRuleSetAsset();
        if (rules == null || rules.getPatterns() == null) return Optional.empty();
        var context = new EndpointContext(chunks, this, position, fillers);
        context.buildQuickAccessRegion();
        var original = RotationTuple.get(rotation);
        for (var pattern : rules.getPatterns()) {
            if (pattern.getOutput() == null) continue;
            var shape = rules.getShapes().get(pattern.getOutput().getShapeId());
            if (shape == null || !(shape.toPacket() instanceof BlockTypeConnectedBlockShape output)) continue;
            var result = BlockType.getAssetMap().getAsset(output.blockId);
            if (result == null) continue;
            var rule = pattern.getRule();
            if (rule == null) return result(result, original, shape.getRelativeRotation());
            for (var allowed : pattern.getAllowedTransformRotations()) {
                var transformed = pattern.isTransformRulesWithOrientation() ? RotationTuple.compose(original, allowed) : allowed;
                context.setRotation(transformed);
                if (rule.check(context)) return result(result, transformed, shape.getRelativeRotation());
            }
        }
        return Optional.empty();
    }

    private static Optional<ConnectedBlockResult> result(BlockType block, RotationTuple rotation, RotationTuple relative) {
        return Optional.of(new ConnectedBlockResult(block.getId(), RotationTuple.compose(rotation, relative).index()));
    }

    private static BlockSection sectionAt(ChunkStore chunks, int x, int y, int z) {
        if (y < 0 || y >= 320) return null;
        var ref = chunks.getChunkSectionReferenceAtBlock(x, y, z);
        return ref == null || !ref.isValid() ? null : chunks.getStore().getComponent(ref, BlockSection.getComponentType());
    }

    private static final class EndpointContext extends ConnectedBlockContext {
        private final Map<Vector3i, BlockType> fillers;
        private final Vector3i offset = new Vector3i();
        private RotationTuple rotation = RotationTuple.NONE;

        EndpointContext(ChunkStore chunks, PatternedConnectedBlockRuleSet rules, Vector3ic position,
                        Map<Vector3i, BlockType> fillers) {
            super(chunks, rules, position, RotationTuple.NONE);
            this.fillers = fillers;
        }

        @Override public void setRotation(RotationTuple rotation) {
            super.setRotation(rotation);
            this.rotation = rotation;
        }

        @Override public BlockType getLocalBlockType(Vector3ic position) {
            rotation.applyRotationTo(offset.set(position));
            var endpoint = fillers.get(offset);
            return endpoint == null ? super.getLocalBlockType(position) : endpoint;
        }

        @Override public RotationTuple getLocalRotation(Vector3ic position) {
            rotation.applyRotationTo(offset.set(position));
            return fillers.containsKey(offset) ? RotationTuple.NONE : super.getLocalRotation(position);
        }
    }
}
