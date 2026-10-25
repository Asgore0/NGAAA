package cn.ngapure.app;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.View;
import java.util.function.BooleanSupplier;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native official follow_v2/get_push_list feed, with public user and exact reply routing. */
public final class NativeFollowingView extends FrameLayout {
    public interface Listener {void page(int page);void user(String uid);void topic(String tid,String pid,String title);void login();}
    public interface TopicPreviews { void attach(LinearLayout row,String tid,String title); }
    private TopicPreviews topicPreviews;
    public void setTopicPreviewFactory(TopicPreviews factory){topicPreviews=factory;}
    private final Activity activity;private final NativeCommunity api;private final Listener listener;private final ExecutorService io=Executors.newSingleThreadExecutor();private final LinearLayout body;private final ScrollView scroll;private final int requested,ink,muted,surface;private final boolean dark;
    private NativeImages images=new NativeImages();private boolean closed;private final BooleanSupplier sameScope;private int loadToken;private boolean loadingPage;
    private final RequestCancellation.Token reads=new RequestCancellation.Token();
    private static final class Setup {
        final NativeCommunity api;final boolean dark;final BooleanSupplier current;
        Setup(Activity activity,NgaStore store){if(activity==null||store==null)throw new IllegalArgumentException("缺少关注动态参数");final String host=store.host(),account=store.accountId(),cookie=store.cookie();api=store.loggedIn()?new NativeCommunity(activity,host,account):new NativeCommunity(activity);dark="dark".equals(store.theme())||"system".equals(store.theme())&&(activity.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;current=()->host.equals(store.host())&&account.equals(store.accountId())&&cookie.equals(store.cookie());}
    }
    public NativeFollowingView(Activity activity,NgaStore store,int page,Listener listener){this(activity,new Setup(activity,store),page,listener);}
    private NativeFollowingView(Activity activity,Setup setup,int page,Listener listener){this(activity,setup.api,page,setup.dark,listener,setup.current);}
    /** Own-UID fixture seam; production supplies the exact host/account/Cookie snapshot guard. */
    NativeFollowingView(Activity activity,NativeCommunity api,int page,boolean night,Listener listener,BooleanSupplier current){super(activity);if(api==null||listener==null||current==null)throw new IllegalArgumentException("缺少关注动态参数");this.activity=activity;this.listener=listener;requested=Math.max(1,page);this.api=api;dark=night;sameScope=current;AppPalette palette=AppPalette.of(dark);ink=palette.ink;muted=palette.muted;surface=palette.surface;setBackgroundColor(palette.background);scroll=new ScrollView(activity){@Override public boolean dispatchTouchEvent(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_DOWN)cancelScrollRestore();return super.dispatchTouchEvent(event);}};scroll.setClipToPadding(false);body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(16),dp(16),dp(16),dp(32));scroll.addView(body,new ScrollView.LayoutParams(-1,-2));addView(scroll,new FrameLayout.LayoutParams(-1,-1));load();}
    public void setAuthorAvatarSource(NativeAvatarResolver.Source source){images.setAuthorAvatarSource(source);}
    public ScrollView scrollContainer(){return scroll;}
    public void close(){closed=true;loadToken++;cancelScrollRestore();reads.cancel();io.shutdownNow();images.close();}
    private void load(){
        if(closed||!sameScope.getAsBoolean())return;final int token=++loadToken;loadingPage=true;body.removeAllViews();body.addView(text("关注动态",22,ink));body.addView(text("已关注用户发布或收藏的主题与回复",13,muted));body.addView(text("正在读取 NGA 云端动态…",14,muted));
        io.execute(()->{RequestCancellation.Scope scope=RequestCancellation.bind(reads);try{if(!sameScope.getAsBoolean())return;NativeCommunity.FollowActivityPage page=api.followingActivity(requested);activity.runOnUiThread(()->{if(!accept(token))return;loadingPage=false;render(page);queueScrollRestore();});}catch(Exception failure){activity.runOnUiThread(()->{if(!accept(token))return;loadingPage=false;body.removeAllViews();body.addView(text("关注动态暂时未载入",20,ink));body.addView(text(failure instanceof NativeCommunity.CommunityException?failure.getMessage():"读取未完成，请稍后重试",14,muted));body.addView(button("重试",this::load));if(failure instanceof NativeCommunity.CommunityException&&((NativeCommunity.CommunityException)failure).code==NativeCommunity.Code.LOGIN_REQUIRED)body.addView(button("登录 NGA",listener::login));queueScrollRestore();});}finally{scope.close();}});
    }
    private void render(NativeCommunity.FollowActivityPage page){body.removeAllViews();body.addView(text("关注动态",22,ink));body.addView(text("NGA 云端 · 第 "+page.page+" / "+page.totalPages+" 页",13,muted));if(page.items.isEmpty()&&page.unavailableItems==0)body.addView(text("本页暂无动态。关注用户后，可在这里查看他们的新主题、回复和收藏。",15,muted));if(page.unavailableItems>0)body.addView(text("本页 "+page.unavailableItems+" 条动态暂未提供可读内容",13,muted));NativeUserActions.UserFilter filter=NativeUserActions.filter(activity);for(NativeCommunity.FollowActivity item:page.items){if(filter.blocks(item.actorUid,item.actorName,item.title)){LinearLayout hidden=card();hidden.addView(text("已按屏蔽设置隐藏此条动态",14,muted));hidden.addView(button("临时显示",()->{int index=body.indexOfChild(hidden);body.removeView(hidden);body.addView(activityCard(item),index);}));}else body.addView(activityCard(item));}LinearLayout pager=new LinearLayout(activity);pager.setGravity(Gravity.CENTER);TextView previous=button("上一页",()->listener.page(page.page-1));previous.setEnabled(page.page>1);previous.setAlpha(page.page>1?1f:.4f);previous.setMinHeight(dp(50));pager.addView(previous,new LinearLayout.LayoutParams(0,-2,1));TextView next=button("下一页",()->listener.page(page.nextPage));next.setEnabled(page.hasNext);next.setAlpha(page.hasNext?1f:.4f);next.setMinHeight(dp(50));pager.addView(next,new LinearLayout.LayoutParams(0,-2,1));body.addView(pager);}
    private LinearLayout activityCard(NativeCommunity.FollowActivity item){LinearLayout card=card(false),actor=new LinearLayout(activity);actor.setGravity(Gravity.CENTER_VERTICAL);AvatarView avatar=new AvatarView(activity,36,images);images.rememberUserAvatar(item.actorUid,item.actorAvatar);avatar.bindUser(item.actorUid,item.actorAvatar,item.actorName);actor.addView(avatar,new LinearLayout.LayoutParams(dp(36),dp(36)));LinearLayout labels=new LinearLayout(activity);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(10),0,0,0);labels.addView(text(item.actorName.isEmpty()?"NGA 用户":item.actorName,15,ink));labels.addView(text(verb(item.type,item.pid)+(item.time.isEmpty()?"":" · "+item.time),12,muted));actor.addView(labels,new LinearLayout.LayoutParams(0,-2,1));actor.setMinimumHeight(dp(48));actor.setContentDescription("查看用户资料");actor.setOnClickListener(v->listener.user(item.actorUid));card.addView(actor);TextView title=text(item.title.isEmpty()?"查看相关主题":item.title,17,ink);title.setPadding(0,dp(10),0,dp(10));title.setMinHeight(dp(52));title.setContentDescription("打开动态相关主题或回复");title.setOnClickListener(v->listener.topic(item.tid,item.pid.equals("0")?"":item.pid,item.title));card.addView(title);if((item.pid.isEmpty()||item.pid.equals("0"))&&topicPreviews!=null)topicPreviews.attach(card,item.tid,item.title);return card;}
    static String verb(int type,String pid){return type==1?"发布了主题":type==2?"发布了回复":"收藏了"+(pid==null||pid.isEmpty()||pid.equals("0")?"主题":"回复");}
    private LinearLayout card(){LinearLayout result=card(false);body.addView(result);return result;}
    private LinearLayout card(boolean ignored){LinearLayout result=new LinearLayout(activity);result.setOrientation(LinearLayout.VERTICAL);result.setPadding(dp(16),dp(12),dp(16),dp(12));GradientDrawable bg=new GradientDrawable();bg.setColor(surface);bg.setCornerRadius(dp(20));result.setBackground(bg);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(12);result.setLayoutParams(params);return result;}
    private TextView button(String label,Runnable action){TextView text=text(label,15,ink);text.setGravity(Gravity.CENTER);text.setMinHeight(dp(48));text.setPadding(dp(12),dp(8),dp(12),dp(8));text.setOnClickListener(v->action.run());return text;}
    private TextView text(String label,int size,int color){TextView result=new TextView(activity);result.setText(label);result.setTextSize(size);result.setTextColor(color);result.setLineSpacing(dp(3),1);return result;}
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
