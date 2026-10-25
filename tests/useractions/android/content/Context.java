package android.content;

import android.content.res.AssetManager;

/** JVM-only constructor surface; all preferences in these fixtures are isolated in memory. */
public abstract class Context {
    public static final int MODE_PRIVATE=0;
    public Context getApplicationContext(){return this;}
    public abstract SharedPreferences getSharedPreferences(String name,int mode);
    public AssetManager getAssets(){throw new UnsupportedOperationException("Assets outside this test");}
}
