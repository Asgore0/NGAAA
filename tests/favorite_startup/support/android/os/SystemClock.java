package android.os;
public final class SystemClock {
 private static volatile long offset;
 private SystemClock(){}
 public static long elapsedRealtime(){return System.nanoTime()/1_000_000L+offset;}
 public static void advance(long ms){offset+=ms;}
 public static void reset(){offset=0;}
}
