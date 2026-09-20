package com.thumbviewer.ntfs;

public class NtfsEntry {
    public final long mftIndex;
    public final String name;
    public final long size;
    public final boolean isDirectory;

    public NtfsEntry(long mftIndex, String name, long size, boolean isDirectory) {
        this.mftIndex = mftIndex;
        this.name = name;
        this.size = size;
        this.isDirectory = isDirectory;
    }
}
