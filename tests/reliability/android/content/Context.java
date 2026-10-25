package android.content;

import android.content.res.AssetManager;

/** JVM-only Context surface; the store test exercises local snapshots without Keystore or files. */
public abstract class Context {
    public static final int MODE_PRIVATE=0;
    public Context getApplicationContext(){return this;}
    public abstract SharedPreferences getSharedPreferences(String name,int mode);
    public AssetManager getAssets(){throw new UnsupportedOperationException("Assets outside this test");}
}
