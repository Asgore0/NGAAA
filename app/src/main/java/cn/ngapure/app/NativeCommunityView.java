package cn.ngapure.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Native community pages with shared routing and explicit browser handoff for web documents. */
public final class NativeCommunityView extends FrameLayout {
    public interface Listener {
        void navigate(String kind,String id,String title,int page);
        void topic(String tid,String title);
        default void topic(String tid,String pid,String title){topic(tid,title);}
        void user(String uid);
        void login();
        default boolean openLink(String url){return false;}
        default void userActions(String uid,String name,boolean self){}
        default void manageFolders(){}
    }
    public interface TopicPreviews { void attach(LinearLayout row,String tid,String title); }
    private TopicPreviews topicPreviews;
    public void setTopicPreviewFactory(TopicPreviews factory){topicPreviews=factory;}
    public interface TopicRows { void attach(LinearLayout row,String tid,String pid,String title,String time,int replies,VoteMetrics votes); }
    private TopicRows topicRowFactory;
    public void setTopicRowFactory(TopicRows factory){topicRowFactory=factory;}
    private interface Work<T> {T run() throws Exception;}
    private interface Success<T> {void accept(T result);}
    private interface Failure {void accept(Exception error);}
    private final Activity activity;
    private final NgaStore store;
    private final NativeCommunity api;
    private final Listener listener;
    private final String kind,id;
    private final int requestedPage;
    private final ExecutorService io=Executors.newFixedThreadPool(2);
    private final RequestCancellation.Token reads=new RequestCancellation.Token();
    private final List<Dialog> dialogs=new ArrayList<>();
    private final LinearLayout content;
    private final ScrollView scroll;
    private final int ink,muted,surface,line,accent;
    private final AppPalette palette;
    private final boolean dark;
    private final String scopeHost,scopeAccount;
    private final NativeMessageDrafts messageDrafts;
    private NativeImages media;
    private NativeAvatarResolver.Source authorAvatarSource;
    private Runnable saveOpenEditor;
    private int generation,directoryRequest;
    private int pendingUiTasks,restoreY;
    private boolean restoringScroll,restoreQueued;
    private final View.OnLayoutChangeListener restoreLayout=(view,l,t,r,b,ol,ot,or,ob)->queueScrollRestore();
    private final Runnable restoreScrollTask=this::applyScrollRestore;
    private boolean closed,writing;

    public NativeCommunityView(Activity activity,NgaStore store,String kind,String id,int page,Listener listener) {
        super(activity);
        if(activity==null||store==null||listener==null||!Arrays.asList("inbox","conversation","reminders","self","user","selfreplies","userreplies","serverfav").contains(kind))
            throw new IllegalArgumentException("无效的原生社区页面");
        this.activity=activity;this.store=store;this.kind=kind;this.id=id==null?"":id;this.requestedPage=Math.max(1,page);this.listener=listener;
        scopeHost=store.host();scopeAccount=store.accountId();
        messageDrafts=store.loggedIn()&&validId(scopeAccount)?new NativeMessageDrafts(activity,scopeHost,scopeAccount):null;
        api=messageDrafts==null?new NativeCommunity(activity):new NativeCommunity(activity,scopeHost,scopeAccount);
        dark="dark".equals(store.theme())||("system".equals(store.theme())&&(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
        palette=AppPalette.of(dark);ink=palette.ink;muted=palette.muted;
        surface=palette.surface;line=palette.line;accent=palette.accent;
        setBackgroundColor(palette.background);
        scroll=new ScrollView(activity){
            @Override public boolean dispatchTouchEvent(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_DOWN)cancelScrollRestore();return super.dispatchTouchEvent(event);}
        };scroll.setFillViewport(true);scroll.setClipToPadding(false);
        content=profileKind(kind)?new LinearLayout(activity){
            @Override protected void onMeasure(int widthSpec,int heightSpec){
                int width=Math.min(MeasureSpec.getSize(widthSpec),dp(760));
                super.onMeasure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),heightSpec);
            }
        }:column();content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(16),dp(12),dp(16),dp(24));
        ScrollView.LayoutParams contentParams=new ScrollView.LayoutParams(-1,-2);contentParams.gravity=Gravity.CENTER_HORIZONTAL;scroll.addView(content,contentParams);
        addView(scroll,new FrameLayout.LayoutParams(-1,-1));reload();
    }
    /** Caller must close before replacing the page or destroying its Activity. */
    public void close() {
        if(closed)return;if(saveOpenEditor!=null)saveOpenEditor.run();closed=true;generation++;
        cancelScrollRestore();
        reads.cancel();
        // A confirmed submit owns a durable pending snapshot: let its worker finish even without this UI.
        if(writing)io.shutdown();else io.shutdownNow();
        if(media!=null)media.close();
        for(Dialog dialog:new ArrayList<>(dialogs))if(dialog.isShowing())dialog.dismiss();dialogs.clear();
    }
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE&&saveOpenEditor!=null&&!closed)saveOpenEditor.run();if(visibility==VISIBLE)queueScrollRestore();}
    /** Caller supplies a session-bound production source; synthetic tests never enable networking. */
    public void setAuthorAvatarSource(NativeAvatarResolver.Source source){authorAvatarSource=source;if(media!=null)media.setAuthorAvatarSource(source);}
    public ScrollView scrollContainer(){return scroll;}
    /** Restores a bounded pixel offset after the current async page layout; later media may move content. */
    public void restoreScrollPosition(int offset){
        if(closed)return;cancelScrollRestore();restoreY=Math.max(0,offset);restoringScroll=true;
        scroll.addOnLayoutChangeListener(restoreLayout);content.addOnLayoutChangeListener(restoreLayout);queueScrollRestore();
    }
    private void applyScrollRestore(){
        restoreQueued=false;if(closed||!restoringScroll)return;
        if(!scroll.isAttachedToWindow()||getWindowVisibility()!=VISIBLE)return;
        if(!scroll.isLaidOut()||scroll.isLayoutRequested()||content.isLayoutRequested()){
            restoreQueued=true;scroll.postOnAnimation(restoreScrollTask);return;
        }
        if(pendingUiTasks>0)return;
        int maximum=Math.max(0,content.getHeight()-scroll.getHeight()+scroll.getPaddingTop()+scroll.getPaddingBottom());
        scroll.scrollTo(0,Math.min(restoreY,maximum));cancelScrollRestore();
    }
    private void queueScrollRestore(){if(!closed&&restoringScroll&&!restoreQueued){restoreQueued=true;scroll.postOnAnimation(restoreScrollTask);}}
    private void cancelScrollRestore(){
        restoringScroll=false;restoreQueued=false;scroll.removeCallbacks(restoreScrollTask);
        scroll.removeOnLayoutChangeListener(restoreLayout);content.removeOnLayoutChangeListener(restoreLayout);
    }
    private void reload() {
        if(closed)return;generation++;pendingUiTasks=0;content.removeAllViews();writing=false;
        if(media!=null)media.close();media=new NativeImages();if(authorAvatarSource!=null)media.setAuthorAvatarSource(authorAvatarSource);
        if(profileKind(kind)){profile("self".equals(kind)||"selfreplies".equals(kind));return;}
        if("serverfav".equals(kind)){favorites();return;}
        loading(content);
        if("inbox".equals(kind))task(()->api.conversations(requestedPage),this::inbox,e->error(content,e,this::reload));
        else if("conversation".equals(kind))task(()->api.conversation(id,requestedPage),this::conversation,e->error(content,e,this::reload));
        else task(()->api.reminders(0),this::reminders,e->error(content,e,this::reload));
    }
    private <T> void task(Work<T> work,Success<T> success,Failure failure) {
        task(work,success,failure,false);
    }
    /** Delivery and its read-back may outlive this screen; closing only cancels disposable reads. */
    private <T> void writeTask(Work<T> work,Success<T> success,Failure failure) {
        task(work,success,failure,true);
    }
    private <T> void task(Work<T> work,Success<T> success,Failure failure,boolean delivery) {
        final int token=generation;if(closed)return;pendingUiTasks++;
        try {io.execute(()->{
            RequestCancellation.Scope scope=delivery?null:RequestCancellation.bind(reads);
            try {T result=work.run();post(token,()->{try{success.accept(result);}finally{finishUiTask(token);}});}
            catch(Exception error){post(token,()->{try{failure.accept(error);}finally{finishUiTask(token);}});}
            finally{if(scope!=null)scope.close();}
        });}catch(RejectedExecutionException ended){finishUiTask(token);/* Closed pages do not revive a worker. */}
    }
    private void finishUiTask(int token){if(!closed&&token==generation){pendingUiTasks--;queueScrollRestore();}}
    private void post(int token,Runnable action) {
        activity.runOnUiThread(()->{if(!closed&&token==generation&&!activity.isFinishing()&&!activity.isDestroyed())action.run();});
    }
    private void inbox(NativeCommunity.ConversationPage page) {
        content.removeAllViews();heading(content,"私信","服务器会话 · 第 "+page.page+" 页");
        content.addView(button("新私信",()->compose(null)),new LinearLayout.LayoutParams(-1,dp(48)));
        if(page.items.isEmpty())empty(content,"暂无私信会话");
        for(NativeCommunity.Conversation item:page.items) {
            LinearLayout card=card(content);card.addView(text(item.subject.isEmpty()?"无标题会话":item.subject,17,ink,true));
            card.addView(personRow("",item.fromName,item.fromUid,item.posts+" 条消息"+(item.time.isEmpty()?"":" · "+item.time),32));
            if(item.unread)card.addView(text("未读",12,accent,true));
            card.setContentDescription("私信会话："+item.subject);card.setOnClickListener(v->listener.navigate("conversation",item.mid,item.subject,1));
        }
        pager(content,page.page,page.hasNext,page.nextPage,"inbox","");
    }
    private void conversation(NativeCommunity.MessagePage page) {
        content.removeAllViews();heading(content,page.subject.isEmpty()?"私信会话":page.subject,"第 "+page.page+" 页 · "+page.participants.size()+" 位参与者");
        if(page.items.isEmpty())empty(content,"本页没有消息");
        for(NativeCommunity.Message item:page.items) {
            LinearLayout card=card(content);card.addView(personRow(item.avatar,item.username,item.uid,item.time+" · #"+item.floor,40));
            messageBody(card,item.content);
        }
        pager(content,page.page,page.hasNext,page.nextPage,"conversation",id);
        content.addView(button("回复私信",()->compose(page)),new LinearLayout.LayoutParams(-1,dp(48)));
    }
    private void reminders(NativeCommunity.ReminderPage page) {
        content.removeAllViews();heading(content,"提醒",page.unread+" 条未读提醒 · 服务器返回");
        if(page.items.isEmpty())empty(content,"暂无提醒");
        for(NativeCommunity.Reminder item:page.items) {
            LinearLayout card=card(content);card.addView(text(item.title.isEmpty()?"社区提醒":item.title,16,ink,true));
            card.addView(personRow("",item.username.isEmpty()?"社区通知":item.username,item.uid,item.time,32));
            if(validId(item.tid))card.addView(button("查看相关主题",()->listener.topic(item.tid,validId(item.pid)?item.pid:"",item.title)),new LinearLayout.LayoutParams(-1,dp(48)));
            else if("message".equals(item.category))card.addView(button("查看私信",()->listener.navigate("inbox","","私信",1)),new LinearLayout.LayoutParams(-1,dp(48)));
        }
        content.addView(button("刷新提醒",this::reload),new LinearLayout.LayoutParams(-1,dp(48)));
    }
    private void profile(boolean self) {
        final boolean replies=profileReplies(kind);
        LinearLayout header=column(),topics=column();content.addView(header);
        if(self){LinearLayout actions=new LinearLayout(activity);actions.addView(button("私信",()->listener.navigate("inbox","","私信",1)),new LinearLayout.LayoutParams(0,-2,1));actions.addView(button("提醒",()->listener.navigate("reminders","","提醒",1)),new LinearLayout.LayoutParams(0,-2,1));actions.addView(button("云端收藏",()->listener.navigate("serverfav","1","云端收藏",1)),new LinearLayout.LayoutParams(0,-2,1));content.addView(actions);}
        profileSections(self,replies);content.addView(topics);loading(header);loading(topics);
        task(()->self?api.self():api.user(id),profile->{
            header.removeAllViews();media.rememberUserAvatar(profile.uid,profile.avatar);LinearLayout card=card(header);card.addView(personRow(profile.avatar,profile.username,profile.uid,"UID "+profile.uid+(profile.group.isEmpty()?"":" · "+profile.group),64));
            card.addView(text(profile.posts+" 条发言",14,ink,false));
            if(!profile.registeredAt.isEmpty())card.addView(text("注册于 "+profile.registeredAt,12,muted,false));
            if(!profile.ipLocation.isEmpty())card.addView(text("IP 属地："+profile.ipLocation,12,muted,false));
            if(profile.nuked||profile.muted)card.addView(text(profile.nuked?"账号已受限制":"账号存在禁言状态",13,muted,false));
            if(!profile.signature.isEmpty()){TextView sign=text(readable(profile.signature),14,ink,false);sign.setTextIsSelectable(true);card.addView(sign);}
            if(!profile.reputation.isEmpty())card.addView(text(profile.reputation,12,muted,false));
            if(!self)header.addView(button("向该用户发私信",()->composeTo(profile.uid)));
            header.addView(button(self?"编辑头像与签名":"关注与屏蔽",()->listener.userActions(profile.uid,profile.username,self)));
            topics.removeAllViews();heading(topics,replies?"用户回复":"用户主题","第 "+requestedPage+" 页 · "+(replies?"点按记录定位到原回复":"该用户发表的主题"));LinearLayout list=column();topics.addView(list);loading(list);
            final String route=profileRoute(self,replies),routeId=self?"":profile.uid;
            task(()->replies?api.userReplies(profile.uid,requestedPage):api.userTopics(profile.uid,requestedPage),page->{
                list.removeAllViews();topicRows(list,page,false,replies);pager(list,page.page,page.hasNext,page.nextPage,route,routeId,page.total>=0?page.totalPages:0);
            },e->{error(list,e,this::reload);if(requestedPage>1)pager(list,requestedPage,false,0,route,routeId);});
        },e->{error(header,e,this::reload);topics.removeAllViews();if(requestedPage>1)pager(topics,requestedPage,false,0,profileRoute(self,replies),self?"":id);});
    }
    private void profileSections(boolean self,boolean replies) {
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(4),dp(4),dp(4),dp(4));
        GradientDrawable panel=new GradientDrawable();panel.setColor(surface);panel.setCornerRadius(dp(24));panel.setStroke(dp(1),line);row.setBackground(panel);
        for(boolean section:new boolean[]{false,true}){
            boolean selected=section==replies;String label=section?"回复":"主题";
            TextView tab=button(label,()->{if(!selected)listener.navigate(profileRoute(self,section),self?"":id,self?"我的资料":"用户资料",1);});
            tab.setSelected(selected);tab.setTextColor(selected?accent:muted);tab.setContentDescription("用户"+label+(selected?"，已选中":"，切换"));
            if(selected){GradientDrawable active=new GradientDrawable();active.setColor(palette.selectionFill);active.setCornerRadius(dp(20));tab.setBackground(active);}
            row.addView(tab,new LinearLayout.LayoutParams(0,-2,1));
        }
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(0,dp(16),0,dp(4));content.addView(row,params);
    }
    static boolean profileKind(String kind){return "self".equals(kind)||"user".equals(kind)||profileReplies(kind);}
    static boolean profileReplies(String kind){return "selfreplies".equals(kind)||"userreplies".equals(kind);}
    static String profileRoute(boolean self,boolean replies){return self?(replies?"selfreplies":"self"):(replies?"userreplies":"user");}
    private void favorites() {
        final String folder=id.isEmpty()?"1":id;
        heading(content,"云端收藏","与 NGA 账号同步的主题和回复");
        content.addView(button("管理收藏夹",()->listener.manageFolders()));
        LinearLayout directory=column();content.addView(directory,new LinearLayout.LayoutParams(-1,-2));
        TextView active=text("1".equals(folder)?"默认收藏夹":"当前收藏夹",15,ink,true);active.setPadding(dp(2),dp(14),dp(2),dp(6));content.addView(active);
        LinearLayout topics=column();content.addView(topics,new LinearLayout.LayoutParams(-1,-2));
        folderDirectory(directory,1,folder,active);loading(topics);
        task(()->api.favorites(folder,requestedPage),page->{
            topics.removeAllViews();TextView summary=text((page.total>=0?"共 "+page.total+" 条收藏 · ":"")+"第 "+page.page+" 页",12,muted,false);summary.setPadding(dp(2),0,dp(2),dp(4));topics.addView(summary);
            topicRows(topics,page,true);pager(topics,page.page,page.hasNext,page.nextPage,"serverfav",folder,page.total>=0?page.totalPages:0);
        },e->error(topics,e,this::reload));
    }
    private void folderDirectory(LinearLayout target,int requested,String selected,TextView active) {
        final int request=++directoryRequest;
        target.removeAllViews();drawFolderChoices(target,folderChoices(selected,new ArrayList<>()));
        TextView status=text("正在读取收藏夹目录…",12,muted,false);target.addView(status);
        task(()->api.favoriteFolders(requested),page->{
            if(request!=directoryRequest)return;
            target.removeAllViews();List<FolderChoice> choices=folderChoices(selected,page.items);drawFolderChoices(target,choices);
            for(FolderChoice choice:choices)if(choice.selected&&(!"当前收藏夹".equals(choice.title)||"当前收藏夹".contentEquals(active.getText())))active.setText(choice.title);
            if(page.page>1||page.hasNext){
                LinearLayout pages=new LinearLayout(activity);pages.setGravity(Gravity.CENTER_VERTICAL);
                TextView previous=button("上一组",()->folderDirectory(target,page.page-1,selected,active));previous.setEnabled(page.page>1);previous.setAlpha(page.page>1?1f:.4f);pages.addView(previous,new LinearLayout.LayoutParams(0,dp(48),1));
                TextView count=text("目录第 "+page.page+" 组",12,muted,false);count.setGravity(Gravity.CENTER);pages.addView(count,new LinearLayout.LayoutParams(0,dp(48),1));
                TextView next=button("下一组",()->folderDirectory(target,page.nextPage,selected,active));next.setEnabled(page.hasNext);next.setAlpha(page.hasNext?1f:.4f);pages.addView(next,new LinearLayout.LayoutParams(0,dp(48),1));target.addView(pages);
            }
        },e->{
            if(request!=directoryRequest)return;
            target.removeAllViews();drawFolderChoices(target,folderChoices(selected,new ArrayList<>()));
            LinearLayout hint=new LinearLayout(activity);hint.setGravity(Gravity.CENTER_VERTICAL);TextView failure=text("目录未载入 · "+safeError(e),12,muted,false);failure.setMaxLines(2);failure.setEllipsize(TextUtils.TruncateAt.END);hint.addView(failure,new LinearLayout.LayoutParams(0,-2,1));hint.addView(button("重试目录",()->folderDirectory(target,requested,selected,active)),new LinearLayout.LayoutParams(-2,dp(48)));target.addView(hint);
        });
    }
    private void drawFolderChoices(LinearLayout target,List<FolderChoice> choices) {
        HorizontalScrollView strip=new HorizontalScrollView(activity);strip.setHorizontalScrollBarEnabled(false);strip.setOverScrollMode(View.OVER_SCROLL_NEVER);strip.setClipToPadding(false);
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(6),0,dp(6));strip.addView(row,new HorizontalScrollView.LayoutParams(-2,-2));target.addView(strip,new LinearLayout.LayoutParams(-1,dp(60)));
        for(FolderChoice choice:choices){
            TextView chip=text(choice.title,14,choice.selected?accent:muted,choice.selected);chip.setGravity(Gravity.CENTER);chip.setSingleLine();chip.setEllipsize(TextUtils.TruncateAt.END);chip.setMaxWidth(dp(220));chip.setPadding(dp(16),0,dp(16),0);chip.setMinWidth(dp(64));chip.setMinHeight(dp(48));chip.setSelected(choice.selected);chip.setFocusable(true);
            GradientDrawable background=new GradientDrawable();background.setColor(choice.selected?palette.selectionFill:surface);background.setCornerRadius(dp(24));background.setStroke(dp(1),choice.selected?palette.selectionStroke:line);chip.setBackground(background);
            chip.setContentDescription(choice.title+(choice.selected?"，已选中":"，切换收藏夹"));chip.setOnClickListener(v->{if(!choice.selected)listener.navigate("serverfav",choice.id,"云端收藏",1);});
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,dp(48));params.setMargins(0,0,dp(8),0);row.addView(chip,params);
            if(choice.selected)chip.post(()->{if(!closed)strip.scrollTo(Math.max(0,chip.getLeft()-dp(16)),0);});
        }
    }
    static final class FolderChoice {
        final String id,title;final boolean selected;
        FolderChoice(String id,String title,boolean selected){this.id=id;this.title=title;this.selected=selected;}
    }
    /** Deterministic directory model keeps the selected folder reachable even on another directory page. */
    static List<FolderChoice> folderChoices(String selected,List<NativeCommunity.Folder> folders) {
        String current=validId(selected)?selected:"1";List<FolderChoice> result=new ArrayList<>();result.add(new FolderChoice("1","默认收藏夹","1".equals(current)));
        for(NativeCommunity.Folder item:folders){
            if(item==null||!validId(item.id)||"1".equals(item.id))continue;boolean duplicate=false;for(FolderChoice prior:result)if(prior.id.equals(item.id)){duplicate=true;break;}if(duplicate)continue;
            result.add(new FolderChoice(item.id,item.name==null||item.name.trim().isEmpty()?"未命名收藏夹":item.name.trim(),current.equals(item.id)));
        }
        boolean found=false;for(FolderChoice item:result)if(item.selected){found=true;break;}
        if(!found)result.add(1,new FolderChoice(current,"当前收藏夹",true));return result;
    }
    private void topicRows(LinearLayout target,NativeCommunity.TopicPage page,boolean favorites){topicRows(target,page,favorites,false);}
    private void topicRows(LinearLayout target,NativeCommunity.TopicPage page,boolean favorites,boolean replies) {
        if(page.unavailableItems>0){TextView partial=text("NGA 未提供本页 "+page.unavailableItems+" 条记录的内容"+(page.items.isEmpty()?"，可继续翻页查看其他记录":"，已显示其余可读记录"),13,muted,false);partial.setPadding(dp(4),dp(10),dp(4),dp(12));partial.setLineSpacing(dp(3),1);target.addView(partial);}
        if(page.items.isEmpty()&&page.unavailableItems==0)empty(target,favorites?(page.page==1?"这个收藏夹还没有内容，收藏主题后会显示在这里":"本页没有收藏，可返回上一页或切换收藏夹"):replies?"本页暂无用户回复":"本页暂无用户主题");
        NativeUserActions.UserFilter filter=NativeUserActions.filter(activity);
        for(NativeCommunity.Topic item:page.items) {
            if(filter.blocks(item.authorId,item.author,item.title)){
                LinearLayout hidden=card(target);hidden.addView(text("已按屏蔽设置隐藏此条记录",14,muted,false));
                hidden.addView(button("临时显示此条",()->{int index=target.indexOfChild(hidden);if(index<0)return;target.removeView(hidden);LinearLayout revealed=column();target.addView(revealed,index);topicCard(revealed,item,favorites,replies);}));
            }else topicCard(target,item,favorites,replies);
        }
    }
    private void topicCard(LinearLayout target,NativeCommunity.Topic item,boolean favorites,boolean replies) {
            LinearLayout card=card(target);card.setPadding(dp(14),dp(12),dp(14),dp(10));
            TextView title=text(item.title.isEmpty()?"主题":item.title,17,ink,true);title.setLineSpacing(dp(3),1);title.setMaxLines(4);title.setEllipsize(TextUtils.TruncateAt.END);
            Runnable open=()->{if(!openTopic(listener,item,favorites,replies))toast("这条回复尚无可用的楼层定位，请稍后重试");};title.setOnClickListener(v->open.run());card.addView(title,new LinearLayout.LayoutParams(-1,-2));card.setOnClickListener(v->open.run());
            if(favorites||replies){
                LinearLayout context=new LinearLayout(activity);context.setGravity(Gravity.CENTER_VERTICAL);
                String label=favorites?(validId(item.pid)?"回复收藏":"主题收藏"):(validId(item.pid)?"定位原回复":"回复定位暂不可用");
                TextView contextLabel=text(label,11,muted,false);contextLabel.setPadding(0,dp(5),dp(6),dp(3));context.addView(contextLabel,new LinearLayout.LayoutParams(0,-2,1));
                if(favorites){TextView remove=button("移除",()->removeFavorite(item));remove.setTextSize(12);remove.setTypeface(Typeface.DEFAULT,Typeface.NORMAL);remove.setContentDescription("移除云端收藏："+item.title);context.addView(remove,new LinearLayout.LayoutParams(dp(56),dp(48)));}
                card.addView(context,new LinearLayout.LayoutParams(-1,-2));
            }
            if(topicRowFactory!=null)topicRowFactory.attach(card,item.tid,item.pid,item.title,item.time,item.replies,item.votes);
            else {
                if(!replies&&(!validId(item.pid)||"0".equals(item.pid))&&topicPreviews!=null)topicPreviews.attach(card,item.tid,item.title);
                LinearLayout stats=new LinearLayout(activity);stats.setGravity(Gravity.CENTER_VERTICAL);stats.setPadding(0,dp(7),0,dp(3));
                TextView metadata=text(item.replies+" 回复",12,muted,false);metadata.setMaxLines(2);metadata.setPadding(0,0,dp(10),0);stats.addView(metadata,new LinearLayout.LayoutParams(0,-2,1));
                TextView time=text(item.time==null?"":item.time,11,muted,false);time.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);time.setSingleLine();time.setEllipsize(TextUtils.TruncateAt.END);time.setMaxWidth(dp(126));stats.addView(time,new LinearLayout.LayoutParams(-2,-2));
                card.addView(stats,new LinearLayout.LayoutParams(-1,-2));
            }
    }
    /** Replies must retain their own positive pid; missing locators never silently open the topic head. */
    static boolean openTopic(Listener listener,NativeCommunity.Topic item,boolean favorites,boolean replies){
        if(item==null||!validId(item.tid)||(replies&&!validId(item.pid)))return false;
        if(favorites||replies)listener.topic(item.tid,replies?item.pid:favoritePid(item.pid),item.title);else listener.topic(item.tid,item.title);
        return true;
    }
    static String favoritePid(String pid){return "0".equals(pid)||validId(pid)?pid:"";}
    static String voteSummary(VoteMetrics votes) { return (votes==null?new VoteMetrics():votes).summary(); }

    private LinearLayout personRow(String avatarUrl,String label,String uid,String detail,int sizeDp) {
        String name=label==null||label.trim().isEmpty()?"NGA 用户":label;
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(7),0,dp(3));row.setMinimumHeight(dp(Math.max(48,sizeDp+10)));
        AvatarView portrait=new AvatarView(activity,sizeDp,media);portrait.bindUser(uid,avatarUrl,name);
        LinearLayout.LayoutParams imageParams=new LinearLayout.LayoutParams(dp(sizeDp),dp(sizeDp));imageParams.setMargins(0,0,dp(10),0);row.addView(portrait,imageParams);
        LinearLayout labels=column();TextView author=text(name,sizeDp>=64?21:13,ink,true);author.setSingleLine();author.setEllipsize(TextUtils.TruncateAt.END);labels.addView(author,new LinearLayout.LayoutParams(-1,-2));
        if(detail!=null&&!detail.isEmpty()){TextView description=text(detail,12,muted,false);description.setMaxLines(2);description.setEllipsize(TextUtils.TruncateAt.END);labels.addView(description,new LinearLayout.LayoutParams(-1,-2));}
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        if(validId(uid)){
            // One full-height target avoids separate 30dp portraits and short text links.
            row.setFocusable(true);row.setOnClickListener(v->{if(alive())listener.user(uid);});
            row.setContentDescription(name+(detail==null||detail.isEmpty()?"":"，"+detail)+"，查看用户资料");
            portrait.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        return row;
    }
    private void removeFavorite(NativeCommunity.Topic item) {
        NativeGlassDialog confirm=dialog().setTitle("移除服务器收藏").setMessage("主题："+item.title+"\n条目："+item.tid+(item.pid.isEmpty()||"0".equals(item.pid)?"":" / "+item.pid)).setNegativeButton("取消",null).setPositiveButton("确认移除",null).create();
        show(confirm);confirm.getButton(NativeGlassDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(writing)return;writing=true;confirm.getButton(NativeGlassDialog.BUTTON_POSITIVE).setEnabled(false);confirm.getButton(NativeGlassDialog.BUTTON_NEGATIVE).setEnabled(false);confirm.setCancelable(false);
            writeTask(()->api.removeFavorite(item.tid,item.pid,id.isEmpty()?"1":id,requestedPage),result->{writing=false;confirm.dismiss();toast(result.message);reload();},e->{writing=false;confirm.dismiss();showFailure(e);});
        });
    }
    private void compose(NativeCommunity.MessagePage reply){editor(reply,"");}
    private void composeTo(String uid){editor(null,uid);}
    private boolean sameAccount(){return messageDrafts!=null&&store.loggedIn()&&scopeHost.equals(store.host())&&scopeAccount.equals(store.accountId());}
    private void editor(NativeCommunity.MessagePage reply,String recipient) {
        if(!sameAccount()){listener.login();return;}
        final String targetMid=reply==null?"":reply.mid;
        final NativeMessageDrafts.Draft restored;
        try{restored=messageDrafts.get(targetMid);}catch(IllegalStateException unavailable){toast(unavailable.getMessage());return;}
        CommunityWritePolicy gate=new CommunityWritePolicy();if(restored!=null&&restored.pending)gate.restoreUncertain();
        final boolean[] completed={false},locked={gate.uncertain()},dirty={false};
        LinearLayout fields=column();fields.setPadding(dp(20),dp(8),dp(20),dp(8));
        EditText to=input("收件人 UID，多个用英文逗号分隔",false);to.setText(restored==null?recipient:restored.toUid);
        EditText lookupName=input("输入完整用户名查找收件人",false);TextView lookup=button("查找用户",()->{});
        EditText subject=input("标题",false),body=input("私信正文",true);
        subject.setText(restored==null?(reply==null?"":reply.subject):restored.subject);body.setText(restored==null?"":restored.body);
        if(reply==null){fields.addView(text("按用户名选择收件人",12,muted,false));fields.addView(lookupName);fields.addView(lookup);fields.addView(text("收件人 UID（支持最多 10 人）",12,muted,false));fields.addView(to);lookup.setEnabled(!locked[0]);lookupName.setEnabled(!locked[0]);}
        fields.addView(subject);fields.addView(body);TextView status=text(locked[0]?"上次发送结果未确认，已恢复锁定的正文。请先只读刷新核对；本窗口不会再次发送。":"本机自动保存草稿。提交前会显示收件人、标题和完整正文供确认。",12,muted,false);fields.addView(status);
        TextView discard=button("清除本机草稿",()->{});fields.addView(discard);
        to.setEnabled(!locked[0]);subject.setEnabled(!locked[0]);body.setEnabled(!locked[0]);
        Runnable persist=()->{
            if(closed||completed[0]||locked[0]||(!dirty[0]&&restored==null))return;
            try{messageDrafts.saveEdited(targetMid,to.getText().toString(),subject.getText().toString(),body.getText().toString(),dirty[0]);}
            catch(IllegalArgumentException|IllegalStateException unavailable){status.setText(unavailable.getMessage());}
        };
        TextWatcher watcher=new TextWatcher(){
            public void beforeTextChanged(CharSequence text,int start,int count,int after){}
            public void onTextChanged(CharSequence text,int start,int before,int count){}
            public void afterTextChanged(Editable value){dirty[0]=true;removeCallbacks(persist);if(!completed[0]&&!locked[0])postDelayed(persist,350);}
        };
        to.addTextChangedListener(watcher);subject.addTextChangedListener(watcher);body.addTextChangedListener(watcher);saveOpenEditor=persist;
        ScrollView scroll=new ScrollView(activity);scroll.addView(fields);
        NativeGlassDialog editor=dialog().setTitle(reply==null?"新私信":"回复私信").setView(scroll).setNegativeButton(locked[0]?"关闭":"取消",null).setNeutralButton("刷新核对",null).setPositiveButton("检查并发送",null).create();
        show(editor);editor.setOnDismissListener(dialog->{dialogs.remove(editor);removeCallbacks(persist);persist.run();if(saveOpenEditor==persist)saveOpenEditor=null;});
        if(reply==null)lookup.setOnClickListener(v->{
            if(locked[0]||completed[0]||!liveEditor(editor))return;String username=lookupName.getText().toString().trim();if(username.isEmpty()||username.length()>100){lookupName.setError("请输入 1–100 字的完整用户名");return;}
            lookup.setEnabled(false);status.setText("正在查找用户…");
            task(()->api.findUser(username),profile->{
                if(!liveEditor(editor)||completed[0]||locked[0])return;lookup.setEnabled(true);status.setText("请核对查找结果后添加收件人");media.rememberUserAvatar(profile.uid,profile.avatar);LinearLayout person=column();person.addView(personRow(profile.avatar,profile.username,profile.uid,"UID "+profile.uid,44));
                NativeGlassDialog found=dialog().setTitle("确认收件人").setView(person).setNegativeButton("取消",null).setPositiveButton("添加到收件人",null).create();show(found);
                found.getButton(NativeGlassDialog.BUTTON_POSITIVE).setOnClickListener(add->{
                    if(!liveEditor(editor)||completed[0]||locked[0]){found.dismiss();return;}String existing=to.getText().toString().trim().replace('，',',');List<String> recipients=new ArrayList<>();if(!existing.isEmpty())for(String value:existing.split(",",-1)){String candidate=value.trim();if(!validId(candidate)){to.setError("请先修正已输入的收件人 UID");found.dismiss();return;}if(!recipients.contains(candidate))recipients.add(candidate);}
                    if(!recipients.contains(profile.uid)){if(recipients.size()>=10){to.setError("最多选择 10 位收件人");found.dismiss();return;}recipients.add(profile.uid);}StringBuilder selected=new StringBuilder();for(String value:recipients){if(selected.length()>0)selected.append(',');selected.append(value);}to.setText(selected.toString());lookupName.getText().clear();status.setText("已添加收件人，发送前还会显示完整确认");found.dismiss();
                });
            },failure->{if(liveEditor(editor)){lookup.setEnabled(true);status.setText("用户查找未完成："+safeError(failure));}});
        });
        editor.getButton(NativeGlassDialog.BUTTON_NEUTRAL).setVisibility(locked[0]?View.VISIBLE:View.GONE);
        editor.getButton(NativeGlassDialog.BUTTON_POSITIVE).setEnabled(gate.canSubmit());
        editor.getButton(NativeGlassDialog.BUTTON_NEUTRAL).setOnClickListener(v->{if(liveEditor(editor)&&gate.uncertain())verifyUncertain(reply);});
        discard.setOnClickListener(v->{
            if(!liveEditor(editor)||writing||completed[0])return;removeCallbacks(persist);persist.run();
            final NativeMessageDrafts.Draft snapshot;
            try{snapshot=messageDrafts.get(targetMid);}catch(IllegalStateException unavailable){status.setText(unavailable.getMessage());return;}
            if(snapshot==null){status.setText("当前没有已保存的本机草稿");return;}
            String warning=snapshot.pending?"请先刷新核对服务器是否已经收到。清除只移除本机正文和待核对记录，不会撤回或删除服务器私信；原请求仍可能已送达。清除后可另建草稿，不会自动重发。":"只清除当前设备的这份草稿，不会删除服务器消息，也不会发送内容。";
            NativeGlassDialog local=dialog().setTitle("清除本机私信草稿？").setMessage(warning).setNegativeButton("保留草稿",null).setPositiveButton("确认清除本机记录",(dialog,which)->{
                if(!liveEditor(editor)||writing||completed[0])return;
                try{if(!messageDrafts.discard(snapshot)){status.setText("草稿已变化，请关闭后重新打开核对，本次未清除");return;}}
                catch(IllegalStateException unavailable){status.setText(unavailable.getMessage());return;}
                completed[0]=true;removeCallbacks(persist);editor.dismiss();toast("已清除本机草稿，服务器消息未更改");
            }).create();show(local);
        });
        editor.getButton(NativeGlassDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(!liveEditor(editor)||writing||!gate.canSubmit())return;
            if(!sameAccount()){status.setText("账号或线路已改变，请关闭后在对应账号中继续；草稿仍保存在原账号范围。");persist.run();return;}
            final String recipientValue=to.getText().toString().trim().replace('，',','),subjectValue=subject.getText().toString(),bodyValue=body.getText().toString();
            if((reply==null&&(recipientValue.isEmpty()||subjectValue.trim().isEmpty()))||bodyValue.trim().isEmpty()){status.setText("请填写收件人、标题和正文");return;}
            String target=reply==null?"收件人 UID："+recipientValue:"当前会话："+reply.mid;
            TextView review=text(target+"\n\n标题："+subjectValue+"\n\n正文：\n"+bodyValue,15,ink,false);review.setPadding(dp(22),dp(12),dp(22),dp(12));review.setTextIsSelectable(true);
            ScrollView reviewScroll=new ScrollView(activity);reviewScroll.addView(review);
            NativeGlassDialog confirm=dialog().setTitle("确认发送私信").setView(reviewScroll).setNegativeButton("返回编辑",null).setPositiveButton("确认发送",null).create();
            show(confirm);confirm.getButton(NativeGlassDialog.BUTTON_POSITIVE).setOnClickListener(send->{
                if(!liveEditor(editor)||writing||!gate.canSubmit())return;
                if(!sameAccount()){confirm.dismiss();status.setText("账号或线路已改变，本次没有发送；草稿保留在原账号。");persist.run();return;}
                final NativeMessageDrafts.Draft pending;
                removeCallbacks(persist);
                try{pending=messageDrafts.prepareSend(targetMid,recipientValue,subjectValue,bodyValue);}
                catch(IllegalArgumentException|IllegalStateException unavailable){confirm.dismiss();status.setText(unavailable.getMessage());return;}
                if(!gate.begin())return;locked[0]=true;writing=true;
                to.setEnabled(false);subject.setEnabled(false);body.setEnabled(false);
                confirm.getButton(NativeGlassDialog.BUTTON_POSITIVE).setEnabled(false);confirm.getButton(NativeGlassDialog.BUTTON_NEGATIVE).setEnabled(false);confirm.setCancelable(false);editor.setCancelable(false);editor.getButton(NativeGlassDialog.BUTTON_NEGATIVE).setEnabled(false);editor.getButton(NativeGlassDialog.BUTTON_POSITIVE).setEnabled(false);status.setText("待核对快照已保存，正在等待服务器确认，请勿重复发送…");
                Work<CommunityMessageDelivery.Result> work=()->CommunityMessageDelivery.run(messageDrafts,pending,
                        ()->reply==null?api.sendMessage(recipientValue,subjectValue,bodyValue):api.replyMessage(reply.mid,subjectValue,bodyValue));
                Success<CommunityMessageDelivery.Result> present=outcome->{
                    if(outcome.acknowledgement!=null){
                        gate.confirmed();completed[0]=true;writing=false;removeCallbacks(persist);
                        confirm.dismiss();editor.dismiss();toast(outcome.snapshotCleared?outcome.acknowledgement.message:"服务器已确认发送，本机恢复记录未清理，重新打开后请先核对");
                        if(reply==null)listener.navigate("inbox","","私信",1);else reload();
                        return;
                    }
                    boolean uncertain=outcome.uncertain;
                    gate.failed(uncertain);locked[0]=uncertain;writing=false;confirm.dismiss();editor.setCancelable(true);editor.getButton(NativeGlassDialog.BUTTON_NEGATIVE).setEnabled(true);
                    to.setEnabled(!uncertain);subject.setEnabled(!uncertain);body.setEnabled(!uncertain);editor.getButton(NativeGlassDialog.BUTTON_POSITIVE).setEnabled(gate.canSubmit());
                    if(gate.uncertain()){editor.getButton(NativeGlassDialog.BUTTON_NEUTRAL).setVisibility(View.VISIBLE);editor.getButton(NativeGlassDialog.BUTTON_NEGATIVE).setText("关闭");status.setText(safeError(outcome.error)+"\n本次发送已持久锁定。正文保留，请先刷新核对服务器会话；关闭或进程重建也不会自动解锁。");}
                    else status.setText(safeError(outcome.error));
                };
                writeTask(work,present,error->present.accept(CommunityMessageDelivery.unknown(error)));
            });
        });
    }
    private void verifyUncertain(NativeCommunity.MessagePage reply) {
        if(!alive()||!sameAccount())return;
        if(reply!=null){verifyConversation(reply.mid,reply.page);return;}
        LinearLayout snapshot=column();snapshot.setPadding(dp(20),dp(10),dp(20),dp(10));loading(snapshot);
        ScrollView scroll=new ScrollView(activity);scroll.addView(snapshot);NativeGlassDialog review=dialog().setTitle("核对服务器私信").setView(scroll).setPositiveButton("返回保留的正文",null).create();show(review);
        task(()->api.conversations(1),page->{
            if(!liveDialog(review)||!sameAccount())return;
            snapshot.removeAllViews();snapshot.addView(text("以下为当前服务器会话，请人工核对。本次发送仍保持锁定。",13,muted,false));
            if(page.items.isEmpty())empty(snapshot,"服务器当前未返回会话；这不证明刚才的请求没有送达");
            for(NativeCommunity.Conversation item:page.items){LinearLayout card=card(snapshot);card.addView(text(item.subject,16,ink,true));card.addView(text(item.time,12,muted,false));card.addView(button("只读查看会话",()->replaceConversation(review,item.mid,1)));}
        },e->{if(liveDialog(review)&&sameAccount()){snapshot.removeAllViews();snapshot.addView(text(safeError(e),14,muted,false));}});
    }
    private void verifyConversation(String mid,int page) {
        if(!alive()||!sameAccount())return;
        LinearLayout snapshot=column();snapshot.setPadding(dp(20),dp(10),dp(20),dp(10));loading(snapshot);
        ScrollView scroll=new ScrollView(activity);scroll.addView(snapshot);NativeGlassDialog review=dialog().setTitle("核对服务器会话").setView(scroll).setPositiveButton("返回保留的正文",null).create();show(review);
        task(()->api.conversation(mid,page),result->{
            if(!liveDialog(review)||!sameAccount())return;
            snapshot.removeAllViews();snapshot.addView(text("只读核对，不会重发或解锁原提交。",13,muted,false));
            if(result.items.isEmpty())empty(snapshot,"本页暂无消息；请继续人工核对");
            for(NativeCommunity.Message item:result.items){LinearLayout card=card(snapshot);card.addView(personRow(item.avatar,item.username,item.uid,item.time,36));messageBody(card,item.content);}
            if(result.page>1)snapshot.addView(button("上一页",()->replaceConversation(review,mid,result.page-1)));
            if(result.hasNext)snapshot.addView(button("下一页",()->replaceConversation(review,mid,result.nextPage)));
        },e->{if(liveDialog(review)&&sameAccount()){snapshot.removeAllViews();snapshot.addView(text(safeError(e),14,muted,false));}});
    }
    private void replaceConversation(NativeGlassDialog previous,String mid,int page){if(!liveDialog(previous)||!sameAccount())return;previous.dismiss();verifyConversation(mid,page);}
    private boolean alive(){return !closed&&!activity.isFinishing()&&!activity.isDestroyed();}
    private boolean liveDialog(NativeGlassDialog dialog){return alive()&&dialog!=null&&dialog.isShowing()&&!dialog.isClosing();}
    private boolean liveEditor(NativeGlassDialog dialog){return liveDialog(dialog)&&sameAccount();}
    private void pager(LinearLayout target,int page,boolean hasNext,int nextPage,String route,String routeId) {pager(target,page,hasNext,nextPage,route,routeId,0);}
    private void pager(LinearLayout target,int page,boolean hasNext,int nextPage,String route,String routeId,int totalPages) {
        String title="conversation".equals(route)?"私信会话":"inbox".equals(route)?"私信":"serverfav".equals(route)?"云端收藏":("self".equals(route)||"selfreplies".equals(route))?"我的资料":"用户资料";
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(12),0,dp(4));
        int rowHeight=LinearLayout.LayoutParams.WRAP_CONTENT;
        TextView previous=button("上一页",()->listener.navigate(route,routeId,title,page-1));previous.setEnabled(page>1);previous.setAlpha(page>1?1f:.4f);row.addView(previous,new LinearLayout.LayoutParams(0,rowHeight,1));
        TextView number=text(totalPages>=page?"第 "+page+" / "+totalPages+" 页":"第 "+page+" 页",13,muted,true);number.setGravity(Gravity.CENTER);number.setMinHeight(dp(48));row.addView(number,new LinearLayout.LayoutParams(0,rowHeight,1));
        boolean more=hasNext&&nextPage>page;TextView next=button("下一页",()->listener.navigate(route,routeId,title,nextPage));next.setEnabled(more);next.setAlpha(more?1f:.4f);row.addView(next,new LinearLayout.LayoutParams(0,rowHeight,1));target.addView(row);
        if(!more&&page>1){TextView end=text("已到最后一页",12,muted,false);end.setGravity(Gravity.CENTER);target.addView(end);}
    }
    private void error(LinearLayout target,Exception failure,Runnable retry) {
        target.removeAllViews();LinearLayout card=card(target);card.addView(text("暂时无法载入",17,ink,true));card.addView(text(safeError(failure),14,muted,false));
        if(failure instanceof NativeCommunity.CommunityException&&(((NativeCommunity.CommunityException)failure).code==NativeCommunity.Code.LOGIN_REQUIRED||((NativeCommunity.CommunityException)failure).code==NativeCommunity.Code.CHALLENGE_REQUIRED))card.addView(button("登录或重新验证 NGA",listener::login));
        card.addView(button("重试",retry));
    }
    private void showFailure(Exception error){NativeGlassDialog dialog=dialog().setTitle("操作未确认").setMessage(safeError(error)).setPositiveButton("知道了",null).create();show(dialog);}
    private void messageBody(LinearLayout target,String source) {
        NativeRichText body=new NativeRichText(activity,media,source,dark,store.fontSize(),this::openLink);
        body.setPadding(0,dp(12),0,dp(8));target.addView(body,new LinearLayout.LayoutParams(-1,-2));
    }
    private void openLink(String raw) {
        if(closed)return;NativeLinkTarget target=NativeLinkTarget.parse(raw,store.host());
        if(target.kind==NativeLinkTarget.INVALID){toast("链接格式不正确");return;}
        if(target.kind==NativeLinkTarget.BLOCKED){toast("请从客户端对应功能确认这项操作");return;}
        if(listener.openLink(target.url))return;
        switch(target.kind){
            case NativeLinkTarget.TOPIC:listener.topic(target.topicId,target.postId,"相关主题");return;
            case NativeLinkTarget.USER:listener.user(target.userId);return;
            case NativeLinkTarget.FORUM:listener.navigate("forum",target.forumId,"版块 "+target.forumId,target.page);return;
            case NativeLinkTarget.FAVORITE:listener.navigate("serverfav",target.favoriteId,"云端收藏",target.page);return;
            case NativeLinkTarget.SELF:listener.navigate("self","","我的资料",1);return;
            case NativeLinkTarget.MESSAGES:listener.navigate("inbox","","消息与私信",target.page);return;
            case NativeLinkTarget.REMINDERS:listener.navigate("reminders","","回复提醒",1);return;
            case NativeLinkTarget.LOGIN:listener.login();return;
            case NativeLinkTarget.HOME:listener.navigate("home","","NGA",1);return;
            default:break;
        }
        NativeGlassDialog confirm=dialog().setTitle(target.official?"打开 NGA 页面":"打开外部链接").setMessage(target.url)
                .setNegativeButton("取消",null).setPositiveButton("浏览器打开",(dialog,which)->{
                    if(!closed&&!BrowserLinks.open(activity,target.url))toast("未找到可用的系统浏览器");
                }).create();show(confirm);
    }
    private String safeError(Exception error){return error instanceof NativeCommunity.CommunityException?error.getMessage():error instanceof IllegalArgumentException?"输入格式不正确，请检查编号、标题和正文长度":"网络或数据处理失败，请稍后重试";}
    private void loading(LinearLayout target){target.removeAllViews();ProgressBar progress=new ProgressBar(activity);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(28),dp(28));p.gravity=Gravity.CENTER_HORIZONTAL;p.setMargins(0,dp(24),0,dp(12));target.addView(progress,p);TextView label=text("正在读取 NGA…",14,muted,false);label.setGravity(Gravity.CENTER);target.addView(label);}
    private void empty(LinearLayout target,String label){LinearLayout card=card(target);card.addView(text(label,15,muted,false));}
    private void heading(LinearLayout target,String title,String subtitle){TextView heading=text(title,21,ink,true);heading.setPadding(0,dp(10),0,dp(8));target.addView(heading);if(!subtitle.isEmpty())target.addView(text(subtitle,12,muted,false));}
    private LinearLayout column(){LinearLayout layout=new LinearLayout(activity);layout.setOrientation(LinearLayout.VERTICAL);return layout;}
    private LinearLayout card(LinearLayout parent){LinearLayout card=column();card.setPadding(dp(16),dp(16),dp(16),dp(16));GradientDrawable drawable=new GradientDrawable();drawable.setColor(surface);drawable.setCornerRadius(dp(18));drawable.setStroke(dp(1),line);card.setBackground(drawable);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(12),0,0);parent.addView(card,p);return card;}
    private TextView text(String value,int size,int color,boolean bold){TextView view=new TextView(activity);view.setText(value==null?"":value);view.setTextSize(size);view.setTextColor(color);view.setIncludeFontPadding(false);view.setPadding(0,dp(4),0,dp(4));if(bold)view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return view;}
    private TextView button(String value,Runnable action){TextView button=text(value,14,ink,true);button.setGravity(Gravity.CENTER);button.setPadding(dp(10),dp(12),dp(10),dp(12));button.setMinHeight(dp(48));button.setFocusable(true);button.setBackgroundResource(android.R.drawable.list_selector_background);button.setOnClickListener(v->{if(alive())action.run();});return button;}
    private EditText input(String hint,boolean multiline){EditText input=new EditText(activity);input.setHint(hint);input.setTextSize(15);input.setTextColor(ink);input.setHintTextColor(muted);input.setInputType(InputType.TYPE_CLASS_TEXT|(multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));input.setSingleLine(!multiline);if(multiline){input.setMinLines(5);input.setGravity(Gravity.TOP);}return input;}
    private NativeGlassDialog.Builder dialog(){return new NativeGlassDialog.Builder(activity,dark);}
    private void show(NativeGlassDialog dialog){if(closed)return;dialogs.add(dialog);dialog.setOnDismissListener(d->dialogs.remove(dialog));dialog.show();}
    private void toast(String text){if(!closed)Toast.makeText(activity,text,Toast.LENGTH_SHORT).show();}
    private static boolean validId(String value){return value!=null&&value.matches("[1-9][0-9]{0,17}");}
    private static String readable(String raw){return PostRenderer.plainText(raw).trim();}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
