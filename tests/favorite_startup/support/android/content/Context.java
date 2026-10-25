package android.content;
import android.net.ConnectivityManager;
public class Context {public static final String CONNECTIVITY_SERVICE="connectivity";public final ConnectivityManager connectivity=new ConnectivityManager();public Context getApplicationContext(){return this;}public Object getSystemService(String name){return connectivity;}}
