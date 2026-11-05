package pedro.advancedstorage;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.Arrays;

final class ItemTransferSettings {
    static final int SLOTS = 36;
    static final BuilderCodec<ItemTransferSettings> CODEC =
        BuilderCodec.builder(ItemTransferSettings.class, ItemTransferSettings::new)
            .append(new KeyedCodec<>("Receive", Codec.BOOLEAN), (s, value) -> s.receive = value, s -> s.receive).add()
            .append(new KeyedCodec<>("Send", Codec.BOOLEAN), (s, value) -> s.send = value, s -> s.send).add()
            .append(new KeyedCodec<>("SendAll", Codec.BOOLEAN), (s, value) -> s.sendAll = value, s -> s.sendAll).add()
            .append(new KeyedCodec<>("ReceiveFilters", Codec.STRING_ARRAY), (s, value) -> s.setFilters(false, value), s -> s.filters(false)).add()
            .append(new KeyedCodec<>("SendFilters", Codec.STRING_ARRAY), (s, value) -> s.setFilters(true, value), s -> s.filters(true)).add()
            .build();

    boolean receive;
    boolean send;
    boolean sendAll;
    private String[] receiveFilters = normalize(null);
    private String[] sendFilters = normalize(null);

    private static String[] normalize(String[] values) {
        var result = values == null ? new String[SLOTS] : Arrays.copyOf(values, SLOTS);
        for (int i = 0; i < result.length; i++) if (result[i] == null) result[i] = "";
        return result;
    }

    String[] filters(boolean sending) { return sending ? sendFilters : receiveFilters; }

    void setFilters(boolean sending, String[] values) {
        if (sending) sendFilters = normalize(values);
        else receiveFilters = normalize(values);
    }

    boolean canSend() { return send && (sendAll || hasFilter(sendFilters)); }

    boolean sends(String itemId) { return send && (sendAll || matches(sendFilters, itemId)); }

    boolean receives(String itemId) {
        return receive && !sends(itemId) && (!hasFilter(receiveFilters) || matches(receiveFilters, itemId));
    }

    ItemTransferSettings copy() {
        var copy = new ItemTransferSettings();
        copy.receive = receive;
        copy.send = send;
        copy.sendAll = sendAll;
        copy.receiveFilters = receiveFilters.clone();
        copy.sendFilters = sendFilters.clone();
        return copy;
    }

    private static boolean hasFilter(String[] filters) {
        for (String filter : filters) if (!filter.isEmpty()) return true;
        return false;
    }

    private static boolean matches(String[] filters, String itemId) {
        for (String filter : filters) if (filter.equals(itemId)) return true;
        return false;
    }
}
