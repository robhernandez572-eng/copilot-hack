package com.thumbviewer.ntfs;

import me.jahnen.libaums.core.driver.BlockDeviceDriver;
import me.jahnen.libaums.core.fs.FileSystem;
import me.jahnen.libaums.core.fs.FileSystemCreator;
import me.jahnen.libaums.core.partition.PartitionTableEntry;

import java.io.IOException;

/** Registers with libaums FileSystemFactory to handle NTFS partitions. */
public class NtfsFileSystemCreator implements FileSystemCreator {
    @Override
    public FileSystem read(PartitionTableEntry entry, BlockDeviceDriver blockDevice)
            throws IOException {
        NtfsReader reader = new NtfsReader(blockDevice);
        if (!reader.mount()) return null;
        return new NtfsFileSystem(reader);
    }
}
