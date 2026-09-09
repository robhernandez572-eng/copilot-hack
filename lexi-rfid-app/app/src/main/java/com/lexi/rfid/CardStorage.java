package com.lexi.rfid;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class CardStorage {
    private static final String PREFS_NAME = "lexi_cards";
    private static final String KEY_CARDS = "saved_cards";
    private final SharedPreferences prefs;

    public CardStorage(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void saveCard(CardData card) {
        List<CardData> cards = loadCards();
        // Replace if same UID exists
        cards.removeIf(c -> c.getUid().equals(card.getUid()));
        cards.add(0, card);
        persistCards(cards);
    }

    public List<CardData> loadCards() {
        List<CardData> cards = new ArrayList<>();
        String json = prefs.getString(KEY_CARDS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                CardData card = new CardData(
                    obj.getString("uid"),
                    obj.optString("rawData", ""),
                    CardData.CardType.valueOf(obj.optString("cardType", "UNKNOWN")),
                    obj.optString("technology", "Unknown")
                );
                card.setName(obj.optString("name", card.getName()));
                card.setNotes(obj.optString("notes", ""));
                card.setCategory(obj.optString("category", "General"));
                cards.add(card);
            }
        } catch (Exception ignored) {}
        return cards;
    }

    public CardData findByUid(String uid) {
        if (uid == null) return null;
        for (CardData card : loadCards()) {
            if (uid.equals(card.getUid())) return card;
        }
        return null;
    }

    public void deleteCard(String uid) {
        List<CardData> cards = loadCards();
        cards.removeIf(c -> c.getUid().equals(uid));
        persistCards(cards);
    }

    private void persistCards(List<CardData> cards) {
        try {
            JSONArray arr = new JSONArray();
            for (CardData card : cards) {
                JSONObject obj = new JSONObject();
                obj.put("uid", card.getUid());
                obj.put("name", card.getName());
                obj.put("rawData", card.getRawData());
                obj.put("cardType", card.getCardType().name());
                obj.put("technology", card.getTechnology());
                obj.put("timestamp", card.getTimestamp());
                obj.put("notes", card.getNotes());
                obj.put("category", card.getCategory());
                arr.put(obj);
            }
            prefs.edit().putString(KEY_CARDS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }
}
