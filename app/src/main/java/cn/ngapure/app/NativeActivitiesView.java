package cn.ngapure.app;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.View;
import java.util.function.BooleanSupplier;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native official Android activity entry. Campaign documents remain explicitly labeled web pages. */
public final class NativeActivitiesView extends FrameLayout {
    public interface Listener{void open(String url);}
    private final Activity activity;private final NativeCommunity api;private final Listener listener;private final LinearLayout body;private final ScrollView scroll;private final ExecutorService io=Executors.newSingleThreadExecutor();private final NativeImages images=new NativeImages();private final int ink,muted,surface,accent;private boolean closed;private final BooleanSupplier sameScope;private int loadToken;private boolean loadingPage;
    private final RequestCancellation.Token reads=new RequestCancellation.Token();
    private static final class Setup {
        final NativeCommunity api;final boolean dark;final BooleanSupplier current;
        Setup(Activity activity,NgaStore store){if(activity==null||store==null)throw new IllegalArgumentException("缺少活动页面参数");final String host=store.host();api=new NativeCommunity(activity);dark="dark".equals(store.theme())||"system".equals(store.theme())&&(activity.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;current=()->host.equals(store.host());}
    }
    public NativeActivitiesView(Activity activity,NgaStore store,Listener listener){this(activity,new Setup(activity,store),listener);}
    private NativeActivitiesView(Activity activity,Setup setup,Listener listener){this(activity,setup.api,setup.dark,listener,setup.current);}
    /** Own-UID fixture seam; production always uses the selected official host and a live host guard. */
    NativeActivitiesView(Activity activity,NativeCommunity api,boolean dark,Listener listener,BooleanSupplier current){super(activity);if(api==null||listener==null||current==null)throw new IllegalArgumentException("缺少活动页面参数");this.activity=activity;this.listener=listener;this.api=api;sameScope=current;AppPalette palette=AppPalette.of(dark);ink=palette.ink;muted=palette.muted;surface=palette.surface;accent=palette.accent;setBackgroundColor(palette.background);scroll=new ScrollView(activity){@Override public boolean dispatchTouchEvent(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_DOWN)cancelScrollRestore();return super.dispatchTouchEvent(event);}};scroll.setClipToPadding(false);body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(20),dp(20),dp(32));scroll.addView(body,new ScrollView.LayoutParams(-1,-2));addView(scroll,new FrameLayout.LayoutParams(-1,-1));load();}
    public ScrollView scrollContainer(){return scroll;}
    public void close(){closed=true;loadToken++;cancelScrollRestore();reads.cancel();io.shutdownNow();images.close();}
    private void load(){
        if(closed||!sameScope.getAsBoolean())return;final int token=++loadToken;loadingPage=true;body.removeAllViews();body.addView(text("官方社区活动",22,ink));body.addView(text("读取 NGA 官方 Android 活动入口",13,muted));
        io.execute(()->{RequestCancellation.Scope scope=RequestCancellation.bind(reads);try{if(!sameScope.getAsBoolean())return;NativeCommunity.OfficialActivity entry=api.officialActivity();activity.runOnUiThread(()->{if(!accept(token))return;loadingPage=false;render(entry);queueScrollRestore();});}catch(Exception failure){activity.runOnUiThread(()->{if(!accept(token))return;loadingPage=false;body.addView(text(failure instanceof NativeCommunity.CommunityException?failure.getMessage():"官方活动暂未载入，请稍后重试",14,muted));refresh();queueScrollRestore();});}finally{scope.close();}});
    }
    private void render(NativeCommunity.OfficialActivity entry){body.removeAllViews();body.addView(text("官方社区活动",22,ink));body.addView(text("来自 NGA 官方 Android 活动接口",13,muted));if(!entry.present){body.addView(text("当前官方未开放活动入口",18,ink));body.addView(text("官方接口目前未提供活动链接。新的活动开放后，刷新即可查看。",14,muted));}else{LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(20),dp(20),dp(20),dp(20));GradientDrawable bg=new GradientDrawable();bg.setColor(surface);bg.setCornerRadius(dp(24));card.setBackground(bg);AvatarView icon=new AvatarView(activity,64,images);icon.bind(entry.icon,"NGA 活动");card.addView(icon,new LinearLayout.LayoutParams(dp(64),dp(64)));card.addView(text("NGA 官方活动",20,ink));long now=System.currentTimeMillis()/1000;String status=entry.start>now?"活动尚未开始":entry.end>1&&entry.end<now?"活动已结束":"查看当前活动";card.addView(text(status,14,muted));card.addView(text("活动内容为官方网页；打开前会显示目标链接供确认。",13,muted));TextView open=text("打开官方活动",16,accent);open.setMinHeight(dp(52));open.setOnClickListener(v->listener.open(entry.url));card.addView(open);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(18);body.addView(card,params);}refresh();}
    private void refresh(){TextView button=text("刷新活动入口",15,accent);button.setMinHeight(dp(52));button.setOnClickListener(v->load());body.addView(button);}
    private TextView text(String value,int size,int color){TextView view=new TextView(activity);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setLineSpacing(dp(4),1);view.setPadding(0,dp(8),0,dp(8));return view;}
    private int restoreY;private boolean restoringScroll,restoreQueued;
    private final View.OnLayoutChangeListener restoreLayout=(view,l,t,r,b,ol,ot,or,ob)->queueScrollRestore();
    private final Runnable restoreTask=this::applyScrollRestore;
    private boolean accept(int token){return !closed&&token==loadToken&&sameScope.getAsBoolean()&&!activity.isFinishing()&&!activity.isDestroyed();}
    public void setBottomInset(int pixels){if(closed)return;scroll.setPadding(scroll.getPaddingLeft(),scroll.getPaddingTop(),scroll.getPaddingRight(),Math.max(0,pixels));queueScrollRestore();}
    public void restoreScrollPosition(int offset){if(closed)return;cancelScrollRestore();restoreY=Math.max(0,offset);restoringScroll=true;scroll.addOnLayoutChangeListener(restoreLayout);body.addOnLayoutChangeListener(restoreLayout);queueScrollRestore();}
    private void applyScrollRestore(){
        restoreQueued=false;if(closed||!restoringScroll)return;if(!sameScope.getAsBoolean()){cancelScrollRestore();return;}
        if(!scroll.isAttachedToWindow()||getWindowVisibility()!=VISIBLE||loadingPage)return;
        if(!scroll.isLaidOut()||scroll.isLayoutRequested()||body.isLayoutRequested()){restoreQueued=true;scroll.postOnAnimation(restoreTask);return;}
        int maximum=Math.max(0,body.getHeight()-scroll.getHeight()+scroll.getPaddingTop()+scroll.getPaddingBottom());scroll.scrollTo(0,Math.min(restoreY,maximum));cancelScrollRestore();
    }
    private void queueScrollRestore(){if(!closed&&restoringScroll&&!restoreQueued){restoreQueued=true;scroll.postOnAnimation(restoreTask);}}
    private void cancelScrollRestore(){restoringScroll=false;restoreQueued=false;scroll.removeCallbacks(restoreTask);scroll.removeOnLayoutChangeListener(restoreLayout);body.removeOnLayoutChangeListener(restoreLayout);}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility==VISIBLE)queueScrollRestore();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
