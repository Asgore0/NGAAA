package cn.ngapure.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual product Main and Android preferences, isolated UID and local synthetic read transport. */
public final class EverydayProbe extends Instrumentation {
    private static final String GOOD="7900000001", FAILED="7900000002", PENDING="7900000003", START="7900000004", OLD_SCOPE="7900000005";
    private static final String SYNTHETIC_A="ngaPassportUid=79001; ngaPassportCid=synthetic-everyday-a";
    private static final String SYNTHETIC_B="ngaPassportUid=79002; ngaPassportCid=synthetic-everyday-b";
    private static final CountDownLatch PENDING_ENTERED=new CountDownLatch(1), PENDING_RELEASE=new CountDownLatch(1);
    private static final CountDownLatch NEW_SCOPE_ENTERED=new CountDownLatch(1), NEW_SCOPE_RELEASE=new CountDownLatch(1);
    private static final AtomicInteger READ_RESPONSES=new AtomicInteger(), BLOCKED_WRITES=new AtomicInteger();
    private final JSONObject report=new JSONObject();
    private MainActivity activity;
    private boolean authorized;
    private int assertions;
    private String stage="guard";

    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticEverydayOnly"));start();}
    private static Object field(Object target,String name)throws Exception{Field member=target.getClass().getDeclaredField(name);member.setAccessible(true);return member.get(target);}
    private static void set(Object target,String name,Object value)throws Exception{Field member=target.getClass().getDeclaredField(name);member.setAccessible(true);member.set(target,value);}
    private static Method method(Class<?> type,String name,Class<?>... types)throws Exception{Method value=type.getDeclaredMethod(name,types);value.setAccessible(true);return value;}
    private <T>T main(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private void check(String label,boolean value)throws Exception{report.put(label,value);assertions++;if(!value)throw new AssertionError(label);}
    private void invoke(String name,Class<?>[] types,Object... values)throws Exception{main(()->{method(MainActivity.class,name,types).invoke(activity,values);return null;});}
    private String scope()throws Exception{return main(()->(String)method(MainActivity.class,"localDataScope").invoke(activity));}
    private void screen(String kind,String tid,int page)throws Exception{
        main(()->{Class<?> type=Class.forName("cn.ngapure.app.MainActivity$Screen");Constructor<?> constructor=type.getDeclaredConstructor(String.class,String.class,String.class,int.class);constructor.setAccessible(true);Object next=constructor.newInstance(kind,tid,"合成常用功能验证",page);method(MainActivity.class,"go",type,boolean.class).invoke(activity,next,false);return null;});waitForIdleSync();
    }
    private static boolean textContains(View view,String needle){if(view instanceof TextView&&((TextView)view).getText().toString().contains(needle))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(textContains(group.getChildAt(i),needle))return true;}return false;}
    private void awaitLoaded(String tid,int page)throws Exception{
        for(int i=0;i<150;i++){
            if(main(()->{Object current=field(activity,"current");return "thread".equals(field(current,"kind"))&&tid.equals(field(current,"id"))&&Integer.valueOf(page).equals(field(current,"page"))&&Integer.valueOf(3).equals(field(activity,"nativePostCount"))&&textContains(activity.getWindow().getDecorView(),"合成只读正文 "+tid);})){waitForIdleSync();SystemClock.sleep(100);return;}
            SystemClock.sleep(30);
        }
        throw new IllegalStateException("SYNTHETIC_THREAD_LOAD_TIMEOUT");
    }
    private static NgaApi.Topic topic(String tid){NgaApi.Topic value=new NgaApi.Topic();value.tid=tid;value.title="合成常用功能 "+tid;value.fid="650";value.author="合成作者";return value;}
    private static Object storeCall(NgaStore store,String name,Class<?>[] types,Object... args)throws Exception{return method(NgaStore.class,name,types).invoke(store,args);}
    private static boolean contains(List<NgaApi.Topic> topics,String tid){for(NgaApi.Topic topic:topics)if(tid.equals(topic.tid))return true;return false;}
    private void historyChecks(NgaStore store)throws Exception{
        stage="historyAndLocalSaved";
        storeCall(store,"clearHistory",new Class<?>[0]);storeCall(store,"clearSavedTopics",new Class<?>[0]);
        storeCall(store,"setHistoryEnabled",new Class<?>[]{boolean.class},true);
        check("historyEnabledPersistsAcrossStoreInstances",Boolean.TRUE.equals(storeCall(new NgaStore(getTargetContext()),"historyEnabled",new Class<?>[0])));
        NgaApi.Topic one=topic("7910000001"),two=topic("7910000002"),three=topic("7910000003");
        store.recordHistory(one);store.recordHistory(two);store.recordHistory(one);
        check("historyDeduplicatesAndMovesLatestFirst",store.history().size()==2&&one.tid.equals(store.history().get(0).tid));
        storeCall(store,"setHistoryEnabled",new Class<?>[]{boolean.class},false);store.recordHistory(three);
        check("disabledHistoryDoesNotRecordNewTopic",!contains(store.history(),three.tid)&&store.history().size()==2);
        check("disabledHistoryPersistsWithoutClearingOldRecords",Boolean.FALSE.equals(storeCall(new NgaStore(getTargetContext()),"historyEnabled",new Class<?>[0]))&&new NgaStore(getTargetContext()).history().size()==2);
        storeCall(store,"removeHistory",new Class<?>[]{String.class},one.tid);
        check("singleHistoryRemovalPreservesOtherEntries",!contains(store.history(),one.tid)&&contains(store.history(),two.tid));
        store.toggleTopic(one);store.toggleTopic(two);
        storeCall(store,"removeSavedTopic",new Class<?>[]{String.class},one.tid);
        check("singleLocalFavoriteRemovalPreservesOthers",!store.isTopicSaved(one.tid)&&store.isTopicSaved(two.tid));
        storeCall(store,"clearSavedTopics",new Class<?>[0]);
        check("clearingLocalFavoritesDoesNotClearHistory",store.savedTopics().isEmpty()&&contains(store.history(),two.tid));
        store.toggleTopic(one);storeCall(store,"clearHistory",new Class<?>[0]);
        check("clearingHistoryDoesNotClearLocalFavorites",store.history().isEmpty()&&store.isTopicSaved(one.tid));
        storeCall(store,"setHistoryEnabled",new Class<?>[]{boolean.class},true);store.recordHistory(three);
        check("reenabledHistoryRecordsAgain",contains(new NgaStore(getTargetContext()).history(),three.tid));
        storeCall(store,"clearSavedTopics",new Class<?>[0]);storeCall(store,"clearHistory",new Class<?>[0]);
    }
    private void androidProgressStorageChecks()throws Exception{
        stage="androidProgressPreferences";
        ReadingProgress progress=new ReadingProgress(getTargetContext());String a=repeat('d',64),b=repeat('e',64);
        progress.clear(a);progress.clear(b);
        ReaderAnchor anchor=ReaderAnchor.create("7920000001",3,42,5000,800,1400);
        check("actualAndroidPreferencesSaveProgress",progress.save(a,"7920000001",3,anchor));
        ReadingProgress.Entry entry=new ReadingProgress(getTargetContext()).get(a,"7920000001");
        check("newStoreRestoresAllReaderGeometry",entry!=null&&entry.page==3&&entry.updatedAt>0&&entry.anchor!=null&&entry.anchor.floor==42&&entry.anchor.fraction==5000&&entry.anchor.columnWidth==800&&entry.anchor.scrollY==1400);
        check("anotherScopeCannotReadSavedProgress",progress.get(b,"7920000001")==null);
        check("invalidSaveCannotOverwriteLoadedPage",!progress.save(a,"7920000001",0,null)&&progress.get(a,"7920000001").page==3);
        Map<String,ReadingProgress.Entry> snapshot=progress.snapshot(a);
        check("androidSnapshotContainsOnlySelectedScope",snapshot.size()==1&&snapshot.get("7920000001").page==3);
        boolean immutable=false;try{snapshot.clear();}catch(UnsupportedOperationException expected){immutable=true;}
        check("androidSnapshotCannotBeModified",immutable);
        check("scopeClearPreservesOtherScope",progress.save(b,"7920000001",2,null)&&progress.clear(a)&&progress.get(a,"7920000001")==null&&progress.get(b,"7920000001").page==2);
        check("androidSnapshotRemainsStableAfterScopeClear",snapshot.size()==1&&snapshot.get("7920000001").page==3);
        check("singleProgressRemovalIsDurable",progress.remove(b,"7920000001")&&new ReadingProgress(getTargetContext()).get(b,"7920000001")==null);
    }
    private static String repeat(char c,int count){StringBuilder value=new StringBuilder();while(count-->0)value.append(c);return value.toString();}
    private static void awaitWrites()throws Exception{
        Field field=MainActivity.class.getDeclaredField("READING_WRITES");field.setAccessible(true);
        FutureTask<Void> barrier=new FutureTask<>(()->null);((Executor)field.get(null)).execute(barrier);barrier.get(5,TimeUnit.SECONDS);
    }
    private static boolean awaitPage(ReadingProgress progress,String scope,String tid,int page){
        long until=SystemClock.uptimeMillis()+5000;
        do{ReadingProgress.Entry entry=progress.get(scope,tid);if(entry!=null&&entry.page==page)return true;SystemClock.sleep(30);}while(SystemClock.uptimeMillis()<until);
        return false;
    }
    private static boolean remainsPage(ReadingProgress progress,String scope,String tid,int expected){
        long until=SystemClock.uptimeMillis()+1500;
        do{ReadingProgress.Entry entry=progress.get(scope,tid);if(expected<0?entry!=null:entry==null||entry.page!=expected)return false;SystemClock.sleep(30);}while(SystemClock.uptimeMillis()<until);
        return true;
    }
    private void mainProgressChecks(NgaStore store)throws Exception{
        stage="mainLoadedPageProgress";ReadingProgress progress=new ReadingProgress(getTargetContext());String a=scope();progress.clear(a);
        screen("thread",GOOD,3);awaitLoaded(GOOD,3);
        main(()->{ScrollView scroll=(ScrollView)field(activity,"currentScroll");scroll.scrollTo(0,450);return null;});waitForIdleSync();
        invoke("persistReadingProgress",new Class<?>[0]);
        awaitWrites();
        check("asyncLoadedPageSaveCompletes",awaitPage(progress,a,GOOD,3));
        ReadingProgress.Entry saved=progress.get(a,GOOD);
        check("mainSavesSuccessfullyLoadedPage",saved!=null&&saved.page==3);
        check("mainCapturesActualReaderAnchor",saved!=null&&saved.anchor!=null&&saved.anchor.matches(GOOD,3));
        stage="sameAccountCredentialRefresh";
        String originalExact=main(()->(String)method(MainActivity.class,"contentScope").invoke(activity));
        String[] refreshed=main(()->{store.setCookie("ngaPassportUid=79001; ngaPassportCid=synthetic-everyday-refreshed");method(MainActivity.class,"persistReadingProgress").invoke(activity);return new String[]{(String)method(MainActivity.class,"localDataScope").invoke(activity),(String)method(MainActivity.class,"contentScope").invoke(activity)};});
        awaitWrites();
        check("sameAccountReloginKeepsLocalDataScope",a.equals(refreshed[0])&&!originalExact.equals(refreshed[1]));
        check("sameAccountCredentialRefreshRetainsDurableProgress",progress.get(refreshed[0],GOOD)!=null&&progress.get(refreshed[0],GOOD).page==3);
        main(()->{store.setCookie(SYNTHETIC_A);return null;});screen("settings","",1);
        stage="mainFailedPageProgress";progress.save(a,FAILED,2,null);screen("thread",FAILED,4);
        for(int i=0;i<120;i++){if(main(()->textContains(activity.getWindow().getDecorView(),"暂时无法加载")))break;SystemClock.sleep(30);}
        invoke("persistReadingProgress",new Class<?>[0]);
        awaitWrites();
        check("failedPageDoesNotReplaceLoadedProgress",remainsPage(progress,a,FAILED,2));
        stage="mainPendingPageProgress";progress.save(a,PENDING,1,null);screen("thread",PENDING,2);
        check("syntheticReadActuallyWaitsBeforeMetadata",PENDING_ENTERED.await(5,TimeUnit.SECONDS));
        invoke("persistReadingProgress",new Class<?>[0]);
        awaitWrites();
        check("pendingReadDoesNotAdvanceProgress",remainsPage(progress,a,PENDING,1));
        PENDING_RELEASE.countDown();awaitLoaded(PENDING,2);invoke("persistReadingProgress",new Class<?>[0]);
        check("pendingPageBecomesProgressOnlyAfterSuccess",awaitPage(progress,a,PENDING,2));
        stage="mainOldScopeProgress";screen("thread",OLD_SCOPE,2);awaitLoaded(OLD_SCOPE,2);
        String b=main(()->{store.setCookie(SYNTHETIC_B);String current=(String)method(MainActivity.class,"localDataScope").invoke(activity);progress.clear(current);method(MainActivity.class,"persistReadingProgress").invoke(activity);return current;});
        waitForIdleSync();awaitWrites();
        check("changedSessionNeverStoresOldPageUnderNewScope",!a.equals(b)&&remainsPage(progress,b,OLD_SCOPE,-1));
        check("scopeRefreshActuallyStartsBlockedNewAccountRead",NEW_SCOPE_ENTERED.await(5,TimeUnit.SECONDS));
        invoke("persistReadingProgress",new Class<?>[0]);awaitWrites();
        check("pendingNewAccountReadCannotStorePreviousAccountPage",remainsPage(progress,b,OLD_SCOPE,-1));
        NEW_SCOPE_RELEASE.countDown();awaitLoaded(OLD_SCOPE,2);
        check("newAccountPageOwnsFreshExactLoadedScope",main(()->Boolean.TRUE.equals(field(activity,"threadLoaded"))&&method(MainActivity.class,"contentScope").invoke(activity).equals(field(activity,"loadedReadingScope"))));
        screen("settings","",1);awaitWrites();
        check("successfullyReloadedNewAccountPageMaySaveItsOwnProgress",awaitPage(progress,b,OLD_SCOPE,2));
        store.setCookie(SYNTHETIC_A);screen("settings","",1);
        stage="mainStartOverProgress";screen("thread",START,3);awaitLoaded(START,3);invoke("persistReadingProgress",new Class<?>[0]);check("oldContinuationWasDurablyPresentBeforeStartOver",awaitPage(progress,a,START,3));
        main(()->{positions().put("thread:"+START+":1:author:",1400);return null;});
        invoke("openThreadFromStart",new Class<?>[]{NgaApi.Topic.class},topic(START));awaitLoaded(START,1);
        check("startOverActuallyLoadsFirstPage",main(()->Integer.valueOf(1).equals(field(field(activity,"current"),"page"))));
        check("startOverDoesNotRestorePreviousFirstPageScroll",main(()->((ScrollView)field(activity,"currentScroll")).getScrollY()==0));
        invoke("persistReadingProgress",new Class<?>[0]);check("startOverReplacesOldContinuationWithFirstPage",awaitPage(progress,a,START,1));
        stage="mainStartOverSamePage";
        main(()->{((ScrollView)field(activity,"currentScroll")).scrollTo(0,900);return null;});waitForIdleSync();
        check("firstPageStartOverFixtureActuallyScrolledDown",main(()->((ScrollView)field(activity,"currentScroll")).getScrollY()>=800));
        invoke("openThreadFromStart",new Class<?>[]{NgaApi.Topic.class},topic(START));awaitLoaded(START,1);
        check("startOverOnAlreadyOpenFirstPageDoesNotRestoreOldScroll",main(()->((ScrollView)field(activity,"currentScroll")).getScrollY()==0));
        stage="mainContinueReading";progress.save(a,GOOD,3,null);screen("settings","",1);
        invoke("openThread",new Class<?>[]{NgaApi.Topic.class},topic(GOOD));awaitLoaded(GOOD,3);
        check("normalOpenContinuesFromDurablePage",main(()->Integer.valueOf(3).equals(field(field(activity,"current"),"page"))));
        stage="mainRemovedHistoryProgress";
        storeCall(store,"removeHistory",new Class<?>[]{String.class},GOOD);progress.remove(a,GOOD);invoke("persistReadingProgress",new Class<?>[0]);
        awaitWrites();
        check("removedCurrentHistoryIsNotRecreatedByPersistence",!contains(store.history(),GOOD)&&remainsPage(progress,a,GOOD,-1));
        stage="mainHistoryOptOutProgress";
        storeCall(store,"setHistoryEnabled",new Class<?>[]{boolean.class},false);screen("thread",START,2);awaitLoaded(START,2);progress.remove(a,START);invoke("persistReadingProgress",new Class<?>[0]);
        awaitWrites();
        check("historyOptOutAlsoPreventsReaderProgressWrites",remainsPage(progress,a,START,-1));
        storeCall(store,"setHistoryEnabled",new Class<?>[]{boolean.class},true);
    }
    @SuppressWarnings("unchecked") private Map<String,Integer> positions()throws Exception{return (Map<String,Integer>)field(activity,"readingPositions");}
    private void route(Intent intent)throws Exception{invoke("routeIntent",new Class<?>[]{Intent.class},intent);waitForIdleSync();}
    private static Intent share(String text){return new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text);}
    private static View description(View view,String expected){
        if(expected.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=description(group.getChildAt(i),expected);if(found!=null)return found;}}
        return null;
    }
    @SuppressWarnings("unchecked") private NativeGlassDialog linkChooser()throws Exception{return main(()->{for(NativeGlassDialog dialog:(Set<NativeGlassDialog>)field(activity,"glassDialogs"))if(dialog.isShowing()&&textContains(dialog.getWindow().getDecorView(),"选择要打开的 NGA 链接"))return dialog;return null;});}
    private boolean settingsRoute()throws Exception{return main(()->"settings".equals(field(field(activity,"current"),"kind")));}
    private static final class ShareMonitor extends ActivityMonitor {
        volatile Intent chooser;
        @Override public ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_CHOOSER.equals(intent.getAction())){chooser=new Intent(intent);return new ActivityResult(Activity.RESULT_CANCELED,null);}return null;}
    }
    @SuppressWarnings("deprecation") private static Intent payload(Intent chooser){return chooser==null?null:chooser.getParcelableExtra(Intent.EXTRA_INTENT);}
    private void sharedIntentChecks(NgaStore store)throws Exception{
        stage="incomingSingleReadOnlyShare";screen("settings","",1);
        route(share("来自其他应用的合成帖子 https://bbs.nga.cn/read.php?tid="+GOOD+"&page=2"));awaitLoaded(GOOD,2);
        check("singleIncomingReadOnlyShareUsesNativeThread",main(()->GOOD.equals(field(field(activity,"current"),"id"))&&Integer.valueOf(2).equals(field(field(activity,"current"),"page"))));
        stage="incomingMultipleReadOnlyShare";screen("settings","",1);
        String multiple="https://bbs.nga.cn/read.php?tid="+GOOD+"\nhttps://bbs.nga.cn/read.php?tid="+START;
        route(share(multiple));NativeGlassDialog chooser=linkChooser();
        check("multipleIncomingLinksStayOnCurrentPageUntilChoice",chooser!=null&&settingsRoute());
        main(()->{View first=description(chooser.getWindow().getDecorView(),"帖子 "+GOOD+" · 第 1 页");if(first==null)throw new IllegalStateException("NO_SYNTHETIC_SHARE_CHOICE");first.performClick();return null;});awaitLoaded(GOOD,1);
        check("choosingIncomingLinkOpensOnlySelectedNativeThread",main(()->GOOD.equals(field(field(activity,"current"),"id"))&&Integer.valueOf(1).equals(field(field(activity,"current"),"page"))));
        stage="incomingShareChoiceOldScope";screen("settings","",1);route(share(multiple));NativeGlassDialog stale=linkChooser();
        check("secondShareChooserWasActuallyOpened",stale!=null);
        main(()->{View first=description(stale.getWindow().getDecorView(),"帖子 "+GOOD+" · 第 1 页");store.setCookie(SYNTHETIC_B);if(first==null)throw new IllegalStateException("NO_OLD_SCOPE_CHOICE");first.performClick();return null;});waitForIdleSync();
        check("oldSessionShareChoiceCannotNavigate",settingsRoute());
        main(()->{store.setCookie(SYNTHETIC_A);return null;});screen("settings","",1);
        stage="incomingInvalidShare";int reads=READ_RESPONSES.get();
        for(String invalid:new String[]{"https://example.invalid/read.php?tid="+GOOD,"https://bbs.nga.cn/post.php?action=reply&tid="+GOOD,"https://bbs.nga.cn/nuke.php?__lib=post&__act=reply","javascript:alert(1)","https://bbs.nga.cn.evil.invalid/read.php?tid="+GOOD,repeat('x',16385)}){
            route(share(invalid));check("invalidShareStaysLocal"+assertions,settingsRoute()&&linkChooser()==null);
        }
        route(new Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_TEXT,"https://bbs.nga.cn/read.php?tid="+GOOD));
        check("unsupportedShareMimeDoesNotOpenThread",settingsRoute());
        route(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,true));
        check("malformedExtraTextTypeDoesNotOpenThread",settingsRoute());
        check("invalidSharesDoNotTriggerSyntheticThreadFetches",READ_RESPONSES.get()==reads);
        stage="outgoingFloorShare";screen("thread",GOOD,2);awaitLoaded(GOOD,2);ShareMonitor monitor=new ShareMonitor();addMonitor(monitor);
        try{
            NgaApi.Post post=new NgaApi.Post();post.pid="7900000042";post.floor=42;
            main(()->{Object current=field(activity,"current");method(MainActivity.class,"shareFloor",current.getClass(),NgaApi.Post.class).invoke(activity,current,post);return null;});
            Intent outgoing=payload(monitor.chooser);
            check("floorShareUsesSystemTextChooserWithoutLaunchingExternalApp",outgoing!=null&&Intent.ACTION_SEND.equals(outgoing.getAction())&&"text/plain".equals(outgoing.getType()));
            String text=outgoing==null?"":outgoing.getStringExtra(Intent.EXTRA_TEXT);
            check("floorShareCarriesSpecificPidAndFloorLabel",text!=null&&text.contains("tid="+GOOD+"&pid=7900000042")&&text.contains("第 42 楼"));
            monitor.chooser=null;post.pid="0";
            main(()->{Object current=field(activity,"current");method(MainActivity.class,"shareFloor",current.getClass(),NgaApi.Post.class).invoke(activity,current,post);return null;});
            outgoing=payload(monitor.chooser);text=outgoing==null?"":outgoing.getStringExtra(Intent.EXTRA_TEXT);
            check("floorWithoutValidPidFallsBackToExplicitCurrentPage",text!=null&&text.contains("tid="+GOOD+"&page=2")&&!text.contains("&pid=0"));
        }finally{removeMonitor(monitor);}
    }

    private static JSONObject thread(URL url)throws Exception{
        Uri uri=Uri.parse(url.toString());String tid=uri.getQueryParameter("tid");int page=Integer.parseInt(uri.getQueryParameter("page"));
        JSONObject meta=new JSONObject().put("tid",tid).put("fid","650").put("subject","合成只读主题 "+tid).put("authorid","79001");
        JSONArray posts=new JSONArray();
        for(int i=0;i<3;i++){StringBuilder body=new StringBuilder("合成只读正文 "+tid+"\n");for(int line=0;line<24;line++)body.append("仅在独立验证器本地生成的阅读内容，第 ").append(line+1).append(" 行。\n");posts.put(new JSONObject().put("pid",Long.parseLong(tid)*10+i).put("lou",(page-1)*20+i).put("authorid","79001").put("author","合成作者").put("postdate",1792710000).put("content",body.toString()).put("recommend",0));}
        return new JSONObject().put("data",new JSONObject().put("__T",meta).put("__R",posts).put("__PAGE",page).put("__R__ROWS_PAGE",20).put("__ROWS",100));
    }
    private static final class LocalRead extends HttpURLConnection {
        private final byte[] bytes;
        private boolean newAccount;
        LocalRead(URL url,byte[] bytes){super(url);this.bytes=bytes;}
        @Override public void setRequestProperty(String key,String value){if("Cookie".equalsIgnoreCase(key))newAccount=value!=null&&value.contains("ngaPassportUid=79002;");super.setRequestProperty(key,value);}
        public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
        @Override public int getResponseCode()throws IOException{if("POST".equals(getRequestMethod())){BLOCKED_WRITES.incrementAndGet();throw new IOException("SYNTHETIC_WRITES_DISABLED");}return 200;}
        @Override public String getContentType(){return "application/json; charset=UTF-8";}
        @Override public InputStream getInputStream()throws IOException{
            if(PENDING.equals(Uri.parse(url.toString()).getQueryParameter("tid"))){PENDING_ENTERED.countDown();try{if(!PENDING_RELEASE.await(10,TimeUnit.SECONDS))throw new IOException("Synthetic pending read timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Synthetic pending read canceled",e);}}
            if(newAccount&&OLD_SCOPE.equals(Uri.parse(url.toString()).getQueryParameter("tid"))){NEW_SCOPE_ENTERED.countDown();try{if(!NEW_SCOPE_RELEASE.await(10,TimeUnit.SECONDS))throw new IOException("Synthetic new-account read timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Synthetic new-account read canceled",e);}}
            READ_RESPONSES.incrementAndGet();return new ByteArrayInputStream(bytes);
        }
        @Override public OutputStream getOutputStream()throws IOException{BLOCKED_WRITES.incrementAndGet();throw new IOException("SYNTHETIC_WRITES_DISABLED");}
    }
    private static void localTransport(){URL.setURLStreamHandlerFactory(protocol->("https".equals(protocol)||"http".equals(protocol))?new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{
        Uri uri=Uri.parse(url.toString());String tid=uri.getQueryParameter("tid");
        if("/read.php".equals(url.getPath())&&tid!=null&&tid.matches("790000000[1345]")&&uri.getQueryParameter("page")!=null){try{return new LocalRead(url,thread(url).toString().getBytes(StandardCharsets.UTF_8));}catch(Exception malformed){throw new IOException("Synthetic read fixture invalid",malformed);}}
        throw new IOException("SYNTHETIC_NETWORK_DISABLED");
    }}:null);}
    @Override public void onStart(){try{
        if(!authorized||!"cn.ngapure.everydayprobe".equals(getTargetContext().getPackageName())||getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("OWN_UID_SYNTHETIC_READS_NO_INTERNET_REQUIRED");
        localTransport();NgaStore store=new NgaStore(getTargetContext());store.setAutoCheckIn(false);store.setMotionEnabled(false);store.setCookie(SYNTHETIC_A);getTargetContext().getSharedPreferences("nga_unread_notifications",0).edit().putBoolean("enabled",false).commit();
        historyChecks(store);androidProgressStorageChecks();
        activity=(MainActivity)startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(),MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
        mainProgressChecks(store);sharedIntentChecks(store);check("allScriptedThreadConnectionsStayedReadOnly",BLOCKED_WRITES.get()==0);
        report.put("completed",true).put("passed",true).put("assertions",assertions).put("syntheticReadResponses",READ_RESPONSES.get());
    }catch(Exception|Error failure){try{report.put("completed",false).put("passed",false).put("failureStage",stage).put("failureType",failure.getClass().getSimpleName()).put("assertions",assertions);}catch(Exception ignored){}
    }finally{PENDING_RELEASE.countDown();NEW_SCOPE_RELEASE.countDown();try{if(activity!=null&&!activity.isDestroyed())main(()->{activity.finish();return null;});report.put("ownUidOnly",true).put("internetPermission",false).put("realNetworkConnections",0).put("realClientStarted",false).put("realAccountAccessed",false).put("syntheticReadOnlyInput",true).put("communityWriteActionInvoked",false).put("privateInputsExported",false);}catch(Exception ignored){}Bundle result=new Bundle();result.putString("safe_everyday_report",report.toString());finish(Activity.RESULT_OK,result);}}
}
