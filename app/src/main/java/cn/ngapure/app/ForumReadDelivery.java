package cn.ngapure.app;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** One response, one receiver. A late response continues on its existing read worker. */
final class ForumReadDelivery<T> {
    private boolean bound,completed,delivered,cancelled;
    private T value;
    private Consumer<T> receiver;

    boolean complete(T result) {
        boolean ready;
        synchronized(this){
            if(completed||cancelled)return false;
            completed=true;value=result;ready=bound;
        }
        if(ready)deliver();
        return true;
    }
    /** Bind on UI. An already completed response is dispatched off UI, never waited for. */
    void bind(Executor earlyResponseExecutor,Consumer<T> callback) {
        if(earlyResponseExecutor==null||callback==null)throw new IllegalArgumentException("Missing response receiver");
        boolean ready;
        synchronized(this){
            if(bound)throw new IllegalStateException("Response receiver already bound");
            bound=true;if(cancelled)return;
            receiver=callback;ready=completed;
        }
        if(ready)earlyResponseExecutor.execute(this::deliver);
    }
    private void deliver(){
        final T result;final Consumer<T> callback;
        synchronized(this){
            if(cancelled||delivered||!completed||!bound)return;
            delivered=true;result=value;callback=receiver;value=null;receiver=null;
        }
        callback.accept(result);
    }
    synchronized void cancel(){cancelled=true;value=null;receiver=null;}
    synchronized boolean cancelled(){return cancelled;}
}
