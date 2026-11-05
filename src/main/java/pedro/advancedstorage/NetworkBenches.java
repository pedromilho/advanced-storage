package pedro.advancedstorage;

import com.hypixel.hytale.builtin.crafting.component.*;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.*;
import com.hypixel.hytale.server.core.asset.type.item.config.*;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.inventory.container.*;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.*;
import com.hypixel.hytale.server.core.universe.world.chunk.section.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.component.*;
import org.bson.*;
import java.util.*;
import java.util.concurrent.*;

/** Installed benches are real items in a native, saved block container. */
public final class NetworkBenches {
    public static final short SLOTS=10;
    static final String KEY="AdvancedStorageBench";
    static final Map<World,Map<UUID,Station>> STATIONS=Collections.synchronizedMap(new WeakHashMap<>());
    private NetworkBenches(){}
    public static BlockType type(ItemStack item) {
        if(ItemStack.isEmpty(item)||!item.getItem().hasBlockType())return null;
        var type=BlockType.getAssetMap().getAsset(item.getItem().getBlockId());
        if(type==null||type.getBench()==null)return null;
        if(type.getBench().getType()==BenchType.Processing)
            return isSalvager(type)?type:null;
        if(!Set.of(BenchType.Crafting,BenchType.StructuralCrafting,BenchType.DiagramCrafting).contains(type.getBench().getType()))return null;
        // The native pack also contains unfinished armory/loom prototypes and a trough alias.
        if(Set.of("Bench_Armory","Bench_Loom","Bench_Trough").contains(item.getItemId()))return null;
        return type;
    }
    public static ItemContainer inventory(World world,StorageNetwork.Pos pos,boolean create) {
        var node=StorageNetwork.node(world,pos);if(node==null||!node.role().equals("Catalog"))return null;
        var chunks=world.getChunkStore();var sectionRef=chunks.getChunkSectionReferenceAtBlock(pos.x(),pos.y(),pos.z());
        if(sectionRef==null||!sectionRef.isValid())return null;
        var section=chunks.getStore().getComponent(sectionRef,BlockComponentSection.getComponentType());
        int index=ChunkUtil.indexBlock(pos.x(),pos.y(),pos.z());
        var ref=BlockModule.getBlockEntity(world,pos.x(),pos.y(),pos.z());
        var holder=section==null?null:section.getBlockHolder(index);
        var component=ref!=null&&ref.isValid()?chunks.getStore().getComponent(ref,ItemContainerBlock.getComponentType()):holder==null?null:holder.getComponent(ItemContainerBlock.getComponentType());
        if(component==null&&create) {
            component=ItemContainerBlock.CODEC.decode(new BsonDocument("Capacity",new BsonInt32(SLOTS)));
            component.setItemContainer(new SimpleItemContainer(SLOTS));
            if(ref!=null&&ref.isValid())chunks.getStore().addComponent(ref,ItemContainerBlock.getComponentType(),component);
            else if(holder!=null)holder.addComponent(ItemContainerBlock.getComponentType(),component);
            else {
                if(section==null)throw new IllegalStateException("Catalog section unavailable");
                holder=ChunkStore.REGISTRY.newHolder();holder.addComponent(ItemContainerBlock.getComponentType(),component);
                holder.addComponent(BlockModule.BlockStateInfo.getComponentType(),new BlockModule.BlockStateInfo(index,sectionRef));
                section.addBlockHolder(index,holder);
            }
            StorageNetwork.dirty(world,pos);
        }
        if(component==null)return null;var container=component.getItemContainer();
        for(short i=0;i<container.getCapacity();i++)container.setSlotFilter(FilterActionType.ADD,i,(action,c,slot,item)->ItemStack.isEmpty(item)||type(item)!=null&&item.getQuantity()==1);
        return container;
    }
    private static boolean isSalvager(BlockType type) {
        return type.getBench().getType()==BenchType.Processing && "Salvagebench".equals(type.getBench().getId());
    }
    public static String display(ItemStack item){return CatalogView.name(item);}
    static ItemStack recipeIcon(CraftingRecipe recipe,boolean salvager) {
        if(salvager) {
            var input=recipe.getInput();
            if(input==null || input.length!=1 || input[0].getItemId()==null) return null;
            return new ItemStack(input[0].getItemId(),input[0].getQuantity());
        }
        var outputs=CraftingManager.getOutputItemStacks(recipe);
        return outputs.isEmpty()?null:outputs.getFirst();
    }
    public static List<CraftingRecipe> recipes(ItemStack item,int tier,String search) {
        var type=type(item);if(type==null)return List.of();var bench=type.getBench();
        String query=search==null?"":search.toLowerCase(Locale.ROOT);
        boolean salvager=isSalvager(type);
        var recipes=new ArrayList<Map.Entry<String,CraftingRecipe>>();
        for(var recipe:CraftingRecipe.getAssetMap().getAssetMap().values()) {
            var requirements=recipe.getBenchRequirement();if(requirements==null)continue;
            boolean match=Arrays.stream(requirements).anyMatch(r->r.id.equals(bench.getId())&&r.type==bench.getType()&&r.requiredTierLevel<=tier);
            if(!match) continue;
            var icon=recipeIcon(recipe,salvager);
            if(ItemStack.isEmpty(icon) || salvager && CraftingManager.getOutputItemStacks(recipe).isEmpty()) continue;
            String name=CatalogView.name(icon);
            if(query.isEmpty() || name.toLowerCase(Locale.ROOT).contains(query)) recipes.add(Map.entry(name,recipe));
        }
        recipes.sort(Map.Entry.comparingByKey());
        return recipes.stream().map(Map.Entry::getValue).toList();
    }
    public static Station station(World world,StorageNetwork.Pos pos,short slot) {
        var inventory=inventory(world,pos,true);if(inventory==null)return null;
        var item=inventory.getItemStack(slot);if(type(item)==null)return null;
        var meta=item.getMetadata()==null?new BsonDocument():item.getMetadata().clone();
        var data=meta.getDocument(KEY,new BsonDocument());
        if(!data.containsKey("Id")) {
            data=data.clone();data.put("Id",new BsonString(UUID.randomUUID().toString()));meta.put(KEY,data);
            item=item.withMetadata(meta);inventory.setItemStackForSlot(slot,item);StorageNetwork.dirty(world,pos);
        }
        var id=UUID.fromString(data.getString("Id").getValue());
        var map=STATIONS.computeIfAbsent(world,w->new HashMap<>());
        var existing=map.get(id);
        if(existing!=null&&existing.pos.equals(pos)&&existing.slot==slot&&!existing.closed)return existing;
        if(existing!=null)existing.closed=true;
        var state=new Station(world,pos,slot,id,item,data);map.put(id,state);return state;
    }
    public static final class Station {
        final World world;final StorageNetwork.Pos pos;final short slot;final UUID id;final BlockType type;
        final BenchBlock bench;
        boolean closed,upgrading,scheduled;int remaining;float elapsed;CraftingRecipe queued;ItemContainer destination;
        public String status="";
        java.util.function.BooleanSupplier jobAccess=()->true;
        Station(World world,StorageNetwork.Pos pos,short slot,UUID id,ItemStack item,BsonDocument data) {
            this.world=world;this.pos=pos;this.slot=slot;this.id=id;type=type(item);
            bench=data.containsKey("Bench")?BenchBlock.CODEC.decode(data.get("Bench")):new BenchBlock();
            if(bench.getTierLevel()<1)bench.setTierLevel(1);
        }
        public int tier(){return bench.getTierLevel();}
        boolean salvager(){return isSalvager(type);}
        public boolean valid() {
            var inventory=inventory(world,pos,false);if(inventory==null||slot>=inventory.getCapacity())return false;
            var item=inventory.getItemStack(slot);var meta=ItemStack.isEmpty(item)?null:item.getMetadata();
            return meta!=null&&meta.containsKey(KEY)&&id.toString().equals(meta.getDocument(KEY).getString("Id",new BsonString("")).getValue());
        }
        void save() {
            if(!valid())return;var inventory=inventory(world,pos,false);var item=inventory.getItemStack(slot);var meta=item.getMetadata().clone();
            var data=new BsonDocument("Id",new BsonString(id.toString())).append("Bench",BenchBlock.CODEC.encode(bench));
            meta.put(KEY,data);if(!meta.equals(item.getMetadata())){if(!inventory.setItemStackForSlot(slot,item.withMetadata(meta)).succeeded())throw new IllegalStateException("Cannot save installed bench");StorageNetwork.dirty(world,pos);}
        }
        public boolean allowed(CraftingRecipe recipe) {
            var req=recipe.getBenchRequirement();return req!=null&&Arrays.stream(req).anyMatch(r->r.id.equals(type.getBench().getId())&&r.type==type.getBench().getType()&&r.requiredTierLevel<=tier()&&(r.requiredAugmentTags==null||r.requiredAugmentTags.length==0));
        }
        public boolean start(CraftingRecipe recipe,int count,ItemContainer destination) {
            return start(recipe,count,destination,()->true);
        }
        public boolean start(CraftingRecipe recipe,int count,ItemContainer destination,java.util.function.BooleanSupplier access) {
            if(!valid()||!allowed(recipe)||remaining>0||upgrading||count<1||count>100)return false;
            this.queued=recipe;remaining=count;elapsed=0;this.destination=salvager()?null:destination;jobAccess=access;status=salvager()?"Salvaging":"Crafting";schedule();return true;
        }
        public void stop(){remaining=0;queued=null;upgrading=false;elapsed=0;status="Stopped";save();}
        public boolean upgrade() {
            if(!valid()||remaining>0||upgrading)return false;
            var requirement=type.getBench().getUpgradeRequirement(tier());if(requirement==null)return false;
            upgrading=true;elapsed=-Math.max(0,requirement.getTimeSeconds());status="Upgrading";save();schedule();return true;
        }
        void tick() {
            if(closed||!valid()){closed=true;return;}
            var net=StorageNetwork.scan(world,pos);if(!net.active())return;
            if(upgrading) {
                elapsed+=.25f;
                if(elapsed>=0){
                    var requirement=type.getBench().getUpgradeRequirement(tier());upgrading=false;
                    if(requirement!=null&&net.craft(Arrays.asList(requirement.getInput()),List.of(),null)){
                        bench.setTierLevel(tier()+1);status="Upgrade complete";
                        save();
                    }else status="Missing upgrade ingredients";
                }
                return;
            }
            if(remaining>0&&queued!=null) {
                boolean salvaging=salvager();
                if(!jobAccess.getAsBoolean()){remaining=0;status=salvaging?"Salvage cancelled":"Craft cancelled";return;}
                elapsed+=.25f;
                var level=salvaging?null:type.getBench().getTierLevel(tier());
                float time=queued.getTimeSeconds()*(level==null?1:Math.max(0,1-level.getCraftingTimeReductionModifier()));
                if(elapsed>=time) {
                    var inputs=CraftingManager.getInputMaterials(queued);
                    var outputs=CraftingManager.getOutputItemStacks(queued);
                    boolean done=salvaging?net.salvage(inputs,outputs):net.craft(inputs,outputs,destination);
                    if(done) {
                        remaining--;elapsed=0;
                        status=remaining==0?(salvaging?"Salvage complete":"Craft complete"):(salvaging?"Salvaging: ":"Crafting: ")+remaining;
                    } else {
                        remaining=0;status=salvaging?"Item missing or network full":"Missing ingredients or output inventory full";
                    }
                }
            }
        }
        void schedule(){if(scheduled||closed)return;scheduled=true;CompletableFuture.delayedExecutor(250,TimeUnit.MILLISECONDS,world).execute(()->{scheduled=false;if(closed||!world.isAlive())return;try{tick();}catch(RuntimeException ex){closed=true;world.getLogger().atSevere().withCause(ex).log("Network bench stopped at %s",pos);}if(!closed&&(remaining>0||upgrading))schedule();});}
    }
    public static void shutdown(){synchronized(STATIONS){for(var map:STATIONS.values())for(var s:map.values())s.closed=true;STATIONS.clear();}}
}
