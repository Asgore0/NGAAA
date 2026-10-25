package cn.ngapure.app;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Exact file-payload read boundaries, including growth/truncation after the length snapshot. */
public final class NativeMediaPayloadTest {
    private static final int MAXIMUM = 20 * 1024 * 1024;
    private static int assertions;
    private static final Method READ;
    static {
        try { READ = NativeMediaCache.class.getDeclaredMethod("readPayload", InputStream.class, int.class); READ.setAccessible(true); }
        catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }
    private static final class Stream extends InputStream {
        int remaining, calls, scalarReads, maximumRequested, interruptAt, chunkLimit = Integer.MAX_VALUE;
        boolean extra, interruptEof, zero;
        byte[] destination;
        Stream(int length) { remaining = length; }
        @Override public int read(byte[] bytes, int offset, int count) {
            calls++; maximumRequested = Math.max(maximumRequested, count);
            if (destination == null) destination = bytes;
            check(destination == bytes, "every chunk uses one result array");
            if (interruptAt == calls) Thread.currentThread().interrupt();
            if (zero) return 0;
            if (remaining <= 0) return -1;
            int received = Math.min(Math.min(remaining, count), chunkLimit);
            Arrays.fill(bytes, offset, offset + received, (byte) 37); remaining -= received; return received;
        }
        @Override public int read() {
            scalarReads++;
            if (interruptEof) Thread.currentThread().interrupt();
            return extra ? 1 : -1;
        }
    }
    private static void check(boolean condition, String label) {
        assertions++; if (!condition) throw new AssertionError(label);
    }
    private static byte[] read(Stream stream, int length) throws Exception {
        try { return (byte[]) READ.invoke(null, stream, length); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw error;
        }
    }
    private static void failure(Stream stream, int length, boolean eof) throws Exception {
        try { read(stream, length); throw new AssertionError("Expected malformed payload rejection"); }
        catch (IOException error) { check(!eof || error instanceof EOFException, "early EOF remains an explicit truncation"); }
    }
    private static void exact(int length, int chunk) throws Exception {
        Stream stream = new Stream(length); stream.chunkLimit = chunk;
        byte[] result = read(stream, length);
        check(result == stream.destination && result.length == length, "no second result allocation or copy");
        check(stream.maximumRequested <= 16384 && stream.scalarReads == 1, "chunk cancellation and one extra-byte check retained");
        for (byte value : result) if (value != 37) throw new AssertionError("Payload bytes changed");
        check(stream.remaining == 0, "all expected bytes consumed");
    }
    private static void interrupted(Stream stream, int length, boolean before) throws Exception {
        if (before) Thread.currentThread().interrupt();
        try {
            check(read(stream, length) == null, "interruption discards partial result");
            check(Thread.currentThread().isInterrupted(), "interrupt status retained for owner cancellation");
            if (before) check(stream.calls == 0 && stream.scalarReads == 0, "already cancelled read touches no stream");
        } finally { Thread.interrupted(); }
    }
    public static void main(String[] args) throws Exception {
        exact(1, 1); exact(16384, 16384); exact(16385, 17); exact(MAXIMUM, 16384);
        failure(new Stream(0), 1, true); failure(new Stream(17000), 18000, true);
        Stream extra = new Stream(12); extra.extra = true; failure(extra, 12, false);
        Stream empty = new Stream(0); failure(empty, 0, false); check(empty.calls == 0, "empty payload rejected before allocating/reading");
        Stream tooLarge = new Stream(MAXIMUM + 1); failure(tooLarge, MAXIMUM + 1, false); check(tooLarge.calls == 0, "oversize rejected before allocating/reading");
        Stream stopped = new Stream(10); stopped.zero = true; failure(stopped, 10, false); check(stopped.calls == 1, "nonprogressing stream cannot spin");
        interrupted(new Stream(20000), 20000, true);
        Stream during = new Stream(20000); during.interruptAt = 2; interrupted(during, 20000, false);
        check(during.calls == 2 && during.scalarReads == 0, "interruption ends at current chunk");
        Stream eof = new Stream(12); eof.interruptEof = true; interrupted(eof, 12, false);
        System.out.println("PASS single-array media payload: " + assertions + " checks; exact result-array identity, 20MiB bound, EOF/growth and cancellation; no network/account");
    }
}
