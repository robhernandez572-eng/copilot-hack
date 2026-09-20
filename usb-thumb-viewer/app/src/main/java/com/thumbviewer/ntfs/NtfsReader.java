package com.thumbviewer.ntfs;

import android.util.Log;

import me.jahnen.libaums.core.driver.BlockDeviceDriver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Minimal read-only NTFS reader that operates on a byte-addressed BlockDeviceDriver
 * where offset 0 = the NTFS partition boot sector (VBR).
 */
public class NtfsReader {
    private static final String TAG = "NtfsReader";

    private final BlockDeviceDriver dev;
    int sectorSize = 512;
    int clusterSize;
    long mftLcn;        // first cluster of $MFT
    int recSize;        // bytes per MFT record
    long[] mftRuns;     // data runs for $MFT: [lcn0, len0, lcn1, len1, ...]
    String volumeLabel = "";
    long capacity;
    long freeSpace;
    private int bytesPerIndexRecord = 4096;

    // Simple LRU-ish cache for MFT records (index -> bytes)
    private final long[] cacheIdx = new long[64];
    private final byte[][] cacheData = new byte[64][];
    private int cachePos = 0;

    public NtfsReader(BlockDeviceDriver dev) {
        this.dev = dev;
        Arrays.fill(cacheIdx, -1L);
    }

    /** Returns true if this block device holds an NTFS partition. */
    public boolean mount() {
        try {
            byte[] boot = readBytes(0, 512);
            if (boot[3] != 'N' || boot[4] != 'T' || boot[5] != 'F' || boot[6] != 'S')
                return false;

            sectorSize = u16(boot, 0x0B);
            if (sectorSize == 0) sectorSize = 512;
            int spc = u8(boot, 0x0D);
            clusterSize = sectorSize * spc;
            mftLcn = u64(boot, 0x30);

            // Bytes per MFT record: positive = clusters, negative = 2^|n|
            byte cpr = boot[0x40];
            recSize = cpr > 0 ? cpr * clusterSize : 1 << (-cpr);
            capacity = u64(boot, 0x28) * sectorSize;

            // Read MFT record 0 ($MFT file) to find MFT data runs.
            // At this point we can't yet call getMftRecord() because mftRuns isn't set,
            // so we read directly at the known MFT start cluster.
            byte[] mft0 = readBytes(mftLcn * clusterSize, recSize);
            applyFixup(mft0);
            if (mft0[0] != 'F') return false;

            // Get $DATA (0x80) non-resident runs from MFT record 0
            int attrOff = u16(mft0, 0x14);
            mftRuns = getDataRuns(mft0, attrOff, 0x80);
            if (mftRuns == null) return false;

            // Read $Volume (MFT entry 3) for label
            try {
                byte[] vol = getMftRecord(3);
                int off = u16(vol, 0x14);
                byte[] vnAttr = findResidentAttr(vol, off, 0x60); // $VOLUME_NAME
                if (vnAttr != null && vnAttr.length >= 2) {
                    volumeLabel = new String(vnAttr, "UTF-16LE").trim();
                }
            } catch (Exception e) { /* not critical */ }

            // Read $Bitmap (MFT entry 6) to estimate free space — skip for now, approximate
            freeSpace = capacity / 10; // rough estimate

            Log.d(TAG, "NTFS mounted: cluster=" + clusterSize + " recSize=" + recSize
                    + " mftLcn=" + mftLcn + " label='" + volumeLabel + "'");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "mount failed: " + e.getMessage());
            return false;
        }
    }

    // ── Directory listing ─────────────────────────────────────────────────

    public List<NtfsEntry> listDirectory(long mftIdx) throws IOException {
        byte[] rec = getMftRecord(mftIdx);
        List<NtfsEntry> result = new ArrayList<>();
        int attrOff = u16(rec, 0x14);

        // $INDEX_ROOT (0x90) — always resident, covers small/one-page directories
        byte[] irVal = findResidentAttr(rec, attrOff, 0x90);
        if (irVal != null && irVal.length > 32) {
            // irVal layout: 16-byte $INDEX_ROOT header + index node header + entries
            // index node header starts at offset 16
            // first entry offset (relative to start of node header) is at node+0
            int nodeOff = 16;
            int firstEntry = u32(irVal, nodeOff);
            parseIndexEntries(irVal, nodeOff + firstEntry, irVal.length, result);
            bytesPerIndexRecord = u32(irVal, 8); // offset 8 in $INDEX_ROOT = bytes per INDX
            if (bytesPerIndexRecord < 512) bytesPerIndexRecord = 4096;
        }

        // $INDEX_ALLOCATION (0xA0) — non-resident, for larger directories
        long[] iaRuns = getDataRuns(rec, attrOff, 0xA0);
        if (iaRuns != null) {
            readIndexAllocation(iaRuns, result);
        }

        return result;
    }

    private void readIndexAllocation(long[] runs, List<NtfsEntry> result) {
        long blockSize = bytesPerIndexRecord;
        long currentByte = 0;
        for (int i = 0; i + 1 < runs.length; i += 2) {
            long lcn = runs[i];
            long runClusters = runs[i + 1];
            long runBytes = runClusters * clusterSize;
            long runStart = lcn * (long) clusterSize;

            for (long off = 0; off < runBytes; off += blockSize) {
                try {
                    byte[] indx = readBytes(runStart + off, (int) blockSize);
                    if (indx[0] != 'I' || indx[1] != 'N' || indx[2] != 'D' || indx[3] != 'X')
                        continue;
                    applyFixup(indx);
                    // node header starts at 0x18
                    int firstEntry = u32(indx, 0x18);
                    int usedSize = u32(indx, 0x1C);
                    parseIndexEntries(indx, 0x18 + firstEntry, 0x18 + usedSize, result);
                } catch (Exception e) {
                    Log.w(TAG, "INDX block read error: " + e.getMessage());
                }
            }
            currentByte += runBytes;
        }
    }

    private void parseIndexEntries(byte[] data, int pos, int end, List<NtfsEntry> result) {
        while (pos + 16 <= end && pos + 16 <= data.length) {
            // MFT file reference: lower 48 bits = record number
            long mftRef = u64(data, pos);
            long recordNum = mftRef & 0x0000FFFFFFFFFFFFL;

            int entLen = u16(data, pos + 8);
            int keyLen = u16(data, pos + 10);
            int flags = u16(data, pos + 12);

            if ((flags & 0x02) != 0) break; // last-entry sentinel
            if (entLen < 16 || pos + entLen > data.length) break;

            if (keyLen > 0 && pos + 16 + keyLen <= data.length) {
                int fn = pos + 16; // $FILE_NAME value starts here
                if (fn + 0x42 + 2 <= data.length) {
                    int nameLen = u8(data, fn + 0x40);
                    int ns = u8(data, fn + 0x41);
                    // Skip DOS-only names (namespace 2) to avoid duplicates
                    if (ns != 2 && nameLen > 0 && fn + 0x42 + nameLen * 2 <= data.length) {
                        try {
                            String name = new String(data, fn + 0x42, nameLen * 2, "UTF-16LE");
                            // Skip NTFS metadata files (names starting with $)
                            if (!name.startsWith("$")) {
                                long size = u64(data, fn + 0x30); // real size
                                int fileAttr = u32(data, fn + 0x38);
                                boolean isDir = (fileAttr & 0x10) != 0;
                                result.add(new NtfsEntry(recordNum, name, size, isDir));
                            }
                        } catch (Exception e) { /* skip bad entry */ }
                    }
                }
            }

            pos += entLen;
        }
    }

    // ── File reading ──────────────────────────────────────────────────────

    public InputStream openFile(long mftIdx) throws IOException {
        byte[] rec = getMftRecord(mftIdx);
        int attrOff = u16(rec, 0x14);

        // Look for $DATA (0x80) attribute
        int pos = attrOff;
        while (pos + 8 <= rec.length) {
            int type = u32(rec, pos);
            if (type == 0xFFFFFFFF) break;
            int attrLen = u32(rec, pos + 4);
            if (attrLen < 8 || pos + attrLen > rec.length) break;

            if (type == 0x80) {
                boolean nonResident = rec[pos + 8] != 0;
                if (!nonResident) {
                    // Resident: data is inline
                    int valLen = u32(rec, pos + 0x10);
                    int valOff = u16(rec, pos + 0x14);
                    byte[] content = new byte[valLen];
                    int copyLen = Math.min(valLen, attrLen - valOff);
                    if (copyLen > 0) System.arraycopy(rec, pos + valOff, content, 0, copyLen);
                    return new java.io.ByteArrayInputStream(content);
                } else {
                    // Non-resident: follow data runs
                    long realSize = u64(rec, pos + 0x38);
                    int runsOff = u16(rec, pos + 0x20);
                    long[] runs = decodeDataRuns(rec, pos + runsOff);
                    return new DataRunStream(this, runs, realSize);
                }
            }
            pos += attrLen;
        }
        return new java.io.ByteArrayInputStream(new byte[0]);
    }

    // ── MFT record access ─────────────────────────────────────────────────

    byte[] getMftRecord(long idx) throws IOException {
        // Check cache
        for (int i = 0; i < cacheIdx.length; i++) {
            if (cacheIdx[i] == idx && cacheData[i] != null) return cacheData[i];
        }

        long byteOffset = idx * (long) recSize;
        byte[] data = readFromRuns(mftRuns, byteOffset, recSize);
        applyFixup(data);
        if (data[0] != 'F' || data[1] != 'I' || data[2] != 'L' || data[3] != 'E') {
            throw new IOException("Invalid MFT record " + idx);
        }

        cacheIdx[cachePos] = idx;
        cacheData[cachePos] = data;
        cachePos = (cachePos + 1) % cacheIdx.length;
        return data;
    }

    // Read `length` bytes from a data-runs map starting at logical byte offset
    byte[] readFromRuns(long[] runs, long logicalOffset, int length) throws IOException {
        long current = 0;
        for (int i = 0; i + 1 < runs.length; i += 2) {
            long lcn = runs[i];
            long runLen = runs[i + 1];
            long runBytes = runLen * clusterSize;
            if (logicalOffset < current + runBytes) {
                long inRunOff = logicalOffset - current;
                long physOffset = lcn * (long) clusterSize + inRunOff;
                return readBytes(physOffset, length);
            }
            current += runBytes;
        }
        throw new IOException("Logical offset " + logicalOffset + " not in data runs");
    }

    // Read `len` bytes sequentially across data runs into a buffer (for DataRunStream)
    int readFromRunsInto(long[] runs, long logicalOffset, byte[] buf, int bufOff, int len)
            throws IOException {
        long current = 0;
        int written = 0;
        for (int i = 0; i + 1 < runs.length && written < len; i += 2) {
            long lcn = runs[i];
            long runClusters = runs[i + 1];
            long runBytes = runClusters * clusterSize;
            long runStart = lcn * (long) clusterSize;

            if (logicalOffset >= current + runBytes) {
                current += runBytes;
                continue;
            }

            long inRunOff = logicalOffset + written - current;
            if (inRunOff < 0) inRunOff = 0;
            long available = runBytes - inRunOff;
            int toRead = (int) Math.min(available, len - written);

            byte[] chunk = readBytes(runStart + inRunOff, toRead);
            System.arraycopy(chunk, 0, buf, bufOff + written, chunk.length);
            written += chunk.length;
            current += runBytes;
        }
        return written > 0 ? written : -1;
    }

    // ── Low-level block device reads ──────────────────────────────────────

    byte[] readBytes(long offset, int len) throws IOException {
        if (len <= 0) return new byte[0];
        // Round up to sector boundary
        int rounded = ((len + sectorSize - 1) / sectorSize) * sectorSize;
        ByteBuffer buf = ByteBuffer.allocate(rounded);
        dev.read(offset, buf);
        byte[] result = new byte[len];
        System.arraycopy(buf.array(), 0, result, 0, Math.min(len, buf.array().length));
        return result;
    }

    // ── NTFS structure helpers ────────────────────────────────────────────

    /** Apply update-sequence-array fixup to a FILE or INDX record. */
    void applyFixup(byte[] data) {
        if (data.length < 8) return;
        int usaOff = u16(data, 4);
        int usaCount = u16(data, 6);
        for (int i = 1; i < usaCount; i++) {
            int sectorEnd = i * sectorSize - 2;
            if (sectorEnd + 1 >= data.length || usaOff + i * 2 + 1 >= data.length) break;
            data[sectorEnd] = data[usaOff + i * 2];
            data[sectorEnd + 1] = data[usaOff + i * 2 + 1];
        }
    }

    /** Find a resident attribute's value bytes, or null if not found / non-resident. */
    byte[] findResidentAttr(byte[] rec, int startOff, int type) {
        int pos = startOff;
        while (pos + 8 <= rec.length) {
            int t = u32(rec, pos);
            if (t == 0xFFFFFFFF) break;
            int len = u32(rec, pos + 4);
            if (len < 8 || pos + len > rec.length) break;
            if (t == type && rec[pos + 8] == 0) { // resident
                int valLen = u32(rec, pos + 0x10);
                int valOff = u16(rec, pos + 0x14);
                byte[] val = new byte[valLen];
                int copyLen = Math.min(valLen, len - valOff);
                if (copyLen > 0) System.arraycopy(rec, pos + valOff, val, 0, copyLen);
                return val;
            }
            pos += len;
        }
        return null;
    }

    /** Get data runs for an attribute (may be non-resident). Returns null if not found. */
    long[] getDataRuns(byte[] rec, int startOff, int type) {
        int pos = startOff;
        while (pos + 8 <= rec.length) {
            int t = u32(rec, pos);
            if (t == 0xFFFFFFFF) break;
            int len = u32(rec, pos + 4);
            if (len < 8 || pos + len > rec.length) break;
            if (t == type) {
                if (rec[pos + 8] == 0) return null; // resident, no data runs
                int runsOff = u16(rec, pos + 0x20);
                return decodeDataRuns(rec, pos + runsOff);
            }
            pos += len;
        }
        return null;
    }

    /** Decode NTFS data run list starting at offset `pos` in `data`. */
    long[] decodeDataRuns(byte[] data, int pos) {
        List<Long> result = new ArrayList<>();
        long currentLcn = 0;
        while (pos < data.length) {
            int header = data[pos++] & 0xFF;
            if (header == 0) break;
            int lenLen = header & 0x0F;
            int offLen = (header >> 4) & 0x0F;
            if (lenLen == 0) break;

            long runLen = 0;
            for (int i = 0; i < lenLen && pos < data.length; i++)
                runLen |= ((long) (data[pos++] & 0xFF)) << (i * 8);

            long lcnDelta = 0;
            for (int i = 0; i < offLen && pos < data.length; i++)
                lcnDelta |= ((long) (data[pos++] & 0xFF)) << (i * 8);

            // Sign-extend lcnDelta
            if (offLen > 0 && pos > 0 && (data[pos - 1] & 0x80) != 0)
                lcnDelta |= -(1L << (offLen * 8));

            if (offLen > 0) currentLcn += lcnDelta; // sparse runs have offLen==0

            result.add(currentLcn);
            result.add(runLen);
        }
        long[] arr = new long[result.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = result.get(i);
        return arr;
    }

    // ── Byte helpers ──────────────────────────────────────────────────────

    static int u8(byte[] d, int o) { return d[o] & 0xFF; }

    static int u16(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    static int u32(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8)
                | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
    }

    static long u64(byte[] d, int o) {
        return (u32(d, o) & 0xFFFFFFFFL) | ((long) u32(d, o + 4) << 32);
    }
}
