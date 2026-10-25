package android.os;
import java.util.concurrent.ConcurrentLinkedQueue;
public final class Handler {
 private static final ConcurrentLinkedQueue<Runnable> posted=new ConcurrentLinkedQueue<>(),delayed=new ConcurrentLinkedQueue<>();
 public Handler(Looper looper){}public boolean post(Runnable work){posted.add(work);return true;}public boolean postDelayed(Runnable work,long delay){delayed.add(work);return true;}
 public void removeCallbacks(Runnable work){while(posted.remove(work)){}while(delayed.remove(work)){}}
 public static void drain(){Runnable work;while((work=posted.poll())!=null)work.run();}
 public static void drainDelayed(){Runnable work;while((work=delayed.poll())!=null)work.run();}
 public static void clear(){posted.clear();delayed.clear();}
}
