package dev.tapdrop;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** The user's items, kept as one JSON array in app-private preferences. */
final class Store {
    private static final String PREFS = "tapdrop";
    private static final String KEY_ITEMS = "items";
    static final String KEY_PAY_PAGE = "pay_page";

    private Store() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The user's items. A fresh install has none: nothing is shared until the user adds it. */
    static List<Item> load(Context c) {
        List<Item> items = new ArrayList<>();
        String raw = prefs(c).getString(KEY_ITEMS, null);
        if (raw == null) return items;
        boolean droppedSamples = false;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                Item item = Item.fromJson(array.getJSONObject(i));
                // Earlier builds seeded placeholder items. They were mistaken for real data.
                if (item.id.startsWith("sample-")) droppedSamples = true;
                else items.add(item);
            }
        } catch (JSONException e) {
            // Unreadable data: start empty instead of crashing on every launch.
            return new ArrayList<>();
        }
        if (droppedSamples) save(c, items);
        return items;
    }

    static void save(Context c, List<Item> items) {
        JSONArray array = new JSONArray();
        for (Item item : items) array.put(item.toJson());
        prefs(c).edit().putString(KEY_ITEMS, array.toString()).apply();
    }

    static Item find(Context c, String id) {
        if (id == null) return null;
        for (Item item : load(c)) if (item.id.equals(id)) return item;
        return null;
    }

    /** Replaces the item with the same id, or appends it. */
    static void upsert(Context c, Item item) {
        List<Item> items = load(c);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(item.id)) {
                items.set(i, item);
                save(c, items);
                TapDropTileService.refresh(c);
                return;
            }
        }
        items.add(item);
        save(c, items);
        TapDropTileService.refresh(c);
    }

    static void delete(Context c, String id) {
        List<Item> items = load(c);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(id)) {
                items.remove(i);
                break;
            }
        }
        save(c, items);
        TapDropTileService.refresh(c);
    }

    static String newId() {
        return Long.toString(System.currentTimeMillis(), 36);
    }

    /**
     * The item shared when a phone taps while TapDrop is closed.
     *
     * Off screen, a tap makes the watch ask the wearer to choose between Samsung Health
     * and TapDrop, then tap again. That second tap reaches the tag with no share screen
     * open, and this is what it gets. The wearer's choice on that screen is the consent.
     * Defaults to the first item in the list until the user picks one.
     */
    private static final String KEY_QUICK = "quick";

    /** Stored in place of an item id when the wearer wants nothing shared while closed. */
    static final String QUICK_NONE = "none";

    /** The quick-tap item's id, or null when there is none or the wearer chose "Nothing". */
    static String quick(Context c) {
        List<Item> items = load(c);
        String chosen = prefs(c).getString(KEY_QUICK, null);
        if (QUICK_NONE.equals(chosen)) return null;
        for (Item item : items) if (item.id.equals(chosen)) return chosen;
        return items.isEmpty() ? null : items.get(0).id;
    }

    static void setQuick(Context c, String id) {
        prefs(c).edit().putString(KEY_QUICK, id).commit();
        TapDropTileService.refresh(c);
    }

    /**
     * The project's own copy of web/pay.html, published by GitHub Pages from this repo.
     * Used unless the wearer points the watch at a page they host themselves.
     */
    static final String DEFAULT_PAY_PAGE = "https://harshtripathi272.github.io/tapdrop/pay.html";

    /** Web page that hands a UPI request to the payer's app. */
    static String payPage(Context c) {
        String page = prefs(c).getString(KEY_PAY_PAGE, "");
        return page.isEmpty() ? DEFAULT_PAY_PAGE : page;
    }
}
