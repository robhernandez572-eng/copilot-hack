package com.thumbviewer.ntfs;

import java.io.IOException;
import java.io.InputStream;

/** Sequential InputStream over NTFS data runs. */
public class DataRunStream extends InputStream {
    private final NtfsReader reader;
    private final long[] runs;
    private final long realSize;
    private long position = 0;

    // Read buffer to avoid per-byte USB calls
    private byte[] buf = new byte[0];
    private int bufStart = 0;
    private int bufEnd = 0;
    private static final int CHUNK = 65536; // 64 KB per read

    DataRunStream(NtfsReader reader, long[] runs, long realSize) {
        this.reader = reader;
        this.runs = runs;
        this.realSize = realSize;
    }

    @Override
    public int read() throws IOException {
        byte[] b = new byte[1];
        int n = read(b, 0, 1);
        return n < 0 ? -1 : b[0] & 0xFF;
    }

    @Override
    public int read(byte[] out, int off, int len) throws IOException {
        if (position >= realSize) return -1;
        long available = realSize - position;
        int toRead = (int) Math.min(len, available);

        // Refill buffer if needed
        if (bufStart >= bufEnd) {
            int chunkSize = (int) Math.min(CHUNK, realSize - position);
            if (chunkSize <= 0) return -1;
            buf = new byte[chunkSize];
            int n = reader.readFromRunsInto(runs, position, buf, 0, chunkSize);
            if (n <= 0) return -1;
            bufStart = 0;
            bufEnd = n;
        }

        int available2 = bufEnd - bufStart;
        int copy = Math.min(toRead, available2);
        System.arraycopy(buf, bufStart, out, off, copy);
        bufStart += copy;
        position += copy;
        return copy;
    }

    @Override
    public void close() { /* nothing to close */ }
}
