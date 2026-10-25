package cn.ngapure.app;

/** Small in-memory snapshot, invalidated by refresh and scoped to host + session digest. */
final class ScopedCache<T> {
    private T value;
    private String scope;
    private long loadedAt,revision;
    synchronized T get(String requestedScope,long now,long lifetime){
        return value!=null&&requestedScope.equals(scope)&&now>=loadedAt&&now-loadedAt<lifetime?value:null;
    }
    synchronized long revision(){return revision;}
    synchronized boolean put(String expectedScope,String currentScope,long expectedRevision,long now,T snapshot){
        if(expectedRevision!=revision||!expectedScope.equals(currentScope))return false;
        scope=expectedScope;loadedAt=now;value=snapshot;return true;
    }
    synchronized void clear(){value=null;scope=null;loadedAt=0;revision++;}
}
