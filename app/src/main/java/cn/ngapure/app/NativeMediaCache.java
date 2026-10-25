package cn.ngapure.app;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.Channels;

/** Bounded, disposable original image bytes. Access only from media workers, except available(). */
final class NativeMediaCache {
    private static final long MAGIC = 0x4e47414d45444941L;
    private static final int HEADER_BYTES = 16, MAX_ITEM_BYTES = 20 * 1024 * 1024;
    private static final Object FILES = new Object();
    private final File directory;
    private final long budget, maxAge;
    private final int maxEntries;
    private static final Map<String,WeakReference<Metadata>> REGISTRY = new HashMap<>();
    private static final class Metadata {
        final Set<String> available = Collections.newSetFromMap(new ConcurrentHashMap<>());
        final Map<String,String> addresses = new HashMap<>();
        volatile long generation;
    }
    private final Metadata metadata;

    static final class Stats {
        final long bytes; final int entries; final boolean accessible;
        Stats(long bytes,int entries,boolean accessible){this.bytes=bytes;this.entries=entries;this.accessible=accessible;}
    }
    static final class ClearResult {
        final long removedBytes; final int removedEntries,failedEntries; final Stats remaining;
        ClearResult(long bytes,int entries,int failed,Stats remaining){removedBytes=bytes;removedEntries=entries;failedEntries=failed;this.remaining=remaining;}
    }

    NativeMediaCache(File directory) { this(directory, 32L * 1024 * 1024, 128, 7L * 24 * 60 * 60 * 1000); }
    NativeMediaCache(File directory, long budget, int maxEntries, long maxAge) {
        this.directory = directory; this.budget = Math.max(HEADER_BYTES, budget);
        this.maxEntries = Math.max(1, maxEntries); this.maxAge = Math.max(0, maxAge);
        String path=directory.getAbsoluteFile().toPath().normalize().toString();
        synchronized(REGISTRY) {
            WeakReference<Metadata> known=REGISTRY.get(path); Metadata shared=known==null?null:known.get();
            if(shared==null) { shared=new Metadata(); REGISTRY.put(path,new WeakReference<>(shared)); }
            metadata=shared;
            if(REGISTRY.size()>16)REGISTRY.entrySet().removeIf(entry->entry.getValue().get()==null);
        }
    }
    boolean available(String address) { return metadata.available.contains(address); }
    long generation() { return metadata.generation; }
    boolean isCurrent(long expected) { return metadata.generation==expected; }
    /** Worker-only snapshot. Never descends into directories or follows cache-entry links. */
    Stats stats() { synchronized(FILES) { return statsLocked(); } }
    /** Invalidates every owner before removing only this cache's regular files. */
    ClearResult clear() {
        synchronized(FILES) {
            metadata.generation++; metadata.available.clear(); metadata.addresses.clear();
            if(!safeDirectory(false))return new ClearResult(0,0,0,new Stats(0,0,!existsWithoutFollowing(directory)));
            File[] files=directory.listFiles();
            if(files==null)return new ClearResult(0,0,0,new Stats(0,0,false));
            long bytes=0;int entries=0,failed=0;
            for(File file:files) {
                if(!ownedName(file.getName())||!regular(file))continue;
                long length=file.length();
                try { if(Files.deleteIfExists(file.toPath())){bytes+=length;entries++;} }
                catch(IOException|SecurityException unavailable){failed++;}
            }
            return new ClearResult(bytes,entries,failed,statsLocked());
        }
    }
    private Stats statsLocked() {
        if(!safeDirectory(false))return new Stats(0,0,!existsWithoutFollowing(directory));
        File[] files=directory.listFiles();if(files==null)return new Stats(0,0,false);
        long bytes=0;int entries=0;
        for(File file:files)if(ownedName(file.getName())&&regular(file)){bytes+=file.length();entries++;}
        return new Stats(bytes,entries,true);
    }
    private static boolean ownedName(String name){return name.matches("m-[0-9a-f]{64}\\.bin")||name.matches("\\.pending-[A-Za-z0-9-]+\\.tmp");}
    private static boolean regular(File file){return Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS);}
    private static boolean existsWithoutFollowing(File file){return Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS);}
    private boolean safeDirectory(boolean create) {
        try {
            if(Files.isSymbolicLink(directory.toPath()))return false;
            if(create&&!existsWithoutFollowing(directory)&&!directory.mkdirs())return false;
            return Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS);
        }catch(SecurityException unavailable){return false;}
    }
    byte[] read(String address) {
        synchronized (FILES) {
            String key = name(address); File file = new File(directory, key);
            if (Thread.currentThread().isInterrupted()) return null;
            if(!safeDirectory(false)||!regular(file)){forget(key);return null;}
            long length = file.length(), now = System.currentTimeMillis();
            if (length <= HEADER_BYTES || length > MAX_ITEM_BYTES + HEADER_BYTES) { discard(file, key); return null; }
            try (DataInputStream input = new DataInputStream(Channels.newInputStream(Files.newByteChannel(file.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)))) {
                if (input.readLong() != MAGIC) { discard(file, key); return null; }
                long created = input.readLong();
                if (created > now + 24L * 60 * 60 * 1000 || now - created > maxAge) { discard(file, key); return null; }
                byte[] bytes = readPayload(input, (int) length - HEADER_BYTES);
                if (bytes == null) return null;
                metadata.available.add(address); metadata.addresses.put(key,address); file.setLastModified(now); return bytes;
            } catch (IOException | SecurityException unavailableFile) { discard(file, key); return null; }
        }
    }
    /** The file length is already bounded. Read into its one result array, retaining chunk cancellation. */
    private static byte[] readPayload(InputStream input, int length) throws IOException {
        if (length <= 0 || length > MAX_ITEM_BYTES) throw new IOException("无效图片缓存长度");
        if (Thread.currentThread().isInterrupted()) return null;
        byte[] bytes = new byte[length]; int offset = 0;
        while (offset < bytes.length) {
            if (Thread.currentThread().isInterrupted()) return null;
            int count = input.read(bytes, offset, Math.min(16384, bytes.length - offset));
            if (Thread.currentThread().isInterrupted()) return null;
            if (count < 0) throw new EOFException("图片缓存不完整");
            if (count == 0) throw new IOException("图片缓存读取中断");
            offset += count;
        }
        if (Thread.currentThread().isInterrupted()) return null;
        int extra = input.read();
        if (Thread.currentThread().isInterrupted()) return null;
        if (extra != -1) throw new IOException("图片缓存长度已改变");
        return bytes;
    }
    boolean put(String address, byte[] bytes) {
        return put(address,bytes,generation());
    }
    boolean put(String address,byte[] bytes,long expectedGeneration) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_ITEM_BYTES
                || bytes.length + HEADER_BYTES > budget || Thread.currentThread().isInterrupted()) return false;
        synchronized (FILES) {
            if(!isCurrent(expectedGeneration))return false;
            String key = name(address); File target = new File(directory, key), temporary = null;
            try {
                if(!safeDirectory(true))return false;
                if(existsWithoutFollowing(target)&&!regular(target))return false;
                temporary = File.createTempFile(".pending-", ".tmp", directory);
                try (DataOutputStream output = new DataOutputStream(new FileOutputStream(temporary))) {
                    output.writeLong(MAGIC); output.writeLong(System.currentTimeMillis()); output.write(bytes);
                }
                if (Thread.currentThread().isInterrupted()) return false;
                if (!temporary.renameTo(target)) return false;
                target.setLastModified(System.currentTimeMillis()); metadata.available.add(address); metadata.addresses.put(key,address); trim();
                return metadata.available.contains(address);
            } catch (IOException | SecurityException unavailableFile) { return false; }
            finally { if (temporary != null && temporary.exists()) temporary.delete(); }
        }
    }
    void remove(String address) {
        remove(address,generation());
    }
    void remove(String address,long expectedGeneration) {
        synchronized (FILES) { if(!isCurrent(expectedGeneration))return;String key = name(address);if(safeDirectory(false))discard(new File(directory,key),key);else forget(key); }
    }
    private void trim() {
        File[] files = directory.listFiles(); if (files == null) return;
        List<File> entries = new ArrayList<>(); long bytes = 0;
        for (File file : files) {
            String key = file.getName();
            if(!regular(file))continue;
            if (key.matches("\\.pending-[A-Za-z0-9-]+\\.tmp")) { file.delete(); continue; }
            if (!key.matches("m-[0-9a-f]{64}\\.bin")) continue;
            if (file.length() <= HEADER_BYTES || file.length() > MAX_ITEM_BYTES + HEADER_BYTES) { discard(file, key); continue; }
            entries.add(file); bytes += file.length();
        }
        entries.sort(Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
        int remaining = entries.size();
        for (File file : entries) {
            if (bytes <= budget && remaining <= maxEntries) break;
            bytes -= file.length(); remaining--; discard(file, file.getName());
        }
    }
    private void forget(String key){String address=metadata.addresses.remove(key);if(address!=null)metadata.available.remove(address);}
    private void discard(File file, String key) { forget(key);if(regular(file))file.delete(); }
    private static String name(String address) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(address.getBytes(StandardCharsets.UTF_8));
            char[] hex = "0123456789abcdef".toCharArray(), value = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) { value[i * 2] = hex[(digest[i] & 255) >>> 4]; value[i * 2 + 1] = hex[digest[i] & 15]; }
            return "m-" + new String(value) + ".bin";
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
