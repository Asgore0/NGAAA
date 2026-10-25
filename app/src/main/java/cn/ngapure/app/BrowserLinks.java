package cn.ngapure.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

/** Opens a normal web document in a browser without passing application credentials. */
public final class BrowserLinks {
    private BrowserLinks() { }

    public static Intent intent(String url) {
        NativeLinkTarget target = NativeLinkTarget.parse(url);
        if (target.kind == NativeLinkTarget.INVALID || target.kind == NativeLinkTarget.BLOCKED) {
            throw new IllegalArgumentException("Invalid browser link");
        }
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(target.url));
        view.addCategory(Intent.CATEGORY_BROWSABLE);
        // Resolve an actual browser, rather than our own read/thread/nuke deep-link
        // filter. The original ACTION_VIEW/data is delivered to that browser.
        view.setSelector(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER));
        return view;
    }

    public static boolean open(Activity activity, String url) {
        try {
            activity.startActivity(intent(url));
            return true;
        } catch (ActivityNotFoundException | SecurityException | IllegalArgumentException unavailable) {
            return false;
        }
    }
}
