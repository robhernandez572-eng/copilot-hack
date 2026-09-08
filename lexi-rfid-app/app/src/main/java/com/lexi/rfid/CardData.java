package com.lexi.rfid;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CardData {
    public enum CardType { NFC_MIFARE, NFC_NDEF, NFC_ISO_DEP, NFC_OTHER, LF_125KHZ, UNKNOWN }

    private String id;
    private String name;
    private String uid;
    private String rawData;
    private CardType cardType;
    private String technology;
    private String timestamp;
    private int atqa;
    private byte sak;

    public CardData(String uid, String rawData, CardType cardType, String technology) {
        this.uid = uid;
        this.rawData = rawData;
        this.cardType = cardType;
        this.technology = technology;
        this.id = uid + "_" + System.currentTimeMillis();
        this.name = "Card " + uid.substring(0, Math.min(4, uid.length())).toUpperCase();
        this.timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUid() { return uid; }
    public String getRawData() { return rawData; }
    public CardType getCardType() { return cardType; }
    public String getTechnology() { return technology; }
    public String getTimestamp() { return timestamp; }
    public int getAtqa() { return atqa; }
    public void setAtqa(int atqa) { this.atqa = atqa; }
    public byte getSak() { return sak; }
    public void setSak(byte sak) { this.sak = sak; }

    public String getTypeLabel() {
        switch (cardType) {
            case NFC_MIFARE: return "MIFARE Classic";
            case NFC_NDEF: return "NDEF";
            case NFC_ISO_DEP: return "ISO-DEP (14443-4)";
            case LF_125KHZ: return "125 KHz LF";
            default: return technology;
        }
    }
}
