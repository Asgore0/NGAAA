package cn.ngapure.app;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.IOException;
import java.util.Locale;

/** Foreground native audio/video dialog; never passes account credentials to a media origin. */
public final class NativeMediaPlayer extends Dialog implements TextureView.SurfaceTextureListener {
    private final Activity activity;
    private final String source;
    private final boolean audio,dark;
    private final LinearLayout shell;
    private final FrameLayout viewport;
    private final TextureView texture;
    private final TextView status, clock;
    private final Button play, full;
    private final SeekBar seek;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final android.view.ViewTreeObserver.OnGlobalLayoutListener geometry=this::fit;
    private final AudioManager manager;
    private final AudioFocusRequest focus;
    private MediaPlayer player;
    private Surface surface;
    private boolean prepared, loading, released, background, fullScreen, seeking, resumeOnFocus, focusHeld, wantsPlay;
    private int position, videoWidth, videoHeight, generation;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (released || player == null || !prepared) return;
            try {
                int duration = player.getDuration(), current = player.getCurrentPosition();
                if (!seeking) { seek.setMax(Math.max(1, duration)); seek.setProgress(current); }
                clock.setText(time(current) + " / " + (duration > 0 ? time(duration) : "直播"));
            } catch (IllegalStateException ignored) { /* A surface or lifecycle event may have released playback. */ }
            handler.postDelayed(this, 350);
        }
    };
    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityCreated(Activity a, Bundle state) { }
        @Override public void onActivityStarted(Activity a) { }
        @Override public void onActivityResumed(Activity a) { if (a == activity) { background = false; fit(); } }
        @Override public void onActivityPaused(Activity a) { if (a == activity) { background = true; pause(false); } }
        @Override public void onActivityStopped(Activity a) { if (a == activity) { releasePlayer(true); setStatus("已暂停，点按继续", false); } }
        @Override public void onActivitySaveInstanceState(Activity a, Bundle state) { }
        @Override public void onActivityDestroyed(Activity a) { if (a == activity) dismiss(); }
    };
    public static boolean open(Context context, String url, boolean audio) {
        Activity owner = activity(context); String safe = NativeMediaPolicy.source(url);
        if (owner == null || safe == null || owner.isFinishing() || owner.isDestroyed()) return false;
        boolean dark=false;try{String theme=owner.getSharedPreferences("nga_local",0).getString("theme","light");dark=theme.equals("dark")||theme.equals("system")&&(owner.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;}catch(RuntimeException ignored){ }
        NativeMediaPlayer dialog = new NativeMediaPlayer(owner, safe, audio,dark); dialog.show(); return true;
    }
    private static Activity activity(Context context) {
        for (int i = 0; context != null && i < 12; i++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof android.content.ContextWrapper)) return null;
            Context next = ((android.content.ContextWrapper) context).getBaseContext(); if (next == context) return null; context = next;
        }
        return null;
    }
    private NativeMediaPlayer(Activity owner, String url, boolean onlyAudio,boolean night) {
        super(owner); activity = owner; source = url; audio = onlyAudio;dark=night;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        manager = (AudioManager) owner.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(this::focusChanged, handler).build();
        shell = new LinearLayout(owner); shell.setOrientation(LinearLayout.VERTICAL); shell.setPadding(dp(12), dp(12), dp(12), dp(16));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(AppPalette.of(dark).surface); bg.setCornerRadius(dp(24)); shell.setBackground(bg);
        LinearLayout heading = new LinearLayout(owner); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label((audio ? "音频 · " : "视频 · ") + NativeMediaPolicy.originLabel(url), 16); title.setMaxLines(2);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = button("关闭", this::dismiss); close.setContentDescription("关闭原生播放器"); heading.addView(close, new LinearLayout.LayoutParams(dp(72), dp(48))); shell.addView(heading);
        viewport = new FrameLayout(owner); viewport.setBackgroundColor(0xff05090f);
        texture = new TextureView(owner); texture.setSurfaceTextureListener(this); viewport.addView(texture, new FrameLayout.LayoutParams(-1, -1));
        if (audio) { texture.setVisibility(View.GONE); TextView note = label("♫", 52); note.setTextColor(AppPalette.of(true).accent); note.setGravity(Gravity.CENTER); viewport.addView(note, new FrameLayout.LayoutParams(-1, -1)); }
        shell.addView(viewport, new LinearLayout.LayoutParams(-1, dp(audio ? 100 : 210)));
        status = label("点按播放，使用系统原生解码", 13); status.setPadding(dp(4), dp(12), dp(4), dp(8)); shell.addView(status);
        seek = new SeekBar(owner); seek.setEnabled(false); seek.setContentDescription("播放进度"); seek.setProgressTintList(ColorStateList.valueOf(AppPalette.of(dark).accent)); seek.setThumbTintList(ColorStateList.valueOf(AppPalette.of(dark).accent)); seek.setProgressBackgroundTintList(ColorStateList.valueOf(AppPalette.of(dark).border));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) { if (user) clock.setText(time(value) + " / " + time(bar.getMax())); }
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) { seeking = false; position = bar.getProgress(); if (player != null && prepared) try { player.seekTo(position); } catch (IllegalStateException ignored) { } }
        }); shell.addView(seek, new LinearLayout.LayoutParams(-1, dp(48)));
        clock = label("00:00 / --:--", 13); clock.setGravity(Gravity.END); clock.setPadding(0, 0, dp(8), dp(6)); shell.addView(clock);
        LinearLayout controls = new LinearLayout(owner); controls.setGravity(Gravity.CENTER_VERTICAL);
        play = button("播放", this::toggle); play.setContentDescription("播放或暂停媒体"); controls.addView(play, new LinearLayout.LayoutParams(0, dp(52), 1));
        full = button("全屏", () -> { fullScreen = !fullScreen; fit(); }); full.setContentDescription("切换播放器全屏"); controls.addView(full, new LinearLayout.LayoutParams(dp(88), dp(52))); if (audio) full.setVisibility(View.GONE);
        shell.addView(controls); setContentView(shell); setOnDismissListener(ignored -> release());
        shell.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { transform(); });
    }
    @Override public void show() {
        super.show(); activity.getApplication().registerActivityLifecycleCallbacks(lifecycle); activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(geometry);
        Window window = getWindow(); if (window != null) { window.setBackgroundDrawableResource(android.R.color.transparent); window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setDimAmount(.65f); }
        fit();
    }
    private void fit() {
        Window window = getWindow(); if (released || window == null) return;
        android.graphics.Rect frame = FoldAdaptiveLayout.visibleSafeBounds(activity);
        int width = frame.width() > 0 ? frame.width() : activity.getResources().getDisplayMetrics().widthPixels;
        int height = frame.height() > 0 ? frame.height() : activity.getResources().getDisplayMetrics().heightPixels;
        int wantedWidth = fullScreen ? width : Math.min(dp(720), Math.max(1, width - dp(20)));
        int chrome=shell.getPaddingTop()+shell.getPaddingBottom();int contentWidth=Math.max(1,wantedWidth-shell.getPaddingLeft()-shell.getPaddingRight());
        for(int i=0;i<shell.getChildCount();i++){View child=shell.getChildAt(i);if(child==viewport||child.getVisibility()==View.GONE)continue;android.view.ViewGroup.LayoutParams params=child.getLayoutParams();child.measure(View.MeasureSpec.makeMeasureSpec(contentWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Math.max(0,params.height),params.height>0?View.MeasureSpec.EXACTLY:View.MeasureSpec.UNSPECIFIED));chrome+=child.getMeasuredHeight();}
        int remaining=Math.max(dp(24),height-chrome-dp(24));int wantedVideo = Math.min(remaining,audio ? dp(100) : Math.max(dp(80),wantedWidth * 9 / 16));
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) viewport.getLayoutParams(); p.height = fullScreen ? remaining : wantedVideo; viewport.setLayoutParams(p);
        int wantedHeight=Math.min(height,p.height+chrome); window.setLayout(wantedWidth,wantedHeight);
        android.graphics.Rect visible=new android.graphics.Rect();activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
        if(!frame.equals(visible)) { window.setGravity(Gravity.TOP|Gravity.LEFT);WindowManager.LayoutParams attrs=window.getAttributes();attrs.x=frame.left-visible.left+Math.max(0,(frame.width()-wantedWidth)/2);attrs.y=frame.top-visible.top+Math.max(0,(frame.height()-wantedHeight)/2);window.setAttributes(attrs); }
        else window.setGravity(Gravity.CENTER);
        full.setText(fullScreen ? "收起" : "全屏");
    }
    private void toggle() {
        if (released || background) return;
        if (player == null) { wantsPlay = true; prepare(); return; }
        if (loading) { wantsPlay = !wantsPlay; play.setText(wantsPlay ? "暂停" : "继续"); return; }
        if (prepared) try { if (player.isPlaying()) pause(false); else start(); } catch(IllegalStateException invalid) { fail(); }
    }
    private void prepare() {
        if (released || background || loading || (!audio && surface == null)) return;
        releasePlayer(false); final int token = ++generation; loading = true; prepared = false; seek.setEnabled(false); setStatus("正在缓冲…", false); play.setText("暂停");
        MediaPlayer pending = new MediaPlayer(); player = pending;
        try {
            pending.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(audio ? AudioAttributes.CONTENT_TYPE_MUSIC : AudioAttributes.CONTENT_TYPE_MOVIE).build());
            if (!audio) pending.setSurface(surface);
            pending.setOnPreparedListener(p -> { if (token != generation || released || p != player) return; loading = false; prepared = true; videoWidth = p.getVideoWidth(); videoHeight = p.getVideoHeight(); transform(); seek.setEnabled(p.getDuration() > 0); if (position > 0) p.seekTo(position); if (wantsPlay && !background) start(); else { play.setText("播放"); setStatus("已暂停", false); } handler.removeCallbacks(tick); handler.post(tick); });
            pending.setOnVideoSizeChangedListener((p, w, h) -> { if (p == player) { videoWidth = w; videoHeight = h; transform(); } });
            pending.setOnBufferingUpdateListener((p, percent) -> { if (p == player && prepared) seek.setSecondaryProgress((int) ((long) seek.getMax() * percent / 100)); });
            pending.setOnInfoListener((p, what, extra) -> { if (p == player && what == MediaPlayer.MEDIA_INFO_BUFFERING_START) setStatus("正在缓冲…", false); if (p == player && what == MediaPlayer.MEDIA_INFO_BUFFERING_END) setStatus(p.isPlaying() ? "正在播放" : "已暂停", false); return false; });
            pending.setOnCompletionListener(p -> { if (p != player) return; position = 0; wantsPlay = false; releaseFocus(); Window completedWindow=getWindow();if(completedWindow!=null)completedWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); play.setText("重播"); setStatus("播放完成", false); });
            pending.setOnErrorListener((p, what, extra) -> { if (p == player) fail(); return true; });
            // Empty headers deliberately omit NGA Cookie, Authorization and app-specific identity.
            pending.setDataSource(source); pending.prepareAsync();
        } catch (IOException | RuntimeException error) { fail(); }
    }
    private void start() {
        if (!prepared || player == null || background || released) return;
        if (manager == null || manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { wantsPlay = false; play.setText("播放"); setStatus("音频被其他应用占用，稍后点按播放", true); return; }
        focusHeld = true;
        try { player.start(); wantsPlay = true; resumeOnFocus = false; play.setText("暂停"); setStatus("正在播放", false); Window w = getWindow(); if (w != null) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
        catch (IllegalStateException error) { fail(); }
    }
    private void pause(boolean transientLoss) {
        if (player != null && prepared) try { boolean playing = player.isPlaying(); if (playing) { position = player.getCurrentPosition(); player.pause(); } resumeOnFocus = transientLoss && (playing || resumeOnFocus); } catch (IllegalStateException ignored) { }
        wantsPlay = false; if (!transientLoss) { resumeOnFocus = false; releaseFocus(); }
        play.setText("继续"); setStatus("已暂停", false); Window w = getWindow(); if (w != null) w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void focusChanged(int change) {
        if (released) return;
        if (change == AudioManager.AUDIOFOCUS_GAIN) { if (resumeOnFocus && !background) start(); }
        else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) pause(true);
        else if (change == AudioManager.AUDIOFOCUS_LOSS) pause(false);
    }
    private void fail() { releasePlayer(true); wantsPlay = false; setStatus("无法播放此媒体。请检查网络或格式，点按重试。", true); play.setText("重试"); }
    private void releasePlayer(boolean savePosition) {
        generation++; handler.removeCallbacks(tick); MediaPlayer old = player; player = null;
        if (old != null) { if (savePosition && prepared) try { position = old.getCurrentPosition(); } catch (IllegalStateException ignored) { } old.setOnErrorListener(null); old.setOnPreparedListener(null); old.setOnCompletionListener(null); old.release(); }
        prepared = false; loading = false; releaseFocus(); Window w = getWindow(); if (w != null) w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void releaseFocus() { if (manager != null && focusHeld) manager.abandonAudioFocusRequest(focus); focusHeld = false; }
    private void release() { if (released) return; released = true; releasePlayer(false); if (surface != null) { surface.release(); surface = null; } activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle); android.view.ViewTreeObserver observer=activity.getWindow().getDecorView().getViewTreeObserver();if(observer.isAlive())observer.removeOnGlobalLayoutListener(geometry); }
    private void setStatus(String value, boolean error) { status.setText(value); status.setTextColor(error ? dark?0xffffb5ad:0xffa8342c : AppPalette.of(dark).muted);if(isShowing())fit(); }
    private void transform() {
        if (videoWidth <= 0 || videoHeight <= 0 || texture.getWidth() <= 0 || texture.getHeight() <= 0) return;
        float w = texture.getWidth(), h = texture.getHeight(), factor = Math.min(w / videoWidth, h / videoHeight);
        Matrix matrix = new Matrix(); matrix.setScale(videoWidth * factor / w, videoHeight * factor / h, w / 2f, h / 2f); texture.setTransform(matrix);
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture value, int w, int h) { if(released)return;if(surface!=null)surface.release();surface = new Surface(value); transform(); if (wantsPlay) prepare(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture value, int w, int h) { transform(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture value) { pause(false); releasePlayer(true); if (surface != null) { surface.release(); surface = null; } return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture value) { }
    private TextView label(String value, int size) { TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(AppPalette.of(dark).ink); return view; }
    private Button button(String value, Runnable action) { Button view = new Button(activity); GradientDrawable fill=new GradientDrawable();fill.setColor(AppPalette.of(dark).soft);fill.setCornerRadius(dp(16));view.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(AppPalette.of(dark).ripple),fill,null)); view.setText(value); view.setAllCaps(false); view.setTextColor(AppPalette.of(dark).ink); view.setMinHeight(dp(48)); view.setOnClickListener(v -> action.run()); return view; }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private static String time(int millis) { int seconds = Math.max(0, millis / 1000); return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60); }
}
