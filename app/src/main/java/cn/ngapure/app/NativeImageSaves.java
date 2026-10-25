package cn.ngapure.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Explicitly confirmed file saves outlive a screen; failed writes are never automatically replayed. */
public final class NativeImageSaves {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor WRITES = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(2), action -> {
                Thread thread = new Thread(action, "nga-image-save"); thread.setDaemon(true); return thread;
            });
    private NativeImageSaves() { }

    /** Only application state and bounded original bytes enter this queue, never an Activity or View. */
    public static boolean save(Context context, Uri target, String url, String expectedFormat,
                               NativeImageExport.Image buffered) {
        if (context == null || target == null || !"content".equals(target.getScheme())
                || target.getAuthority() == null || target.getAuthority().isEmpty()
                || !NavigationState.imageUrl(url) || !NativeImages.allowed(url)) return false;
        final Context app = context.getApplicationContext();
        try {
            WRITES.execute(() -> {
                String result;
                try {
                    NativeImageExport.Image image = buffered == null ? NativeImageExport.inspect(NativeImages.bytes(url)) : buffered;
                    if (expectedFormat != null && !expectedFormat.equals(image.extension))
                        throw new IOException("原图格式已变化，请重新保存");
                    try (OutputStream output = app.getContentResolver().openOutputStream(target, "wt")) { image.writeTo(output); }
                    result = "原图已保存";
                } catch (Exception failure) { result = failure.getMessage() == null ? "保存失败，请重试" : failure.getMessage(); }
                final String message = result;
                MAIN.post(() -> Toast.makeText(app, message, Toast.LENGTH_SHORT).show());
            });
            return true;
        } catch (RejectedExecutionException busy) { return false; }
    }
}
