package com.lexi.rfid;

import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.Ndef;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.nfc.tech.NfcF;
import android.nfc.tech.NfcV;
import android.nfc.tech.IsoDep;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;

import java.io.IOException;
import java.util.Arrays;

public class NfcHelper {

    public static CardData readTag(Tag tag) {
        byte[] tagId = tag.getId();
        String uid = bytesToHex(tagId);
        String[] techList = tag.getTechList();
        StringBuilder rawData = new StringBuilder();
        CardData.CardType cardType = CardData.CardType.NFC_OTHER;
        String technology = "NFC";

        // Try MIFARE Classic first
        if (hasTech(techList, MifareClassic.class.getName())) {
            cardType = CardData.CardType.NFC_MIFARE;
            technology = "MIFARE Classic";
            rawData.append(readMifare(tag));
        } else if (hasTech(techList, Ndef.class.getName())) {
            cardType = CardData.CardType.NFC_NDEF;
            technology = "NDEF";
            rawData.append(readNdef(tag));
        } else if (hasTech(techList, IsoDep.class.getName())) {
            cardType = CardData.CardType.NFC_ISO_DEP;
            technology = "ISO-DEP";
            rawData.append(readIsoDep(tag));
        } else if (hasTech(techList, NfcA.class.getName())) {
            technology = "NFC-A (ISO 14443-3A)";
            rawData.append(readNfcA(tag));
        } else if (hasTech(techList, NfcB.class.getName())) {
            technology = "NFC-B (ISO 14443-3B)";
            rawData.append("UID: ").append(uid);
        } else if (hasTech(techList, NfcF.class.getName())) {
            technology = "NFC-F (JIS 6319-4)";
            rawData.append("UID: ").append(uid);
        } else if (hasTech(techList, NfcV.class.getName())) {
            technology = "NFC-V (ISO 15693)";
            rawData.append(readNfcV(tag));
        } else if (hasTech(techList, MifareUltralight.class.getName())) {
            technology = "MIFARE Ultralight";
            rawData.append(readMifareUltralight(tag));
        } else {
            rawData.append("UID: ").append(uid).append("\nTechs: ").append(Arrays.toString(techList));
        }

        return new CardData(uid, rawData.toString(), cardType, technology);
    }

    private static String readMifare(Tag tag) {
        StringBuilder sb = new StringBuilder();
        MifareClassic mifare = MifareClassic.get(tag);
        if (mifare == null) return "Could not open MIFARE";
        try {
            mifare.connect();
            int sectors = mifare.getSectorCount();
            sb.append("Sectors: ").append(sectors).append("\n");
            for (int s = 0; s < Math.min(sectors, 16); s++) {
                boolean authed = false;
                try { authed = mifare.authenticateSectorWithKeyA(s, MifareClassic.KEY_DEFAULT); } catch (IOException ignored) {}
                if (!authed) try { authed = mifare.authenticateSectorWithKeyB(s, MifareClassic.KEY_DEFAULT); } catch (IOException ignored) {}
                if (authed) {
                    int blocks = mifare.getBlockCountInSector(s);
                    for (int b = 0; b < blocks; b++) {
                        int blockIdx = mifare.sectorToBlock(s) + b;
                        try {
                            byte[] data = mifare.readBlock(blockIdx);
                            sb.append("S").append(s).append("B").append(b).append(": ").append(bytesToHex(data)).append("\n");
                        } catch (IOException ignored) {}
                    }
                } else {
                    sb.append("S").append(s).append(": [auth failed]\n");
                }
            }
        } catch (IOException e) {
            sb.append("Error: ").append(e.getMessage());
        } finally {
            try { mifare.close(); } catch (IOException ignored) {}
        }
        return sb.toString();
    }

    private static String readNdef(Tag tag) {
        StringBuilder sb = new StringBuilder();
        Ndef ndef = Ndef.get(tag);
        if (ndef == null) return "Could not open NDEF";
        try {
            ndef.connect();
            NdefMessage msg = ndef.getNdefMessage();
            if (msg != null) {
                for (NdefRecord rec : msg.getRecords()) {
                    sb.append("TNF: ").append(rec.getTnf()).append("\n");
                    sb.append("Type: ").append(new String(rec.getType())).append("\n");
                    sb.append("Payload: ").append(bytesToHex(rec.getPayload())).append("\n");
                    try { sb.append("Text: ").append(new String(rec.getPayload(), "UTF-8")).append("\n"); }
                    catch (Exception ignored) {}
                }
            } else {
                sb.append("Empty NDEF tag");
            }
            sb.append("Max size: ").append(ndef.getMaxSize()).append(" bytes\n");
            sb.append("Writable: ").append(ndef.isWritable()).append("\n");
        } catch (Exception e) {
            sb.append("Error: ").append(e.getMessage());
        } finally {
            try { ndef.close(); } catch (IOException ignored) {}
        }
        return sb.toString();
    }

    private static String readIsoDep(Tag tag) {
        StringBuilder sb = new StringBuilder();
        IsoDep iso = IsoDep.get(tag);
        if (iso == null) return "Could not open ISO-DEP";
        try {
            iso.connect();
            byte[] hiLayerResponse = iso.getHiLayerResponse();
            byte[] historicalBytes = iso.getHistoricalBytes();
            if (hiLayerResponse != null) sb.append("Hi-Layer: ").append(bytesToHex(hiLayerResponse)).append("\n");
            if (historicalBytes != null) sb.append("Historical: ").append(bytesToHex(historicalBytes)).append("\n");
            // Send SELECT command
            byte[] select = new byte[]{0x00, (byte)0xA4, 0x04, 0x00, 0x00};
            try {
                byte[] resp = iso.transceive(select);
                sb.append("SELECT response: ").append(bytesToHex(resp)).append("\n");
            } catch (IOException ignored) {}
        } catch (Exception e) {
            sb.append("Error: ").append(e.getMessage());
        } finally {
            try { iso.close(); } catch (IOException ignored) {}
        }
        return sb.toString();
    }

    private static String readNfcA(Tag tag) {
        StringBuilder sb = new StringBuilder();
        NfcA nfcA = NfcA.get(tag);
        if (nfcA == null) return "Could not open NFC-A";
        try {
            nfcA.connect();
            sb.append("ATQA: ").append(bytesToHex(nfcA.getAtqa())).append("\n");
            sb.append("SAK: ").append(String.format("%02X", nfcA.getSak())).append("\n");
        } catch (Exception e) {
            sb.append("Error: ").append(e.getMessage());
        } finally {
            try { nfcA.close(); } catch (IOException ignored) {}
        }
        return sb.toString();
    }

    private static String readNfcV(Tag tag) {
        StringBuilder sb = new StringBuilder();
        NfcV nfcV = NfcV.get(tag);
        if (nfcV == null) return "Could not open NFC-V";
        try {
            nfcV.connect();
            sb.append("DSF ID: ").append(String.format("%02X", nfcV.getDsfId())).append("\n");
            sb.append("Response flags: ").append(String.format("%02X", nfcV.getResponseFlags())).append("\n");
        } catch (Exception e) {
            sb.append("Error: ").append(e.getMessage());
        } finally {
            try { nfcV.close(); } catch (IOException ignored) {}
        }
        return sb.toString();
    }

    private static String readMifareUltralight(Tag tag) {
        StringBuilder sb = new StringBuilder();
        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul == null) return "Could not open MIFARE Ultralight";
        try {
            ul.connect();
            sb.append("Type: ").append(ul.getType() == MifareUltralight.TYPE_ULTRALIGHT ? "Ultralight" : "Ultralight C").append("\n");
            for (int page = 0; page < 16; page++) {
                try {
                    byte[] data = ul.readPages(page);
                    sb.append("P").append(String.format("%02d", page)).append(": ")
                      .append(bytesToHex(Arrays.copyOf(data, 4))).append("\n");
                } catch (IOException ignored) { break; }
            }
        } catch (Exception e) {
            sb.append("Error: ").append(e.getMessage());
        } finally {
            try { ul.close(); } catch (IOException ignored) {}
        }
        return sb.toString();
    }

    private static boolean hasTech(String[] techs, String tech) {
        for (String t : techs) if (t.equals(tech)) return true;
        return false;
    }

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    public static String formatUid(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(":");
            sb.append(String.format("%02X", bytes[i]));
        }
        return sb.toString();
    }
}
