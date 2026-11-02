package pedro.advancedstorage;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import java.util.*;

/** A selection is a detached snapshot, never an item removed into a temporary cursor inventory. */
public final class CatalogGestures {
    public static final int NETWORK=-1001,INVENTORY=-1002,HOTBAR=-1003,BACKPACK=-1004;
    public record Section(int nativeId,ItemContainer container) {}
    public interface Commands {
        void move(int from,int slot,int quantity,int to,int targetSlot);
        void quickMove(int from,int slot,int quantity);
    }
    public enum Result { PICKED, MOVED, CANCELLED, IGNORED, STALE, REJECTED }
    private record Selection(int grid,int slot,ItemStack expected,int quantity,boolean shift,boolean unitPickup) {}
    final Map<Integer,Section> sections;
    final Commands commands;
    final Map<Integer,ItemStack[]> displayed=new HashMap<>();
    Selection selection;
    Selection completedSource;
    Integer resetGrid;
    public Integer takeResetGrid() {var grid=resetGrid;resetGrid=null;return grid;}
    private void finish(Selection source) {selection=null;completedSource=source;resetGrid=source.grid;}
    public boolean completedOrigin(CatalogInput event) {
        return completedSource!=null && Objects.equals(sourceGrid(event.sourceSection),completedSource.grid)
            && Objects.equals(event.sourceSlot,completedSource.slot);
    }
    String completedDrop;
    long completedAt;
    boolean rightSweep;
    final Set<String> visited=new HashSet<>();
    final Set<String> placedOnPress=new HashSet<>();
    public CatalogGestures(Map<Integer,Section> sections,Commands commands) {
        this.sections=Map.copyOf(sections);this.commands=commands;capture();
    }
    public boolean holding() {return selection!=null;}
    public void cancel() {selection=null;completedSource=null;rightSweep=false;visited.clear();placedOnPress.clear();}
    public String selectionLabel() {return selection==null?"":CatalogView.name(selection.expected)+" x "+selection.quantity;}
    public void capture() {
        if(holding()) return;
        for(var entry:sections.entrySet()) {
            var container=entry.getValue().container;
            if(container instanceof CatalogContainer catalog) {displayed.put(entry.getKey(),catalog.snapshot());continue;}
            var snapshot=new ItemStack[container.getCapacity()];
            for(short slot=0;slot<snapshot.length;slot++) {
                var item=container.getItemStack(slot);
                snapshot[slot]=ItemStack.isEmpty(item)?null:item.withMetadata(item.getMetadata());
            }
            displayed.put(entry.getKey(),snapshot);
        }
    }
    public ItemStack[] displayed(int grid) {return displayed.getOrDefault(grid,ItemStack.EMPTY_ARRAY).clone();}
    private boolean valid(int grid,Integer slot) {
        var section=sections.get(grid);
        return section!=null && slot!=null && slot>=0 && slot<section.container.getCapacity();
    }
    private ItemStack shown(int grid,int slot) {
        var values=displayed.get(grid);return values==null || slot>=values.length?null:values[slot];
    }
    private boolean matches(Selection source) {
        if(!valid(source.grid,source.slot)) return false;
        var item=sections.get(source.grid).container.getItemStack((short)source.slot);
        return !ItemStack.isEmpty(item) && source.expected.isStackableWith(item) && item.getQuantity()>=source.quantity;
    }
    private long playerCount(ItemStack template) {
        long total=0;
        for(var entry:sections.entrySet()) if(entry.getKey()!=NETWORK) {
            var container=entry.getValue().container;
            for(short slot=0;slot<container.getCapacity();slot++) {
                var item=container.getItemStack(slot);
                if(!ItemStack.isEmpty(item) && item.isStackableWith(template)) total+=item.getQuantity();
            }
        }
        return total;
    }
    private Integer sourceGrid(Integer id) {
        if(id==null) return null;
        for(var entry:sections.entrySet()) if(entry.getKey().equals(id) || entry.getValue().nativeId==id) return entry.getKey();
        return null;
    }
    private void remember(int grid,int slot) {
        var values=displayed.get(grid);
        if(values!=null && slot<values.length) values[slot]=sections.get(grid).container.getItemStack((short)slot);
    }
    private Result gather(int grid,int slot) {
        var target=sections.get(grid).container;
        var expected=shown(grid,slot);
        var current=target.getItemStack((short)slot);
        if(ItemStack.isEmpty(expected) || !Objects.equals(expected,current)) {cancel();return Result.STALE;}
        cancel();resetGrid=grid;placedOnPress.add(grid+":"+slot);
        int maximum=current.getItem().getMaxStack();
        boolean changed=false;
        // Prefer loose player stacks before taking more from the network.
        var order=new ArrayList<>(sections.keySet());
        order.sort(Comparator.comparingInt(id->id==NETWORK?1:0));
        for(int sourceGrid:order) {
            var source=sections.get(sourceGrid);
            for(int sourceSlot=0;sourceSlot<source.container.getCapacity();sourceSlot++) {
                if(sourceGrid==grid && sourceSlot==slot) continue;
                current=target.getItemStack((short)slot);
                if(ItemStack.isEmpty(current) || !current.isStackableWith(expected)) return Result.STALE;
                int space=maximum-current.getQuantity();
                if(space<=0) return changed?Result.MOVED:Result.IGNORED;
                var sourceExpected=shown(sourceGrid,sourceSlot);
                var live=source.container.getItemStack((short)sourceSlot);
                if(ItemStack.isEmpty(sourceExpected) || !sourceExpected.isStackableWith(expected)
                    || !Objects.equals(sourceExpected,live)) continue;
                commands.move(source.nativeId,sourceSlot,Math.min(space,live.getQuantity()),sections.get(grid).nativeId,slot);
                var after=target.getItemStack((short)slot);
                if(!Objects.equals(current,after)) {changed=true;remember(grid,slot);remember(sourceGrid,sourceSlot);}
            }
        }
        return changed?Result.MOVED:Result.IGNORED;
    }
    public Result handle(CatalogInput event) {
        if(event.action.equals("cancel")) {
            var completed=selection==null?completedSource:null;
            cancel();completedSource=completed;return Result.CANCELLED;
        }
        if(event.section==null || !valid(event.section,event.slot)) return Result.IGNORED;
        int grid=event.section,slot=event.slot;
        String destination=grid+":"+slot;
        // The native drag controller reports the second click through the press envelope.
        // Consume it before single-click cancellation disposes the source grid.
        boolean doublePress=event.action.equals("press") && !event.right && event.clickCount!=null
            && event.clickCount>=2 && event.clickCount%2==0
            && Objects.equals(sourceGrid(event.sourceSection),grid) && Objects.equals(event.sourceSlot,slot);
        if(!event.right && (event.action.equals("double") || doublePress)) return gather(grid,slot);
        if(event.action.equals("enter") && completedSource!=null && completedSource.grid==grid && completedSource.slot==slot) completedSource=null;
        if(event.action.equals("exit")) {visited.remove(destination);return Result.IGNORED;}
        if(event.action.equals("pick")) {
            completedDrop=null;completedSource=null;
            if(selection!=null) return Result.IGNORED;
            rightSweep=false;visited.clear();placedOnPress.clear();
            var item=shown(grid,slot);
            if(ItemStack.isEmpty(item)) return Result.IGNORED;
            if(event.itemId!=null && !event.itemId.equals(item.getItemId())) return Result.STALE;
            int quantity=event.right?(event.shift?(item.getQuantity()+1)/2:1):item.getQuantity();
            if(event.quantity!=null && event.quantity>0) quantity=Math.min(quantity,event.quantity);
            var picked=new Selection(grid,slot,item,quantity,event.shift && !event.right,event.right && !event.shift);
            if(!matches(picked)) return Result.STALE;
            selection=picked;return Result.PICKED;
        }
        String dropKey=event.sourceSection+":"+event.sourceSlot+":"+grid+":"+slot+":"+event.itemId+":"+event.quantity;
        boolean drop=event.action.equals("drop"),press=event.action.equals("press"),hover=event.action.equals("enter");
        if(drop && placedOnPress.contains(destination)) {
            rightSweep=false;visited.clear();return Result.IGNORED;
        }
        boolean wasHolding=selection!=null;
        // Some native split pickups omit SlotClicking. The press envelope names the current cursor source.
        if(selection!=null && press && event.sourceSection!=null && event.sourceSlot!=null) {
            var namedGrid=sourceGrid(event.sourceSection);
            if(namedGrid!=null && (namedGrid!=selection.grid || event.sourceSlot!=selection.slot)) selection=null;
            else if(selection.unitPickup && event.quantity!=null && event.quantity>1)
                selection=new Selection(selection.grid,selection.slot,selection.expected,
                    Math.min(selection.expected.getQuantity(),event.quantity),false,false);
        }
        if(selection==null && (drop || press)) {
            if(completedOrigin(event)) return Result.IGNORED;
            if(drop && dropKey.equals(completedDrop) && System.nanoTime()-completedAt<200_000_000L) return Result.IGNORED;
            if(event.sourceSection==null || event.sourceSlot==null || event.itemId==null || event.quantity==null || event.quantity<=0) return Result.IGNORED;
            var sourceGrid=sourceGrid(event.sourceSection);
            if(sourceGrid==null || !valid(sourceGrid,event.sourceSlot)) return Result.STALE;
            var expected=shown(sourceGrid,event.sourceSlot);
            if(ItemStack.isEmpty(expected) || !expected.getItemId().equals(event.itemId)) return Result.STALE;
            selection=new Selection(sourceGrid,event.sourceSlot,expected,Math.min(expected.getQuantity(),event.quantity),false,
                                    event.quantity==1 && event.dragRight);
        }
        if(selection==null) return Result.IGNORED;
        var source=selection;
        if(event.action.equals("release")) {
            rightSweep=false;visited.clear();
            if(!source.shift || source.grid!=grid || source.slot!=slot) return Result.IGNORED;
            cancel();
            if(!matches(source)) return Result.STALE;
            long before=playerCount(source.expected);
            commands.quickMove(sections.get(source.grid).nativeId,source.slot,source.quantity);
            return before!=playerCount(source.expected)?Result.MOVED:Result.REJECTED;
        }
        if(press && !event.right) {rightSweep=false;visited.clear();placedOnPress.clear();}
        if(hover && !rightSweep) return Result.IGNORED;
        if(!drop && !press && !hover) return Result.IGNORED;
        if(press && event.right) {
            if(!rightSweep) {placedOnPress.clear();visited.clear();}
            rightSweep=true;
            // A plain right pickup carries one visually; the held-button sweep draws from its original stack.
            if(source.unitPickup && matches(source)) {
                var live=sections.get(source.grid).container.getItemStack((short)source.slot);
                source=new Selection(source.grid,source.slot,source.expected,live.getQuantity(),false,false);
                selection=source;
            }
        }
        boolean one=press && event.right || hover || event.right;
        if(!matches(source)) {cancel();return Result.STALE;}
        if(event.sourceSection!=null && !Objects.equals(sourceGrid(event.sourceSection),source.grid)
            || event.sourceSlot!=null && event.sourceSlot!=source.slot
            || event.itemId!=null && !event.itemId.equals(source.expected.getItemId())) {cancel();return Result.STALE;}
        if(source.grid==grid && source.slot==slot) {
            // A click back on the origin ends the native cursor without moving its real contents.
            // A native split can omit pickup: its first left press on the origin is already a cancellation.
            // Initial right pickup and stationary Shift-left quick transfer keep their existing path.
            if(drop || press && (wasHolding || !event.right) && !source.shift) {
                cancel();finish(source);return Result.CANCELLED;
            }
            return Result.IGNORED;
        }
        if(rightSweep && !visited.add(destination)) return Result.IGNORED;
        var target=sections.get(grid);
        var expectedTarget=shown(grid,slot);
        var currentTarget=target.container.getItemStack((short)slot);
        if(!Objects.equals(expectedTarget,currentTarget) && !(ItemStack.isEmpty(expectedTarget) && ItemStack.isEmpty(currentTarget))) {
            cancel();return Result.STALE;
        }
        // A right placement never swaps an incompatible item or a full destination stack.
        if(one && !ItemStack.isEmpty(currentTarget) && (!currentTarget.isStackableWith(source.expected)
                || currentTarget.getQuantity()>=currentTarget.getItem().getMaxStack())) return Result.REJECTED;
        int quantity=one?1:source.quantity;
        if(event.quantity!=null && event.quantity>0) quantity=Math.min(quantity,event.quantity);
        var sourceContainer=sections.get(source.grid).container;
        var before=sourceContainer.getItemStack((short)source.slot);
        var targetBefore=target.container.getItemStack((short)slot);
        long playerBefore=source.grid==NETWORK?playerCount(source.expected):0;
        commands.move(sections.get(source.grid).nativeId,source.slot,quantity,target.nativeId,slot);
        var after=sourceContainer.getItemStack((short)source.slot);
        var targetAfter=target.container.getItemStack((short)slot);
        boolean changed=!Objects.equals(before,after) || !Objects.equals(targetBefore,targetAfter);
        if(changed) {
            int remainingSource=ItemStack.isEmpty(after)?0:after.isStackableWith(source.expected)?after.getQuantity():0;
            int moved=source.grid==NETWORK?(int)Math.max(0,playerCount(source.expected)-playerBefore):Math.max(0,before.getQuantity()-remainingSource);
            int remaining=source.quantity-moved;
            if(one && remaining>0 && remainingSource>0)
                selection=new Selection(source.grid,source.slot,source.expected,Math.min(remaining,remainingSource),false,false);
            else finish(source);
            remember(grid,slot);remember(source.grid,source.slot);
            if(one || press) placedOnPress.add(destination);
        } else if(!one) finish(source);
        completedDrop=dropKey;completedAt=System.nanoTime();
        return changed?Result.MOVED:Result.REJECTED;
    }
}
