package android.content;
import android.app.NotificationManager;import android.app.job.JobScheduler;import java.util.HashMap;import java.util.Map;import java.util.concurrent.atomic.AtomicInteger;import java.util.concurrent.atomic.AtomicReference;import java.lang.reflect.Proxy;
/** JVM framework boundary; every scheduler/preference/notice call records its caller thread. */
public class Context {
 public static final String JOB_SCHEDULER_SERVICE="jobs";public static final int MODE_PRIVATE=0;
 public static final AtomicInteger callerWork=new AtomicInteger();public static volatile Thread caller;
 public static final AtomicReference<Runnable> beforePreferences=new AtomicReference<>();public volatile int permission=0;
 public final JobScheduler jobs=new JobScheduler();public final NotificationManager notifications=new NotificationManager();public Context application=this;
 private final Map<String,Object> values=new HashMap<>();
 public static void touch(){if(Thread.currentThread()==caller)callerWork.incrementAndGet();}
 public Context getApplicationContext(){return application;}
 public Object getSystemService(String name){touch();return jobs;}
 public <T>T getSystemService(Class<T> type){touch();return type.cast(type==JobScheduler.class?jobs:notifications);}
 public int checkSelfPermission(String requested){touch();return permission;}
 public synchronized SharedPreferences getSharedPreferences(String name,int mode){touch();Runnable hook=beforePreferences.getAndSet(null);if(hook!=null)hook.run();return (SharedPreferences)Proxy.newProxyInstance(Context.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
  touch();String m=method.getName();if(m.equals("getBoolean")||m.equals("getString")||m.equals("getLong")||m.equals("getInt")){synchronized(values){return values.getOrDefault((String)args[0],args[1]);}}
  if(m.equals("contains")){synchronized(values){return values.containsKey(args[0]);}}
  if(m.equals("getAll")){synchronized(values){return new HashMap<>(values);}}
  if(m.equals("edit"))return editor();throw new UnsupportedOperationException(m);
 });}
 private Object editor(){Map<String,Object> pending=new HashMap<>();return Proxy.newProxyInstance(Context.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(proxy,method,args)->{
  touch();String m=method.getName();if(m.startsWith("put")){pending.put((String)args[0],args[1]);return proxy;}if(m.equals("remove")){pending.put((String)args[0],null);return proxy;}
  if(m.equals("commit")||m.equals("apply")){synchronized(values){for(Map.Entry<String,Object> e:pending.entrySet()){if(e.getValue()==null)values.remove(e.getKey());else values.put(e.getKey(),e.getValue());}}return m.equals("commit")?Boolean.TRUE:null;}throw new UnsupportedOperationException(m);
 });}
}
