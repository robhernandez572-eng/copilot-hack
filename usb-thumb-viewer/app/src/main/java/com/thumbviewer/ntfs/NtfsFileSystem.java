package com.thumbviewer.ntfs;

import me.jahnen.libaums.core.fs.FileSystem;
import me.jahnen.libaums.core.fs.UsbFile;
import me.jahnen.libaums.core.partition.PartitionTypes;

public class NtfsFileSystem implements FileSystem {
    private final NtfsReader reader;
    private final NtfsFile root;

    public NtfsFileSystem(NtfsReader reader) {
        this.reader = reader;
        this.root = new NtfsFile(reader);
    }

    public NtfsReader getReader() { return reader; }

    @Override public UsbFile getRootDirectory() { return root; }
    @Override public String getVolumeLabel() { return reader.volumeLabel; }
    @Override public long getCapacity() { return reader.capacity; }
    @Override public long getFreeSpace() { return reader.freeSpace; }
    @Override public long getOccupiedSpace() { return reader.capacity - reader.freeSpace; }
    @Override public int getChunkSize() { return reader.clusterSize > 0 ? reader.clusterSize : 4096; }
    @Override public int getType() { return PartitionTypes.NTFS_EXFAT; }
}
