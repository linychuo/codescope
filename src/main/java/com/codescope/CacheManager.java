package com.codescope;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class CacheManager {

    private static final String CACHE_FILE = ".jdt-cache";
    private final Path cachePath;

    public CacheManager(Path rootDir) {
        this.cachePath = rootDir.resolve(CACHE_FILE);
    }

    public void save(Map<Path, Long> timestamps) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(Files.newOutputStream(cachePath))) {
            dos.writeInt(timestamps.size());
            for (Map.Entry<Path, Long> entry : timestamps.entrySet()) {
                dos.writeUTF(entry.getKey().toAbsolutePath().toString());
                dos.writeLong(entry.getValue());
            }
        }
    }

    public Map<Path, Long> load() throws IOException {
        Map<Path, Long> result = new HashMap<>();
        if (!Files.exists(cachePath)) return result;

        try (DataInputStream dis = new DataInputStream(Files.newInputStream(cachePath))) {
            int size = dis.readInt();
            for (int i = 0; i < size; i++) {
                String path = dis.readUTF();
                long modified = dis.readLong();
                result.put(Paths.get(path), modified);
            }
        } catch (EOFException e) {
            return new HashMap<>();
        }
        return result;
    }

    public boolean isValid(Map<Path, Long> cached) throws IOException {
        Map<Path, Long> onDisk = load();
        if (onDisk.size() != cached.size()) return false;

        for (Map.Entry<Path, Long> entry : cached.entrySet()) {
            Long cachedModified = entry.getValue();
            Long onDiskModified = onDisk.get(entry.getKey());
            if (!cachedModified.equals(onDiskModified)) {
                return false;
            }
        }
        return true;
    }
}