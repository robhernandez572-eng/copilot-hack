package com.thumbviewer.ntfs;

import me.jahnen.libaums.core.fs.UsbFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Read-only UsbFile implementation backed by an NTFS record.
 * Write operations throw UnsupportedOperationException.
 */
public class NtfsFile implements UsbFile {

    private final NtfsReader reader;
    private final long mftIdx;
    private final String fileName;
    private final long fileSize;
    private final boolean directory;
    private final NtfsFile parentFile;

    // Root constructor
    public NtfsFile(NtfsReader reader) {
        this.reader = reader;
        this.mftIdx = 5; // NTFS root directory
        this.fileName = "/";
        this.fileSize = 0;
        this.directory = true;
        this.parentFile = null;
    }

    // Regular constructor
    NtfsFile(NtfsReader reader, NtfsFile parent, NtfsEntry entry) {
        this.reader = reader;
        this.mftIdx = entry.mftIndex;
        this.fileName = entry.name;
        this.fileSize = entry.size;
        this.directory = entry.isDirectory;
        this.parentFile = parent;
    }

    @Override public boolean isDirectory() { return directory; }
    @Override public boolean isRoot() { return parentFile == null; }

    @Override
    public String getName() { return fileName; }

    @Override
    public void setName(String n) { throw new UnsupportedOperationException(); }

    @Override
    public String getAbsolutePath() {
        if (parentFile == null) return "/";
        String pp = parentFile.getAbsolutePath();
        return pp.equals("/") ? "/" + fileName : pp + "/" + fileName;
    }

    @Override public UsbFile getParent() { return parentFile; }

    @Override public long getLength() { return fileSize; }
    @Override public void setLength(long l) { throw new UnsupportedOperationException(); }

    @Override
    public UsbFile search(String path) throws IOException {
        if (!directory) return null;
        String[] parts = path.split("/", 2);
        String target = parts[0];
        for (UsbFile child : listFiles()) {
            if (child.getName().equalsIgnoreCase(target)) {
                if (parts.length == 1) return child;
                if (child.isDirectory()) return child.search(parts[1]);
            }
        }
        return null;
    }

    @Override public long createdAt() { return 0; }
    @Override public long lastModified() { return 0; }
    @Override public long lastAccessed() { return 0; }

    @Override
    public String[] list() throws IOException {
        UsbFile[] files = listFiles();
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) names[i] = files[i].getName();
        return names;
    }

    @Override
    public UsbFile[] listFiles() throws IOException {
        if (!directory) throw new IOException("Not a directory");
        List<NtfsEntry> entries = reader.listDirectory(mftIdx);
        entries.sort((a, b) -> {
            if (a.isDirectory != b.isDirectory) return a.isDirectory ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        UsbFile[] result = new UsbFile[entries.size()];
        for (int i = 0; i < entries.size(); i++)
            result[i] = new NtfsFile(reader, this, entries.get(i));
        return result;
    }

    @Override
    public void read(long offset, ByteBuffer dest) throws IOException {
        if (directory) throw new IOException("Is a directory");
        try (InputStream is = reader.openFile(mftIdx)) {
            long skipped = 0;
            while (skipped < offset) {
                long n = is.skip(offset - skipped);
                if (n <= 0) break;
                skipped += n;
            }
            byte[] tmp = new byte[dest.remaining()];
            int read = 0;
            while (read < tmp.length) {
                int n = is.read(tmp, read, tmp.length - read);
                if (n < 0) break;
                read += n;
            }
            dest.put(tmp, 0, read);
        }
    }

    // Return a fresh InputStream for image loading
    public InputStream openStream() throws IOException {
        return reader.openFile(mftIdx);
    }

    @Override public void write(long off, ByteBuffer src) { throw new UnsupportedOperationException(); }
    @Override public void flush() { }
    @Override public void close() { }
    @Override public UsbFile createDirectory(String n) { throw new UnsupportedOperationException(); }
    @Override public UsbFile createFile(String n) { throw new UnsupportedOperationException(); }
    @Override public void moveTo(UsbFile dest) { throw new UnsupportedOperationException(); }
    @Override public void delete() { throw new UnsupportedOperationException(); }
}
