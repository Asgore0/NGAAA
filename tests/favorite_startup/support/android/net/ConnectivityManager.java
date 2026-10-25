package android.net;
import java.util.concurrent.atomic.AtomicInteger;
public final class ConnectivityManager {
 public static class NetworkCallback {public void onAvailable(Network network){}public void onLost(Network network){}}
 public final AtomicInteger registrations=new AtomicInteger(),unregistrations=new AtomicInteger();public volatile NetworkCallback callback;public volatile Thread registeredOn;public volatile boolean available=true;
 public Network getActiveNetwork(){return available?new Network():null;}public NetworkCapabilities getNetworkCapabilities(Network network){return network==null?null:new NetworkCapabilities();}
 public void registerDefaultNetworkCallback(NetworkCallback value){if(callback!=null)throw new AssertionError("duplicate callback");callback=value;registeredOn=Thread.currentThread();registrations.incrementAndGet();}
 public void unregisterNetworkCallback(NetworkCallback value){if(callback!=value)throw new IllegalStateException("wrong callback");callback=null;unregistrations.incrementAndGet();}
}
