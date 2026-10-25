package cn.ngapure.app;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real local filesystem cache contracts. No Android decoding, account or network. */
public final class NativeMediaCacheTest {
    private static int assertions;
    private static final List<File> roots = new ArrayList<>();
    private static final String A = "https://img.nga.cn/attachments/a.png?size=normal";
    private static final String B = "https://img.nga.cn/attachments/b.png";
    private static final String C = "https://img.nga.cn/attachments/c.png";
    private static void check(boolean value,String note){assertions++;if(!value)throw new AssertionError(note);}
    private static File root()throws Exception {File parent=Files.createTempDirectory("ngapure-media-cache-test-").toFile();roots.add(parent);return new File(parent,"cache");}
    private static void delete(File file){File[] children=Files.isSymbolicLink(file.toPath())?null:file.listFiles();if(children!=null)for(File child:children)delete(child);if(!file.delete()&&Files.exists(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new AssertionError("fixture cleanup");}
    private static byte[] bytes(int count,int marker){byte[] value=new byte[count];Arrays.fill(value,(byte)marker);return value;}
    public static void main(String[] args)throws Exception {
        try {roundTripAndReopen();boundsAndLru();invalidAndExpired();ioAndInterrupt();concurrentAtomicWrites();metadataNeverWaitsForDisk();sharedDirectoryEviction();statsAndSelectiveClear();linksNeverFollowed();generationRevokesOtherOwners();failedDeletionRemainsVisible();}
        finally {for(File file:roots)delete(file);}
        System.out.println("PASS original media disk cache: 11 scenarios, "+assertions+" checks; temporary app-private analogue only; no network/account");
    }
    private static void roundTripAndReopen()throws Exception {
        File directory=root();NativeMediaCache cache=new NativeMediaCache(directory);byte[] picture=bytes(83,3);
        check(!directory.exists(),"configuration creates no directory on calling thread");check(!cache.available(A),"uncached not releasable");
        check(cache.put(A,picture),"verified response stored");check(cache.available(A),"successful atomic storage enables local reload");
        check(Arrays.equals(cache.read(A),picture),"exact original bytes");File[] files=directory.listFiles();check(files!=null&&files.length==1,"one finished cache entry");
        check(files[0].getName().matches("m-[0-9a-f]{64}\\.bin"),"disk name contains hash only");check(!new String(Files.readAllBytes(files[0].toPath()),java.nio.charset.StandardCharsets.ISO_8859_1).contains(A),"disk payload contains no request URL");
        NativeMediaCache reopened=new NativeMediaCache(directory);check(reopened.available(A),"same-process owners share completed local-byte metadata");
        check(Arrays.equals(reopened.read(A),picture)&&reopened.available(A),"previous process local bytes reusable after verification");
        reopened.remove(A);check(!reopened.available(A)&&reopened.read(A)==null,"invalidating clears release metadata and bytes");
    }
    private static void boundsAndLru()throws Exception {
        File directory=root();NativeMediaCache cache=new NativeMediaCache(directory,128,2,Long.MAX_VALUE);byte[] picture=bytes(24,4);
        check(!cache.put(A,new byte[0]),"empty never stored");check(!cache.put(A,bytes(129,1)),"entry exceeding disk budget rejected");check(!cache.available(A),"failed write never enables release");
        check(cache.put(A,picture)&&cache.put(B,picture),"two entries fit");File oldest=null;for(File file:directory.listFiles())if(file.isFile()){file.setLastModified(1);oldest=file;break;}
        check(oldest!=null,"oldest entry exists");check(cache.put(C,picture),"new entry replaces oldest");check(directory.listFiles().length==2,"entry limit retained");
        long total=0;for(File file:directory.listFiles())total+=file.length();check(total<=128,"byte budget includes headers");check(Arrays.equals(cache.read(C),picture),"newest bytes survive trim");
        check(!oldest.exists(),"least recently used file removed");check(!(cache.available(A)&&cache.available(B)),"eviction removes release qualification");
        NativeMediaCache onlyOne=new NativeMediaCache(root(),56,8,Long.MAX_VALUE);check(onlyOne.put(A,picture)&&onlyOne.put(B,picture),"single-entry byte budget works");check(!onlyOne.available(A)&&onlyOne.available(B),"byte eviction reflected in metadata");
    }
    private static void invalidAndExpired()throws Exception {
        File directory=root();NativeMediaCache cache=new NativeMediaCache(directory,1024,8,1000);byte[] picture=bytes(4,2);
        check(cache.put(A,picture),"expiry fixture");File file=directory.listFiles()[0];try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.seek(8);out.writeLong(System.currentTimeMillis()-2000);}
        check(cache.read(A)==null&&!cache.available(A)&&!file.exists(),"expired creation timestamp removed rather than extended by access");
        check(cache.put(A,picture),"corruption fixture");file=directory.listFiles()[0];try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.seek(0);out.writeByte(0);}
        check(cache.read(A)==null&&!cache.available(A),"unknown header invalidates");
        check(cache.put(A,picture),"future fixture");file=directory.listFiles()[0];try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.seek(8);out.writeLong(System.currentTimeMillis()+48L*60*60*1000);}
        check(cache.read(A)==null&&!cache.available(A),"wildly future timestamp invalidates");
        check(cache.put(A,picture),"length fixture");file=directory.listFiles()[0];try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.setLength(20L*1024*1024+17);}
        check(cache.read(A)==null&&!file.exists(),"oversized original rejected before allocating");
    }
    private static void ioAndInterrupt()throws Exception {
        File bad=root();check(bad.createNewFile(),"unwritable-directory fixture");NativeMediaCache cache=new NativeMediaCache(bad);check(!cache.put(A,bytes(8,1))&&!cache.available(A),"cache unavailable does not throw or grant release");
        cache=new NativeMediaCache(root());check(cache.put(A,bytes(8,1)),"interrupt fixture");Thread.currentThread().interrupt();try{check(cache.read(A)==null,"interrupted read does not start");check(!cache.put(B,bytes(8,1)),"interrupted write does not start");check(Thread.currentThread().isInterrupted(),"interrupt flag retained");}finally{Thread.interrupted();}
        check(cache.read(A)!=null,"interrupted read did not corrupt completed data");
    }
    private static void concurrentAtomicWrites()throws Exception {
        File directory=root();NativeMediaCache first=new NativeMediaCache(directory),second=new NativeMediaCache(directory);CountDownLatch start=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        Runnable action=()->{try{start.await();for(int round=0;round<30;round++){NativeMediaCache cache=Thread.currentThread().getName().equals("first")?first:second;byte[] picture=bytes(8192,round);if(!cache.put(A,picture))throw new AssertionError("concurrent write");byte[] read=cache.read(A);if(read==null||read.length!=8192)throw new AssertionError("atomic full payload");byte marker=read[0];for(byte value:read)if(value!=marker)throw new AssertionError("mixed payload");}}catch(Throwable error){failure.compareAndSet(null,error);}};
        Thread a=new Thread(action,"first"),b=new Thread(action,"second");a.start();b.start();start.countDown();a.join(5000);b.join(5000);check(!a.isAlive()&&!b.isAlive(),"concurrent workers finish");check(failure.get()==null,"atomic replacement exposes only a complete verified byte array");
        check(directory.listFiles().length==1,"temporary files removed after writes");
    }
    private static void sharedDirectoryEviction()throws Exception {
        File directory=root();NativeMediaCache first=new NativeMediaCache(directory,56,8,Long.MAX_VALUE),second=new NativeMediaCache(directory,56,8,Long.MAX_VALUE);byte[] picture=bytes(24,2);
        check(first.put(A,picture)&&first.available(A)&&second.available(A),"same directory local-copy metadata shared");
        File orphan=new File(directory,".pending-crashed.tmp");check(orphan.createNewFile(),"recent orphan fixture");
        check(second.put(B,picture),"second owner replacement succeeds");
        check(!first.available(A)&&!second.available(A)&&first.available(B),"other owner eviction revokes old release qualification");
        check(!orphan.exists(),"orphan temp removed even if less than one hour old");
        second.remove(B);check(!first.available(B),"other owner invalidation revokes shared qualification");
    }
    private static void metadataNeverWaitsForDisk()throws Exception {
        NativeMediaCache cache=new NativeMediaCache(root());check(cache.put(A,bytes(8,1)),"metadata fixture");java.lang.reflect.Field lockField=NativeMediaCache.class.getDeclaredField("FILES");lockField.setAccessible(true);Object lock=lockField.get(null);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);Thread disk=new Thread(()->{synchronized(lock){entered.countDown();try{release.await();}catch(InterruptedException cancelled){Thread.currentThread().interrupt();}}});disk.start();check(entered.await(2,TimeUnit.SECONDS),"simulated disk lock held");
        CountDownLatch done=new CountDownLatch(1);Thread scrolling=new Thread(()->{if(cache.available(A))done.countDown();});scrolling.start();boolean independent=done.await(1,TimeUnit.SECONDS);release.countDown();disk.join(2000);scrolling.join(2000);check(independent,"scrolling metadata does not contend with disk I/O lock");
    }
    private static void statsAndSelectiveClear()throws Exception {
        File directory=root();NativeMediaCache cache=new NativeMediaCache(directory);
        NativeMediaCache.Stats empty=cache.stats();check(empty.accessible&&empty.bytes==0&&empty.entries==0&&!directory.exists(),"missing cache is empty without creating directory");
        check(cache.put(A,bytes(83,1)),"stats fixture");
        File unknown=new File(directory,"credentials.json"),nested=new File(directory,"m-"+new String(new char[64]).replace('\0','a')+".bin");
        Files.write(unknown.toPath(),bytes(17,2));check(nested.mkdir(),"cache-shaped directory fixture");Files.write(new File(nested,"keep.txt").toPath(),bytes(2,2));
        File pending=new File(directory,".pending-crashed.tmp");Files.write(pending.toPath(),bytes(7,3));
        NativeMediaCache.Stats before=cache.stats();check(before.accessible&&before.bytes==106&&before.entries==2,"stats include headers and own pending bytes only");
        NativeMediaCache.ClearResult result=cache.clear();check(result.removedBytes==106&&result.removedEntries==2&&result.failedEntries==0,"clear reports actual regular files and bytes");
        check(result.remaining.accessible&&result.remaining.bytes==0&&result.remaining.entries==0,"clear remaining reflects physical directory");
        check(unknown.exists()&&new File(nested,"keep.txt").exists(),"unknown files and nested directories untouched");
        check(!cache.available(A)&&cache.read(A)==null,"clear revokes local reload metadata");
        NativeMediaCache.ClearResult again=cache.clear();check(again.removedEntries==0&&again.remaining.bytes==0,"repeated clear is harmless");
    }
    private static void linksNeverFollowed()throws Exception {
        File directory=root();NativeMediaCache cache=new NativeMediaCache(directory);check(cache.put(A,bytes(9,4)),"symlink fixture");File entry=directory.listFiles()[0];check(entry.delete(),"replace cache entry by a link");
        File external=new File(directory.getParentFile(),"outside.bin");Files.write(external.toPath(),bytes(205,5));Files.createSymbolicLink(entry.toPath(),external.toPath());
        check(cache.stats().bytes==0&&cache.stats().entries==0,"cache entry link is not counted");
        check(cache.read(A)==null&&!cache.available(A),"cache read never opens linked content");
        check(!cache.put(A,bytes(4,6)),"write never replaces a linked cache path");
        NativeMediaCache.ClearResult result=cache.clear();check(result.removedEntries==0&&external.length()==205&&Files.isSymbolicLink(entry.toPath()),"clear neither follows nor removes cache entry link");
        File rootLink=root();Files.createSymbolicLink(rootLink.toPath(),directory.toPath());NativeMediaCache linked=new NativeMediaCache(rootLink);
        check(!linked.stats().accessible&&!linked.clear().remaining.accessible,"linked cache directory rejected");
        check(!linked.put(B,bytes(4,1))&&linked.read(A)==null,"linked root never used for storage or reading");
        check(external.length()==205&&Files.isSymbolicLink(entry.toPath()),"outside content and link survive root operations");
    }
    private static void generationRevokesOtherOwners()throws Exception {
        File directory=root();NativeMediaCache first=new NativeMediaCache(directory),second=new NativeMediaCache(directory);byte[] picture=bytes(12,2);
        check(first.put(A,picture)&&second.available(A),"shared clear fixture");long old=second.generation();
        NativeMediaCache.ClearResult result=first.clear();check(result.removedEntries==1&&!first.available(A)&&!second.available(A),"clear immediately revokes both owners");
        check(!second.isCurrent(old)&&!second.put(B,picture,old),"delayed pre-clear writer cannot restore removed bytes");
        check(second.stats().entries==0&&second.put(C,picture,second.generation()),"new request generation can cache normally");
        check(first.available(C)&&first.read(C)!=null,"new cache shared and readable after clear");
        second.remove(C,old);check(first.available(C)&&first.read(C)!=null,"older corrupt decode cannot delete a fresh generation entry");
        long current=first.generation();second.clear();check(!first.put(A,picture,current)&&!first.available(C),"repeated other-owner clear also invalidates generation");
    }
    private static void failedDeletionRemainsVisible()throws Exception {
        File directory=root();NativeMediaCache cache=new NativeMediaCache(directory);check(cache.put(A,bytes(11,2)),"read-only clear fixture");
        java.util.Set<java.nio.file.attribute.PosixFilePermission> before=Files.getPosixFilePermissions(directory.toPath());
        try {
            Files.setPosixFilePermissions(directory.toPath(),java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
            NativeMediaCache.ClearResult result=cache.clear();
            check(result.removedEntries==0&&result.removedBytes==0&&result.failedEntries==1,"failed physical deletion is reported");
            check(result.remaining.accessible&&result.remaining.entries==1&&result.remaining.bytes==27,"failure snapshot reports actual remaining bytes");
            check(!cache.available(A),"even failed clear revokes old view-retirement qualification");
        } finally {Files.setPosixFilePermissions(directory.toPath(),before);}
        check(cache.clear().removedEntries==1&&cache.stats().entries==0,"clear can retry after storage becomes writable");
    }
}
