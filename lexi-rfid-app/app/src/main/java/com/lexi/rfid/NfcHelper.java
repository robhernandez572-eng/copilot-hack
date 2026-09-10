package com.lexi.rfid;

import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.nfc.tech.NfcF;
import android.nfc.tech.NfcV;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NfcHelper {

    // ── READ ───────────────────────────────────────────────────────────────

    public static CardData readTag(Tag tag) {
        byte[] tagId = tag.getId();
        String uid = bytesToHex(tagId);
        String[] techList = tag.getTechList();
        StringBuilder rawData = new StringBuilder();
        CardData.CardType cardType = CardData.CardType.NFC_OTHER;
        String technology = "NFC";

        if (hasTech(techList, MifareClassic.class.getName())) {
            cardType = CardData.CardType.NFC_MIFARE;
            technology = "MIFARE Classic";
            rawData.append(readMifare(tag));
        } else if (hasTech(techList, MifareUltralight.class.getName())) {
            technology = "MIFARE Ultralight";
            rawData.append(readMifareUltralight(tag));
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
        } else {
            rawData.append("UID: ").append(uid).append("\nTechs: ").append(Arrays.toString(techList));
        }

        return new CardData(uid, rawData.toString(), cardType, technology);
    }

    /** Read MIFARE Classic with default keys only. */
    private static String readMifare(Tag tag) {
        return readMifareWithKeys(tag, MifareClassic.KEY_DEFAULT, MifareClassic.KEY_DEFAULT);
    }

    /**
     * Read MIFARE Classic with user-supplied Key A and Key B (each 6 bytes).
     * Falls back to KEY_DEFAULT and KEY_NFC_FORUM if custom keys fail.
     * Returns formatted sector dump string.
     */
    public static String readMifareWithKeys(Tag tag, byte[] customKeyA, byte[] customKeyB) {
        StringBuilder sb = new StringBuilder();
        MifareClassic mifare = MifareClassic.get(tag);
        if (mifare == null) return "Could not open MIFARE";
        // All key candidates to try in order
        byte[][] keysA = buildKeyCandidates(customKeyA);
        byte[][] keysB = buildKeyCandidates(customKeyB);
        try {
            mifare.connect();
            int sectors = mifare.getSectorCount();
            sb.append("Sectors: ").append(sectors).append("\n");
            for (int s = 0; s < Math.min(sectors, 40); s++) {
                boolean authed = false;
                // Try Key A candidates
                for (byte[] ka : keysA) {
                    try { if (mifare.authenticateSectorWithKeyA(s, ka)) { authed = true; break; } } catch (IOException ignored) {}
                }
                // Try Key B candidates if Key A failed
                if (!authed) {
                    for (byte[] kb : keysB) {
                        try { if (mifare.authenticateSectorWithKeyB(s, kb)) { authed = true; break; } } catch (IOException ignored) {}
                    }
                }
                if (authed) {
                    int blocks = mifare.getBlockCountInSector(s);
                    for (int b = 0; b < blocks; b++) {
                        int blockIdx = mifare.sectorToBlock(s) + b;
                        try {
                            byte[] data = mifare.readBlock(blockIdx);
                            sb.append("S").append(s).append("B").append(b).append(": ").append(bytesToHex(data)).append("\n");
                        } catch (IOException ignored) {
                            sb.append("S").append(s).append("B").append(b).append(": [read error]\n");
                        }
                    }
                } else {
                    sb.append("S").append(s).append(": [auth failed — wrong key?]\n");
                }
            }
        } catch (IOException e) {
            sb.append("Error: ").append(e.getMessage());
        } finally {
            try { mifare.close(); } catch (IOException ignored) {}
        }
        return sb.toString();
    }

    private static byte[][] buildKeyCandidates(byte[] custom) {
        return new byte[][]{
            custom,
            MifareClassic.KEY_DEFAULT,
            MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY,
            MifareClassic.KEY_NFC_FORUM,
            {(byte)0xA0,(byte)0xA1,(byte)0xA2,(byte)0xA3,(byte)0xA4,(byte)0xA5},
            {(byte)0xD3,(byte)0xF7,(byte)0xD3,(byte)0xF7,(byte)0xD3,(byte)0xF7},
        };
    }

    /** Re-read a tag using custom keys; returns a new CardData with the richer dump. */
    public static CardData readTagWithCustomKeys(Tag tag, byte[] keyA, byte[] keyB) {
        byte[] tagId = tag.getId();
        String uid = bytesToHex(tagId);
        String raw = readMifareWithKeys(tag, keyA, keyB);
        return new CardData(uid, raw, CardData.CardType.NFC_MIFARE, "MIFARE Classic");
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
                sb.append("Empty NDEF tag\n");
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
            byte[] hi = iso.getHiLayerResponse();
            byte[] hist = iso.getHistoricalBytes();
            if (hi != null) sb.append("Hi-Layer: ").append(bytesToHex(hi)).append("\n");
            if (hist != null) sb.append("Historical: ").append(bytesToHex(hist)).append("\n");
            byte[] select = {0x00, (byte)0xA4, 0x04, 0x00, 0x00};
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
            for (int page = 0; page < 48; page++) {
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

    // ── WRITE ──────────────────────────────────────────────────────────────

    /** Write card data to a physical NFC tag. Returns a status message. */
    public static String writeTag(Tag tag, CardData card) {
        String[] techList = tag.getTechList();
        String tech = card.getTechnology();

        // Match source card type to target tag technology
        if (tech.contains("MIFARE Classic") && hasTech(techList, MifareClassic.class.getName())) {
            return writeMifareClassic(tag, card.getRawData());
        }
        if (tech.contains("Ultralight") && hasTech(techList, MifareUltralight.class.getName())) {
            return writeMifareUltralight(tag, card.getRawData());
        }
        // NDEF on any NDEF-capable tag
        if (hasTech(techList, Ndef.class.getName())) {
            return writeNdef(tag, card);
        }
        if (hasTech(techList, NdefFormatable.class.getName())) {
            return formatAndWriteNdef(tag, card);
        }
        // Fallback: try whichever write method matches the blank tag
        if (hasTech(techList, MifareClassic.class.getName())) {
            return writeMifareClassic(tag, card.getRawData());
        }
        if (hasTech(techList, MifareUltralight.class.getName())) {
            return writeMifareUltralight(tag, card.getRawData());
        }
        return "Cannot write to this tag type:\n" + Arrays.toString(techList);
    }

    private static String writeMifareClassic(Tag tag, String rawData) {
        MifareClassic mifare = MifareClassic.get(tag);
        if (mifare == null) return "Cannot open MIFARE Classic for writing";
        Map<String, byte[]> blocks = parseMifareBlocks(rawData);
        if (blocks.isEmpty()) return "No MIFARE block data found in saved card";
        StringBuilder result = new StringBuilder("MIFARE Classic write:\n");
        int written = 0, failed = 0;
        try {
            mifare.connect();
            for (Map.Entry<String, byte[]> entry : blocks.entrySet()) {
                String key = entry.getKey(); // "S0B3"
                byte[] data = entry.getValue();
                int sIdx = Integer.parseInt(key.substring(1, key.indexOf('B')));
                int bIdx = Integer.parseInt(key.substring(key.indexOf('B') + 1));
                // Skip sector trailer (last block) to avoid locking card
                int blockCount = mifare.getBlockCountInSector(sIdx);
                if (bIdx == blockCount - 1) continue;
                int absBlock = mifare.sectorToBlock(sIdx) + bIdx;
                boolean authed = false;
                try { authed = mifare.authenticateSectorWithKeyA(sIdx, MifareClassic.KEY_DEFAULT); } catch (Exception ignored) {}
                if (!authed) try { authed = mifare.authenticateSectorWithKeyB(sIdx, MifareClassic.KEY_DEFAULT); } catch (Exception ignored) {}
                if (authed) {
                    try {
                        mifare.writeBlock(absBlock, data);
                        written++;
                    } catch (Exception e) {
                        result.append("Block ").append(absBlock).append(": FAIL\n");
                        failed++;
                    }
                } else {
                    result.append("S").append(sIdx).append(": auth failed\n");
                    failed++;
                }
            }
        } catch (Exception e) {
            result.append("Error: ").append(e.getMessage()).append("\n");
        } finally {
            try { mifare.close(); } catch (IOException ignored) {}
        }
        result.append("Written: ").append(written).append(" blocks");
        if (failed > 0) result.append(", Failed: ").append(failed);
        return result.toString();
    }

    private static String writeMifareUltralight(Tag tag, String rawData) {
        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul == null) return "Cannot open MIFARE Ultralight for writing";
        Map<Integer, byte[]> pages = parseUltralightPages(rawData);
        if (pages.isEmpty()) return "No Ultralight page data found in saved card";
        StringBuilder result = new StringBuilder("Ultralight write:\n");
        int written = 0, failed = 0;
        try {
            ul.connect();
            for (Map.Entry<Integer, byte[]> entry : pages.entrySet()) {
                int page = entry.getKey();
                if (page < 4) continue; // Skip UID/config pages 0-3
                try {
                    ul.writePage(page, entry.getValue());
                    written++;
                } catch (Exception e) {
                    result.append("Page ").append(page).append(": FAIL\n");
                    failed++;
                }
            }
        } catch (Exception e) {
            result.append("Error: ").append(e.getMessage()).append("\n");
        } finally {
            try { ul.close(); } catch (IOException ignored) {}
        }
        result.append("Written: ").append(written).append(" pages");
        if (failed > 0) result.append(", Failed: ").append(failed);
        return result.toString();
    }

    private static String writeNdef(Tag tag, CardData card) {
        Ndef ndef = Ndef.get(tag);
        if (ndef == null) return "Cannot open NDEF for writing";
        NdefMessage message = buildNdefMessage(card);
        try {
            ndef.connect();
            if (!ndef.isWritable()) return "Tag is read-only";
            if (ndef.getMaxSize() < message.toByteArray().length)
                return "Tag too small (" + ndef.getMaxSize() + " bytes available)";
            ndef.writeNdefMessage(message);
            return "NDEF written successfully";
        } catch (Exception e) {
            return "NDEF write error: " + e.getMessage();
        } finally {
            try { ndef.close(); } catch (IOException ignored) {}
        }
    }

    private static String formatAndWriteNdef(Tag tag, CardData card) {
        NdefFormatable formatable = NdefFormatable.get(tag);
        if (formatable == null) return "Tag is not NDEF-formatable";
        NdefMessage message = buildNdefMessage(card);
        try {
            formatable.connect();
            formatable.format(message);
            return "Tag formatted and NDEF written";
        } catch (Exception e) {
            return "Format error: " + e.getMessage();
        } finally {
            try { formatable.close(); } catch (IOException ignored) {}
        }
    }

    private static NdefMessage buildNdefMessage(CardData card) {
        String text = "UID:" + card.getUid() + "|Type:" + card.getTechnology()
            + "|Name:" + card.getName();
        NdefRecord record = NdefRecord.createTextRecord("en", text);
        return new NdefMessage(new NdefRecord[]{record});
    }

    // ── PARSERS ────────────────────────────────────────────────────────────

    /** Parse "S0B0: AABBCC..." lines → key="S0B0", value=bytes */
    private static Map<String, byte[]> parseMifareBlocks(String rawData) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        Pattern p = Pattern.compile("(S\\d+B\\d+): ([0-9A-Fa-f]{32})");
        Matcher m = p.matcher(rawData);
        while (m.find()) result.put(m.group(1), hexToBytes(m.group(2)));
        return result;
    }

    /** Parse "P04: AABBCCDD" lines → page index → 4 bytes */
    private static Map<Integer, byte[]> parseUltralightPages(String rawData) {
        Map<Integer, byte[]> result = new LinkedHashMap<>();
        Pattern p = Pattern.compile("P(\\d+): ([0-9A-Fa-f]{8})");
        Matcher m = p.matcher(rawData);
        while (m.find()) result.put(Integer.parseInt(m.group(1)), hexToBytes(m.group(2)));
        return result;
    }

    // ── UTILITIES ──────────────────────────────────────────────────────────

    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                + Character.digit(hex.charAt(i + 1), 16));
        return data;
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

    private static boolean hasTech(String[] techs, String tech) {
        for (String t : techs) if (t.equals(tech)) return true;
        return false;
    }
}
