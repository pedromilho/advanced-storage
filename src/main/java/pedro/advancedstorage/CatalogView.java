package pedro.advancedstorage;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import java.text.Normalizer;
import java.util.*;

public record CatalogView(String search,Order order) {
    public enum Order { ALPHABETICAL, QUANTITY }
    public static final CatalogView DEFAULT=new CatalogView("",Order.ALPHABETICAL);
    public CatalogView {
        search=search==null?"":search.substring(0,Math.min(100,search.length()));
        order=order==null?Order.ALPHABETICAL:order;
    }
    public static String name(ItemStack item) {
        String translated=I18nModule.get().getMessage("en-US",item.getItem().getTranslationKey());
        return translated==null?item.getItemId().replace("AdvancedStorage_","").replace('_',' '):translated;
    }
    static String normalize(String text) {
        return Normalizer.normalize(text,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).trim();
    }
    public List<StorageNetwork.StoredItem> select(List<StorageNetwork.StoredItem> contents) {
        String query=normalize(search);
        Comparator<StorageNetwork.StoredItem> alpha=Comparator.comparing(e->normalize(name(e.template)));
        alpha=alpha.thenComparing(e->e.template.getItemId());
        var comparator=order==Order.QUANTITY?Comparator.<StorageNetwork.StoredItem>comparingLong(e->e.count).reversed().thenComparing(alpha):alpha;
        return contents.stream().filter(e->normalize(name(e.template)+" "+e.template.getItemId()).contains(query)).sorted(comparator).toList();
    }
}
