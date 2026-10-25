package android.content;

import android.content.res.AssetManager;

/** JVM-only constructor surface. The test never opens Android assets or real app storage. */
public abstract class Context {
    public Context getApplicationContext(){return this;}
    public AssetManager getAssets(){throw new UnsupportedOperationException("Assets are outside this test");}
}
