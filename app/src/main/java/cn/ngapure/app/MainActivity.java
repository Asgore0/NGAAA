package cn.ngapure.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Native NGA client: account, reading, composing and community screens. */
public final class MainActivity extends Activity {
    private NgaStore store;
    private NgaApi api;
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private ForumFavoritesCoordinator favoriteCoordinator;
    private ExecutorService favoriteWrites;
    private volatile boolean favoriteSyncRunning;
    private final Object favoriteForegroundToken=new Object();
    private final WeakHashMap<TextView,Boolean> favoriteCounts=new WeakHashMap<>();
    private Runnable favoritePageUpdate;
    private TextView favoriteCloudStatus;
    private View favoriteCloudAction;
    private String favoriteProjectionSignature="";
    private long favoritePageRefreshRevision;
    private final WeakHashMap<TextView,FavoriteBinding> favoriteBindings=new WeakHashMap<>();
    private final Runnable favoriteObserver=this::refreshFavoriteBindings;
    private static final class FavoriteBinding {
        final String[] forum;final int owner;final String scope;
        FavoriteBinding(String[] forum,int owner,String scope){this.forum=forum;this.owner=owner;this.scope=scope;}
    }
    /** One render transaction owns only hashes and immutable favorite state, never credentials. */
    private static final class FavoriteRenderSnapshot {
        final String contentScope,accountScope,localScope;
        final Set<String> favorites,pending,busy;
        FavoriteRenderSnapshot(String contentScope,String accountScope,String localScope,Set<String> favorites,Set<String> pending,Set<String> busy){
            this.contentScope=contentScope;this.accountScope=accountScope;this.localScope=localScope;
            this.favorites=Collections.unmodifiableSet(new HashSet<>(favorites));
            this.pending=Collections.unmodifiableSet(new HashSet<>(pending));this.busy=Collections.unmodifiableSet(new HashSet<>(busy));
        }
    }
    private FavoriteRenderSnapshot favoriteRenderPass;
    private FavoriteRenderSnapshot forumFavoriteSnapshot;
    private boolean forumFavoriteRefreshRunning,forumFavoriteRefreshDirty;
    private volatile long userFilterRevision;
    private java.util.function.Consumer<FavoriteRenderSnapshot> directoryFavoriteRenderUpdate;
    private final ScreenReadTasks pageReads = new ScreenReadTasks();
    private ForumReadLaunch activeForumRead;
    private final ArrayDeque<Screen> back = new ArrayDeque<>();
    private final ForumPageSnapshots<Screen> forumSnapshots = new ForumPageSnapshots<>();
    private ForumResponseCache forumResponseCache;
    private String nativeForumSource="none";
    private long nativeForumCacheAgeMs, nativeForumRequestNanos, nativeForumHeadersNanos, nativeForumBodyNanos, nativeForumParsedNanos;
    private int nativeForumRequests;
    private long nativeForumResponseBytes;
    // Numeric startup diagnostics only; no account, URL or content is retained here.
    private long startupAtNs;
    private volatile long startupSetupNs,startupCatalogNs,startupCatalogStartedNs,startupCatalogFinishedNs,startupForumQueuedNs,startupWorkerStartedNs,startupSessionReadyNs,startupCacheReadyNs,startupNetworkStartedNs,startupResponseReadyNs,startupContentBoundNs,startupFirstDrawNs,startupCreateEndedNs;
    private boolean catalogReady,catalogStarted,catalogDeferred;
    private Screen startupForumOwner;
    private String startupSessionAtFirstDraw="";
    private String startupLocksAtFirstDraw="";
    private long startupOffset(){return startupAtNs==0?0:System.nanoTime()-startupAtNs;}

    private LinearLayout root;
    private FrameLayout body;
    private final PageTransition pageTransition=new PageTransition();
    private Dialog navigationDrawer;
    private NativePageJumpDialog pageJumpDialog;
    private String pageJumpScope,pageJumpPageKey,searchScope,searchPageKey;
    private NativeGlassDialog searchDialog;
    private EditText searchInput;
    private PromptDraftState pendingPrompt;
    private String selectedSearchForum="";
    private ReadingProgress readingProgress;
    private BoardVisits boardVisits;
    private boolean threadLoaded;
    private String loadedReadingScope="";
    private static final Object READING_WRITES_LOCK=new Object();
    private static volatile long readingWriteEpoch;
    private static volatile long boardWriteEpoch;
    private static final Executor READING_WRITES=new ThreadPoolExecutor(0,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(128),r->{Thread t=new Thread(r,"nga-reading-position");t.setDaemon(true);return t;},new ThreadPoolExecutor.DiscardOldestPolicy());
    private final HashSet<NativeGlassDialog> glassDialogs = new HashSet<>();
    private FrameLayout homeCanvas;
    private LinearLayout mainToolbar;
    private GlassPanel navigationGlass;
    private PopupWindow homeGroupPopup;
    private LiquidSelection primarySelection;
    private final Handler navigationHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences sessionPreferences;
    private boolean contentStarted;
    private final Runnable sessionContentTask=this::refreshSessionContent;
    private final SharedPreferences.OnSharedPreferenceChangeListener sessionObserver=(preferences,key)->{
        if(contentStarted&&(key==null||"host".equals(key)||"cookies_encrypted".equals(key))){
            // A failed credential commit can roll its live map back before returning. Inspect the
            // final exact session on the next UI turn, and coalesce every key in that transaction.
            navigationHandler.removeCallbacks(sessionContentTask);navigationHandler.post(sessionContentTask);
        }
    };
    private int pendingHomeTab = -1, homeBuilds;
    private static final int HOME_NAVIGATION_QUIET_MS = 32;
    // The lens responds immediately. A short quiet window only coalesces expensive
    // page construction when several taps arrive during the same quick gesture.
    private final Runnable homeNavigationTask = this::commitHomeNavigation;
    private final NativeImages nativeImages = new NativeImages();
    private TopicPreviewImages topicPreviewImages;
    private final Set<TopicPreviewView> topicPreviews=Collections.newSetFromMap(new WeakHashMap<TopicPreviewView,Boolean>());
    private NativeImages.Request pictureRequest;
    private NativePictureView pictureView;
    private NativeImageGalleryView galleryView;
    private String pictureUrl;
    private NativeLoginView loginView;
    private NativeEditorView editorView;
    private NativeEditorView attachmentTarget;
    private NativeCommunityView communityView;
    private NativeFollowingView followingView;
    private NativeActivitiesView activitiesView;
    private NativeThreadActions threadActions;
    private NativeThreadActionDialog threadSheets;
    private final VoteMetrics.ConfirmedSelections reactionSelections=new VoteMetrics.ConfirmedSelections();
    private final Map<String,NativeVoteBar> readerVoteViews=new HashMap<>();
    private NgaApi.ThreadPage readerThreadPage;
    private static final String REACTION_SELECTION_STATE="confirmed_reaction_selections_v1";
    private NativeUserActionDialogs userSheets;
    private AvatarPickerState avatarPicker;
    private NativeUserActions.UserFilter userFilter;
    private boolean refreshingCloudBlocks;
    private FoldAdaptiveLayout adaptiveLayout;
    private String savingImage, savingImageFormat;
    private NativeImageExport.Image preparedExport;
    private boolean preparingExport, imageReady;
    private TextView imageSaveButton;
    private Screen current;
    private volatile int generation;
    private int homeTab = 1, mainTab = 0;
    private int nativePostCount,nativeVoteCount,nativeDisplayedVoteCount,nativeChildForumCount;
    private int nativeForumTopicCount=-1;
    private String readerAccountId="";
    private EditText communitySearch;
    private ListView communityList;
    private final Object catalogRestoreTag=new Object();
    private static final class CatalogListView extends ListView{
        Runnable cancelPendingRestore;
        CatalogListView(Context context){super(context);}
        @Override public boolean dispatchTouchEvent(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_DOWN&&cancelPendingRestore!=null)cancelPendingRestore.run();return super.dispatchTouchEvent(event);}
    }
    private String communityCategory="收藏版块",communityQuery="";
    private int communityPosition,communityOffset;
    private boolean communityGrid=true;
    private Runnable directoryFilterTask;
    private boolean directoryFilterPending;
    private String activeDirectoryScope="";
    private int communityColumns=1;
    private String communityAnchorKey="",communityAnchorIdentity="",communityDataIdentity="";
    private List<BoardDirectory.Row> communityRows=Collections.emptyList();
    private ForumTopicListView forumList;
    private Screen forumListOwner;
    private ScrollView currentScroll;
    private String currentScrollKey;
    private final HashMap<String,Integer> readingPositions=new LinkedHashMap<String,Integer>(128,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Integer> entry){return size()>128;}
    };
    private final ScopedCache<List<NgaApi.Topic>> homeCache=new ScopedCache<>();
    private String activeContentScope="";

    private String ratingCategory="2";
    private int ratingPage=1;
    private int bg, surface, ink, muted, line, gold;
    private AppPalette palette = AppPalette.of(false);
    private boolean dark;
    private final ArrayList<String[]> catalog = new ArrayList<>();
    private final HashMap<String,String> forumIcons=new HashMap<>();
    private static final String[][] FORUMS = {
        {"7","艾泽拉斯议事厅","魔兽世界","WOW"},
        {"422","炉石传说","暴雪游戏","HS"},
        {"459","守望先锋","暴雪游戏","OW"},
        {"-152678","英雄联盟","电竞竞技","LOL"},
        {"321","DOTA2","电竞竞技","D2"},
        {"414","游戏综合讨论区","游戏天地","GAME"},
        {"650","原神","手机游戏","原"},
        {"818","崩坏：星穹铁道","手机游戏","星"},
        {"-362960","最终幻想14","网络游戏","FF14"},
        {"614","PS 游戏综合讨论","主机游戏","PS"},
        {"615","XBOX 游戏综合讨论","主机游戏","XBOX"},
        {"616","Nintendo 游戏综合讨论","主机游戏","NS"},
        {"436","消费电子 IT 新闻","玩家生活","数码"},
        {"-7","网事杂谈","玩家生活","杂谈"},
        {"-343809","寂寞的车","玩家生活","车"},
        {"-7955747","晴风村","玩家生活","生活"},
        {"-576177","音乐影视","玩家生活","影音"},
        {"524","漩涡书院","玩家生活","书"},
        {"-202020","程序员职业交流","玩家生活","代码"}
    };
    private static final class Screen {
        final String kind, id; String title; int page, totalPages;
        NgaApi.Topic topic;
        ImageGalleryState gallery; String galleryScope=""; boolean galleryExpired;
        ReaderAnchor readerAnchor;
        ForumListAnchor forumAnchor;
        boolean locateRequested,requestRating,subForumsExpanded,forceReaderAnchor;
        String action="", fid="", tid="", pid="", extra="";
        Screen(String k, String i, String t, int p) { kind=k; id=i; title=t; page=p; }
    }
    private static final String NAVIGATION_STATE="ngapure_navigation_v1";
    private static final String HOME_SCREEN_STATE="ngapure_home_v1";
    private static final String BOARD_DIRECTORY_ANCHOR_STATE="ngapure_board_directory_anchor_v1";
    private static final String AVATAR_PICKER_STATE="ngapure_avatar_picker_v1";
    private static final String AVATAR_PREVIEW_STATE="ngapure_avatar_preview_v1";
    private static final String IMAGE_VIEWPORT_STATE="ngapure_image_viewport_v1",IMAGE_VIEWPORT_URL="ngapure_image_viewport_url_v1";
    private static final String IMAGE_GALLERY_STATE="ngaaa_image_gallery_v1";
    private static final int BACK_GALLERY_CHARS=96*1024;
    private int restoredBackGalleryChars;
    private static final String PROMPT_DRAFT_STATE="ngapure_prompt_draft_v1";
    private static final String AUTH_METHOD_STATE="ngapure_auth_method_v1",AUTH_METHOD_SCOPE="ngapure_auth_method_scope_v1";
    private int pendingLoginMethod=-1;
    private NavigationState.Entry navigationEntry(Screen screen){
        if(screen==null)return null;String fid=screen.fid;
        if("thread".equals(screen.kind)&&fid.isEmpty()&&screen.topic!=null&&screen.topic.fid!=null&&screen.topic.fid.matches("-?[1-9][0-9]{0,11}"))fid=screen.topic.fid;
        Integer y=readingPositions.get(screenKey(screen));
        if(screen==current&&currentScroll!=null)y=currentScroll.getScrollY();
        return NavigationState.entry(screen.kind,screen.gallery==null?screen.id:screen.gallery.currentUrl(),NavigationState.title(screen.title),screen.page,screen.action,fid,screen.tid,screen.pid,screen.extra,y==null?-1:NavigationState.offset(y));
    }
    private Screen navigationScreen(NavigationState.Entry entry){
        Screen screen=new Screen(entry.kind,entry.id,entry.title,entry.page);screen.action=entry.action;screen.fid=entry.fid;screen.tid=entry.tid;screen.pid=entry.pid;screen.extra=entry.extra;
        if("thread".equals(entry.kind)&&!entry.fid.isEmpty()){screen.topic=new NgaApi.Topic();screen.topic.tid=entry.id;screen.topic.title=entry.title;screen.topic.fid=entry.fid;}
        return screen;
    }
    private void saveNavigation(Bundle target){
        snapshotGallery();captureForumPosition();NavigationState.Entry top=navigationEntry(current);if(top==null)top=NavigationState.entry("home","","NGA",1,"","","","","",0);
        Bundle state=new Bundle();state.putInt("version",NavigationState.VERSION);state.putStringArray("current",NavigationState.encode(top));saveForumAnchor(state,"current",current);if(current!=null&&"forum".equals(top.kind)&&current.subForumsExpanded)state.putBoolean("current_subforums_expanded",true);int count=0;
        for(Screen screen:back){NavigationState.Entry entry=navigationEntry(screen);if(entry!=null){state.putStringArray("back_"+count,NavigationState.encode(entry));saveForumAnchor(state,"back_"+count,screen);if("forum".equals(entry.kind)&&screen.subForumsExpanded)state.putBoolean("back_"+count+"_subforums_expanded",true);if(++count==NavigationState.MAX_BACK)break;}}
        snapshotGallery();saveGallery(state,"current",current,ImageGalleryState.MAX_ENCODED_CHARS);
        int budget=BACK_GALLERY_CHARS,index=0;for(Screen screen:back){if(navigationEntry(screen)!=null){budget-=saveGallery(state,"back_"+index,screen,budget);if(++index==NavigationState.MAX_BACK)break;}}
        state.putInt("backCount",count);target.putBundle(NAVIGATION_STATE,state);
    }
    private void snapshotGallery(){if(galleryView!=null&&current!=null&&"image".equals(current.kind)&&current.gallery!=null)current.gallery=galleryView.snapshot();}
    private int saveGallery(Bundle state,String key,Screen screen,int budget){
        if(screen==null||!"image".equals(screen.kind)||(screen.gallery==null&&!screen.galleryExpired))return 0;
        state.putBoolean(key+"_has_gallery",true);String encoded=screen.gallery==null?"":screen.gallery.encode();
        if(encoded.isEmpty()||encoded.length()>budget||!screen.galleryScope.equals(contentScope()))return 0;
        state.putString(key+"_gallery",encoded);state.putString(key+"_gallery_scope",screen.galleryScope);return encoded.length();
    }
    private void restoreGallery(Bundle saved,Screen screen,int validBackIndex){
        if(saved==null||screen==null||!"image".equals(screen.kind))return;Object raw=stateValue(saved,NAVIGATION_STATE);if(!(raw instanceof Bundle))return;Bundle state=(Bundle)raw;
        String key="current";if(validBackIndex>=0){int valid=0;key=null;int count=stateInt(state,"backCount",0);for(int i=0;i<Math.min(count,NavigationState.MAX_BACK);i++)if(NavigationState.decode(stateValue(state,"back_"+i))!=null){if(valid++==validBackIndex){key="back_"+i;break;}}}if(key==null||!Boolean.TRUE.equals(stateValue(state,key+"_has_gallery")))return;
        screen.galleryExpired=true;String encoded=stateString(state,key+"_gallery",null),scope=stateString(state,key+"_gallery_scope",null);if(!contentScope().equals(scope)||encoded==null||encoded.length()>ImageGalleryState.MAX_ENCODED_CHARS)return;
        if(validBackIndex>=0){if(encoded.length()>BACK_GALLERY_CHARS-restoredBackGalleryChars)return;restoredBackGalleryChars+=encoded.length();}
        ImageGalleryState gallery=ImageGalleryState.decode(encoded);if(gallery!=null&&gallery.currentUrl().equals(screen.id)){screen.gallery=gallery;screen.galleryScope=scope;screen.galleryExpired=false;}
    }
    private boolean restoreSubForumsExpanded(Bundle saved,NavigationState.Entry expected,int validBackIndex){
        if(saved==null||expected==null||!"forum".equals(expected.kind))return false;Object raw=stateValue(saved,NAVIGATION_STATE);if(!(raw instanceof Bundle))return false;Bundle state=(Bundle)raw;
        int count=stateInt(state,"backCount",-1);if(stateInt(state,"version",-1)!=NavigationState.VERSION||count<0||count>NavigationState.MAX_BACK)return false;
        String key="current";if(validBackIndex>=0){int valid=0;key=null;for(int i=0;i<count;i++)if(NavigationState.decode(stateValue(state,"back_"+i))!=null){if(valid++==validBackIndex){key="back_"+i;break;}}}if(key==null)return false;
        NavigationState.Entry actual=NavigationState.decode(stateValue(state,key));return actual!=null&&"forum".equals(actual.kind)&&expected.id.equals(actual.id)&&expected.page==actual.page&&expected.extra.equals(actual.extra)&&Boolean.TRUE.equals(stateValue(state,key+"_subforums_expanded"));
    }
    private void captureForumPosition(){
        if(forumList!=null&&forumListOwner==current){ForumListAnchor anchor=forumList.anchor();if(anchor!=null)current.forumAnchor=anchor;}
    }
    private void saveForumAnchor(Bundle state,String key,Screen screen){
        if(screen!=null&&"forum".equals(screen.kind)&&screen.forumAnchor!=null&&screen.forumAnchor.matches(contentScope(),screen.id,screen.extra,screen.page))state.putStringArray(key+"_forum_anchor",screen.forumAnchor.encode());
    }
    private ForumListAnchor restoreForumAnchor(Bundle saved,NavigationState.Entry expected,int validBackIndex){
        if(saved==null||expected==null||!"forum".equals(expected.kind))return null;Object raw=stateValue(saved,NAVIGATION_STATE);if(!(raw instanceof Bundle))return null;Bundle state=(Bundle)raw;
        int count=stateInt(state,"backCount",-1);if(stateInt(state,"version",-1)!=NavigationState.VERSION||count<0||count>NavigationState.MAX_BACK)return null;
        String key="current";if(validBackIndex>=0){int valid=0;key=null;for(int i=0;i<count;i++)if(NavigationState.decode(stateValue(state,"back_"+i))!=null){if(valid++==validBackIndex){key="back_"+i;break;}}}if(key==null)return null;
        ForumListAnchor anchor=ForumListAnchor.decode(stateValue(state,key+"_forum_anchor"));return anchor!=null&&anchor.matches(contentScope(),expected.id,expected.extra,expected.page)?anchor:null;
    }
    private NavigationState.Snapshot readNavigation(Bundle saved){
        if(saved==null)return null;
        try{
            if(saved.containsKey(NAVIGATION_STATE)){
                Object raw=stateValue(saved,NAVIGATION_STATE);if(!(raw instanceof Bundle))return null;Bundle state=(Bundle)raw;
                int count=stateInt(state,"backCount",-1);if(stateInt(state,"version",-1)!=NavigationState.VERSION||count<0||count>NavigationState.MAX_BACK)return null;
                ArrayList<Object> frames=new ArrayList<>();for(int i=0;i<count;i++)frames.add(stateValue(state,"back_"+i));
                return NavigationState.decode(stateValue(state,"current"),frames);
            }
            // Read one pre-0.9 screen conservatively; new saves only use the bounded schema above.
            if(!saved.containsKey("screenKind"))return null;
            NavigationState.Entry top=NavigationState.entry(stateString(saved,"screenKind",""),stateString(saved,"screenId",""),stateString(saved,"screenTitle","NGA"),stateInt(saved,"screenPage",-1),stateString(saved,"screenAction",""),stateString(saved,"screenFid",""),stateString(saved,"screenTid",""),stateString(saved,"screenPid",""),stateString(saved,"screenExtra",""),NavigationState.offset(stateInt(saved,"readingY",0)));
            if(top==null)return null;ArrayList<NavigationState.Entry> previous=new ArrayList<>();if(!"home".equals(top.kind))previous.add(NavigationState.entry("home","","NGA",1,"","","","","",0));
            return new NavigationState.Snapshot(top,previous);
        }catch(RuntimeException malformed){return null;}
    }
    // Raw access permits exact type checks on every supported API; typed getters may coerce bad state.
    @SuppressWarnings("deprecation") private static Object stateValue(Bundle state,String key){try{return state.get(key);}catch(RuntimeException malformed){return null;}}
    private static int stateInt(Bundle state,String key,int fallback){Object value=stateValue(state,key);return value instanceof Integer?(Integer)value:fallback;}
    private static String stateString(Bundle state,String key,String fallback){try{if(!state.containsKey(key))return fallback;Object value=stateValue(state,key);return value instanceof String?(String)value:null;}catch(RuntimeException malformed){return null;}}
    @Override public void onCreate(Bundle state) {
        startupAtNs=System.nanoTime();super.onCreate(state);
        nativeImages.setMediaCacheDirectory(new File(getCacheDir(),"anonymous-media"));
        store = new NgaStore(this);
        // Start the navigation preferences load before optional journals; Keystore work can overlap it.
        final NativeQrRecoveryStore authorizationRecovery=new NativeQrRecoveryStore(this);
        final SharedPreferences directoryUi=getSharedPreferences("nga_board_directory_ui",MODE_PRIVATE);
        final NgaStore.CheckInSession initialSession=store.captureCheckInSession();forumResponseCache=new ForumResponseCache(this);readingProgress=new ReadingProgress(this);boardVisits=new BoardVisits(this); api = new NgaApi(this);topicPreviewImages=new TopicPreviewImages((scope,tid)->api.firstPostImages(tid,scope),task->navigationHandler.post(task));threadActions=new NativeThreadActions(this);activeContentScope=contentScope();activeDirectoryScope=localDataScope();
        reactionSelections.restore(activeContentScope,state==null?null:stateValue(state,REACTION_SELECTION_STATE));
        try{communityGrid=directoryUi.getBoolean("grid",true);}catch(ClassCastException malformed){communityGrid=true;}
        sessionPreferences=getApplicationContext().getSharedPreferences("nga_local",MODE_PRIVATE);sessionPreferences.registerOnSharedPreferenceChangeListener(sessionObserver);
        favoriteCoordinator=ForumFavoritesCoordinator.get(this);favoriteWrites=favoriteCoordinator.executor();
        SessionScopeSnapshot initialScope=store.captureScopeSnapshot();store.activateFavoriteScope(initialScope.loggedIn?initialScope.localDataScope:"");favoriteCoordinator.addObserver(favoriteObserver);
        configureAuthorAvatars(initialSession);
        nativeImages.setLocalAvatarSource(url->{String asset=BoardIconCatalog.assetFor(url);return asset.isEmpty()?null:getAssets().open(asset);});
        startupSetupNs=startupOffset();
        NavigationState.Snapshot restored=NavigationState.restoreOrResumeAuthorization(readNavigation(state),authorizationRecovery.hasPending(store.host()));
        catalogDeferred=restored!=null?"forum".equals(restored.current.kind):initialForumTarget(getIntent())!=null;
        if(catalogDeferred){Collections.addAll(catalog,FORUMS);}else applyCatalog(readCatalog());
        if (state != null) {
            homeTab=NavigationState.tab(stateInt(state,"tab",1),1);mainTab=NavigationState.tab(stateInt(state,"mainTab",0),0);
            HomeScreenState home=HomeScreenState.decode(stateValue(state,HOME_SCREEN_STATE));
            ratingCategory=home.ratingCategory;ratingPage=home.ratingPage;communityCategory=home.boardCategory;communityQuery=home.boardQuery;communityPosition=home.boardPosition;communityOffset=home.boardOffset;
            if(!restoreDirectoryAnchor(stateValue(state,BOARD_DIRECTORY_ANCHOR_STATE))){communityPosition=0;communityOffset=0;}
            avatarPicker=AvatarPickerState.decode(stateValue(state,AVATAR_PICKER_STATE));
        }
        if(state!=null){
            String image=stateString(state,"savingImage",null),format=stateString(state,"savingImageFormat",null);
            if(NavigationState.imageUrl(image)&&NativeImages.allowed(image)&&Arrays.asList("jpg","jpeg","png","gif","webp","bmp","avif").contains(format)){savingImage=image;savingImageFormat=format;}
        }
        if(state!=null&&restored!=null&&"auth".equals(restored.current.kind)&&contentScope().equals(stateString(state,AUTH_METHOD_SCOPE,null))){
            int method=stateInt(state,AUTH_METHOD_STATE,-1);if(method>=0&&method<=2)pendingLoginMethod=method;
        }
        if(restored!=null){
            int anchorIndex=0;for(NavigationState.Entry entry:restored.back){Screen screen=navigationScreen(entry);restoreGallery(state,screen,anchorIndex);screen.subForumsExpanded=restoreSubForumsExpanded(state,entry,anchorIndex);screen.forumAnchor=restoreForumAnchor(state,entry,anchorIndex);screen.requestRating="editor".equals(screen.kind)&&Boolean.TRUE.equals(stateValue(state,"back_request_rating_"+anchorIndex));ReaderAnchor savedAnchor=ReaderAnchor.decode(stateValue(state,"back_reader_anchor_"+anchorIndex++));if("thread".equals(screen.kind)&&savedAnchor!=null&&savedAnchor.matches(screen.id,screen.page))screen.readerAnchor=savedAnchor;back.addLast(screen);if(entry.scrollY>=0)readingPositions.put(screenKey(screen),entry.scrollY);}
            Screen screen=navigationScreen(restored.current);restoreGallery(state,screen,-1);screen.subForumsExpanded=restoreSubForumsExpanded(state,restored.current,-1);screen.forumAnchor=restoreForumAnchor(state,restored.current,-1);screen.requestRating="editor".equals(screen.kind)&&Boolean.TRUE.equals(stateValue(state,"current_request_rating"));ReaderAnchor anchor=ReaderAnchor.decode(stateValue(state,"reader_anchor_v1"));if("thread".equals(screen.kind)&&anchor!=null&&anchor.matches(screen.id,screen.page))screen.readerAnchor=anchor;if(restored.current.scrollY>=0)readingPositions.put(screenKey(screen),restored.current.scrollY);go(screen,false);
        }else if(!openInitialForumIntent(getIntent())){go(new Screen("home","","NGA",1),false);routeIntent(getIntent());}
        Object avatarPreview=state==null?null:stateValue(state,AVATAR_PREVIEW_STATE);
        if(avatarPreview!=null)userSheets().restoreAvatarPreview(avatarPreview,contentScope(),store.loggedIn());
        if(state!=null&&galleryView==null&&current!=null&&"image".equals(current.kind)&&pictureView!=null&&current.id.equals(stateString(state,IMAGE_VIEWPORT_URL,null))){Object viewport=stateValue(state,IMAGE_VIEWPORT_STATE);if(viewport instanceof float[])pictureView.restoreViewport((float[])viewport);}
        pendingPrompt=state==null?null:PromptDraftState.decode(stateValue(state,PROMPT_DRAFT_STATE));restorePendingPrompt();
        CheckInService.checkOnOpenAsync(this);
        startupCreateEndedNs=startupOffset();
    }
    private static final class CatalogData {
        final ArrayList<String[]> rows=new ArrayList<>();final HashMap<String,String> icons=new HashMap<>();
    }
    /** Parsing the full directory is independent of a board response and never gates a direct board link. */
    private CatalogData readCatalog(){
        long begin=System.nanoTime();startupCatalogStartedNs=startupOffset();CatalogData result=new CatalogData();
        try{for(NgaApi.Forum f:api.forums()){String alias=f.name.substring(0,Math.min(2,f.name.length()));for(String[] common:FORUMS)if(common[0].equals(f.key))alias+=" "+common[3];result.rows.add(new String[]{f.key,f.name,f.category,alias,f.parentFid,f.searchAliases,android.text.TextUtils.join("\n",f.categoryNames)});result.icons.put(f.key,BoardIconCatalog.resolve(f.fid,f.stid,f.icon));}}
        catch(Exception unavailable){result.rows.clear();result.icons.clear();}
        if(result.rows.isEmpty()){Collections.addAll(result.rows,FORUMS);for(String[] row:FORUMS)result.icons.put(row[0],BoardIconCatalog.resolve(row[0],"",""));}
        startupCatalogNs=System.nanoTime()-begin;startupCatalogFinishedNs=startupOffset();return result;
    }
    private void applyCatalog(CatalogData data){catalog.clear();catalog.addAll(data.rows);forumIcons.putAll(data.icons);catalogReady=true;}
    private void ensureCatalogAsync(){
        if(catalogReady||catalogStarted||isFinishing()||isDestroyed())return;catalogStarted=true;
        try{io.execute(()->{CatalogData data=readCatalog();runOnUiThread(()->{if(isFinishing()||isDestroyed())return;applyCatalog(data);if(current!=null&&"home".equals(current.kind)){if(mainTab==1)go(current,false);else if(mainTab==0&&homeTab==0&&favoritePageUpdate!=null)favoritePageUpdate.run();}});});}
        catch(RejectedExecutionException stopped){catalogStarted=false;}
    }
    private void observeStartupForumDraw(View view,Screen owner,int token,String requestScope){
        final ViewTreeObserver observer=view.getViewTreeObserver();
        class FirstDraw implements ViewTreeObserver.OnDrawListener,View.OnAttachStateChangeListener{
            boolean posted;
            private void remove(){if(observer.isAlive())observer.removeOnDrawListener(this);ViewTreeObserver currentObserver=view.getViewTreeObserver();if(currentObserver!=observer&&currentObserver.isAlive())currentObserver.removeOnDrawListener(this);view.removeOnAttachStateChangeListener(this);}
            public void onDraw(){if(posted)return;posted=true;
                boolean live=token==generation&&current==owner&&!isFinishing()&&!isDestroyed()&&requestScope.equals(contentScope());
                if(live&&startupFirstDrawNs==0){startupFirstDrawNs=startupOffset();startupSessionAtFirstDraw=NgaStore.sessionReadDiagnostics();startupLocksAtFirstDraw=NgaStore.sessionLockDiagnostics();}
                navigationHandler.post(()->{remove();if(live&&token==generation&&current==owner&&!isFinishing()&&!isDestroyed()&&requestScope.equals(contentScope()))ensureCatalogAsync();});
            }
            public void onViewAttachedToWindow(View v){}
            public void onViewDetachedFromWindow(View v){remove();}
        }
        FirstDraw listener=new FirstDraw();view.addOnAttachStateChangeListener(listener);observer.addOnDrawListener(listener);
    }
    private void refreshSessionContent(){if(contentStarted&&store!=null&&current!=null&&root!=null&&!isFinishing()&&!isDestroyed()&&!contentScope().equals(activeContentScope)){avatarPicker=null;go(current,false);}}
    @Override protected void onStart(){super.onStart();contentStarted=true;refreshSessionContent();NativeNotifications.onForegroundAsync(this);refreshCloudBlocks();if(favoriteCoordinator!=null)favoriteCoordinator.onForeground(favoriteForegroundToken);}
    @Override protected void onResume(){super.onResume();refreshSessionContent();}
    @Override protected void onStop(){for(TopicPreviewView preview:new ArrayList<>(topicPreviews))preview.pause();if(topicPreviewImages!=null)topicPreviewImages.cancelAll();contentStarted=false;navigationHandler.removeCallbacks(sessionContentTask);if(favoriteCoordinator!=null)favoriteCoordinator.onBackground(favoriteForegroundToken);if(store!=null)store.clearScopeSnapshot();super.onStop();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);routeIntent(intent);}
    private NativeLinkTarget initialForumTarget(Intent intent){
        if(intent==null||!Intent.ACTION_VIEW.equals(intent.getAction())||intent.getData()==null||!trusted(intent.getData()))return null;
        NativeLinkTarget target=NativeLinkTarget.parse(intent.getData().toString(),store.host());return target.kind==NativeLinkTarget.FORUM?target:null;
    }
    /** A forum deep link has its own destination; do not start and cancel a homepage feed first. */
    private boolean openInitialForumIntent(Intent intent){
        NativeLinkTarget target=initialForumTarget(intent);if(target==null)return false;
        ForumRoute route=ForumRoute.fromParts(target.forumId,target.collectionId,target.parentForumId);
        String title=route.stid.isEmpty()?findForum(route.fid)[1]:"主题合集 "+route.stid;
        back.push(new Screen("home","","NGA",1));openForum(route,title,target.page,false);return true;
    }
    private void routeIntent(Intent intent){
        if(intent==null)return;
        if(Intent.ACTION_VIEW.equals(intent.getAction())&&intent.getData()!=null&&trusted(intent.getData()))handleLink(intent.getData().toString());
        else if(Intent.ACTION_SEND.equals(intent.getAction())&&"text/plain".equals(intent.getType())){
            try{CharSequence value=intent.getCharSequenceExtra(Intent.EXTRA_TEXT);if(value==null||value.length()>16384){toast("分享内容过长或没有文字链接");return;}openSharedText(value.toString(),true);}catch(RuntimeException malformed){toast("无法读取这条分享，请复制 NGA 链接后重试");}
        }
    }
    @Override public void onSaveInstanceState(Bundle b) { super.onSaveInstanceState(b); b.putInt("tab",homeTab); b.putInt("mainTab",mainTab); // Save the rendered tab together with its own scroll offset.
        if(store!=null)b.putStringArray(REACTION_SELECTION_STATE,reactionSelections.encode(contentScope()));
        captureCatalogPosition();b.putStringArray(HOME_SCREEN_STATE,HomeScreenState.capture(ratingCategory,ratingPage,communityCategory,communityQuery,communityPosition,communityOffset).encode());
        if(!communityAnchorKey.isEmpty())b.putStringArray(BOARD_DIRECTORY_ANCHOR_STATE,new String[]{communityAnchorKey,communityAnchorIdentity});
        if(avatarPicker!=null)b.putStringArray(AVATAR_PICKER_STATE,avatarPicker.encode());
        PromptDraftState prompt=capturePromptDraft();if(prompt!=null)b.putStringArray(PROMPT_DRAFT_STATE,prompt.encode());
        if(loginView!=null&&current!=null&&"auth".equals(current.kind)){int method=loginView.selectedMethod();if(method>=0&&method<=2){b.putInt(AUTH_METHOD_STATE,method);b.putString(AUTH_METHOD_SCOPE,contentScope());}}
        if(userSheets!=null){String[] preview=userSheets.saveAvatarPreview(contentScope());if(preview!=null)b.putStringArray(AVATAR_PREVIEW_STATE,preview);}
        snapshotGallery();if(galleryView==null&&pictureView!=null&&current!=null&&"image".equals(current.kind)&&current.id.equals(pictureUrl)){float[] viewport=pictureView.viewportState();if(viewport!=null&&viewport.length==3&&NavigationState.imageUrl(pictureUrl)){b.putString(IMAGE_VIEWPORT_URL,pictureUrl);b.putFloatArray(IMAGE_VIEWPORT_STATE,viewport);}}
        if(editorView!=null)editorView.saveOnPause();
        if(NavigationState.imageUrl(savingImage)&&NativeImages.allowed(savingImage)){b.putString("savingImage",savingImage);b.putString("savingImageFormat",savingImageFormat);}
        ReaderAnchor anchor=captureReaderAnchor();if(anchor!=null)b.putStringArray("reader_anchor_v1",anchor.encode());
        saveNavigation(b);b.putBoolean("current_request_rating",current!=null&&current.requestRating);int anchorIndex=0;for(Screen screen:back){b.putBoolean("back_request_rating_"+anchorIndex,screen.requestRating);ReaderAnchor savedAnchor=screen.readerAnchor;if(savedAnchor!=null&&savedAnchor.matches(screen.id,screen.page))b.putStringArray("back_reader_anchor_"+anchorIndex,savedAnchor.encode());if(++anchorIndex==NavigationState.MAX_BACK)break;}
    }
    @Override protected void onPause(){if(editorView!=null)editorView.saveOnPause();persistReadingProgress();super.onPause();}
    @Override public void dump(String prefix,java.io.FileDescriptor fd,java.io.PrintWriter out,String[] args){
        if(args!=null&&Arrays.asList(args).contains("--motion-trace-start")){
            if(primarySelection!=null){primarySelection.startMotionTrace();out.println(prefix+"LiquidTrace started");}
            else out.println(prefix+"LiquidTrace unavailable");
            return;
        }
        if(args!=null&&Arrays.asList(args).contains("--motion-trace")){
            if(primarySelection!=null)primarySelection.dumpMotionTrace(out,prefix);
            else out.println(prefix+"LiquidTrace unavailable");
            return;
        }
        if(args!=null&&(Arrays.asList(args).contains("--ui-state")||Arrays.asList(args).contains("--startup-state"))){
            out.println(prefix+"NgaPureStartup setupNs="+startupSetupNs+" catalogNs="+startupCatalogNs+" catalogStartedNs="+startupCatalogStartedNs+" catalogFinishedNs="+startupCatalogFinishedNs+" catalogDeferred="+catalogDeferred+" catalogReady="+catalogReady+" forumQueuedNs="+startupForumQueuedNs+" workerStartedNs="+startupWorkerStartedNs+" sessionReadyNs="+startupSessionReadyNs+" cacheReadyNs="+startupCacheReadyNs+" networkStartedNs="+startupNetworkStartedNs+" responseReadyNs="+startupResponseReadyNs+" contentBoundNs="+startupContentBoundNs+" firstDrawNs="+startupFirstDrawNs+" createEndedNs="+startupCreateEndedNs);
            out.println(prefix+"NgaPureSessionReads "+NgaStore.sessionReadDiagnostics());
            out.println(prefix+"NgaPureSessionLocks "+NgaStore.sessionLockDiagnostics());
            if(!startupLocksAtFirstDraw.isEmpty())out.println(prefix+"NgaPureSessionLocksAtFirstDraw "+startupLocksAtFirstDraw);
            if(!startupSessionAtFirstDraw.isEmpty())out.println(prefix+"NgaPureSessionAtFirstDraw "+startupSessionAtFirstDraw);
            out.println(prefix+"NgaPureStartupCheckIn "+CheckInService.startupDiagnostics());
            out.println(prefix+"NgaPureStartupNotifications "+NativeNotifications.startupDiagnostics());
            if(Arrays.asList(args).contains("--startup-state"))return;
            out.println(prefix+"NgaPureUI screen="+(current==null?"none":current.kind)+" main="+mainTab+" group="+homeTab+" scroll="+(currentScroll==null?-1:currentScroll.getScrollY()));
            out.println(prefix+"NgaPurePage page="+(current==null?0:current.page)+" generation="+generation+" jumpSheet="+(pageJumpDialog!=null));
            out.println(prefix+"NgaPureDialogs prompts="+glassDialogs.size());
            if(communityList!=null)out.println(prefix+"NgaPureDirectory columns="+communityColumns+" rows="+communityRows.size()+" catalogEntries="+catalog.size()+" queryLength="+communityQuery.length()+" firstRow="+communityList.getFirstVisiblePosition());
            out.println(prefix+"NgaPureFavoriteSync pending="+store.pendingFavoriteChanges(favoriteScope()).size()+" running="+favoriteCoordinator.running());
            ForumFavoritesCoordinator.Snapshot favoritesState=favoriteCoordinator.status(favoriteScope());out.println(prefix+"NgaPureFavoriteAuto loaded="+favoritesState.loaded+" cloudCount="+favoritesState.cloudCount+" failure="+favoritesState.failure.name());
            if(current!=null&&"forum".equals(current.kind)&&ForumRoute.isKey(current.id))out.println(prefix+"NgaPureForum route="+current.id+" parent="+current.extra+" childCount="+nativeChildForumCount+" topicCount="+nativeForumTopicCount);
            if(current!=null&&"forum".equals(current.kind))out.println(prefix+"NgaPureForumLoad source="+nativeForumSource+" ageMs="+nativeForumCacheAgeMs+" requests="+nativeForumRequests+" requestNs="+nativeForumRequestNanos+" headersNs="+nativeForumHeadersNanos+" bodyNs="+nativeForumBodyNanos+" parsedNs="+nativeForumParsedNanos+" responseBytes="+nativeForumResponseBytes);
            out.println(prefix+"NgaPureUI loggedIn="+(store!=null&&store.loggedIn())+" autoCheckIn="+(store!=null&&store.autoCheckIn())+" glass="+(store!=null&&store.glassEnabled()));
            out.println(prefix+"NgaPureUI dockRows="+(navigationGlass==null?0:1)+" groupPopup="+(homeGroupPopup!=null&&homeGroupPopup.isShowing()));
            if(primarySelection!=null)out.println(prefix+"NgaPureMotion "+primarySelection.motionState());
            if(navigationGlass!=null)out.println(prefix+"NgaPureUI navPath="+navigationGlass.renderingPath()+" snapshots="+navigationGlass.snapshotCount()+" visible="+navigationGlass.isShown());
            out.println(prefix+"NgaPureUI pendingTab="+pendingHomeTab+" homeBuilds="+homeBuilds);
            out.println(prefix+"NgaPureTransition "+pageTransition.state());
            ReaderAnchor position=captureReaderAnchor();out.println(prefix+"NgaPureReading floor="+(position==null?-1:position.floor)+" fraction="+(position==null?-1:position.fraction)+" column="+(position==null?0:position.columnWidth));
            out.println(prefix+"NgaPureResources pageQueued="+pageReads.queued()+" positions="+readingPositions.size()+" "+nativeImages.state());
            int previewCount=0,previewLoaded=0;for(TopicPreviewView preview:topicPreviews){previewCount+=preview.imageCount();previewLoaded+=preview.loadedImageCount();}if(galleryView!=null){ImageGalleryState state=galleryView.snapshot();out.println(prefix+"NgaPureGallery index="+(state.index+1)+" total="+state.urls.size()+" ready="+galleryView.ready()+" limited="+state.truncated);}
            out.println(prefix+"NgaPureTopicPreviews enabled="+store.topicPreviewsEnabled()+" rows="+topicPreviews.size()+" images="+previewCount+" loaded="+previewLoaded);
            out.println(prefix+"NgaPureVotes posts="+nativePostCount+" likesKnown="+nativeVoteCount+" knownCounts="+nativeDisplayedVoteCount);
            out.println(prefix+"NgaPureNative webViews="+webComponentCount(root)+" editor="+(editorView!=null)+" community="+(communityView!=null)+" auth="+(loginView!=null)+" checkInToday="+NgaStore.today().equals(store.lastCheckInDay()));
            if(loginView!=null)loginView.dumpState(out,prefix);
            return;
        }
        super.dump(prefix,fd,out,args);
    }
    private int webComponentCount(View view){if(view==null)return 0;int count=view.getClass().getName().startsWith("android.webkit.")?1:0;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)count+=webComponentCount(group.getChildAt(i));}return count;}
    private int dp(float n) { return (int)(n*getResources().getDisplayMetrics().density+.5f); }
    private void colors() {
        String theme=store.theme();
        dark="dark".equals(theme) || ("system".equals(theme) && (getResources().getConfiguration().uiMode & 48)==32);
        palette=AppPalette.of(dark);
        bg=palette.background; surface=palette.surface; ink=palette.ink;
        muted=palette.muted; line=palette.line; gold=palette.accent;
        setTheme(dark ? R.style.AppThemeDark : R.style.AppTheme);
    }
    private GradientDrawable shape(int color,int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView text(String s,int size,int color) {
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(false);return t;
    }
    private TextView button(String label,Runnable action) {
        TextView t=text(label,14,ink);t.setPadding(dp(16),dp(11),dp(16),dp(11));t.setGravity(Gravity.CENTER);
        t.setMinimumHeight(dp(48));t.setMinimumWidth(dp(48));t.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(palette.ripple),shape(surface,8),null));
        t.setOnClickListener(v->action.run());if(store.motionEnabled())Motion.press(t);return t;
    }
    private LinearLayout vertical() { LinearLayout l=new LinearLayout(this); l.setOrientation(1);return l; }
    private LinearLayout horizontal() { LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l; }
    private void separator(LinearLayout l) { View v=new View(this);v.setBackgroundColor(line);l.addView(v,new LinearLayout.LayoutParams(-1,dp(1))); }
    private String screenKey(){return screenKey(current);}
    private String screenKey(Screen screen){return screen.kind+":"+screen.id+":"+screen.page+("thread".equals(screen.kind)&&screen.pid.matches("[0-9]{1,18}")?":pid:"+screen.pid:"")+("home".equals(screen.kind)?":"+mainTab+":"+homeTab+(mainTab==2?":"+ratingCategory+":"+ratingPage:""):"")+("search".equals(screen.kind)?":fid:"+screen.extra:"")+("thread".equals(screen.kind)?":author:"+screen.extra:"");}
    private LinearLayout scrollBody() {
        final int columnLimit=current!=null&&"thread".equals(current.kind)?680:720;
        ScrollView scroll=new ScrollView(this){@Override public boolean dispatchTouchEvent(android.view.MotionEvent event){NativeRichText.onScrollTouch(this,event);return super.dispatchTouchEvent(event);}@Override protected void onMeasure(int widthSpec,int heightSpec){int gutter=Math.max(0,(View.MeasureSpec.getSize(widthSpec)-dp(columnLimit))/2);if(getPaddingLeft()!=gutter||getPaddingRight()!=gutter)setPadding(gutter,0,gutter,0);super.onMeasure(widthSpec,heightSpec);}};scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content=vertical();if(current!=null&&"thread".equals(current.kind))content.setTag("reader-column");content.setPadding(0,0,0,dp(20+homeBottomSpace()));scroll.addView(content);body.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        currentScroll=scroll;currentScrollKey=screenKey();scroll.setTag(currentScrollKey);restoreReadingPosition(content);return content;
    }
    private void restoreReadingPosition(LinearLayout content){
        if(!(content.getParent() instanceof ScrollView))return;ScrollView scroll=(ScrollView)content.getParent();Integer position=readingPositions.get(String.valueOf(scroll.getTag()));if(position!=null&&position>0)restoreScrollAfterLayout(content,scroll,position,generation);
    }
    private void restoreScrollAfterLayout(LinearLayout content,ScrollView scroll,int position,int token){
        final ViewTreeObserver initial=content.getViewTreeObserver();
        class Restore implements ViewTreeObserver.OnGlobalLayoutListener,View.OnAttachStateChangeListener{
            private void remove(){if(initial.isAlive())initial.removeOnGlobalLayoutListener(this);ViewTreeObserver active=content.getViewTreeObserver();if(active!=initial&&active.isAlive())active.removeOnGlobalLayoutListener(this);content.removeOnAttachStateChangeListener(this);}
            public void onGlobalLayout(){if(token!=generation||currentScroll!=scroll||isDestroyed()){remove();return;}if(content.getHeight()<1||scroll.getHeight()<1)return;remove();scroll.scrollTo(0,position);}
            public void onViewAttachedToWindow(View v){}
            public void onViewDetachedFromWindow(View v){remove();}
        }
        Restore restore=new Restore();content.addOnAttachStateChangeListener(restore);initial.addOnGlobalLayoutListener(restore);
    }
    private static String scopeDigest(String host,String identity){
        try{
            byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest((host+"\0"+identity).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            char[] digits="0123456789abcdef".toCharArray(),value=new char[bytes.length*2];
            for(int i=0;i<bytes.length;i++){value[i*2]=digits[(bytes[i]&255)>>>4];value[i*2+1]=digits[bytes[i]&15];}return new String(value);
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private String contentScope(){return store.captureScopeSnapshot().contentScope;}
    private String localDataScope(){return store.captureScopeSnapshot().localDataScope;}
    private FavoriteRenderSnapshot captureFavoriteRenderSnapshot(){
        SessionScopeSnapshot session=store.captureScopeSnapshot();String local=session.localDataScope,account=session.loggedIn?local:"";
        Set<String> pending=new HashSet<>(),busy=new HashSet<>();
        for(ForumFavoriteSyncState.Change change:store.pendingFavoriteChanges(account)){pending.add(change.key);if(favoriteCoordinator!=null&&favoriteCoordinator.processing(account,change.key))busy.add(change.key);}
        return new FavoriteRenderSnapshot(session.contentScope,account,local,store.favoriteForums(),pending,busy);
    }
    private FavoriteRenderSnapshot favoriteSnapshotForRender(){
        if(favoriteRenderPass!=null)return favoriteRenderPass;
        // A forum's content must not wait for a concurrent durable favorite commit.
        return current!=null&&"forum".equals(current.kind)?forumFavoriteSnapshot:captureFavoriteRenderSnapshot();
    }
    private void configureAuthorAvatars(){configureAuthorAvatars(store.captureCheckInSession());}
    private void configureAuthorAvatars(NgaStore.CheckInSession session){nativeImages.setAuthorAvatarSource(authorAvatarSource(session));}
    private NativeAvatarResolver.Source authorAvatarSource(){return authorAvatarSource(store.captureCheckInSession());}
    private NativeAvatarResolver.Source authorAvatarSource(final NgaStore.CheckInSession expected){
        if(expected.account.isEmpty())return NativeAvatarResolver.publicSource();
        final String expectedScope=scopeDigest(expected.host,expected.cookie);
        return NativeAvatarResolver.sessionSource(expected.host,expected.cookie,
                ()->expectedScope.equals(store.captureScopeSnapshot().contentScope));
    }
    private List<NgaApi.Topic> homeTopics() throws Exception{
        final String scope=contentScope();final long revision=homeCache.revision();
        List<NgaApi.Topic> snapshot=homeCache.get(scope,android.os.SystemClock.elapsedRealtime(),5*60*1000);if(snapshot!=null)return new ArrayList<>(snapshot);
        List<NgaApi.Topic> loaded=api.hotTopics();
        if(!Thread.currentThread().isInterrupted())homeCache.put(scope,contentScope(),revision,android.os.SystemClock.elapsedRealtime(),new ArrayList<>(loaded));
        return loaded;
    }
    private List<NgaApi.Topic> homeTopicsSnapshot(){List<NgaApi.Topic> snapshot=homeCache.get(contentScope(),android.os.SystemClock.elapsedRealtime(),5*60*1000);return snapshot==null?null:new ArrayList<>(snapshot);}
    private void refreshHomeTopics(){homeCache.clear();}
    private void clearForumData(){forumSnapshots.clear();if(forumResponseCache!=null)forumResponseCache.invalidate();}
    private void refreshCurrent(){if(current!=null)current.forumAnchor=null;forumList=null;forumListOwner=null;clearForumData();if(topicPreviewImages!=null)topicPreviewImages.invalidate(contentScope());refreshHomeTopics();if(currentScroll!=null)currentScroll.scrollTo(0,0);readingPositions.remove(screenKey());go(current,false);}
    private void closeNativePage(){closeNativePage(false);}
    private void closeNativePage(boolean preserveAuthorization) {
        if(activeForumRead!=null){activeForumRead.delivery.cancel();activeForumRead=null;}
        cancelDirectoryFilter();directoryFilterTask=null;forumList=null;forumListOwner=null;
        for(TopicPreviewView preview:new ArrayList<>(topicPreviews))preview.close();topicPreviews.clear();if(topicPreviewImages!=null)topicPreviewImages.cancelAll();
        threadLoaded=false;loadedReadingScope="";readerThreadPage=null;readerVoteViews.clear();
        nativePostCount=0;nativeVoteCount=0;nativeDisplayedVoteCount=0;nativeChildForumCount=0;nativeForumTopicCount=-1;
        nativeForumSource="none";nativeForumCacheAgeMs=0;nativeForumRequestNanos=0;nativeForumHeadersNanos=0;nativeForumBodyNanos=0;nativeForumParsedNanos=0;nativeForumRequests=0;nativeForumResponseBytes=0;
        if(pictureRequest!=null){pictureRequest.cancel();pictureRequest=null;}
        if(galleryView!=null){galleryView.close();galleryView=null;}
        pictureView=null;pictureUrl=null;
        if(loginView!=null){if(preserveAuthorization)loginView.suspend();else loginView.close();loginView=null;}
        if(editorView!=null){editorView.close();editorView=null;}
        if(communityView!=null){communityView.close();communityView=null;}
        if(followingView!=null){followingView.close();followingView=null;}
        if(activitiesView!=null){activitiesView.close();activitiesView=null;}
        if(threadSheets!=null){threadSheets.dismiss();threadSheets=null;}
        if(userSheets!=null){userSheets.close();userSheets=null;}
        attachmentTarget=null;imageSaveButton=null;imageReady=false;preparingExport=false;
    }
    /** Decorative glyphs belong to their labeled parent target, not the keyboard or screen-reader order. */
    private IconView decoration(String glyph,int color){IconView view=new IconView(this,glyph);view.setColor(color);view.setFocusable(false);view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return view;}
    private IconView icon(String glyph,String description,int color,Runnable click){
        IconView view=new IconView(this,glyph);view.setColor(color);view.setContentDescription(description);view.setOnClickListener(v->click.run());if(store.motionEnabled())Motion.press(view);return view;
    }
    private void go(Screen s,boolean push) {
        go(s,push,false);
    }
    private void go(Screen s,boolean push,boolean backRestore) {
        if(isFinishing()||isDestroyed())return;
        PromptDraftState waitingPrompt=pendingPrompt;pendingPrompt=null;
        closeGlassDialogs();closePageJump();pageTransition.finish();pageReads.cancelAll();
        Screen previousScreen=current;
        boolean sameThread=previousScreen!=null&&"thread".equals(previousScreen.kind)&&"thread".equals(s.kind)&&previousScreen.id.equals(s.id);
        boolean pageForward=push&&"thread".equals(s.kind)&&!sameThread;
        boolean pageBack=!push&&previousScreen!=null&&"thread".equals(previousScreen.kind)&&!sameThread;
        View outgoing=(store.motionEnabled()&&Motion.enabled(this)&&(pageForward||pageBack))?PageTransition.capture(root):null;
        cancelHomeNavigation();
        dismissHomeGroups();rememberReadingState();
        clearFavoritePageBindings();
        String localScope=localDataScope();if(!localScope.equals(activeDirectoryScope)){resetDirectoryPosition();activeDirectoryScope=localScope;}
        // Bindings were cleared above; the new screen must own any asynchronous favorite refresh.
        String scope=contentScope();reactionSelections.useScope(scope);if(!scope.equals(activeContentScope)){s.forumAnchor=null;for(Screen retained:back)retained.forumAnchor=null;clearForumData();readingPositions.clear();refreshHomeTopics();activeContentScope=scope;store.activateFavoriteScope(favoriteScope());favoriteCoordinator.requestRefresh(true);configureAuthorAvatars();}
        if(push&&current!=null){back.push(current);while(back.size()>NavigationState.MAX_BACK)back.removeLast();}
        current=s;ArrayList<Screen> retainedForums=new ArrayList<>(back);retainedForums.add(current);forumSnapshots.retain(retainedForums,SystemClock.elapsedRealtime());
        generation++;userFilterRevision++;closeNativePage();
        // Start the real page read while the new toolbar/body are being constructed.
        final ForumReadLaunch forumLaunch="forum".equals(s.kind)?beginForumRead(s,backRestore,scope):null;
        colors();userFilter="forum".equals(s.kind)?null:NativeUserActions.filter(this);communitySearch=null;homeCanvas=null;navigationGlass=null;primarySelection=null;
        final boolean homeScreen="home".equals(s.kind);final int toolbarColor=bg;
        final FoldAdaptiveLayout paneLayout=new FoldAdaptiveLayout(this);adaptiveLayout=paneLayout;FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(bg);root=vertical();root.setBackgroundColor(bg);frame.addView(root,new FrameLayout.LayoutParams(-1,-1));final LinearLayout layoutRoot=root;final View[] footerHolder=new View[1];
        View statusScrim=new View(this);statusScrim.setBackgroundColor(bg);frame.addView(statusScrim,new FrameLayout.LayoutParams(-1,0,Gravity.TOP));View navigationScrim=new View(this);navigationScrim.setBackgroundColor(bg);frame.addView(navigationScrim,new FrameLayout.LayoutParams(-1,0,Gravity.BOTTOM));
        getWindow().getDecorView();
        if(Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            frame.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());android.graphics.Insets keyboard=insets.getInsets(WindowInsets.Type.ime());int[] padding=paneLayout.insetsForPane(bars.left,bars.top,bars.right,Math.max(bars.bottom,keyboard.bottom));int[] scrims=paneLayout.insetsForPane(bars.left,bars.top,bars.right,bars.bottom);layoutRoot.setPadding(padding[0],padding[1],padding[2],padding[3]);statusScrim.setLayoutParams(new FrameLayout.LayoutParams(-1,scrims[1],Gravity.TOP));navigationScrim.setLayoutParams(new FrameLayout.LayoutParams(-1,scrims[3],Gravity.BOTTOM));if(footerHolder[0]!=null){int visibility=padding[3]>scrims[3]?View.GONE:View.VISIBLE;boolean changed=footerHolder[0].getVisibility()!=visibility;footerHolder[0].setVisibility(visibility);if(changed&&visibility==View.VISIBLE)footerHolder[0].post(this::ensureGlassMaterial);}return insets;});
            WindowInsetsController controller=getWindow().getInsetsController();if(controller!=null)controller.setSystemBarsAppearance(dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }else getWindow().getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setStatusBarColor(toolbarColor);getWindow().setNavigationBarColor(bg);
        mainToolbar=createToolbar(s);root.addView(mainToolbar,new LinearLayout.LayoutParams(-1,dp(56)));
        FrameLayout pageArea=new FrameLayout(this);pageArea.setClipChildren(false);pageArea.setClipToPadding(false);root.addView(pageArea,new LinearLayout.LayoutParams(-1,0,1));
        body=new FrameLayout(this);body.setBackgroundColor(bg);pageArea.addView(body,new FrameLayout.LayoutParams(-1,-1));
        if(homeScreen){homeCanvas=body;View footer=bottomNavigation();footerHolder[0]=footer;pageArea.addView(footer,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));home();}
        else if("forum".equals(s.kind))forum(s,forumLaunch);else if("thread".equals(s.kind))thread(s);else if("settings".equals(s.kind))settings();else if("profile".equals(s.kind))profile();else if("saved".equals(s.kind))localTopics(true);else if("history".equals(s.kind))localTopics(false);else if("image".equals(s.kind)){if(s.gallery!=null||s.galleryExpired)imageGallery(s);else image(s.id);}else if("search".equals(s.kind))searchResults(s);else if("drafts".equals(s.kind))drafts();else nativePage(s);
        paneLayout.addView(frame,new FrameLayout.LayoutParams(-1,-1));setContentView(paneLayout);frame.requestApplyInsets();
        if(outgoing!=null)pageTransition.start(frame,root,outgoing,pageBack);
        if(waitingPrompt!=null&&current==s&&waitingPrompt.matches(contentScope(),promptPageIdentity())){pendingPrompt=waitingPrompt;restorePendingPrompt();}
        if(!"forum".equals(s.kind)&&!catalogReady)ensureCatalogAsync();
    }
    private void rememberReadingState(){
        snapshotGallery();captureForumPosition();persistReadingProgress();
        ReaderAnchor anchor=captureReaderAnchor();if(current!=null&&"thread".equals(current.kind))current.readerAnchor=anchor;
        if(currentScroll!=null&&currentScrollKey!=null)readingPositions.put(currentScrollKey,currentScroll.getScrollY());captureCatalogPosition();communityList=null;forumList=null;forumListOwner=null;currentScroll=null;currentScrollKey=null;
    }
    private void persistReadingProgress(){
        if(readingProgress==null||store==null||current==null||!"thread".equals(current.kind)||!threadLoaded||!current.extra.isEmpty()||!store.historyEnabled()||!loadedReadingScope.equals(contentScope()))return;
        final String scope=localDataScope(),tid=current.id;final int page=current.page;final ReaderAnchor anchor=captureReaderAnchor();final Context app=getApplicationContext();final long epoch=readingWriteEpoch;
        READING_WRITES.execute(()->{synchronized(READING_WRITES_LOCK){if(epoch!=readingWriteEpoch)return;NgaStore local=new NgaStore(app);if(!local.historyEnabled())return;boolean retained=false;for(NgaApi.Topic t:local.history())if(tid.equals(t.tid)){retained=true;break;}if(retained)new ReadingProgress(app).save(scope,tid,page,anchor);}});
    }
    private void removeReadingProgress(String tid){synchronized(READING_WRITES_LOCK){readingWriteEpoch++;boolean saved=tid==null?readingProgress.clear(localDataScope()):readingProgress.remove(localDataScope(),tid);if(!saved)toast("阅读位置未能清除，请稍后重试");}}
    private void recordBoardVisit(ForumRoute route,String title){
        if(!store.historyEnabled())return;final Context app=getApplicationContext();final String scope=localDataScope(),key=route.key,parent=route.parentFid,image=forumIcon(route.key);
        String label=title==null||title.trim().isEmpty()?key:title.trim();if(label.length()>120){int end=120;if(Character.isHighSurrogate(label.charAt(end-1)))end--;label=label.substring(0,end);}final String name=label;final long epoch=boardWriteEpoch;
        READING_WRITES.execute(()->{synchronized(READING_WRITES_LOCK){if(epoch!=boardWriteEpoch||!new NgaStore(app).historyEnabled())return;try{new BoardVisits(app).record(scope,key,name,parent,image);}catch(IllegalStateException ignored){/* A failed local record must not interrupt a readable board. */}}});
    }
    private boolean clearBoardVisits(){try{synchronized(READING_WRITES_LOCK){boardWriteEpoch++;boardVisits.clear(localDataScope());}return true;}catch(IllegalStateException failed){return false;}}
    private String directoryIdentity(){return localDataScope()+"\n"+communityCategory+"\n"+communityQuery;}
    private boolean restoreDirectoryAnchor(Object raw){
        if(!(raw instanceof String[])||((String[])raw).length!=2)return false;String[] value=(String[])raw;
        if(value[0]==null||value[1]==null||value[0].length()>180||value[1].length()>450||value[0].matches("(?s).*[\\x00-\\x1f\\x7f].*")||!directoryIdentity().equals(value[1]))return false;
        if(!ForumRoute.isKey(value[0])&&!(value[0].startsWith("category:")&&value[0].length()>9))return false;
        communityAnchorKey=value[0];communityAnchorIdentity=value[1];return true;
    }
    private String directoryRowKey(BoardDirectory.Row row){return row.isHeader()?"category:"+row.category:row.entries.isEmpty()?"":row.entries.get(0)[0];}
    private int directoryAnchorPosition(){
        if(communityAnchorKey.isEmpty())return Math.max(0,Math.min(communityPosition,communityRows.size()-1));
        if(!directoryIdentity().equals(communityAnchorIdentity)){communityOffset=0;return 0;}
        if(communityAnchorKey.startsWith("category:")){for(int i=0;i<communityRows.size();i++)if(communityRows.get(i).isHeader()&&communityAnchorKey.equals(directoryRowKey(communityRows.get(i))))return i;}
        int found=BoardDirectory.rowForKey(communityRows,communityAnchorKey);return found>=0?found:Math.max(0,Math.min(communityPosition,communityRows.size()-1));
    }
    private void cancelCatalogRestore(){if(communityList instanceof CatalogListView){Runnable cancel=((CatalogListView)communityList).cancelPendingRestore;if(cancel!=null)cancel.run();}}
    private void cancelDirectoryFilter(){if(directoryFilterTask!=null)navigationHandler.removeCallbacks(directoryFilterTask);directoryFilterPending=false;}
    private boolean flushDirectoryFilter(){if(directoryFilterPending&&directoryFilterTask!=null){Runnable task=directoryFilterTask;cancelDirectoryFilter();task.run();return true;}return false;}
    private void resetDirectoryPosition(){cancelDirectoryFilter();cancelCatalogRestore();communityPosition=0;communityOffset=0;communityAnchorKey="";communityAnchorIdentity="";}
    private void captureCatalogPosition(){if(communityList!=null&&communityList.getTag()!=catalogRestoreTag&&directoryIdentity().equals(communityDataIdentity)){View first=communityList.getChildAt(0);if(first!=null){communityPosition=communityList.getFirstVisiblePosition();communityOffset=first.getTop()-communityList.getListPaddingTop();if(communityPosition<communityRows.size()){communityAnchorKey=directoryRowKey(communityRows.get(communityPosition));communityAnchorIdentity=communityDataIdentity;}}}}
    private void restoreCatalogPosition(ListView list,int position,int offset,int token){
        if(position==0&&offset==0)return;
        final ViewTreeObserver initial=list.getViewTreeObserver();list.setTag(catalogRestoreTag);
        class Restore implements ViewTreeObserver.OnPreDrawListener,View.OnAttachStateChangeListener{
            private boolean pending=true;
            private void remove(){if(!pending)return;pending=false;if(initial.isAlive())initial.removeOnPreDrawListener(this);ViewTreeObserver active=list.getViewTreeObserver();if(active!=initial&&active.isAlive())active.removeOnPreDrawListener(this);list.removeOnAttachStateChangeListener(this);if(list instanceof CatalogListView)((CatalogListView)list).cancelPendingRestore=null;if(list.getTag()==catalogRestoreTag)list.setTag(null);}
            public boolean onPreDraw(){
                if(!pending)return true;
                if(token!=generation||communityList!=list||isDestroyed()){remove();return true;}
                if(list.getCount()==0){remove();return true;}
                if(!list.isLaidOut()||list.isLayoutRequested()||list.getChildCount()==0)return true;
                // AbsListView's internal padding is initialized by layout, not setPadding().
                remove();list.setSelectionFromTop(position,offset);return false;
            }
            public void onViewAttachedToWindow(View view){}
            public void onViewDetachedFromWindow(View view){remove();}
        }
        Restore restore=new Restore();list.addOnAttachStateChangeListener(restore);if(list instanceof CatalogListView)((CatalogListView)list).cancelPendingRestore=restore::remove;initial.addOnPreDrawListener(restore);
    }
    /** Keeps the leading route and a readable title when a fold pane is narrower than the window. */
    private final class ResponsiveToolbar extends LinearLayout {
        TextView title;View secondary,favorite;IconView overflow;boolean homeScreen,compact;
        ResponsiveToolbar(){super(MainActivity.this);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);}
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            if(title!=null){
                int width=View.MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight();
                float scale=Math.min(1.5f,getResources().getConfiguration().fontScale);
                int wanted=(int)Math.ceil(Math.min(title.getPaint().measureText(title.getText().toString()),dp(136)*scale))+dp(16);
                int controls=dp(48)*(secondary==null?2:3)+(favorite==null?0:dp(48));
                compact=width<controls+wanted;
                if(secondary!=null)secondary.setVisibility(compact?View.GONE:View.VISIBLE);
                if(favorite!=null)favorite.setVisibility(compact?View.GONE:View.VISIBLE);
                title.setPadding(dp(compact?4:8),0,dp(compact?4:8),0);
                if(homeScreen&&overflow!=null){overflow.setGlyph(compact?"more":"bell");overflow.setContentDescription(compact?"更多操作":"消息");}
            }
            super.onMeasure(widthSpec,heightSpec);
        }
    }
    private LinearLayout createToolbar(Screen s){
        final boolean homeScreen="home".equals(s.kind);
        ResponsiveToolbar bar=new ResponsiveToolbar();bar.homeScreen=homeScreen;bar.setPadding(dp(8),0,dp(8),0);bar.setBackgroundColor(bg);
        bar.addView(icon(homeScreen?"menu":"back",homeScreen?"打开菜单":"返回",ink,()->{if(homeScreen)drawer();else onBackPressed();}),new LinearLayout.LayoutParams(dp(48),dp(56)));
        if(homeScreen&&mainTab==1){
            LinearLayout box=horizontal();box.setBackground(shape(palette.soft,22));
            box.addView(icon("search","搜索",muted,this::search),new LinearLayout.LayoutParams(dp(48),dp(48)));
            communitySearch=new EditText(this);communitySearch.setTextSize(15);communitySearch.setTextColor(ink);communitySearch.setHintTextColor(muted);communitySearch.setSingleLine();
            communitySearch.setFilters(new InputFilter[]{new InputFilter.LengthFilter(HomeScreenState.QUERY_LIMIT)});communitySearch.setHint("搜索名称或 ID");communitySearch.setContentDescription("版块筛选关键词");
            communitySearch.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);communitySearch.setPadding(0,0,dp(4),0);communitySearch.setBackgroundColor(Color.TRANSPARENT);
            box.addView(communitySearch,new LinearLayout.LayoutParams(0,dp(48),1));
            IconView clear=icon("close","清空版块搜索",muted,()->{if(communitySearch!=null)communitySearch.setText("");});clear.setTag("directory-search-clear");clear.setVisibility(communityQuery.isEmpty()?View.GONE:View.VISIBLE);box.addView(clear,new LinearLayout.LayoutParams(dp(48),dp(48)));
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(48),1);bp.setMargins(dp(4),0,dp(8),0);bar.addView(box,bp);
        }else{
            String name=homeScreen?new String[]{"发现","版块","评分","我的"}[mainTab]:s.title;TextView title=text(name,homeScreen?24:16,ink);title.setTag("screen-title");title.setTypeface(null,homeScreen?1:0);title.setSingleLine();title.setEllipsize(TextUtils.TruncateAt.END);title.setPadding(dp(8),0,dp(8),0);bar.title=title;bar.addView(title,new LinearLayout.LayoutParams(0,dp(56),1));
            bar.secondary=homeScreen&&mainTab==3?icon("settings","设置",ink,()->go(new Screen("settings","","设置",1),true)):icon("search","搜索",ink,this::search);
            bar.addView(bar.secondary,new LinearLayout.LayoutParams(dp(48),dp(56)));
            bar.overflow=icon(homeScreen?"bell":"more",homeScreen?"消息":"更多操作",ink,()->{if(homeScreen&&!bar.compact)messages();else menu();});bar.addView(bar.overflow,new LinearLayout.LayoutParams(dp(48),dp(56)));
        }
        return bar;
    }
    private int homeBottomSpace(){return current!=null&&"home".equals(current.kind)?(primarySelection==null?64:primarySelection.preferredHeightDp())+16:0;}
    private void ensureGlassMaterial(){
        GlassPanel nav=navigationGlass;if(nav!=null&&nav.isShown())nav.refreshBackdrop();
    }
    private void cancelHomeNavigation(){
        navigationHandler.removeCallbacks(homeNavigationTask);pendingHomeTab=-1;
    }
    private void commitHomeNavigation(){
        int destination=pendingHomeTab;
        if(destination>=0&&primarySelection!=null&&primarySelection.isTrackingTouch()){
            navigationHandler.postDelayed(homeNavigationTask,HOME_NAVIGATION_QUIET_MS);return;
        }
        pendingHomeTab=-1;
        if(destination<0||isFinishing()||isDestroyed()||current==null||!"home".equals(current.kind)||mainTab==destination)return;
        rememberReadingState();mainTab=destination;back.clear();replaceHomeContent(true);
    }
    private void replaceHomeContent(boolean toolbar){
        closeGlassDialogs();
        closePageJump();
        homeBuilds++;clearFavoritePageBindings();
        generation++;pageReads.cancelAll();closeNativePage();communitySearch=null;
        if(toolbar){int at=root.indexOfChild(mainToolbar);root.removeView(mainToolbar);mainToolbar=createToolbar(current);root.addView(mainToolbar,at,new LinearLayout.LayoutParams(-1,dp(56)));}
        homeCanvas.removeAllViews();body=homeCanvas;home();
        updateHomeNavigationLabel();
        ensureGlassMaterial();
    }
    private void selectHomeGroup(int index){
        cancelHomeNavigation();dismissHomeGroups();if(homeTab==index)return;rememberReadingState();homeTab=index;replaceHomeContent(false);
    }
    private void navigateHome(int index){
        dismissHomeGroups();
        if(current!=null&&"home".equals(current.kind)){
            if(primarySelection!=null&&primarySelection.getSelection()!=index)primarySelection.setSelection(index,true);
            cancelHomeNavigation();
            if(mainTab==index)return;
            pendingHomeTab=index;
            if(store.motionEnabled()&&android.animation.ValueAnimator.areAnimatorsEnabled())navigationHandler.postDelayed(homeNavigationTask,HOME_NAVIGATION_QUIET_MS);
            else homeNavigationTask.run();
            return;
        }
        mainTab=index;back.clear();go(new Screen("home","","NGA",1),false);
    }
    private GlassPanel dockMaterial(int radius){
        GlassPanel panel=new GlassPanel(this);panel.setNightMode(dark);panel.setSurfaceColor(surface);panel.setCornerRadiusDp(radius);panel.setMotionEnabled(store.motionEnabled());panel.setGlassEnabled(store.glassEnabled());panel.setLensingEnabled(true);panel.setElevation(dp(5));return panel;
    }
    private LiquidSelection dockSelection(String[] labels,int selected,boolean icons,LiquidSelection.OnSelected callback,GlassPanel panel){
        LiquidSelection selection=new LiquidSelection(this);selection.setNightMode(dark);selection.setTintColor(gold);selection.setMotionEnabled(store.motionEnabled());selection.setItems(labels,selected,callback);
        if(icons)selection.setIcons(new String[]{"home","community","star","user"});
        selection.setOnInteractionListener(panel::setInteractiveLight);selection.setOnShapeListener(panel::setLensMotion);panel.addView(selection,new FrameLayout.LayoutParams(-1,dp(selection.preferredHeightDp())));return selection;
    }
    private String homeGroupName(){return new String[]{"收藏","推荐","热帖","活动"}[homeTab];}
    private void updateHomeNavigationLabel(){
        if(primarySelection==null)return;
        primarySelection.setItemLabel(0,(mainTab==0?homeGroupName():"首页")+" ▾");
        primarySelection.setItemDescription(0,mainTab==0?"首页，"+homeGroupName()+"，点按切换分组":"首页");
    }
    /** Centers controls using the measured window, including split-screen and fold cover displays. */
    private FrameLayout cappedControls(int capDp,int horizontalPadding,int verticalPadding){
        FrameLayout frame=new FrameLayout(this){@Override protected void onMeasure(int widthSpec,int heightSpec){
            int available=Math.max(1,View.MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight());
            if(getChildCount()>0)getChildAt(0).getLayoutParams().width=Math.min(dp(capDp),available);
            super.onMeasure(widthSpec,heightSpec);
        }};
        frame.setPadding(dp(horizontalPadding),dp(verticalPadding),dp(horizontalPadding),dp(verticalPadding));frame.setClipChildren(false);frame.setClipToPadding(false);return frame;
    }
    private View bottomNavigation(){
        FrameLayout footer=cappedControls(480,12,8);
        navigationGlass=dockMaterial(32);navigationGlass.setTag("bottom-navigation");primarySelection=dockSelection(new String[]{"首页 ▾","版块","评分","我的"},mainTab,true,this::navigateHome,navigationGlass);
        primarySelection.setOnReselectedListener(index->{if(index==0)showHomeGroups();});updateHomeNavigationLabel();
        footer.addView(navigationGlass,new FrameLayout.LayoutParams(-1,dp(primarySelection.preferredHeightDp()),Gravity.CENTER_HORIZONTAL));footer.post(this::ensureGlassMaterial);return footer;
    }
    private void dismissHomeGroups(){
        PopupWindow popup=homeGroupPopup;homeGroupPopup=null;if(popup!=null)popup.dismiss();
    }
    private void showHomeGroups(){
        if(pendingHomeTab==0){navigationHandler.removeCallbacks(homeNavigationTask);homeNavigationTask.run();}
        if(current==null||!"home".equals(current.kind)||mainTab!=0||navigationGlass==null||!navigationGlass.isShown())return;
        if(homeGroupPopup!=null&&homeGroupPopup.isShowing()){dismissHomeGroups();return;}
        GlassPanel material=new GlassPanel(this);material.setNightMode(dark);material.setSurfaceColor(surface);material.setCornerRadiusDp(24);material.setMotionEnabled(store.motionEnabled());material.setTintOpacity(dark?.82f:.84f);material.setGlassEnabled(store.glassEnabled());material.setBlurSource(root);
        LinearLayout list=vertical();list.setPadding(dp(8),dp(8),dp(8),dp(8));TextView heading=text("首页内容",12,muted);heading.setPadding(dp(12),0,0,0);list.addView(heading,new LinearLayout.LayoutParams(-1,dp(32)));
        String[] names={"收藏","推荐","热帖","活动"};String[] glyphs={"heart","home","bell","calendar"};
        for(int i=0;i<names.length;i++){
            final int index=i;LinearLayout row=horizontal();row.setPadding(dp(12),0,dp(12),0);row.setContentDescription(names[i]);row.setSelected(i==homeTab);row.setFocusable(true);row.setBackground(i==homeTab?shape(palette.selectionFill,16):null);
            IconView glyph=decoration(glyphs[i],i==homeTab?gold:muted);row.addView(glyph,new LinearLayout.LayoutParams(dp(22),dp(22)));TextView title=text(names[i],16,i==homeTab?gold:ink);title.setPadding(dp(12),0,0,0);title.setTypeface(null,i==homeTab?1:0);row.addView(title,new LinearLayout.LayoutParams(0,-1,1));
            if(i==homeTab){IconView check=decoration("check",gold);row.addView(check,new LinearLayout.LayoutParams(dp(20),dp(20)));}
            row.setOnClickListener(v->selectHomeGroup(index));list.addView(row,new LinearLayout.LayoutParams(-1,dp(48)));
        }
        ScrollView choices=new ScrollView(this);choices.setVerticalScrollBarEnabled(false);choices.addView(list);material.addView(choices,new FrameLayout.LayoutParams(-1,-1));
        int width=Math.min(dp(244),root.getWidth()-dp(24));list.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));int desiredHeight=list.getMeasuredHeight();
        int[] anchor=new int[2],rootScreen=new int[2],rootWindow=new int[2];navigationGlass.getLocationInWindow(anchor);root.getLocationOnScreen(rootScreen);root.getLocationInWindow(rootWindow);Rect visible=visibleWindow();int originX=rootScreen[0]-rootWindow[0],originY=rootScreen[1]-rootWindow[1];int safeTop=visible.top-originY+dp(8),safeLeft=visible.left-originX+dp(12),safeRight=visible.right-originX-dp(12);int height=Math.min(desiredHeight,Math.max(dp(48),anchor[1]-safeTop-dp(8)));int x=Math.max(safeLeft,Math.min(anchor[0],safeRight-width)),y=Math.max(safeTop,anchor[1]-height-dp(8));
        PopupWindow popup=new PopupWindow(material,width,height,true);popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));popup.setOutsideTouchable(true);popup.setElevation(dp(12));popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);popup.setAnimationStyle(0);homeGroupPopup=popup;popup.setOnDismissListener(()->{material.animate().cancel();if(homeGroupPopup==popup)homeGroupPopup=null;});
        popup.showAtLocation(root,Gravity.TOP|Gravity.LEFT,x,y);
        material.post(()->{if(!popup.isShowing())return;material.refreshBackdrop();if(store.motionEnabled()&&android.animation.ValueAnimator.areAnimatorsEnabled()){material.setPivotX(dp(42));material.setPivotY(height);material.setScaleX(.94f);material.setScaleY(.88f);material.setAlpha(0f);material.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(210).setInterpolator(new android.view.animation.PathInterpolator(.2f,.8f,.2f,1f)).withEndAction(()->{if(popup.isShowing())material.refreshBackdrop();}).start();}});
    }
    private void home(){
        if(mainTab==1){boardCatalogScreen();return;}if(mainTab==2){ratings();return;}if(mainTab==3){profile();return;}
        if(homeTab==3){activitiesView=new NativeActivitiesView(this,store,this::handleLink);activitiesView.setBottomInset(dp(homeBottomSpace()));body.addView(activitiesView,new FrameLayout.LayoutParams(-1,-1));currentScroll=activitiesView.scrollContainer();currentScrollKey=screenKey();currentScroll.setTag(currentScrollKey);Integer saved=readingPositions.get(currentScrollKey);if(saved!=null)activitiesView.restoreScrollPosition(saved);return;}if(homeTab==2){hotTopics();return;}
        LinearLayout list=scrollBody();
        favoriteBoardsEntry(list);
        if(homeTab==0){
            section(list,"收藏版块","你的常逛社区");LinearLayout saved=vertical();list.addView(saved);final int owner=generation;
            favoritePageUpdate=()->{if(owner!=generation)return;saved.removeAllViews();List<String[]> favorites=favoriteBoardRows();for(String[] forum:favorites)boardRow(saved,forum,()->{if(favoritePageUpdate!=null)favoritePageUpdate.run();});if(favorites.isEmpty())empty(saved,"把喜欢的社区放在这里","登录后会自动拉回云端收藏，也可在版块页点亮星标。","打开收藏版块",this::openFavoriteBoards);};
            favoritePageUpdate.run();return;
        }
        section(list,"为你推荐","社区精选");LinearLayout feed=vertical();list.addView(feed);List<NgaApi.Topic> cached=homeTopicsSnapshot();if(cached!=null){recommendationGrid(feed,cached);restoreReadingPosition(list);ensureGlassMaterial();return;}
        TextView loading=text("正在加载社区内容…",14,muted);loading.setPadding(dp(20),dp(30),dp(20),dp(30));feed.addView(loading);final int token=generation;
        pageReads.execute(()->{try{List<NgaApi.Topic> topics=homeTopics();runOnUiThread(()->{if(token!=generation)return;feed.removeAllViews();recommendationGrid(feed,topics);restoreReadingPosition(list);ensureGlassMaterial();});}catch(Exception e){runOnUiThread(()->{if(token!=generation)return;feed.removeAllViews();networkError(feed,e,"https://www.nga.cn/");ensureGlassMaterial();});}});
    }
    private void section(LinearLayout parent,String name,String hint){
        LinearLayout row=horizontal();row.setPadding(dp(20),dp(12),dp(16),dp(8));LinearLayout copy=vertical();TextView title=text(name,20,ink);title.setTypeface(null,1);copy.addView(title);if(!empty(hint)){TextView subtitle=text(hint,11,muted);subtitle.setPadding(0,dp(5),0,0);copy.addView(subtitle);}row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        if(current!=null&&"home".equals(current.kind)&&mainTab==0&&homeTab!=0)row.addView(icon("refresh","刷新内容",muted,()->refreshCurrent()),new LinearLayout.LayoutParams(dp(48),dp(48)));parent.addView(row);
    }
    private void recommendationGrid(LinearLayout target,List<NgaApi.Topic> topics){
        for(int i=0;i<topics.size();i++){
            NgaApi.Topic topic=topics.get(i);if(userFilter!=null&&userFilter.blocks(topic.authorId,topic.author,topic.title))continue;String forum=empty(topic.forum)?findForum(topic.fid)[1]:topic.forum;
            if(i==0){LinearLayout feature=vertical();feature.setBackground(shape(surface,18));feature.setClipToOutline(true);ImageView cover=new ImageView(this);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);cover.setBackgroundColor(line);cover.setContentDescription(clean(topic.title));feature.addView(cover,new LinearLayout.LayoutParams(-1,dp(158)));loadThumbnail(cover,topic.image,generation);
                LinearLayout caption=vertical();caption.setPadding(dp(16),dp(14),dp(16),dp(16));TextView tag=text(forum,11,gold);caption.addView(tag);TextView title=text(clean(topic.title),18,ink);title.setTypeface(null,1);title.setMaxLines(3);title.setEllipsize(TextUtils.TruncateAt.END);title.setLineSpacing(dp(3),1);title.setPadding(0,dp(8),0,dp(10));caption.addView(title);caption.addView(text(topic.replies+" 条讨论",11,muted));feature.addView(caption);feature.setOnClickListener(v->openThread(topic));LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,-2);fp.setMargins(dp(16),dp(4),dp(16),dp(14));target.addView(feature,fp);
            }else{
                LinearLayout card=horizontal();card.setGravity(Gravity.TOP);card.setPadding(dp(16),dp(15),dp(16),dp(15));card.setBackground(shape(surface,16));LinearLayout copy=vertical();TextView tag=text(forum,11,gold);copy.addView(tag);TextView title=text(clean(topic.title),16,ink);title.setMaxLines(3);title.setEllipsize(TextUtils.TruncateAt.END);title.setLineSpacing(dp(3),1);title.setPadding(0,dp(7),0,dp(10));copy.addView(title);copy.addView(text(topic.replies+" 条讨论",11,muted));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);cp.rightMargin=dp(14);card.addView(copy,cp);ImageView cover=new ImageView(this);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);cover.setBackground(shape(line,12));cover.setClipToOutline(true);card.addView(cover,new LinearLayout.LayoutParams(dp(88),dp(84)));loadThumbnail(cover,topic.image,generation);card.setOnClickListener(v->openThread(topic));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.setMargins(dp(16),0,dp(16),dp(12));target.addView(card,rp);
            }
        }
        if(topics.isEmpty())empty(target,"暂时没有推荐内容","稍后刷新再看看。","刷新",()->{refreshHomeTopics();go(current,false);});
    }
    private void loadThumbnail(ImageView view,String address,int token){
        if(empty(address)||!isAttachment(Uri.parse(address)))return;
        // Download only after a cover becomes visible. Detached pages release pending callbacks.
        class Thumbnail implements View.OnAttachStateChangeListener,ViewTreeObserver.OnScrollChangedListener,ViewTreeObserver.OnWindowFocusChangeListener,View.OnLayoutChangeListener{
            NativeImages.Request request;boolean started,closed;ViewTreeObserver observer;
            void watch(){observer=view.getViewTreeObserver();observer.addOnScrollChangedListener(this);observer.addOnWindowFocusChangeListener(this);view.addOnLayoutChangeListener(this);view.post(this::check);}
            void unwatch(){if(observer!=null&&observer.isAlive()){observer.removeOnScrollChangedListener(this);observer.removeOnWindowFocusChangeListener(this);}view.removeOnLayoutChangeListener(this);}
            void close(){closed=true;if(request!=null)request.cancel();request=null;unwatch();view.removeOnAttachStateChangeListener(this);}
            void check(){
                if(closed||started||token!=generation||!view.isAttachedToWindow()||view.getWindowVisibility()!=View.VISIBLE||!view.isShown())return;
                if(!view.getGlobalVisibleRect(new Rect()))return;started=true;unwatch();
                request=nativeImages.load(address,false,new NativeImages.Callback(){
                    public void loaded(Bitmap bitmap){if(!closed&&token==generation&&view.isAttachedToWindow()){view.setImageBitmap(bitmap);ensureGlassMaterial();}close();}
                    public void failed(String message){close();}
                });
            }
            public void onViewAttachedToWindow(View v){watch();}
            public void onViewDetachedFromWindow(View v){close();}
            public void onScrollChanged(){check();}
            public void onWindowFocusChanged(boolean focused){if(focused)check();}
            public void onLayoutChange(View v,int l,int t,int r,int b,int ol,int ot,int or,int ob){check();}
        }
        Thumbnail binding=new Thumbnail();view.addOnAttachStateChangeListener(binding);if(view.isAttachedToWindow())binding.watch();
    }
    private String[] findForum(String fid) {for(String[] f:catalog)if(f[0].equals(fid))return f;for(String[] f:FORUMS)if(f[0].equals(fid))return f;String name=store.favoriteForumName(fid);String image=store.favoriteForumIcon(fid);if(!image.isEmpty())forumIcons.put(fid,image);return new String[]{fid,name.isEmpty()?(fid.startsWith("stid:")?"合集 "+fid.substring(5):"版块 "+fid):name,"其他","N"};}
    private List<String[]> favoriteBoardRows(){return favoriteBoardRows(store.favoriteForums());}
    private List<String[]> favoriteBoardRows(Set<String> favorites){
        ArrayList<String[]> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(String[] row:catalog)if(favorites.contains(row[0])&&ForumRoute.isKey(row[0])&&seen.add(row[0]))result.add(row);
        ArrayList<String> missing=new ArrayList<>(favorites);java.util.Collections.sort(missing);
        for(String key:missing)if(ForumRoute.isKey(key)&&seen.add(key))result.add(findForum(key));return result;
    }
    private String forumIcon(String key){String image=forumIcons.get(key);if(empty(image))image=store.favoriteForumIcon(key);if(empty(image)&&ForumRoute.isKey(key))image=ForumRoute.fromKey(key).iconUrl();if(!empty(image))forumIcons.put(key,image);return image;}
    private String favoriteScope(){return ForumFavoritesCoordinator.scope(this);}
    private void clearFavoritePageBindings(){
        favoritePageUpdate=null;directoryFavoriteRenderUpdate=null;favoriteCloudStatus=null;favoriteCloudAction=null;favoriteBindings.clear();favoriteCounts.clear();
        forumFavoriteSnapshot=null;forumFavoriteRefreshRunning=false;forumFavoriteRefreshDirty=false;
    }
    private void refreshFavoriteBindings(){
        if(favoriteCoordinator==null||isDestroyed())return;
        if(current!=null&&"forum".equals(current.kind)){requestForumFavoriteRefresh();return;}
        FavoriteRenderSnapshot snapshot=captureFavoriteRenderSnapshot();
        if(!snapshot.contentScope.equals(activeContentScope)){
            navigationHandler.removeCallbacks(sessionContentTask);navigationHandler.post(sessionContentTask);return;
        }
        FavoriteRenderSnapshot previous=favoriteRenderPass;favoriteRenderPass=snapshot;
        try{
            favoriteSyncRunning=favoriteCoordinator.running();
            if(directoryFavoriteRenderUpdate!=null)directoryFavoriteRenderUpdate.accept(snapshot);
            for(TextView count:new ArrayList<>(favoriteCounts.keySet()))if(count!=null)count.setText(snapshot.favorites.size()+" 个");
            for(Map.Entry<TextView,FavoriteBinding> entry:new ArrayList<>(favoriteBindings.entrySet())){
                TextView star=entry.getKey();FavoriteBinding binding=entry.getValue();
                if(star!=null&&binding.owner==generation&&binding.scope.equals(snapshot.contentScope))paintForumFavorite(star,binding.forum,snapshot);
            }
            if(favoriteCloudStatus!=null){
                ForumFavoritesCoordinator.Snapshot state=favoriteCoordinator.status(snapshot.accountScope);
                favoriteCloudStatus.setText(favoriteSyncMessage(state,snapshot.accountScope));
                if(favoriteCloudAction!=null){favoriteCloudAction.setEnabled(!state.running);favoriteCloudAction.setAlpha(state.running?.45f:1f);}
            }
            StringBuilder projection=new StringBuilder(snapshot.contentScope).append('\n');
            Map<String,NgaStore.FavoriteForumMetadata> metadata=store.favoriteForumMetadataSnapshot();
            for(String key:new TreeSet<>(snapshot.favorites)){NgaStore.FavoriteForumMetadata entry=metadata.get(key);projection.append(key).append('\0').append(entry==null?"":entry.name).append('\0').append(entry==null?"":entry.icon).append('\n');}
            String signature=projection.toString();
            if(!signature.equals(favoriteProjectionSignature)){
                favoriteProjectionSignature=signature;Runnable update=favoritePageUpdate;if(update!=null){favoritePageRefreshRevision++;update.run();}
            }
        }finally{favoriteRenderPass=previous;}
    }
    /** Coalesce display-only snapshots; durable favorite mutations retain their original ordering. */
    private void requestForumFavoriteRefresh(){
        if(current==null||!"forum".equals(current.kind)||isFinishing()||isDestroyed())return;
        if(forumFavoriteRefreshRunning){forumFavoriteRefreshDirty=true;return;}
        final Screen owner=current;final int token=generation;final String scope=activeContentScope;
        forumFavoriteRefreshRunning=true;forumFavoriteRefreshDirty=false;
        boolean accepted=pageReads.execute(()->{
            try{
                long before=store.favoriteForumRevision();FavoriteRenderSnapshot snapshot=captureFavoriteRenderSnapshot();long after=store.favoriteForumRevision();
                RequestCancellation.check();
                runOnUiThread(()->{
                    if(token!=generation||current!=owner||isFinishing()||isDestroyed())return;
                    forumFavoriteRefreshRunning=false;
                    if(!scope.equals(contentScope())||!scope.equals(snapshot.contentScope))return;
                    boolean unchanged=before==after&&after==store.favoriteForumRevision();
                    if(unchanged){
                        forumFavoriteSnapshot=snapshot;
                        for(Map.Entry<TextView,FavoriteBinding> entry:new ArrayList<>(favoriteBindings.entrySet())){
                            TextView star=entry.getKey();FavoriteBinding binding=entry.getValue();
                            if(star!=null&&binding.owner==token&&scope.equals(binding.scope))paintForumFavorite(star,binding.forum,snapshot);
                        }
                    }
                    if(forumFavoriteRefreshDirty||!unchanged)requestForumFavoriteRefresh();
                });
            }catch(Exception unavailable){runOnUiThread(()->{
                if(token!=generation||current!=owner||isFinishing()||isDestroyed())return;
                forumFavoriteRefreshRunning=false;
                if(!scope.equals(contentScope()))return;
                if(forumFavoriteSnapshot==null)for(TextView star:new ArrayList<>(favoriteBindings.keySet()))if(star!=null){star.setEnabled(true);star.setContentDescription("收藏状态暂不可用，点按重试");}
            });}
        });
        if(!accepted){forumFavoriteRefreshRunning=false;navigationHandler.postDelayed(()->{if(token==generation&&current==owner)requestForumFavoriteRefresh();},120);}
    }
    private String favoriteSyncMessage(ForumFavoritesCoordinator.Snapshot state,String accountScope){
        if(accountScope.isEmpty())return "登录 NGA 后自动同步收藏";
        if(state.running)return state.pending>0?"正在自动同步 "+state.pending+" 项更改…":"正在自动更新云端收藏…";
        String failure=state.failure.name();
        if("LOGIN".equals(failure))return "登录已失效，重新登录后继续同步";
        if("CHALLENGE".equals(failure))return "需完成安全验证，收藏更改已保留";
        if("PERMISSION".equals(failure)||"RATE_LIMIT".equals(failure))return "云端暂未接受操作，收藏更改已保留";
        if(state.pending>0)return state.pending+" 项待同步，联网后自动重试";
        if(!"NONE".equals(failure))return "暂时无法更新，已保留本机收藏";
        return state.loaded?"云端 "+state.cloudCount+" 个收藏 · 已自动同步":"打开应用后自动读取云端收藏";
    }
    private void paintForumFavorite(TextView star,String[] f){paintForumFavorite(star,f,favoriteSnapshotForRender());}
    private void paintForumFavorite(TextView star,String[] f,FavoriteRenderSnapshot snapshot){
        if(snapshot==null){if(!"☆".contentEquals(star.getText()))star.setText("☆");star.setSelected(false);star.setEnabled(false);star.setAlpha(.45f);star.setContentDescription("正在读取收藏状态 "+f[1]);return;}
        star.setEnabled(true);
        boolean selected=snapshot.favorites.contains(f[0]),pending=snapshot.pending.contains(f[0]),busy=snapshot.busy.contains(f[0]);
        String state=pending?(busy?"正在同步":"待同步"):"";
        SpannableString label=new SpannableString((selected?"★":"☆")+(pending?"\n"+state:""));
        if(pending)label.setSpan(new android.text.style.RelativeSizeSpan(.4f),2,label.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if(!TextUtils.equals(star.getText(),label))star.setText(label);star.setSelected(selected);star.setAlpha(pending?.8f:1f);
        star.setContentDescription((selected?"取消收藏 ":"收藏 ")+f[1]+(pending?"，"+state+"，长按重试同步":""));
    }
    private void bindForumFavorite(TextView star,String[] f,Runnable update){bindForumFavorite(star,f,update,favoriteSnapshotForRender());}
    private void bindForumFavorite(TextView star,String[] f,Runnable update,FavoriteRenderSnapshot snapshot){
        final int owner=generation;final String scope=snapshot==null?activeContentScope:snapshot.contentScope;
        star.setTag("forum-favorite:"+f[0]);star.setGravity(Gravity.CENTER);star.setTextSize(23);star.setTextColor(gold);
        star.setMinWidth(dp(48));star.setMinHeight(dp(48));
        favoriteBindings.put(star,new FavoriteBinding(f,owner,scope));paintForumFavorite(star,f,snapshot);
        star.setOnClickListener(v->{if(owner!=generation||!scope.equals(contentScope()))return;if(current!=null&&"forum".equals(current.kind)&&forumFavoriteSnapshot==null){requestForumFavoriteRefresh();return;}long refreshed=favoritePageRefreshRevision;setForumFavorite(f,!store.favoriteForums().contains(f[0]));if(update!=null&&refreshed==favoritePageRefreshRevision)update.run();});
        star.setOnLongClickListener(v->{if(owner!=generation||!scope.equals(contentScope()))return true;if(!store.loggedIn()){login();return true;}favoriteCoordinator.schedule();refreshFavoriteBindings();toast("正在核对并同步待处理收藏");return true;});
    }
    private void setForumFavorite(String[] f,boolean desired){
        String scope=favoriteScope();if(scope.isEmpty()){toast("登录 NGA 后可同步版块收藏");login();return;}
        try{
            store.activateFavoriteScope(scope);store.queueFavorite(scope,f[0],f[1],forumIcon(f[0]),desired);
        }catch(IllegalStateException|IllegalArgumentException unavailable){toast("收藏未能保存，请稍后重试");return;}
        refreshFavoriteBindings();favoriteCoordinator.schedule();refreshFavoriteBindings();
    }
    private void syncCloudBoards(TextView status,View action,Runnable update,int owner){
        if(!store.loggedIn()){status.setText("登录 NGA 后可自动同步账号收藏。");login();return;}
        favoriteCoordinator.requestRefresh(true);refreshFavoriteBindings();
    }
    private void hotTopics(){
        final int token=generation;final LinearLayout list=scrollBody();favoriteBoardsEntry(list);section(list,"正在热议","推荐内容按讨论数量排序");LinearLayout feed=vertical();list.addView(feed);
        java.util.function.Consumer<List<NgaApi.Topic>> render=topics->{if(token!=generation)return;feed.removeAllViews();ArrayList<NgaApi.Topic> visible=new ArrayList<>();for(NgaApi.Topic t:topics)visible.add(t);java.util.Collections.sort(visible,(a,b)->Integer.compare(b.replies,a.replies));recommendationGrid(feed,visible);restoreReadingPosition(list);ensureGlassMaterial();};
        List<NgaApi.Topic> cached=homeTopicsSnapshot();if(cached!=null){render.accept(cached);return;}TextView loading=text("正在加载社区内容…",14,muted);loading.setPadding(dp(20),dp(30),dp(20),dp(30));feed.addView(loading);
        pageReads.execute(()->{try{List<NgaApi.Topic> topics=homeTopics();runOnUiThread(()->render.accept(topics));}catch(Exception e){runOnUiThread(()->{if(token!=generation)return;feed.removeAllViews();networkError(feed,e,"https://www.nga.cn/");ensureGlassMaterial();});}});
    }
    private void openFavoriteBoards(){
        resetDirectoryPosition();communityCategory="收藏版块";communityQuery="";
        if(current!=null&&"home".equals(current.kind)&&mainTab==1)replaceHomeContent(true);else navigateHome(1);
    }
    private void favoriteBoardsEntry(LinearLayout parent){
        LinearLayout row=horizontal();row.setPadding(dp(18),dp(8),dp(14),dp(8));row.setBackground(shape(surface,14));
        IconView mark=decoration("star",gold);row.addView(mark,new LinearLayout.LayoutParams(dp(22),dp(22)));
        TextView title=text("收藏版块",15,ink);title.setTypeface(null,1);title.setPadding(dp(10),0,dp(8),0);title.setMinHeight(dp(40));row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView count=text(store.favoriteForums().size()+" 个",12,muted);count.setTag("forum-favorite-count");favoriteCounts.put(count,Boolean.TRUE);row.addView(count);IconView arrow=decoration("arrowRight",muted);row.addView(arrow,new LinearLayout.LayoutParams(dp(24),dp(24)));
        row.setMinimumHeight(dp(48));row.setFocusable(true);row.setContentDescription("打开收藏版块");row.setOnClickListener(v->openFavoriteBoards());LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(dp(16),dp(8),dp(16),dp(10));parent.addView(row,params);
    }
    private List<String[]> recentBoardRows(){return recentBoardRows(localDataScope());}
    private List<String[]> recentBoardRows(String localScope){
        ArrayList<String[]> result=new ArrayList<>();
        for(BoardVisits.Entry entry:boardVisits.get(localScope)){
            String[] known=findForum(entry.key);String[] row={entry.key,entry.name,known[2],known[3],entry.parentFid,known.length>5?known[5]:entry.name,known.length>6?known[6]:known[2]};
            if(!entry.icon.isEmpty())forumIcons.put(entry.key,entry.icon);result.add(row);
        }
        return result;
    }
    private List<String[]> allDirectoryRows(){return allDirectoryRows(favoriteSnapshotForRender());}
    private List<String[]> allDirectoryRows(FavoriteRenderSnapshot snapshot){
        ArrayList<String[]> result=new ArrayList<>(catalog);result.addAll(favoriteBoardRows(snapshot.favorites));
        try{result.addAll(recentBoardRows(snapshot.localScope));}catch(IllegalStateException unavailable){/* Keep the public directory usable when local history cannot be read. */}
        return result;
    }
    private void hideDirectoryKeyboard(){android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null&&communitySearch!=null)keyboard.hideSoftInputFromWindow(communitySearch.getWindowToken(),0);if(communitySearch!=null)communitySearch.clearFocus();}
    private void chooseDirectoryCategory(java.util.function.Consumer<String> choose){
        final int owner=generation;final String scope=contentScope();LinkedHashMap<String,Integer> counts=new LinkedHashMap<>();
        List<String[]> entries=BoardDirectory.filter(allDirectoryRows(),"","",Collections.emptySet());
        for(String[] entry:entries){Set<String> names=new LinkedHashSet<>();names.add(entry[2]);if(entry.length>6)Collections.addAll(names,entry[6].split("\\n"));for(String name:names)if(!name.isEmpty())counts.put(name,counts.getOrDefault(name,0)+1);}
        ArrayList<String> keys=new ArrayList<>();keys.add("全部版块");keys.addAll(counts.keySet());String[] labels=new String[keys.size()];labels[0]="全部分类 · "+entries.size();for(int i=1;i<labels.length;i++)labels[i]=keys.get(i)+" · "+counts.get(keys.get(i));
        alert().setTitle("选择版块分类").setItems(labels,(d,n)->{if(owner==generation&&scope.equals(contentScope()))choose.accept(keys.get(n));}).setNegativeButton("取消",null).show();
    }
    private void chooseDirectoryLayout(Runnable relayout){
        final int owner=generation;final String scope=contentScope();
        alert().setTitle("版块排列").setItems(new String[]{communityGrid?"紧凑网格 · 当前":"紧凑网格",communityGrid?"清晰列表":"清晰列表 · 当前"},(d,n)->{
            if(owner!=generation||!scope.equals(contentScope()))return;boolean next=n==0;if(communityGrid==next)return;
            captureCatalogPosition();cancelCatalogRestore();communityGrid=next;getSharedPreferences("nga_board_directory_ui",MODE_PRIVATE).edit().putBoolean("grid",next).apply();relayout.run();
        }).setNegativeButton("取消",null).show();
    }
    private void directoryMore(Runnable refresh){
        final int owner=generation;final String scope=contentScope();
        alert().setTitle("版块目录").setItems(new String[]{"按 ID 或链接打开","清空最近访问","收藏同步状态"},(d,n)->{
            if(owner!=generation||!scope.equals(contentScope()))return;if(n==0){customForum();return;}
            if(n==2){String account=favoriteScope();ForumFavoritesCoordinator.Snapshot state=favoriteCoordinator.status(account);
                alert().setTitle("云端收藏").setMessage(favoriteSyncMessage(state,account)).setNegativeButton("关闭",null)
                    .setPositiveButton("立即同步",(dialog,which)->{if(owner==generation&&scope.equals(contentScope())){if(!store.loggedIn()){login();return;}favoriteCoordinator.requestRefresh(true);refreshFavoriteBindings();toast("正在核对并同步收藏");}}).show();return;}
            final String localScope=localDataScope();alert().setTitle("清空最近访问？").setMessage("清除当前账号在这台设备上的版块访问记录，收藏和帖子阅读进度会保留。")
                .setNegativeButton("取消",null).setPositiveButton("清空",(dialog,which)->{if(owner!=generation||!scope.equals(contentScope()))return;try{synchronized(READING_WRITES_LOCK){boardWriteEpoch++;boardVisits.clear(localScope);}resetDirectoryPosition();refresh.run();if(communityList!=null)communityList.setSelection(0);}catch(IllegalStateException failed){toast("最近访问未能清空，请稍后重试");}}).show();
        }).setNegativeButton("取消",null).show();
    }
    /** One quiet action strip; narrow panes and large text retain full-size targets on two rows. */
    private final class DirectoryTools extends LinearLayout {
        final LinearLayout tabs,categoryBox,actions;final TextView category;final TextView[] choices;boolean compact;int categoryWidth;
        DirectoryTools(LinearLayout modes,TextView[] labels,LinearLayout box,TextView label,View layout,View more){super(MainActivity.this);tabs=modes;choices=labels;categoryBox=box;category=label;setTag("directory-compact-header");setGravity(Gravity.CENTER_VERTICAL);setPadding(dp(12),dp(4),dp(12),dp(4));actions=horizontal();actions.addView(categoryBox);actions.addView(layout,new LinearLayout.LayoutParams(dp(48),dp(48)));actions.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));addView(tabs);addView(actions);categoryWidth=dp(84);arrange(false);}
        private void arrange(boolean small){compact=small;setOrientation(small?VERTICAL:HORIZONTAL);tabs.setLayoutParams(new LinearLayout.LayoutParams(small?-1:0,dp(48),small?0:1));actions.setLayoutParams(new LinearLayout.LayoutParams(small?-1:categoryWidth+dp(96),dp(48)));categoryBox.setLayoutParams(new LinearLayout.LayoutParams(small?0:categoryWidth,dp(48),small?1:0));}
        @Override protected void onMeasure(int w,int h){int width=View.MeasureSpec.getSize(w)-getPaddingLeft()-getPaddingRight();int modes=0;for(TextView choice:choices)modes+=Math.max(dp(48),(int)Math.ceil(choice.getPaint().measureText(choice.getText().toString()))+dp(16));int nextWidth=Math.max(dp(84),(int)Math.ceil(category.getPaint().measureText("分类"))+dp(38));boolean small=width<modes+nextWidth+dp(96);if(small!=compact||nextWidth!=categoryWidth){categoryWidth=nextWidth;arrange(small);}super.onMeasure(w,h);}
    }
    private final class DirectoryTile extends LinearLayout {
        final AvatarView image;final TextView name,detail,star;final boolean compact;
        DirectoryTile(boolean compact){super(MainActivity.this);this.compact=compact;setOrientation(compact?VERTICAL:HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setPadding(dp(compact?8:12),dp(compact?6:8),dp(compact?8:4),dp(compact?2:8));setMinimumHeight(dp(compact?92:78));setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(palette.ripple),shape(surface,16),null));setFocusable(true);
            image=new AvatarView(MainActivity.this,compact?28:36,nativeImages);name=text("",compact?14:15,ink);name.setTypeface(null,1);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);name.setLineSpacing(dp(2),1);detail=text("",compact?10:11,muted);detail.setSingleLine();detail.setEllipsize(TextUtils.TruncateAt.END);star=text("☆",18,gold);
            if(compact){
                LinearLayout titleRow=horizontal();titleRow.setMinimumHeight(dp(36));titleRow.addView(image,new LinearLayout.LayoutParams(dp(28),dp(28)));LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(0,-2,1);titleParams.leftMargin=dp(8);titleRow.addView(name,titleParams);addView(titleRow,new LinearLayout.LayoutParams(-1,-2));
                LinearLayout detailRow=horizontal();detail.setPadding(0,0,dp(4),0);detailRow.addView(detail,new LinearLayout.LayoutParams(0,-2,1));detailRow.addView(star,new LinearLayout.LayoutParams(dp(48),-2));addView(detailRow,new LinearLayout.LayoutParams(-1,-2));
            }else{
                addView(image,new LinearLayout.LayoutParams(dp(36),dp(36)));LinearLayout words=vertical();words.setPadding(dp(10),0,dp(3),0);words.addView(name);detail.setPadding(0,dp(3),0,0);words.addView(detail);addView(words,new LinearLayout.LayoutParams(0,-2,1));addView(star,new LinearLayout.LayoutParams(dp(48),dp(56)));
            }
            if(store.motionEnabled())Motion.press(this);
        }
        void bind(String[] entry,Runnable update){bind(entry,update,favoriteSnapshotForRender());}
        void bind(String[] entry,Runnable update,FavoriteRenderSnapshot snapshot){String title=entry[1];final int owner=generation;final String scope=snapshot.contentScope;image.bind(forumIcon(entry[0]),title);name.setText(title);detail.setText((entry[0].startsWith("stid:")?"合集 · ":"")+entry[2]);setContentDescription("打开版块 "+title+(entry[0].startsWith("stid:")?"，主题合集":""));setTag("directory-board:"+entry[0]);setOnClickListener(v->{if(owner!=generation||!scope.equals(contentScope()))return;hideDirectoryKeyboard();openForum(entry);});bindForumFavorite(star,entry,update,snapshot);star.setTextSize(18);}
    }
    private final class DirectoryRowView extends LinearLayout {
        final boolean header;final int columns;final TextView heading;final ArrayList<DirectoryTile> tiles=new ArrayList<>();
        DirectoryRowView(boolean header,int columns){super(MainActivity.this);this.header=header;this.columns=columns;setOrientation(HORIZONTAL);setGravity(Gravity.TOP);setPadding(dp(12),dp(header?12:4),dp(12),dp(4));
            if(header){heading=text("",14,ink);heading.setTypeface(null,1);if(android.os.Build.VERSION.SDK_INT>=28)heading.setAccessibilityHeading(true);heading.setPadding(dp(3),dp(4),0,dp(3));addView(heading,new LinearLayout.LayoutParams(-1,-2));}
            else{heading=null;for(int i=0;i<columns;i++){DirectoryTile tile=new DirectoryTile(columns>1);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i>0)lp.leftMargin=dp(8);addView(tile,lp);tiles.add(tile);}}
        }
        void bind(BoardDirectory.Row row,Runnable update,FavoriteRenderSnapshot snapshot){if(header){heading.setText(row.category);return;}for(int i=0;i<tiles.size();i++){DirectoryTile tile=tiles.get(i);if(i<row.entries.size()){tile.setVisibility(View.VISIBLE);tile.bind(row.entries.get(i),update,snapshot);}else{tile.setVisibility(View.INVISIBLE);tile.setOnClickListener(null);tile.star.setOnClickListener(null);tile.star.setOnLongClickListener(null);favoriteBindings.remove(tile.star);}}}
    }
    private void boardCatalogScreen() {
        if(!catalogReady){ensureCatalogAsync();LinearLayout loading=vertical();loading.setGravity(Gravity.CENTER);loading.addView(new ProgressBar(this),new LinearLayout.LayoutParams(dp(28),dp(28)));TextView label=text("正在加载板块目录…",14,muted);label.setPadding(dp(16),dp(16),dp(16),0);loading.addView(label);body.addView(loading,new FrameLayout.LayoutParams(-1,-1));return;}
        LinearLayout outer=new LinearLayout(this){@Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);int gutter=Math.max(0,(w-dp(880))/2);setPadding(gutter,0,gutter,0);}};
        outer.setOrientation(LinearLayout.VERTICAL);body.addView(outer,new FrameLayout.LayoutParams(-1,-1));
        Set<String> categories=new LinkedHashSet<>();for(String[] entry:catalog){categories.add(entry[2]);if(entry.length>6)Collections.addAll(categories,entry[6].split("\\n"));}
        if("推荐收藏".equals(communityCategory))communityCategory="收藏版块";
        if(!"收藏版块".equals(communityCategory)&&!"最近访问".equals(communityCategory)&&!"全部版块".equals(communityCategory)&&!categories.contains(communityCategory))communityCategory="全部版块";
        LinearLayout tabs=horizontal();TextView favorites=text("收藏",14,ink),recent=text("最近",14,ink),all=text("全部",14,ink);TextView[] choices={favorites,recent,all};String[] modes={"收藏版块","最近访问","全部版块"};
        for(int i=0;i<choices.length;i++){TextView tab=choices[i];tab.setGravity(Gravity.CENTER);tab.setMinHeight(dp(48));tab.setSingleLine();tab.setContentDescription(modes[i]);tabs.addView(tab,new LinearLayout.LayoutParams(0,dp(48),1));}
        LinearLayout categoryBox=horizontal();categoryBox.setContentDescription("选择版块分类");categoryBox.setFocusable(true);categoryBox.setPadding(dp(12),0,dp(8),0);categoryBox.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(palette.ripple),null,shape(Color.WHITE,12)));TextView category=text("分类",14,gold);category.setGravity(Gravity.CENTER_VERTICAL);category.setSingleLine();category.setEllipsize(TextUtils.TruncateAt.END);categoryBox.addView(category,new LinearLayout.LayoutParams(0,-1,1));IconView chevron=decoration("arrowRight",gold);chevron.setRotation(90);categoryBox.addView(chevron,new LinearLayout.LayoutParams(dp(18),dp(18)));IconView layout=icon("community","排列方式",muted,()->{});IconView more=icon("more","目录更多操作",muted,()->{});outer.addView(new DirectoryTools(tabs,choices,categoryBox,category,layout,more));
        FrameLayout holder=new FrameLayout(this);outer.addView(holder,new LinearLayout.LayoutParams(-1,0,1));CatalogListView list=new CatalogListView(this);communityList=list;list.setDivider(null);list.setDividerHeight(0);list.setCacheColorHint(bg);list.setPadding(0,dp(2),0,dp(12+homeBottomSpace()));list.setClipToPadding(false);holder.addView(list,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout emptyPanel=vertical();emptyPanel.setGravity(Gravity.CENTER);emptyPanel.setPadding(dp(24),dp(24),dp(24),dp(24+homeBottomSpace()));TextView emptyTitle=text("",18,ink);emptyTitle.setGravity(Gravity.CENTER);emptyPanel.addView(emptyTitle);TextView emptyDetail=text("",13,muted);emptyDetail.setGravity(Gravity.CENTER);emptyDetail.setPadding(0,dp(12),0,dp(16));emptyPanel.addView(emptyDetail);ScrollView emptyScroll=new ScrollView(this);emptyScroll.setFillViewport(true);emptyScroll.addView(emptyPanel,new ScrollView.LayoutParams(-1,-2));holder.addView(emptyScroll,new FrameLayout.LayoutParams(-1,-1));list.setEmptyView(emptyScroll);
        final int owner=generation;final Runnable[] update={null};final ArrayList<BoardDirectory.Row> rows=new ArrayList<>();
        class DirectoryData {
            List<String[]> filtered=Collections.emptyList();FavoriteRenderSnapshot favorites;
            String identity="";boolean grouped,ready,packed;
            // ListView is GONE while its adapter is empty; its match-parent holder still has the actual pane width.
            int measuredWidth(){return holder.getWidth();}
            int measuredColumns(){return BoardDirectory.columns(measuredWidth()-dp(24),getResources().getDisplayMetrics().density,getResources().getConfiguration().fontScale,communityGrid);}
        }
        DirectoryData data=new DirectoryData();
        BaseAdapter adapter=new BaseAdapter(){public int getCount(){return rows.size();}public Object getItem(int position){return rows.get(position);}public long getItemId(int position){return position;}public int getViewTypeCount(){return 2;}public int getItemViewType(int position){return rows.get(position).isHeader()?0:1;}public boolean isEnabled(int position){return !rows.get(position).isHeader();}public View getView(int position,View recycled,ViewGroup parent){BoardDirectory.Row row=rows.get(position);DirectoryRowView view=recycled instanceof DirectoryRowView?(DirectoryRowView)recycled:null;if(view==null||view.header!=row.isHeader()||view.columns!=communityColumns)view=new DirectoryRowView(row.isHeader(),communityColumns);view.bind(row,update[0],data.favorites);return view;}};list.setAdapter(adapter);
        java.util.function.IntConsumer repack=columns->{
            if(owner!=generation||communityList!=list||!data.ready)return;
            rows.clear();rows.addAll(BoardDirectory.pack(data.filtered,columns,data.grouped));communityColumns=columns;
            communityRows=new ArrayList<>(rows);communityDataIdentity=data.identity;data.packed=true;adapter.notifyDataSetChanged();
        };
        update[0]=()->{
            if(owner!=generation||communityList!=list)return;String query=communityQuery.trim();boolean searching=!query.isEmpty(),saved="收藏版块".equals(communityCategory),visited="最近访问".equals(communityCategory);List<String[]> source;boolean localUnavailable=false;
            FavoriteRenderSnapshot snapshot=favoriteSnapshotForRender();
            if(!snapshot.contentScope.equals(activeContentScope)){navigationHandler.removeCallbacks(sessionContentTask);navigationHandler.post(sessionContentTask);return;}
            try{source=searching?allDirectoryRows(snapshot):saved?favoriteBoardRows(snapshot.favorites):visited?recentBoardRows(snapshot.localScope):allDirectoryRows(snapshot);}catch(IllegalStateException unavailable){source=Collections.emptyList();localUnavailable=true;}
            List<String[]> filtered=BoardDirectory.filter(source,query,searching||saved||visited?"":communityCategory,snapshot.favorites);
            data.filtered=filtered;data.favorites=snapshot;data.grouped=!searching&&!saved&&!visited&&"全部版块".equals(communityCategory);
            data.identity=snapshot.localScope+"\n"+communityCategory+"\n"+communityQuery;data.ready=true;data.packed=false;
            boolean[] chosen={saved&&!searching,visited&&!searching,searching||!saved&&!visited};for(int i=0;i<choices.length;i++){TextView tab=choices[i];tab.setSelected(chosen[i]);tab.setTextColor(chosen[i]?gold:muted);tab.setTypeface(null,chosen[i]?1:0);tab.setBackground(chosen[i]?shape(surface,14):null);}
            category.setText(searching||saved||visited||"全部版块".equals(communityCategory)?"分类":communityCategory);layout.setContentDescription("排列方式");View clear=mainToolbar.findViewWithTag("directory-search-clear");if(clear!=null)clear.setVisibility(communityQuery.isEmpty()?View.GONE:View.VISIBLE);
            emptyTitle.setText(localUnavailable?"最近访问暂时无法读取":searching?"没有找到版块或合集":saved?"还没有收藏版块":visited?"还没有最近访问":"这个分类暂无版块");emptyDetail.setText(localUnavailable?"稍后重试，现有记录会保留。":searching?"试试名称、别名或编号，也可以按 ID 直接打开。":saved?"在版块卡片上点亮星标，收藏会自动同步到云端。":visited?store.historyEnabled()?"成功打开版块后会出现在这里。":"已暂停记录，可在设置中开启浏览历史。":"选择其他分类，或搜索整个目录。");
            // A new list has no width yet. Bind once after layout, never build a throwaway one-column grid.
            if(data.measuredWidth()>dp(24))repack.accept(data.measuredColumns());
        };
        java.util.function.Consumer<String> choose=mode->{if(owner!=generation)return;hideDirectoryKeyboard();resetDirectoryPosition();communityCategory=mode;communityQuery="";if(communitySearch!=null)communitySearch.setText("");update[0].run();list.setSelection(0);};
        for(int i=0;i<choices.length;i++){String mode=modes[i];choices[i].setOnClickListener(v->choose.accept(mode));}
        categoryBox.setOnClickListener(v->{flushDirectoryFilter();hideDirectoryKeyboard();chooseDirectoryCategory(choose);});more.setOnClickListener(v->{flushDirectoryFilter();hideDirectoryKeyboard();directoryMore(update[0]);});layout.setOnClickListener(v->{flushDirectoryFilter();hideDirectoryKeyboard();chooseDirectoryLayout(()->{if(owner!=generation||communityList!=list)return;if(data.ready&&data.measuredWidth()>dp(24))repack.accept(data.measuredColumns());restoreCatalogPosition(list,directoryAnchorPosition(),communityOffset,owner);});});
        emptyPanel.addView(button("浏览全部版块",()->choose.accept("全部版块")));emptyPanel.addView(button("按 ID 或链接打开",this::customForum));
        if(communitySearch!=null){communitySearch.setText(communityQuery);communitySearch.setSelection(communitySearch.length());communitySearch.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE||action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH||(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER)){hideDirectoryKeyboard();return true;}return false;});
            Runnable filter=()->{directoryFilterPending=false;if(owner==generation&&communityList==list){update[0].run();list.setSelection(0);}};directoryFilterTask=filter;
            communitySearch.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(Editable value){}public void onTextChanged(CharSequence value,int a,int b,int c){if(owner!=generation||communityList!=list)return;String next=value.toString();if(next.equals(communityQuery))return;resetDirectoryPosition();communityQuery=next;View clear=mainToolbar.findViewWithTag("directory-search-clear");if(clear!=null)clear.setVisibility(communityQuery.isEmpty()?View.GONE:View.VISIBLE);navigationHandler.removeCallbacks(filter);directoryFilterPending=true;navigationHandler.postDelayed(filter,120);}});
            list.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){navigationHandler.removeCallbacks(filter);}});
        }
        list.getViewTreeObserver().addOnPreDrawListener(()->{
            if(owner!=generation||communityList!=list||!data.ready||data.measuredWidth()<=dp(24))return true;int columns=data.measuredColumns();
            if(data.packed&&columns==communityColumns)return true;
            if(data.packed)captureCatalogPosition();cancelCatalogRestore();
            // Width/font changes only rearrange the existing filtered snapshot; no storage or filtering here.
            repack.accept(columns);restoreCatalogPosition(list,directoryAnchorPosition(),communityOffset,owner);return false;
        });
        directoryFavoriteRenderUpdate=snapshot->{if(owner==generation&&communityList==list&&(data.favorites==null||data.favorites.contentScope.equals(snapshot.contentScope)))data.favorites=snapshot;};
        favoritePageUpdate=()->{if(owner!=generation||communityList!=list)return;if(flushDirectoryFilter())return;captureCatalogPosition();cancelCatalogRestore();update[0].run();if(data.packed)restoreCatalogPosition(list,directoryAnchorPosition(),communityOffset,owner);};
        refreshFavoriteBindings();
        if(!data.ready){update[0].run();if(data.packed)restoreCatalogPosition(list,directoryAnchorPosition(),communityOffset,owner);}
    }
    private void ratings(){
        LinearLayout outer=vertical();body.addView(outer,new FrameLayout.LayoutParams(-1,-1));LinearLayout tabs=horizontal();String[] ids={"2","4","8"}, names={"传统游戏","手机游戏","影视"};
        for(int i=0;i<ids.length;i++){String category=ids[i];TextView tab=text(names[i],15,category.equals(ratingCategory)?ink:muted);tab.setGravity(Gravity.CENTER);tab.setTypeface(null,category.equals(ratingCategory)?1:0);tab.setPadding(0,dp(14),0,dp(14));tab.setOnClickListener(v->{ratingCategory=category;ratingPage=1;go(current,false);});tabs.addView(tab,new LinearLayout.LayoutParams(0,-2,1));}outer.addView(tabs);separator(outer);
        FrameLayout target=new FrameLayout(this);outer.addView(target,new LinearLayout.LayoutParams(-1,0,1));body=target;ProgressBar loading=new ProgressBar(this);target.addView(loading,new FrameLayout.LayoutParams(dp(30),dp(30),Gravity.CENTER));final int token=generation;
        pageReads.execute(()->{try{NgaRatings.Page page=new NgaRatings(this).list(ratingCategory,ratingPage);runOnUiThread(()->{if(token!=generation)return;target.removeAllViews();body=target;LinearLayout list=scrollBody();
            for(NgaRatings.Item item:page.items){LinearLayout row=horizontal();row.setPadding(dp(16),dp(14),dp(16),dp(14));ImageView cover=new ImageView(this);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);cover.setBackground(shape(line,5));cover.setClipToOutline(true);row.addView(cover,new LinearLayout.LayoutParams(dp(62),dp(86)));loadThumbnail(cover,item.image,generation);LinearLayout details=vertical();details.setPadding(dp(13),0,dp(10),0);TextView title=text(item.name,16,ink);title.setMaxLines(2);title.setTypeface(null,1);details.addView(title);TextView tags=text(android.text.TextUtils.join(" / ",item.tags),11,muted);tags.setPadding(0,dp(7),0,dp(7));tags.setMaxLines(2);details.addView(tags);details.addView(text(item.scoreCount+" 人评分",11,muted));row.addView(details,new LinearLayout.LayoutParams(0,-2,1));TextView score=text(item.score,22,gold);score.setTypeface(null,1);row.addView(score);row.setOnClickListener(v->{NgaApi.Topic topic=new NgaApi.Topic();topic.tid=item.tid;topic.title=item.name;openThread(topic);});if(store.motionEnabled())Motion.press(row);list.addView(row);separator(list);}
            LinearLayout paging=horizontal();paging.setPadding(dp(12),dp(12),dp(12),0);if(ratingPage>1)paging.addView(button("上一页",()->{ratingPage--;go(current,false);}),new LinearLayout.LayoutParams(0,-2,1));paging.addView(text("第 "+ratingPage+" 页",12,muted),new LinearLayout.LayoutParams(0,-2,1));if(page.hasNext)paging.addView(button("下一页",()->{ratingPage++;go(current,false);}),new LinearLayout.LayoutParams(0,-2,1));list.addView(paging);ensureGlassMaterial();
        });}catch(Exception e){runOnUiThread(()->{if(token!=generation)return;target.removeAllViews();body=target;LinearLayout list=scrollBody();networkError(list,e,"https://game.nga.cn/");ensureGlassMaterial();});}});
    }
    private void boardCatalog(LinearLayout l,String filter) {
        EditText query=new EditText(this);query.setTextColor(ink);query.setHintTextColor(muted);query.setTextSize(14);query.setSingleLine();query.setHint("搜索版块名称 / 输入 fid");query.setPadding(dp(16),dp(8),dp(16),dp(8));query.setBackground(shape(surface,8));
        LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(-1,dp(46));qp.setMargins(dp(16),dp(10),dp(16),dp(4));l.addView(query,qp);
        LinearLayout list=vertical();l.addView(list);fillBoards(list,filter);
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){fillBoards(list,s.toString());}public void afterTextChanged(Editable e){}});
        l.addView(button("通过版块 ID 打开",()->customForum()));
    }
    private void fillBoards(LinearLayout list,String filter) {
        list.removeAllViews();String group="";String q=filter.toLowerCase(Locale.ROOT);int count=0;
        for(String[] f:FORUMS) if((f[0]+f[1]+f[2]+f[3]).toLowerCase(Locale.ROOT).contains(q)) {
            if(!group.equals(f[2])) {group=f[2];section(list,group,"");}boardRow(list,f);count++;
        }
        if(count==0)empty(list,"没有找到版块","可以输入版块 ID 直接打开，或换个关键词。","输入版块 ID",()->customForum());
    }
    private void boardRow(LinearLayout l,String[] f) {boardRow(l,f,null);}
    private void boardRow(LinearLayout l,String[] f,Runnable onFavoriteChanged) {
        LinearLayout row=horizontal();row.setPadding(dp(20),dp(11),dp(18),dp(11));
        AvatarView icon=new AvatarView(this,44,nativeImages);icon.bind(forumIcon(f[0]),f[1]);row.addView(icon,new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout labels=vertical();labels.setPadding(dp(10),0,dp(8),0);TextView name=text(f[1],16,ink);labels.addView(name);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);TextView sub=text(f[2],12,muted);sub.setSingleLine();sub.setEllipsize(TextUtils.TruncateAt.END);sub.setPadding(0,dp(4),0,0);labels.addView(sub);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        TextView star=text("☆",23,gold);row.addView(star,new LinearLayout.LayoutParams(dp(48),dp(56)));bindForumFavorite(star,f,onFavoriteChanged);
        row.setOnClickListener(v->openForum(f));l.addView(row);separator(l);
    }
    private void toggleForum(String[] f){setForumFavorite(f,!store.favoriteForums().contains(f[0]));}
    private void openForum(String[] f){
        try { ForumRoute route=ForumRoute.fromKey(f[0]);openForum(route.withParent(f.length>4?f[4]:""),f[1],1,true); }
        catch(IllegalArgumentException invalid){toast("版块或合集编号无法识别");}
    }
    private void openForum(ForumRoute route,String title,int page,boolean push){
        Screen next=new Screen("forum",route.key,title,page);next.extra=route.parentFid;go(next,push);
    }
    private void forumPage(Screen origin,int page){
        openForum(ForumRoute.fromKey(origin.id).withParent(origin.extra),origin.title,page,false);
    }
    private void subForumRow(LinearLayout parent,NgaApi.Forum forum){
        LinearLayout row=horizontal();row.setPadding(dp(10),dp(8),dp(6),dp(8));row.setMinimumHeight(dp(72));
        row.setBackground(shape(surface,18));row.setTag("subforum:"+forum.key);
        row.setContentDescription("打开"+forum.category+" "+forum.name);row.setFocusable(true);
        AvatarView avatar=new AvatarView(this,32,nativeImages);avatar.bind(forum.icon,forum.name);row.addView(avatar,new LinearLayout.LayoutParams(dp(32),dp(32)));
        LinearLayout words=vertical();words.setPadding(dp(8),0,0,0);
        TextView name=text(forum.name,14,ink);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);name.setTypeface(null,1);words.addView(name);
        TextView detail=text(forum.description.isEmpty()?forum.category:forum.description,10,muted);detail.setSingleLine();detail.setEllipsize(TextUtils.TruncateAt.END);detail.setPadding(0,dp(3),0,0);words.addView(detail);
        row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        String[] favorite={forum.key,forum.name,forum.category,"N"};if(!empty(forum.icon))forumIcons.put(forum.key,forum.icon);
        TextView star=text("☆",23,gold);row.addView(star,new LinearLayout.LayoutParams(dp(48),dp(56)));bindForumFavorite(star,favorite,null);
        row.setOnClickListener(v->openForum(ForumRoute.fromKey(forum.key).withParent(forum.parentFid),forum.name,1,true));
        if(store.motionEnabled())Motion.press(row);
        parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    private void forumSection(LinearLayout parent,String name,String hint){
        LinearLayout row=horizontal();row.setPadding(dp(20),dp(10),dp(20),dp(6));
        TextView label=text(name,13,muted);label.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        if(!empty(hint)){TextView count=text(hint,11,muted);count.setPadding(dp(12),0,0,0);row.addView(count);}
        parent.addView(row);
    }
    private void subForums(LinearLayout list,List<NgaApi.Forum> forums){
        if(forums.isEmpty())return;
        forumSection(list,"子版块与合集",String.valueOf(forums.size()));
        GridLayout grid=new GridLayout(this){@Override protected void onMeasure(int widthSpec,int heightSpec){
            int width=View.MeasureSpec.getSize(widthSpec);
            float fontScale=Math.max(1f,getResources().getConfiguration().fontScale);
            int columns=width-getPaddingLeft()-getPaddingRight()>=dp(352*fontScale)?2:1;
            boolean changed=getColumnCount()!=columns;
            // Existing children may still occupy the second column. Grow the grid before
            // assigning wider indices, but shrink only after every child has moved.
            if(columns>getColumnCount())setColumnCount(columns);
            int content=Math.max(1,width-getPaddingLeft()-getPaddingRight());
            for(int i=0;i<getChildCount();i++){
                GridLayout.LayoutParams params=(GridLayout.LayoutParams)getChildAt(i).getLayoutParams();
                int childWidth=Math.max(1,content/columns-dp(8));
                if(changed||params.width!=childWidth){params.columnSpec=GridLayout.spec(i%columns);params.rowSpec=GridLayout.spec(i/columns);params.width=childWidth;getChildAt(i).setLayoutParams(params);}
            }
            if(changed&&getColumnCount()!=columns)setColumnCount(columns);
            super.onMeasure(widthSpec,heightSpec);
        }};
        grid.setColumnCount(1);grid.setPadding(dp(12),0,dp(12),dp(8));
        final Screen owner=current;
        TextView toggle=button("展开全部 "+forums.size()+" 个",()->{});toggle.setContentDescription("展开全部子版块");
        Runnable render=()->{
            grid.removeAllViews();int count=owner.subForumsExpanded?forums.size():Math.min(2,forums.size());
            FavoriteRenderSnapshot previous=favoriteRenderPass;favoriteRenderPass=favoriteSnapshotForRender();
            try{for(int i=0;i<count;i++){
                LinearLayout cell=vertical();subForumRow(cell,forums.get(i));GridLayout.LayoutParams params=new GridLayout.LayoutParams();
                params.width=-1;params.height=-2;params.setMargins(dp(4),dp(4),dp(4),dp(4));grid.addView(cell,params);
            }}finally{favoriteRenderPass=previous;}
            toggle.setText(owner.subForumsExpanded?"收起子版块":"展开全部 "+forums.size()+" 个");toggle.setContentDescription(owner.subForumsExpanded?"收起子版块":"展开全部子版块");
        };
        render.run();list.addView(grid,new LinearLayout.LayoutParams(-1,-2));
        if(forums.size()>2){toggle.setOnClickListener(v->{if(current!=owner)return;owner.subForumsExpanded=!owner.subForumsExpanded;render.run();});LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(dp(16),0,dp(16),dp(8));list.addView(toggle,params);}
    }
    private static final class ForumReadResult {
        final NgaStore.CheckInSession session;final NgaApi.ForumPage page;final long loadedAt;
        final String source;final ForumResponseCache.Ticket cacheTicket;final Exception failure;
        ForumReadResult(NgaStore.CheckInSession session,NgaApi.ForumPage page,long loadedAt,String source,ForumResponseCache.Ticket ticket){
            this.session=session;this.page=page;this.loadedAt=loadedAt;this.source=source;cacheTicket=ticket;failure=null;
        }
        ForumReadResult(Exception failure){session=null;page=null;loadedAt=0;source="";cacheTicket=null;this.failure=failure;}
    }
    private static final class ForumReadLaunch {
        final Screen owner;final int token,pageNumber;final ForumRoute route;final String scope,host;
        final NgaApi.ForumPage backSnapshot;
        final ForumReadDelivery<ForumReadResult> delivery=new ForumReadDelivery<>();
        ForumReadLaunch(Screen owner,int token,ForumRoute route,String scope,String host,NgaApi.ForumPage snapshot){
            this.owner=owner;this.token=token;pageNumber=owner.page;this.route=route;this.scope=scope;this.host=host;backSnapshot=snapshot;
        }
    }
    /** Called after old reads/page ownership are closed, before any new controls are built. */
    private ForumReadLaunch beginForumRead(Screen screen,boolean backRestore,String requestScope){
        final ForumRoute route;
        try{route=ForumRoute.fromKey(screen.id).withParent(screen.extra);}catch(IllegalArgumentException invalid){return null;}
        if(!backRestore)forumSnapshots.remove(screen);
        NgaApi.ForumPage snapshot=backRestore?forumSnapshots.get(screen,requestScope,route.key,route.parentFid,screen.page,SystemClock.elapsedRealtime()):null;
        final ForumReadLaunch launch=new ForumReadLaunch(screen,generation,route,requestScope,store.host(),snapshot);
        activeForumRead=launch;
        if(snapshot!=null)return launch;
        final boolean traceStartup=startupForumQueuedNs==0;
        if(traceStartup){startupForumQueuedNs=startupOffset();startupForumOwner=screen;}
        if(!pageReads.execute(()->{try{
            if(traceStartup)startupWorkerStartedNs=startupOffset();
            RequestCancellation.check();
            NgaStore.CheckInSession session=store.captureCheckInSession();if(traceStartup)startupSessionReadyNs=startupOffset();
            RequestCancellation.check();
            if(!requestScope.equals(scopeDigest(session.host,session.cookie))){launch.delivery.cancel();return;}
            ForumResponseCache.Ticket cacheTicket=forumResponseCache.capture();
            ForumResponseCache.Entry hit=forumResponseCache.load(requestScope,route.key,route.parentFid,launch.pageNumber);if(traceStartup)startupCacheReadyNs=startupOffset();
            RequestCancellation.check();
            if(traceStartup&&hit==null)startupNetworkStartedNs=startupOffset();
            NgaApi.ForumPage page=hit==null?api.readForum(route.key,launch.pageNumber,route.parentFid,session):hit.page;if(traceStartup)startupResponseReadyNs=startupOffset();
            long loadedAt=SystemClock.elapsedRealtime()-(hit==null?0:hit.ageMs);
            RequestCancellation.check();
            launch.delivery.complete(new ForumReadResult(session,page,loadedAt,hit==null?"network":hit.source,cacheTicket));
        }catch(Exception failure){launch.delivery.complete(new ForumReadResult(failure));}}))
            launch.delivery.complete(new ForumReadResult(new java.io.IOException("页面正在切换，请重试")));
        return launch;
    }
    private void forum(Screen s,ForumReadLaunch launch) {
        if(launch==null){LinearLayout list=scrollBody();empty(list,"无法打开版块","版块或合集编号无效。","返回",this::onBackPressed);return;}
        final ForumRoute route=launch.route;final String requestScope=launch.scope,requestHost=launch.host;
        NgaApi.ForumPage cached=launch.backSnapshot;
        LinearLayout outer=vertical();body.addView(outer,new FrameLayout.LayoutParams(-1,-1));
        final String[] favorite={route.key,s.title,route.stid.isEmpty()?"版块":"合集","N"};
        TextView favoriteStar=text("☆",23,gold);mainToolbar.addView(favoriteStar,2,new LinearLayout.LayoutParams(dp(48),dp(56)));if(mainToolbar instanceof ResponsiveToolbar)((ResponsiveToolbar)mainToolbar).favorite=favoriteStar;bindForumFavorite(favoriteStar,favorite,null);
        FrameLayout listHolder=new FrameLayout(this);outer.addView(listHolder,new LinearLayout.LayoutParams(-1,0,1));body=listHolder;
        if(cached==null){ProgressBar progress=new ProgressBar(this);FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(dp(32),dp(32),Gravity.CENTER);body.addView(progress,pp);}
        final int token=launch.token;final FrameLayout target=body;
        java.util.function.Consumer<NgaApi.ForumPage> render=page->{
                if(token!=generation||current!=s||!requestScope.equals(contentScope()))return;target.removeAllViews();body=target;
                nativeChildForumCount=page.subForums.size();nativeForumTopicCount=page.topics.size();
                if(!page.name.isEmpty()){s.title=page.name;favorite[1]=page.name;paintForumFavorite(favoriteStar,favorite);TextView toolbarTitle=mainToolbar.findViewWithTag("screen-title");if(toolbarTitle!=null&&!page.name.contentEquals(toolbarTitle.getText()))toolbarTitle.setText(page.name);}
                recordBoardVisit(route,s.title);
                String parent=route.parentFid.isEmpty()&&!route.stid.isEmpty()?page.fid:route.parentFid;
                if(page.topics.isEmpty()){
                    LinearLayout l=scrollBody();forumHeader(l,page,route,parent);empty(l,"这个版块暂时没有主题",page.subForums.isEmpty()?"可从右上角菜单刷新重试。":"可先进入上方子版块或主题合集。","刷新",this::refreshCurrent);
                }else renderForumTopics(target,s,route,requestScope,page,parent);
                if(startupFirstDrawNs==0&&startupForumOwner==s){startupContentBoundNs=startupOffset();observeStartupForumDraw(target,s,token,requestScope);}else if(!catalogReady)ensureCatalogAsync();
        };
        java.util.function.Consumer<Exception> failed=e->runOnUiThread(()->{if(token!=generation||current!=s||!requestScope.equals(contentScope()))return;target.removeAllViews();body=target;LinearLayout l=scrollBody();networkError(l,e,route.url(requestHost,s.page));ensureCatalogAsync();});
        if(cached!=null){userFilter=NativeUserActions.filter(this);nativeForumSource="back";render.accept(cached);}
        else launch.delivery.bind(work->{
            // Only an unusually early response needs a new worker; do not build its filter on UI.
            if(launch.delivery.cancelled()||token!=generation||current!=s)return;
            if(!pageReads.execute(work)){launch.delivery.cancel();failed.accept(new java.io.IOException("页面正在切换，请重试"));}
        },result->{
            if(launch.delivery.cancelled())return;
            if(result.failure!=null){failed.accept(result.failure);return;}
            // Normally runs directly on the original read worker with its cancellation scope intact.
            prepareForumRender(s,token,requestScope,route,result.session,result.page,result.loadedAt,result.source,result.cacheTicket,render,failed);
        });
        FrameLayout footer=cappedControls(680,12,8);ForumPaging paging=new ForumPaging(s);footer.addView(paging,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER_HORIZONTAL));outer.addView(footer,new LinearLayout.LayoutParams(-1,-2));
        requestForumFavoriteRefresh();
    }
    private void forumHeader(LinearLayout header,NgaApi.ForumPage page,ForumRoute route,String parent){
        if(ForumRoute.isForumId(parent)&&!parent.equals(route.fid))header.addView(button("返回父版块",()->openForum(ForumRoute.fromKey(parent),findForum(parent)[1],1,true)));
        subForums(header,page.subForums);if(!page.topics.isEmpty()&&!page.subForums.isEmpty())forumSection(header,"主题列表","");
    }
    /** Submit the complete filtered page once. Native ListView alone decides which rows need views. */
    private void renderForumTopics(FrameLayout target,Screen owner,ForumRoute route,String scope,NgaApi.ForumPage page,String parent){
        final ArrayList<NgaApi.Topic> topics=new ArrayList<>();ArrayList<String> ids=new ArrayList<>();
        for(NgaApi.Topic topic:page.topics){
            if(topic.fid==null||topic.fid.isEmpty())topic.fid=page.fid;if(topic.forum==null||topic.forum.isEmpty())topic.forum=page.name.isEmpty()?owner.title:page.name;
            if(userFilter!=null&&userFilter.blocks(topic.authorId,topic.author,topic.title))continue;topics.add(topic);ids.add(topic.tid);
        }
        ForumTopicListView list=new ForumTopicListView(this,scope,route.key,route.parentFid,owner.page,ids);forumList=list;forumListOwner=owner;
        LinearLayout header=vertical();forumHeader(header,page,route,parent);if(header.getChildCount()>0)list.addHeaderView(header,null,false);
        list.setCacheColorHint(bg);list.setRecyclerListener(view->{if(view instanceof ForumTopicRow)((ForumTopicRow)view).recycle();});
        list.setAdapter(new BaseAdapter(){
            public int getCount(){return topics.size();}public Object getItem(int position){return topics.get(position);}public long getItemId(int position){try{return Long.parseLong(topics.get(position).tid);}catch(RuntimeException malformed){return position;}}
            public boolean hasStableIds(){return true;}
            public View getView(int position,View recycled,ViewGroup parentView){ForumTopicRow row=recycled instanceof ForumTopicRow?(ForumTopicRow)recycled:new ForumTopicRow();row.bind(topics.get(position));return row;}
        });
        target.addView(list,new FrameLayout.LayoutParams(-1,-1));list.restore(owner.forumAnchor);
    }
    /** The title/metadata hierarchy is recycled; authenticated preview ownership never is. */
    private final class ForumTopicRow extends LinearLayout{
        final LinearLayout content;final TextView title;final TopicListMeta meta;NgaApi.Topic topic;
        ForumTopicRow(){super(MainActivity.this);setOrientation(VERTICAL);content=vertical();content.setPadding(dp(20),dp(12),dp(20),dp(8));title=text("",17,ink);title.setGravity(Gravity.TOP);title.setLineSpacing(dp(2),1.04f);content.addView(title);meta=new TopicListMeta("","0","",0,null);content.addView(meta,new LinearLayout.LayoutParams(-1,-2));addView(content,new LinearLayout.LayoutParams(-1,-2));View separator=new View(MainActivity.this);separator.setBackgroundColor(line);addView(separator,new LinearLayout.LayoutParams(-1,dp(1)));}
        void recycle(){for(int i=content.getChildCount()-1;i>=0;i--){View child=content.getChildAt(i);if(child instanceof TopicPreviewView){TopicPreviewView preview=(TopicPreviewView)child;preview.close();topicPreviews.remove(preview);content.removeViewAt(i);}}topic=null;setOnClickListener(null);}
        void bind(NgaApi.Topic value){
            if(topic==value)return;recycle();topic=value;String cleanedTitle=clean(value.title);title.setText(cleanedTitle);meta.replies.setText(value.replies+" 回复");meta.time.setText(empty(value.time)?"":value.time);setTag("forum-topic:"+value.tid);
            // Match the original row order: title, optional preview, then metadata.
            content.removeView(meta);addTopicPreview(content,value.tid,cleanedTitle,true);content.addView(meta,new LinearLayout.LayoutParams(-1,-2));
            final int token=generation;final String scope=activeContentScope;setOnClickListener(view->{if(token==generation&&scope.equals(contentScope()))openThread(value);});
        }
    }
    /** Read the filter off-main; a simultaneous cloud refresh can only re-read rules, never the page. */
    private void prepareForumRender(Screen screen,int token,String scope,ForumRoute route,NgaStore.CheckInSession session,
            NgaApi.ForumPage page,long loadedAt,String source,ForumResponseCache.Ticket cacheTicket,java.util.function.Consumer<NgaApi.ForumPage> render,java.util.function.Consumer<Exception> failed){
        try{
            RequestCancellation.check();long revision=userFilterRevision;
            NativeUserActions.UserFilter filter=NativeUserActions.filter(getApplicationContext(),session);
            RequestCancellation.check();
            runOnUiThread(()->{
                if(token!=generation||current!=screen||isFinishing()||isDestroyed()||!scope.equals(contentScope()))return;
                if(revision!=userFilterRevision){
                    if(!pageReads.execute(()->prepareForumRender(screen,token,scope,route,session,page,loadedAt,source,cacheTicket,render,failed)))failed.accept(new java.io.IOException("页面正在切换，请重试"));
                    return;
                }
                userFilter=filter;forumSnapshots.put(screen,scope,route.key,route.parentFid,screen.page,page,loadedAt);
                nativeForumSource=source;nativeForumCacheAgeMs="network".equals(source)?0:Math.max(0,SystemClock.elapsedRealtime()-loadedAt);
                nativeForumRequests=page.requestCount;nativeForumRequestNanos=page.requestNanos;nativeForumHeadersNanos=page.headersNanos;
                nativeForumBodyNanos=page.bodyNanos;nativeForumParsedNanos=page.parsedNanos;nativeForumResponseBytes=page.responseBytes;
                render.accept(page);
                if("network".equals(source))forumResponseCache.storeAsync(scope,route.key,route.parentFid,page,cacheTicket);
            });
        }catch(Exception unavailable){failed.accept(unavailable);}
    }
    /** Text labels keep their original layout until their measured glyphs need more than one line. */
    private final class ForumPaging extends LinearLayout {
        final Screen owner;final TextView previous,next,number;final IconView backPage,nextPage;boolean compact;
        ForumPaging(Screen screen){super(MainActivity.this);owner=screen;setTag("forum-paging");setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setPadding(dp(4),0,dp(4),0);setBackground(shape(surface,24));
            previous=button("‹ 上一页",()->forumPage(owner,owner.page-1));next=button("下一页 ›",()->forumPage(owner,owner.page+1));number=button("跳页 "+owner.page,()->jumpPage(false));number.setSingleLine();number.setEllipsize(TextUtils.TruncateAt.END);number.setContentDescription("跳转页码，当前第 "+owner.page+" 页");
            setClipToOutline(true);for(TextView control:new TextView[]{previous,number,next})control.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(palette.ripple),null,shape(Color.WHITE,24)));
            backPage=icon("back","上一页",gold,()->{if(owner.page>1)forumPage(owner,owner.page-1);});backPage.setEnabled(owner.page>1);backPage.setAlpha(owner.page>1?1f:.4f);nextPage=icon("arrowRight","下一页",gold,()->forumPage(owner,owner.page+1));
            addView(previous,new LinearLayout.LayoutParams(0,-2,1));addView(backPage,new LinearLayout.LayoutParams(dp(48),dp(48)));addView(number,new LinearLayout.LayoutParams(0,-2,1));addView(next,new LinearLayout.LayoutParams(0,-2,1));addView(nextPage,new LinearLayout.LayoutParams(dp(48),dp(48)));
        }
        private float labelWidth(TextView label,String value){return label.getPaint().measureText(value)+label.getPaddingLeft()+label.getPaddingRight();}
        @Override protected void onMeasure(int w,int h){
            int available=View.MeasureSpec.getSize(w)-getPaddingLeft()-getPaddingRight(),count=owner.page>1?3:2;
            float widest=Math.max(labelWidth(next,"下一页 ›"),labelWidth(number,"跳页 "+owner.page));if(owner.page>1)widest=Math.max(widest,labelWidth(previous,"‹ 上一页"));compact=available<count*widest;
            previous.setVisibility(!compact&&owner.page>1?View.VISIBLE:View.GONE);next.setVisibility(compact?View.GONE:View.VISIBLE);backPage.setVisibility(compact?View.VISIBLE:View.GONE);nextPage.setVisibility(compact?View.VISIBLE:View.GONE);
            String value=compact?String.valueOf(owner.page):"跳页 "+owner.page;if(!value.contentEquals(number.getText()))number.setText(value);
            super.onMeasure(w,h);
        }
    }
    /** Quiet list metadata: discussion count at left, timestamp at right; reactions live in the reader. */
    private final class TopicListMeta extends LinearLayout{
        final LinearLayout info;final TextView replies,time;boolean stackInfo;
        TopicListMeta(String tid,String pid,String when,int count,VoteMetrics value){super(MainActivity.this);setTag("topic-metadata");setPadding(0,dp(10),0,dp(4));
            info=horizontal();info.setBaselineAligned(false);info.setGravity(Gravity.CENTER_VERTICAL);replies=text(count+" 回复",11,muted);replies.setSingleLine();replies.setEllipsize(TextUtils.TruncateAt.END);
            time=text(empty(when)?"":when,11,muted);time.setTag("topic-time");time.setSingleLine();time.setEllipsize(TextUtils.TruncateAt.END);time.setGravity(Gravity.END);
            info.addView(replies);info.addView(time);addView(info,new LinearLayout.LayoutParams(-1,-2));arrange(false);
        }
        private void arrange(boolean stacked){
            stackInfo=stacked;info.setOrientation(stacked?VERTICAL:HORIZONTAL);replies.setGravity(Gravity.START);
            replies.setLayoutParams(new LinearLayout.LayoutParams(stacked?-1:-2,-2));
            LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(stacked?-1:0,-2,stacked?0:1);tp.leftMargin=stacked?0:dp(12);tp.topMargin=stacked?dp(4):0;time.setLayoutParams(tp);
        }
        @Override protected void onMeasure(int w,int h){
            int available=Math.max(1,View.MeasureSpec.getSize(w)-getPaddingLeft()-getPaddingRight());
            float infoWidth=replies.getPaint().measureText(replies.getText().toString())+time.getPaint().measureText(time.getText().toString())+dp(12);
            boolean stacked=available<infoWidth;if(stacked!=stackInfo)arrange(stacked);super.onMeasure(w,h);
        }
    }
    private void topicRow(LinearLayout l,NgaApi.Topic t) {
        if(userFilter!=null&&userFilter.blocks(t.authorId,t.author,t.title))return;
        String cleanedTitle=clean(t.title);
        LinearLayout row=vertical();row.setPadding(dp(20),dp(12),dp(20),dp(8));TextView title=text(cleanedTitle,17,ink);title.setGravity(Gravity.TOP);title.setLineSpacing(dp(2),1.04f);row.addView(title);
        TopicListMeta meta=new TopicListMeta(t.tid,"0",t.time,t.replies,t.votes);addTopicPreview(row,t.tid,cleanedTitle,true);row.addView(meta,new LinearLayout.LayoutParams(-1,-2));row.setOnClickListener(v->openThread(t));l.addView(row);separator(l);
    }
    private void addCommunityTopicRow(LinearLayout row,String tid,String pid,String title,String time,int replies,VoteMetrics votes){
        TopicListMeta meta=new TopicListMeta(tid,empty(pid)?"0":pid,time,replies,votes);if(empty(pid)||"0".equals(pid))addTopicPreview(row,tid,title);row.addView(meta,new LinearLayout.LayoutParams(-1,-2));
    }
    private void addTopicPreview(LinearLayout row,String tid,String title){
        addTopicPreview(row,tid,title,false);
    }
    private void addTopicPreview(LinearLayout row,String tid,String title,boolean cleaned){
        if(topicPreviewImages==null||!store.topicPreviewsEnabled()||tid==null||!tid.matches("[1-9][0-9]{0,17}"))return;
        final int owner=generation;final String scope=activeContentScope;
        TopicPreviewView preview=new TopicPreviewView(this,row,scope,tid,cleaned?title:clean(title),nativeImages,topicPreviewImages,
                ()->owner==generation&&!isFinishing()&&!isDestroyed()&&scope.equals(contentScope()),
                url->{if(owner==generation&&scope.equals(contentScope()))handleLink(url);},muted,surface,line,
                state->{if(owner==generation&&scope.equals(contentScope()))openGallery(state,scope);});
        topicPreviews.add(preview);row.addView(preview,new LinearLayout.LayoutParams(-1,-2));
    }
    private NgaApi.Topic readerTopic(NgaApi.Topic source){
        NgaApi.Topic copy=new NgaApi.Topic();copy.tid=source.tid;copy.title=source.title;copy.author=source.author;copy.authorId=source.authorId;
        copy.time=source.time;copy.forum=source.forum;copy.fid=source.fid;copy.image=source.image;copy.avatar=source.avatar;copy.replies=source.replies;
        copy.votes=source.votes==null?new VoteMetrics():source.votes.copy();return copy;
    }
    private void openThread(NgaApi.Topic t){
        ReadingProgress.Entry progress=readingProgress.get(localDataScope(),t.tid);
        store.recordHistory(t);Screen s=new Screen("thread",t.tid,clean(t.title),progress==null?1:progress.page);s.topic=readerTopic(t);
        if(progress!=null&&progress.anchor!=null){s.readerAnchor=progress.anchor;s.forceReaderAnchor=true;readingPositions.put(screenKey(s),progress.anchor.scrollY);}go(s,true);
    }
    private void openThreadFromStart(NgaApi.Topic topic){
        if(current!=null&&"thread".equals(current.kind)&&current.id.equals(topic.tid)){threadLoaded=false;current.readerAnchor=null;}
        removeReadingProgress(topic.tid);
        store.recordHistory(topic);Screen next=new Screen("thread",topic.tid,clean(topic.title),1);next.topic=readerTopic(topic);go(next,current==null||!"thread".equals(current.kind));
        // go() captures the outgoing page first. Discard those offsets before its async read returns.
        readingPositions.keySet().removeIf(key->key.startsWith("thread:"+topic.tid+":"));
    }
    /** The page control keeps a full touch target; secondary actions remain in the route menu. */
    private final class ReaderTools extends LinearLayout {
        final TextView page;final View favorite,reply;boolean compact;
        ReaderTools(TextView number,View previous,View next,View save,View compose){super(MainActivity.this);page=number;favorite=save;reply=compose;setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setTag("reader-tools");setPadding(dp(4),0,dp(4),0);addView(previous,new LinearLayout.LayoutParams(dp(48),dp(48)));addView(page,new LinearLayout.LayoutParams(0,dp(48),1));addView(next,new LinearLayout.LayoutParams(dp(48),dp(48)));addView(favorite,new LinearLayout.LayoutParams(dp(48),dp(48)));addView(reply,new LinearLayout.LayoutParams(dp(48),dp(48)));}
        @Override protected void onMeasure(int w,int h){int width=View.MeasureSpec.getSize(w)-getPaddingLeft()-getPaddingRight();int label=Math.max(dp(48),(int)Math.ceil(page.getPaint().measureText(page.getText().toString()))+dp(12));compact=width<dp(192)+label;favorite.setVisibility(compact?GONE:VISIBLE);reply.setVisibility(compact?GONE:VISIBLE);super.onMeasure(w,h);}
    }
    private boolean compactReaderTools(){View tools=root==null?null:root.findViewWithTag("reader-tools");return tools instanceof ReaderTools&&((ReaderTools)tools).compact;}
    private void thread(Screen s) {
        final boolean locatePid=s.locateRequested||NavigationState.locatePost(s.pid,readingPositions.get(screenKey(s)));
        LinearLayout outer=vertical();body.addView(outer,new FrameLayout.LayoutParams(-1,-1));FrameLayout target=new FrameLayout(this);outer.addView(target,new LinearLayout.LayoutParams(-1,0,1));body=target;
        ProgressBar loading=new ProgressBar(this);target.addView(loading,new FrameLayout.LayoutParams(dp(32),dp(32),Gravity.CENTER));
        final TextView pageNumber=text(String.valueOf(s.page),18,ink);pageNumber.setSingleLine();pageNumber.setGravity(Gravity.CENTER);pageNumber.setContentDescription("跳转页码");pageNumber.setEnabled(s.totalPages>0);pageNumber.setOnClickListener(v->jumpPage(true));
        final IconView previous=icon("back","上一页",ink,()->{if(s.page>1)threadPage(s,s.page-1);});previous.setEnabled(s.page>1);previous.setAlpha(s.page>1?1f:.3f);
        final IconView next=icon("arrowRight","下一页",ink,()->threadPage(s,s.page+1));next.setEnabled(false);next.setAlpha(.3f);
        final int token=generation;
        final String requestScope=contentScope();
        pageReads.execute(()->{try {
            NgaApi.ThreadPage page=api.thread(s.id,s.page,s.pid,s.extra);
            if(topicPreviewImages!=null&&store.topicPreviewsEnabled()&&page.page==1&&s.id.equals(page.tid))for(NgaApi.Post first:page.posts)if(first.floor==0&&"0".equals(first.pid)){topicPreviewImages.seed(requestScope,s.id,TopicPreviewImages.withVotes(TopicPreviewImages.extract(first.content),first.votes));break;}
            runOnUiThread(()->{if(token!=generation||!requestScope.equals(contentScope()))return;threadLoaded=true;loadedReadingScope=requestScope;if(!page.title.isEmpty()){s.title=page.title;TextView toolbarTitle=mainToolbar.findViewWithTag("screen-title");if(toolbarTitle!=null)toolbarTitle.setText(clean(page.title));}s.page=page.page;s.totalPages=page.totalPages;if(locatePid)readingPositions.remove(screenKey(s));s.locateRequested=false;target.removeAllViews();body=target;LinearLayout l=scrollBody();pageNumber.setText(page.page+" / "+page.totalPages);pageNumber.setEnabled(true);previous.setEnabled(s.page>1);previous.setAlpha(s.page>1?1f:.3f);next.setEnabled(s.page<page.totalPages);next.setAlpha(s.page<page.totalPages?1f:.3f);
                TextView title=text(clean(page.title),21,ink);title.setTypeface(null,1);title.setLineSpacing(dp(3),1.06f);title.setPadding(dp(28),dp(14),dp(28),dp(8));l.addView(title);
                TextView sub=text(findForum(page.fid)[1]+(s.extra.isEmpty()?"":" · 只看楼主")+" · 第 "+page.page+" 页",11,muted);sub.setPadding(dp(28),0,dp(28),dp(12));l.addView(sub);
                if(store.motionEnabled())Motion.enter(l);
                if(s.topic==null){s.topic=new NgaApi.Topic();s.topic.tid=s.id;s.topic.title=page.title;s.topic.fid=page.fid;}if(!page.fid.isEmpty())s.topic.fid=page.fid;if(!page.title.isEmpty())s.topic.title=page.title;if(!page.authorId.isEmpty())s.topic.authorId=page.authorId;store.recordHistory(s.topic);
                readerAccountId=store.accountId();readerThreadPage=page;readerVoteViews.clear();final Map<String,NativeVoteBar> voteViews=readerVoteViews;boolean needsVotes=false;nativePostCount=page.posts.size();nativeVoteCount=0;nativeDisplayedVoteCount=0;
                for(NgaApi.Post post:page.posts){int index=l.getChildCount();NativeVoteBar votes=postRow(l,post);if(votes!=null)voteViews.put(post.floor+":"+post.pid,votes);boolean known=post.votes.countsKnown();needsVotes|=!known;if(known)nativeDisplayedVoteCount++;if(post.votes.likesKnown)nativeVoteCount++;if(locatePid&&s.pid.equals(post.pid))locatePostAfterLayout(l,l.getChildAt(index),currentScroll,token);}
                if(needsVotes)loadPostVotes(s,page,voteViews,token);
                LinearLayout nav=horizontal();nav.setPadding(dp(16),dp(16),dp(16),dp(12));
                if(s.page>1)nav.addView(button("‹ 上一页",()->threadPage(s,s.page-1)),new LinearLayout.LayoutParams(0,-2,1));
                nav.addView(button("跳转页码",()->jumpPage(true)),new LinearLayout.LayoutParams(0,-2,1));
                if(s.page<page.totalPages)nav.addView(button("下一页 ›",()->threadPage(s,s.page+1)),new LinearLayout.LayoutParams(0,-2,1));l.addView(nav);if(!locatePid)restoreReaderAnchor(l,currentScroll,s,token);restorePendingPrompt();
            });
        } catch(Exception e){runOnUiThread(()->{if(token!=generation)return;target.removeAllViews();body=target;LinearLayout l=scrollBody();networkError(l,e,base()+"/read.php?tid="+s.id+"&page="+s.page);});}});
        FrameLayout footer=cappedControls(680,12,8);GlassPanel material=new GlassPanel(this);material.setNightMode(dark);material.setSurfaceColor(surface);material.setCornerRadiusDp(24);material.setGlassEnabled(false);
        ReaderTools tools=new ReaderTools(pageNumber,previous,next,icon("star","收藏帖子",ink,()->favoriteOptions()),icon("edit","回复帖子",ink,()->compose(s.topic==null?"":s.topic.fid,s.id)));material.addView(tools);footer.addView(material,new FrameLayout.LayoutParams(-1,dp(48),Gravity.CENTER_HORIZONTAL));outer.addView(footer,new LinearLayout.LayoutParams(-1,-2));
    }

    private void threadPage(Screen old,int p){Screen n=new Screen("thread",old.id,old.title,p);n.topic=old.topic;n.totalPages=old.totalPages;n.extra=old.extra;go(n,false);}
    private ReaderAnchor captureReaderAnchor(){
        if(current==null||!"thread".equals(current.kind)||currentScroll==null||currentScroll.getChildCount()==0)return null;
        View content=currentScroll.getChildAt(0);if(!(content instanceof LinearLayout)||content.getWidth()<1)return null;
        int y=currentScroll.getScrollY();LinearLayout column=(LinearLayout)content;
        PostCard previous=null;for(int i=0;i<column.getChildCount();i++){View child=column.getChildAt(i);if(child instanceof PostCard){PostCard card=(PostCard)child;if(child.getTop()<=y&&child.getBottom()>y)return ReaderAnchor.create(current.id,current.page,card.floor,Math.min(10000,(int)((long)(y-card.getTop())*10000/Math.max(1,card.getHeight()))),column.getWidth(),NavigationState.offset(y));if(previous!=null&&previous.getBottom()<=y&&card.getTop()>y)return ReaderAnchor.create(current.id,current.page,card.floor,0,column.getWidth(),NavigationState.offset(y));previous=card;}}
        return null;
    }
    private void restoreReaderAnchor(LinearLayout column,ScrollView scroll,Screen screen,int token){
        ReaderAnchor anchor=screen.readerAnchor;if(anchor==null||!anchor.matches(screen.id,screen.page))return;
        final ViewTreeObserver observer=column.getViewTreeObserver();
        class Restore implements ViewTreeObserver.OnGlobalLayoutListener,View.OnAttachStateChangeListener{
            void remove(){if(observer.isAlive())observer.removeOnGlobalLayoutListener(this);ViewTreeObserver current=column.getViewTreeObserver();if(current!=observer&&current.isAlive())current.removeOnGlobalLayoutListener(this);column.removeOnAttachStateChangeListener(this);}
            public void onGlobalLayout(){
                if(token!=generation||currentScroll!=scroll||isDestroyed()){remove();return;}
                if(column.getWidth()<1||scroll.getHeight()<1)return;remove();
                boolean forced=screen.forceReaderAnchor;screen.forceReaderAnchor=false;if(!forced&&Math.abs(column.getWidth()-anchor.columnWidth)<=2)return;
                for(int i=0;i<column.getChildCount();i++){View child=column.getChildAt(i);if(child instanceof PostCard&&((PostCard)child).floor==anchor.floor){scroll.scrollTo(0,anchor.targetY(child.getTop(),child.getHeight()));return;}}
            }
            public void onViewAttachedToWindow(View view){}
            public void onViewDetachedFromWindow(View view){remove();}
        }
        Restore restore=new Restore();column.addOnAttachStateChangeListener(restore);observer.addOnGlobalLayoutListener(restore);
    }
    private void locatePostAfterLayout(LinearLayout content,View anchor,ScrollView scroll,int token){
        final ViewTreeObserver initial=content.getViewTreeObserver();
        class Locator implements ViewTreeObserver.OnGlobalLayoutListener,View.OnAttachStateChangeListener{
            private void remove(){if(initial.isAlive())initial.removeOnGlobalLayoutListener(this);ViewTreeObserver active=content.getViewTreeObserver();if(active!=initial&&active.isAlive())active.removeOnGlobalLayoutListener(this);content.removeOnAttachStateChangeListener(this);}
            @Override public void onGlobalLayout(){if(token!=generation||currentScroll!=scroll||isDestroyed()){remove();return;}if(anchor.getHeight()<1||scroll.getHeight()<1)return;remove();scroll.smoothScrollTo(0,anchor.getTop());}
            @Override public void onViewAttachedToWindow(View view){}
            @Override public void onViewDetachedFromWindow(View view){remove();}
        }
        Locator locator=new Locator();content.addOnAttachStateChangeListener(locator);initial.addOnGlobalLayoutListener(locator);
    }
    /** One page-sized data read after the native body appears; never a request for each floor or list row. */
    private void loadPostVotes(Screen screen,NgaApi.ThreadPage page,Map<String,NativeVoteBar> labels,int token){
        final int resolvedPage=page.page;final String scope=contentScope();
        reactionSelections.useScope(scope);final long revision=reactionSelections.revision();
        pageReads.execute(()->{if(token!=generation)return;try{
            Map<String,VoteMetrics> values=api.postVotes(screen.id,resolvedPage,screen.extra);
            runOnUiThread(()->{if(token!=generation||current!=screen||readerThreadPage!=page||isDestroyed()
                    ||!scope.equals(contentScope())||!reactionSelections.accepts(scope,revision))return;
                for(NgaApi.Post post:page.posts){String key=post.floor+":"+post.pid;VoteMetrics value=values.get(key);
                    if(value!=null){post.votes=post.votes.supplementedBy(value);NativeVoteBar label=labels.get(key);if(label!=null)label.counts(post.votes);}}
                updateReaderVoteDiagnostics(page);
            });
        }catch(Exception unavailable){ /* A failed or net-only read cannot fabricate independent totals. */ }});
    }
    private void updateReaderVoteDiagnostics(NgaApi.ThreadPage page){
        int known=0,displayed=0;for(NgaApi.Post post:page.posts){if(post.votes.likesKnown)known++;if(post.votes.countsKnown())displayed++;}
        nativeVoteCount=known;nativeDisplayedVoteCount=displayed;
    }
    private final class PostCard extends LinearLayout{
        final int floor;
        PostCard(int floor){super(MainActivity.this);this.floor=floor;setOrientation(VERTICAL);setTag("post-card");setBackground(shape(surface,20));}
    }
    private NativeVoteBar postRow(LinearLayout list,NgaApi.Post post) {return postRow(list,post,false);}
    private NativeVoteBar postRow(LinearLayout list,NgaApi.Post post,boolean reveal) {
        if(!reveal&&userFilter!=null&&userFilter.blocks(post.authorId,post.author,post.content)){
            PostCard hidden=new PostCard(post.floor);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.setMargins(dp(12),0,dp(12),dp(10));hidden.setPadding(dp(16),dp(12),dp(16),dp(12));list.addView(hidden,hp);
            hidden.addView(text((post.floor==0?"楼主":"#"+post.floor)+" · 已按屏蔽规则隐藏",13,muted));hidden.addView(button("临时显示这层",()->{int index=list.indexOfChild(hidden);list.removeView(hidden);postRow(list,post,true);View actual=list.getChildAt(list.getChildCount()-1);list.removeView(actual);list.addView(actual,index);}));return null;
        }
        PostCard card=new PostCard(post.floor);LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);cardParams.setMargins(dp(12),0,dp(12),dp(10));list.addView(card,cardParams);
        LinearLayout header=horizontal();header.setPadding(dp(12),dp(10),dp(16),dp(6));
        String name=empty(post.author)?"NGA 用户":post.author;
        LinearLayout authorTarget=horizontal();authorTarget.setTag("post-author");authorTarget.setMinimumHeight(dp(48));
        FrameLayout avatarTarget=new FrameLayout(this);AvatarView avatar=new AvatarView(this,36,nativeImages);avatar.bindUser(post.authorId,post.avatar,name);avatarTarget.addView(avatar,new FrameLayout.LayoutParams(dp(36),dp(36),Gravity.CENTER));authorTarget.addView(avatarTarget,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout info=vertical();info.setPadding(dp(6),0,dp(8),0);TextView author=text(name,14,ink);author.setTypeface(null,1);author.setSingleLine();author.setEllipsize(TextUtils.TruncateAt.END);info.addView(author);TextView time=text(post.time,11,muted);time.setPadding(0,dp(5),0,0);time.setSingleLine();time.setEllipsize(TextUtils.TruncateAt.END);info.addView(time);authorTarget.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(authorTarget,new LinearLayout.LayoutParams(0,-2,1));TextView floor=text(post.floor==0?"楼主":"#"+post.floor,11,post.floor==0?gold:muted);floor.setPadding(dp(8),dp(5),dp(8),dp(5));if(post.floor==0)floor.setBackground(shape(palette.selectionFill,8));header.addView(floor);card.addView(header);
        if(post.authorId.matches("[1-9][0-9]*")){authorTarget.setContentDescription("查看 "+name+" 的资料");authorTarget.setOnClickListener(v->nativeGo("user",post.authorId,name,1));}
        NativeRichText content=new NativeRichText(this,nativeImages,post.content,dark,store.fontSize(),this::handleLink,post.authorId,current.id,post.pid);content.setPadding(dp(16),dp(2),dp(16),dp(8));card.addView(content,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout actions=horizontal();actions.setTag("post-actions");actions.setMinimumHeight(dp(48));actions.setPadding(dp(16),0,dp(4),dp(4));NativeVoteBar votes=new NativeVoteBar(this,dark);final Screen owner=current;final String scope=contentScope();votes.bind(post.votes,()->{if(current==owner&&scope.equals(contentScope()))react(post,1);},()->{if(current==owner&&scope.equals(contentScope()))react(post,-1);},reactionSelections.selected(scope,owner==null?"":owner.id,post.pid));View spacer=new View(this);actions.addView(spacer,new LinearLayout.LayoutParams(0,1,1));
        TextView quote=text("引用回复",13,gold);quote.setGravity(Gravity.CENTER);quote.setPadding(dp(12),0,dp(12),0);quote.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(palette.ripple),shape(Color.TRANSPARENT,12),null));quote.setOnClickListener(v->openEditor("quote",current.topic==null?"":current.topic.fid,current.id,post.pid));actions.addView(quote,new LinearLayout.LayoutParams(-2,dp(48)));
        actions.addView(icon("more","更多楼层操作",muted,()->postActions(post)),new LinearLayout.LayoutParams(dp(48),dp(48)));card.addView(new PostActionRow(votes,actions),new LinearLayout.LayoutParams(-1,-2));if(readerThreadPage!=null&&owner!=null&&owner.id.equals(readerThreadPage.tid))readerVoteViews.put(post.floor+":"+post.pid,votes);return votes;
    }
    private final class PostActionRow extends LinearLayout{
        final NativeVoteBar votes;final LinearLayout actions;boolean compact;
        PostActionRow(NativeVoteBar votes,LinearLayout actions){super(MainActivity.this);this.votes=votes;this.actions=actions;setGravity(Gravity.CENTER_VERTICAL);setPadding(dp(12),0,dp(4),dp(5));actions.setPadding(0,0,0,0);addView(votes);addView(actions);arrange(false);}
        void arrange(boolean small){compact=small;setOrientation(small?VERTICAL:HORIZONTAL);votes.setLayoutParams(new LinearLayout.LayoutParams(small?-1:dp(180*Math.min(1.5f,getResources().getConfiguration().fontScale)),-2));actions.setLayoutParams(new LinearLayout.LayoutParams(small?-1:0,-2,small?0:1));}
        @Override protected void onMeasure(int w,int h){boolean small=View.MeasureSpec.getSize(w)<dp(340)*Math.min(1.5f,getResources().getConfiguration().fontScale);if(small!=compact)arrange(small);super.onMeasure(w,h);}
    }
    private void postActions(NgaApi.Post post){
        Screen screen=current;ArrayList<String> labels=new ArrayList<>();ArrayList<Runnable> actions=new ArrayList<>();
        labels.add("点赞 / 撤赞");actions.add(()->react(post,1));
        labels.add("点踩 / 撤销点踩");actions.add(()->react(post,-1));
        labels.add("收藏这层回复到云端");actions.add(()->{if(!store.loggedIn()){login();return;}userSheets().chooseFavorite(screen.id,post.pid,()->toast("云端收藏已更新"));});
        labels.add("举报这层回复");actions.add(()->{if(!store.loggedIn()){login();return;}threadSheets().showReport(screen.id,post.pid,screen.topic==null?"":screen.topic.fid,"",()->{});});
        if(post.authorId.matches("[1-9][0-9]{0,17}")){labels.add("用户关注与屏蔽");actions.add(()->userSheets().showUser(post.authorId,post.author,post.authorId.equals(store.accountId())));}
        labels.add("复制正文");actions.add(()->copyText("NGA 正文",PostRenderer.plainText(post.content),"正文已复制"));
        labels.add("复制楼层链接");actions.add(()->copyText("NGA 楼层",floorUrl(screen,post),"楼层链接已复制"));
        labels.add("分享这层");actions.add(()->shareFloor(screen,post));
        labels.add("复制原始代码");actions.add(()->copyText("NGA 原始代码",post.content,"原始代码已复制"));
        if(!post.authorId.isEmpty()&&post.authorId.equals(store.accountId())){labels.add("编辑这层回复");actions.add(()->{if(current==screen)openEditor("modify",screen.topic==null?"":screen.topic.fid,screen.id,post.pid);});}
        alert().setTitle(post.floor==0?"楼主内容":"第 "+post.floor+" 楼").setItems(labels.toArray(new String[0]),(dialog,index)->actions.get(index).run()).setNegativeButton("取消",null).show();
    }
    private void localTopics(boolean saved){
        LinearLayout list=scrollBody();section(list,saved?"本地收藏":"浏览历史",saved?"保存在这台设备，可搜索和整理":"点击继续阅读 · 长按管理记录");
        EditText filter=dialogInput("搜索标题、作者或版块");filter.setContentDescription(saved?"筛选本地收藏":"筛选浏览历史");filter.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});list.addView(filter);
        LinearLayout controls=horizontal();TextView count=text("",12,muted);count.setPadding(dp(20),0,dp(8),0);controls.addView(count,new LinearLayout.LayoutParams(0,dp(48),1));controls.addView(button("清空",()->confirmClearLocal(saved)));list.addView(controls);
        LinearLayout rows=vertical();list.addView(rows);final int owner=generation;
        Runnable render=()->{if(owner!=generation)return;rows.removeAllViews();List<NgaApi.Topic> all=saved?store.savedTopics():store.history();String q=filter.getText().toString().trim().toLowerCase(Locale.ROOT);int visible=0;Map<String,ReadingProgress.Entry> progressSnapshot=readingProgress.snapshot(localDataScope());
            for(NgaApi.Topic topic:all){if(!(topic.title+" "+topic.author+" "+topic.forum).toLowerCase(Locale.ROOT).contains(q))continue;visible++;LinearLayout row=vertical();row.setPadding(dp(20),dp(15),dp(20),dp(15));TextView title=text(clean(topic.title),16,ink);title.setMaxLines(3);title.setEllipsize(TextUtils.TruncateAt.END);title.setLineSpacing(dp(3),1);row.addView(title);
                ReadingProgress.Entry progress=progressSnapshot.get(topic.tid);String position=progress==null?"从第一页阅读":"继续第 "+progress.page+" 页"+(progress.anchor==null?"":" · 第 "+progress.anchor.floor+" 楼");TextView detail=text(position,12,muted);detail.setPadding(0,dp(8),0,dp(2));row.addView(detail);TopicListMeta meta=new TopicListMeta(topic.tid,"0",topic.time,topic.replies,topic.votes);addTopicPreview(row,topic.tid,topic.title);row.addView(meta,new LinearLayout.LayoutParams(-1,-2));row.setMinimumHeight(dp(72));row.setBackgroundColor(surface);row.setContentDescription(clean(topic.title)+"，"+position+"，长按管理");row.setOnClickListener(v->openThread(topic));row.setOnLongClickListener(v->{localTopicActions(topic,saved);return true;});rows.addView(row);separator(rows);}
            count.setText(visible+" / "+all.size()+" 条");if(visible==0)empty(rows,all.isEmpty()?(saved?"还没有本地收藏":"还没有阅读记录"):"没有匹配的记录",all.isEmpty()?(saved?"在帖子收藏菜单中保存，之后可在这里整理。":store.historyEnabled()?"阅读帖子后会记录进度。":"已暂停记录，可在设置中重新开启。"):"试试标题、作者或版块名称。","去逛逛",()->go(new Screen("home","","NGA",1),true));
        };filter.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){navigationHandler.removeCallbacks(render);navigationHandler.postDelayed(render,120);}public void afterTextChanged(Editable e){}});rows.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){navigationHandler.removeCallbacks(render);}});render.run();
    }
    private void localTopicActions(NgaApi.Topic topic,boolean saved){
        final Screen owner=current;alert().setTitle(clean(topic.title)).setItems(new String[]{"继续阅读","从头阅读",saved?"移出本地收藏":"删除这条历史","复制帖子链接"},(d,n)->{if(current!=owner)return;if(n==0)openThread(topic);else if(n==1)openThreadFromStart(topic);else if(n==2){if(saved)store.removeSavedTopic(topic.tid);else{store.removeHistory(topic.tid);removeReadingProgress(topic.tid);}go(owner,false);}else copyText("NGA 帖子",base()+"/read.php?tid="+topic.tid,"帖子链接已复制");}).setNegativeButton("取消",null).show();
    }
    private void confirmClearLocal(boolean saved){
        final Screen owner=current;alert().setTitle(saved?"清空本地收藏？":"清空浏览历史？").setMessage(saved?"清除这台设备保存的帖子列表，NGA 云端收藏不受影响。":"清除本机帖子历史，以及当前账号的阅读位置和最近版块访问。此操作不可恢复。")
            .setNegativeButton("取消",null).setPositiveButton("清空",(d,w)->{if(current!=owner)return;boolean visitsCleared=true;if(saved)store.clearSavedTopics();else{store.clearHistory();removeReadingProgress(null);visitsCleared=clearBoardVisits();}go(owner,false);toast(saved?"本地收藏已清空":visitsCleared?"浏览历史已清空":"帖子历史已清空，最近访问暂未能清除");}).show();
    }
    private void saveCurrent(){if(current.topic==null){toast("帖子加载后可收藏");return;}store.toggleTopic(current.topic);toast(store.isTopicSaved(current.id)?"已加入本地收藏":"已取消本地收藏");}
    private void favoriteOptions(){
        if(current==null||!"thread".equals(current.kind)||current.topic==null){toast("帖子加载后可收藏");return;}
        final Screen owner=current;
        String local=store.isTopicSaved(owner.id)?"取消本地收藏":"保存到本地收藏";
        alert().setTitle("收藏帖子").setMessage("本地收藏仅在这台设备保存；云端收藏跟随 NGA 账号。")
            .setItems(new String[]{local,"选择云端收藏夹","查看云端收藏夹"},(dialog,index)->{
                if(current!=owner)return;
                if(index==0)saveCurrent();else if(index==1)cloudSave();else nativeGo("serverfav","1","云端收藏",1);
            }).setNegativeButton("取消",null).show();
    }
    private void copyText(String label,String value,String message){android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(clipboard==null){toast("系统剪贴板暂不可用");return;}clipboard.setPrimaryClip(ClipData.newPlainText(label,value));toast(message);}
    private void shareText(String text,String title){Intent intent=new Intent(Intent.ACTION_SEND);intent.setType("text/plain");intent.putExtra(Intent.EXTRA_TEXT,text);try{startActivity(Intent.createChooser(intent,title));}catch(ActivityNotFoundException unavailable){toast("未找到可用的分享应用");}}
    private String floorUrl(Screen screen,NgaApi.Post post){return base()+"/read.php?tid="+screen.id+(post.pid.matches("[1-9][0-9]{0,17}")?"&pid="+post.pid:"&page="+screen.page);}
    private void shareFloor(Screen screen,NgaApi.Post post){shareText(clean(screen.title)+" · "+(post.floor==0?"楼主":"第 "+post.floor+" 楼")+"\n"+floorUrl(screen,post),"分享这层");}
    private void shareThread(){shareText(current.title+"\n"+base()+"/read.php?tid="+current.id,"分享帖子");}
    private void jumpPage(boolean thread){
        showPageJump(thread,null);
    }
    private void showPageJump(boolean thread,PromptDraftState restored){
        if(current==null||isFinishing()||isDestroyed()||!(thread?"thread":"forum").equals(current.kind))return;
        pendingPrompt=null;closePageJump();if(searchDialog!=null)searchDialog.dismiss();final Screen origin=current;final int token=generation;pageJumpScope=contentScope();pageJumpPageKey=promptPageIdentity();final String ownerScope=pageJumpScope,ownerPageKey=pageJumpPageKey;
        pageJumpDialog=new NativePageJumpDialog(this,root,dark,store.glassEnabled(),store.motionEnabled(),origin.page,thread?origin.totalPages:0,new NativePageJumpDialog.Listener(){
            public void onPageSelected(int page){if(current!=origin||generation!=token||isFinishing()||isDestroyed()||page==origin.page||!ownerScope.equals(contentScope())||!ownerPageKey.equals(promptPageIdentity()))return;if(thread)threadPage(origin,page);else forumPage(origin,page);}
            public void onDismiss(NativePageJumpDialog source){if(pageJumpDialog==source){pageJumpDialog=null;pageJumpScope=null;pageJumpPageKey=null;}}
        });pageJumpDialog.show();if(restored!=null)pageJumpDialog.restoreDraft(restored.text,restored.selectionStart,restored.selectionEnd);
    }
    private void closePageJump(){NativePageJumpDialog pending=pageJumpDialog;pageJumpDialog=null;pageJumpScope=null;pageJumpPageKey=null;if(pending!=null)pending.close();}
    private void networkError(LinearLayout l,Exception e,String url){String msg=e.getMessage();if(msg==null)msg="连接失败，请检查网络";empty(l,"暂时无法加载",msg,"重试",()->go(current,false));if(!store.loggedIn()||msg.matches("(?s).*(?:未登录|尚未登录|登录.*(?:失效|过期)|重新登录|请.*登录).*"))l.addView(button(store.loggedIn()?"重新登录 NGA":"登录 NGA",()->login()));l.addView(button("切换访问域名",()->hostDialog()));}
    private void empty(LinearLayout l,String title,String detail,String action,Runnable run){LinearLayout panel=vertical();panel.setPadding(dp(24),dp(42),dp(24),dp(24));TextView icon=text("◇",38,gold);icon.setGravity(Gravity.CENTER);panel.addView(icon);TextView t=text(title,18,ink);t.setGravity(Gravity.CENTER);t.setPadding(0,dp(14),0,dp(10));panel.addView(t);TextView d=text(detail,13,muted);d.setGravity(Gravity.CENTER);d.setLineSpacing(dp(4),1);panel.addView(d);TextView b=button(action,run);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(20);panel.addView(b,p);l.addView(panel);}
    private void profile(){
        LinearLayout list=scrollBody();LinearLayout account=horizontal();account.setPadding(dp(18),dp(22),dp(18),dp(22));account.setBackground(shape(surface,20));
        AvatarView avatar=new AvatarView(this,56,nativeImages);avatar.bindUser(store.loggedIn()?store.accountId():"","","我的 NGA");account.addView(avatar,new LinearLayout.LayoutParams(dp(56),dp(56)));
        LinearLayout details=vertical();details.setPadding(dp(16),0,0,0);TextView title=text(store.loggedIn()?"我的 NGA":"登录 NGA",20,ink);title.setTypeface(null,1);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);details.addView(title);
        TextView status=text(store.loggedIn()?"已登录 · 点按查看主题与回复":"扫码、账号密码或手机验证码登录",12,muted);status.setPadding(0,dp(8),0,0);details.addView(status);account.addView(details,new LinearLayout.LayoutParams(0,-2,1));
        if(store.loggedIn()){final int accountGeneration=generation;pageReads.execute(()->{try{NativeCommunity.UserProfile value=new NativeCommunity(this).self();runOnUiThread(()->{if(accountGeneration==generation&&!isDestroyed()){avatar.bindUser(value.uid,value.avatar,value.username);if(!empty(value.username))title.setText(value.username);}});}catch(Exception unavailable){ /* Keep the account entry and cached author avatar usable. */ }});}
        account.setContentDescription(store.loggedIn()?"查看我的资料、主题和回复":"登录 NGA");account.setOnClickListener(v->{if(store.loggedIn())nativeGo("self","","我的资料",1);else login();});LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);ap.setMargins(dp(16),dp(12),dp(16),dp(20));list.addView(account,ap);
        LinearLayout reading=preferenceGroup(list,"阅读与收藏");
        preferenceRow(reading,"云端收藏","与 NGA 账号同步的帖子","",()->nativeGo("serverfav","1","云端收藏",1));
        preferenceRow(reading,"本地收藏","仅保存在当前设备","",()->go(new Screen("saved","","本地收藏",1),true));
        preferenceRow(reading,"浏览历史","接着上次的位置阅读","",()->go(new Screen("history","","浏览历史",1),true));
        preferenceRow(reading,"草稿箱","未发布的主题、回复与编辑","",()->nativeGo("drafts","","草稿箱",1));
        LinearLayout interaction=preferenceGroup(list,"互动");
        preferenceRow(interaction,"关注动态","关注用户的主题、回复与收藏","",()->nativeGo("following","","关注动态",1));
        preferenceRow(interaction,"我的关注","同步 NGA 云端关注关系","",()->{if(!store.loggedIn())login();else userSheets().showFollowing(1);});
        preferenceRow(interaction,"回复提醒","提到我和回复我的内容","",()->nativeGo("reminders","","回复提醒",1));
        preferenceRow(interaction,"消息与私信","","",()->messages());
        LinearLayout management=preferenceGroup(list,"管理");
        preferenceRow(management,"云端屏蔽","同步 NGA 屏蔽用户与关键词","",()->{if(!store.loggedIn())login();else userSheets().showCloudBlocks();});
        preferenceRow(management,"本机屏蔽","管理在当前设备隐藏的用户","",()->userSheets().showBlockedUsers());
        preferenceRow(management,"每日签到",store.checkInMessage(),"",()->checkIn());
        preferenceRow(management,"设置","阅读外观、账号与自动签到","",()->go(new Screen("settings","","设置",1),true));
        TextView foot=text(appVersionLabel(),11,muted);foot.setGravity(Gravity.CENTER);foot.setPadding(0,dp(16),0,dp(12));list.addView(foot);
    }
    private LinearLayout preferenceGroup(LinearLayout parent,String label){
        TextView heading=text(label,13,muted);heading.setPadding(dp(24),dp(10),dp(24),dp(8));if(Build.VERSION.SDK_INT>=28)heading.setAccessibilityHeading(true);parent.addView(heading);
        LinearLayout group=vertical();group.setBackground(shape(surface,18));group.setClipToOutline(true);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(dp(16),0,dp(16),dp(12));parent.addView(group,lp);return group;
    }
    private void preferenceSeparator(LinearLayout group){if(group.getChildCount()==0)return;View divider=new View(this);divider.setBackgroundColor(line);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1));lp.setMargins(dp(16),0,dp(16),0);group.addView(divider,lp);}
    private void preferenceRow(LinearLayout group,String title,String description,String value,Runnable action){preferenceSeparator(group);group.addView(new NativePreferenceRow(this,dark,title,description,value,action),new LinearLayout.LayoutParams(-1,-2));}
    private void settingRow(LinearLayout l,String name,String hint,Runnable action){LinearLayout r=horizontal();r.setPadding(dp(20),dp(17),dp(20),dp(17));r.setBackgroundColor(surface);LinearLayout a=vertical();a.addView(text(name,16,ink));if(!empty(hint)){TextView sub=text(hint,12,muted);sub.setPadding(0,dp(5),dp(12),0);sub.setMaxLines(3);a.addView(sub);}r.addView(a,new LinearLayout.LayoutParams(0,-2,1));r.addView(text("›",23,muted));r.setOnClickListener(v->action.run());l.addView(r);separator(l);}
    private void settings(){
        LinearLayout l=scrollBody();LinearLayout reading=preferenceGroup(l,"阅读偏好");
        preferenceRow(reading,"界面效果","",store.glassEnabled()?"液态玻璃":"实色界面",()->alert().setTitle("界面效果").setSingleChoiceItems(new String[]{"实色界面","液态玻璃"},store.glassEnabled()?1:0,(d,n)->{store.setGlassEnabled(n==1);d.dismiss();go(current,false);}).setNegativeButton("取消",null).show());
        preferenceRow(reading,"界面动画","流体胶囊与轻触反馈",store.motionEnabled()?"已开启":"已关闭",()->{store.setMotionEnabled(!store.motionEnabled());go(current,false);});
        preferenceRow(reading,"主题外观","",themeLabel(),()->alert().setTitle("主题外观").setItems(new String[]{"浅色 · 通透白","深色 · 柔和阅读","跟随系统"},(d,n)->{store.setTheme(new String[]{"light","dark","system"}[n]);go(current,false);}).show());
        preferenceRow(reading,"正文字号","",store.fontSize()+" sp",()->alert().setTitle("正文字号").setItems(new String[]{"小 · 14","标准 · 16","大 · 18","特大 · 20","超大 · 22"},(d,n)->{store.setFontSize(new int[]{14,16,18,20,22}[n]);go(current,false);}).show());
        preferenceRow(reading,"浏览历史",store.historyEnabled()?"记住页码和阅读位置":"不再记录新的历史或位置",store.historyEnabled()?"已开启":"已暂停",()->{synchronized(READING_WRITES_LOCK){readingWriteEpoch++;store.setHistoryEnabled(!store.historyEnabled());}go(current,false);});
        preferenceRow(reading,"帖子图片预览",store.topicPreviewsEnabled()?"列表中预览楼主首帖图片":"仅在进入帖子后加载图片",store.topicPreviewsEnabled()?"已开启":"已关闭",()->{store.setTopicPreviewsEnabled(!store.topicPreviewsEnabled());go(current,false);});
        preferenceRow(reading,"媒体缓存","查看占用，清理图片与表情缓存","",this::cacheManagement);
        LinearLayout account=preferenceGroup(l,"账号与签到");
        LinearLayout sr=horizontal();sr.setPadding(dp(16),dp(12),dp(16),dp(12));sr.setMinimumHeight(dp(56));LinearLayout text=vertical();text.addView(text("自动签到",16,ink));TextView explain=text("后台每日尝试，打开应用时补签\n登录后生效；系统省电可能延迟",12,muted);explain.setPadding(0,dp(4),dp(12),0);text.addView(explain);sr.addView(text,new LinearLayout.LayoutParams(0,-2,1));Switch toggle=new Switch(this);toggle.setContentDescription("自动签到");toggle.setMinHeight(dp(48));toggle.setMinWidth(dp(48));toggle.setChecked(store.autoCheckIn());toggle.setOnCheckedChangeListener((b,c)->{store.setAutoCheckIn(c);CheckInService.schedule(this);if(c)CheckInService.checkOnOpen(this);toast(c?"自动签到已开启":"自动签到已关闭");});sr.addView(toggle);account.addView(sr);
        preferenceRow(account,"立即签到",store.checkInMessage(),"",()->checkIn());
        preferenceRow(account,"签到执行记录",store.lastCheckInAttempt(),"",()->alert().setTitle("签到执行记录").setMessage(store.lastCheckInAttempt()+"\n\n"+store.checkInMessage()+"\n\n仅在服务器确认后记录今日完成，后台执行时间由系统安排。").setPositiveButton("知道了",null).show());
        preferenceRow(account,"登录管理",store.loggedIn()?"点击重新登录":"",store.loggedIn()?"已登录":"未登录",()->login());
        if(store.loggedIn())preferenceRow(account,"退出登录","清除设备上的账号凭据","",()->alert().setTitle("退出登录？").setMessage("将清除本机 NGA 登录状态，保留本地收藏和阅读记录。").setNegativeButton("取消",null).setPositiveButton("退出",(d,w)->{try{store.clearLogin();}catch(IllegalStateException failure){toast(failure.getMessage()==null?"无法安全退出登录，请重试":failure.getMessage());return;}CheckInService.schedule(this);NativeNotifications.schedule(this);go(current,false);}).show());
        LinearLayout notifications=preferenceGroup(l,"消息提醒");preferenceRow(notifications,"后台未读提醒",NativeNotifications.status(this),"",this::notificationSettings);
        LinearLayout app=preferenceGroup(l,"连接与应用");preferenceRow(app,"论坛域名","",store.host(),()->hostDialog());preferenceRow(app,"关于与隐私","无广告 SDK，无埋点，凭据仅保存在本机","",()->about());
    }
    private void notificationSettings(){
        long checked=NativeNotifications.lastCheckedAt(this);String when=checked==0?"尚未检查":android.text.format.DateFormat.format("MM-dd HH:mm",checked).toString();
        NativeGlassDialog.Builder choice=alert().setTitle("后台未读提醒").setMessage(NativeNotifications.status(this)+"\n上次检查："+when+"\n\n开启后定期读取 NGA 回复提醒与私信未读状态，由 Android 安排执行；系统省电可能延迟。通知不显示私信正文。首次开启仅建立基线。").setNegativeButton("取消",null);
        if(NativeNotifications.enabled(this))choice.setNeutralButton("关闭提醒",(d,n)->{try{NativeNotifications.setEnabled(this,false);go(current,false);}catch(RuntimeException failure){toast("设置未保存，请重试");}});
        choice.setPositiveButton(NativeNotifications.enabled(this)&&!NativeNotifications.canNotify(this)?"通知权限设置":"开启提醒",(d,n)->{
            if(!store.loggedIn()){login();return;}
            if(Build.VERSION.SDK_INT>=33&&!NativeNotifications.permissionGranted(this)){requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},704);return;}
            if(!NativeNotifications.canNotify(this)){Intent settings=new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName());try{startActivity(settings);}catch(ActivityNotFoundException unavailable){toast("请在系统应用设置中允许通知");}return;}
            enableNotifications();
        }).show();
    }
    private String cacheSize(long bytes){return bytes<1024?bytes+" B":bytes<1024*1024?String.format(Locale.SIMPLIFIED_CHINESE,"%.1f KB",bytes/1024d):String.format(Locale.SIMPLIFIED_CHINESE,"%.1f MB",bytes/(1024d*1024d));}
    private void cacheManagement(){
        final int token=generation;final String scope=contentScope();TextView status=text("正在统计媒体缓存…",15,ink);status.setPadding(dp(24),dp(12),dp(24),dp(18));status.setLineSpacing(dp(5),1);
        NativeGlassDialog dialog=alert().setTitle("媒体缓存").setMessage("清理图片、头像和表情缓存；账号、草稿、收藏和阅读记录会保留。当前页面的图片仍可继续查看。").setView(status).setNegativeButton("关闭",null).setPositiveButton("清理缓存",null).show();dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE).setEnabled(false);
        java.util.function.BooleanSupplier live=()->token==generation&&scope.equals(contentScope())&&dialog.isShowing()&&!dialog.isClosing()&&!isFinishing()&&!isDestroyed();
        io.execute(()->{NativeImages.CacheStats stats=nativeImages.cacheStats();runOnUiThread(()->{if(!live.getAsBoolean())return;status.setText("图片文件 "+cacheSize(stats.diskBytes)+" · "+stats.diskEntries+" 项\n内存缓存 "+cacheSize(stats.memoryBytes)+(stats.diskAccessible?"":"\n暂时无法读取缓存目录，请稍后重试"));dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE).setEnabled(stats.diskAccessible||stats.memoryBytes>0);});});
        dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(!live.getAsBoolean())return;dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE).setEnabled(false);dialog.getButton(NativeGlassDialog.BUTTON_NEGATIVE).setEnabled(false);dialog.setCancelable(false);status.setText("正在清理媒体缓存…");io.execute(()->{try{NativeImages.CacheClearResult result=nativeImages.clearCaches();runOnUiThread(()->{if(!live.getAsBoolean())return;dialog.setCancelable(true);dialog.getButton(NativeGlassDialog.BUTTON_NEGATIVE).setEnabled(true);dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE).setEnabled(true);dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE).setText("再次清理");status.setText("已清理 "+cacheSize(result.removedDiskBytes)+" · "+result.removedDiskEntries+" 项\n剩余图片文件 "+cacheSize(result.remaining.diskBytes)+"\n内存缓存 "+cacheSize(result.remaining.memoryBytes)+(result.failedDiskEntries>0||!result.remaining.diskAccessible?"\n部分文件未能清理，可稍后重试":"\n清理完成"));});}catch(RuntimeException failure){runOnUiThread(()->{if(!live.getAsBoolean())return;dialog.setCancelable(true);dialog.getButton(NativeGlassDialog.BUTTON_NEGATIVE).setEnabled(true);dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE).setEnabled(true);status.setText("缓存暂时无法清理，请稍后重试。");});}});});
    }
    private void readingFontDialog(){
        final Screen owner=current;LinearLayout panel=vertical();TextView preview=text("这是正文预览。调整字号，让长帖阅读更舒服。",store.fontSize(),ink);preview.setPadding(dp(24),dp(16),dp(24),dp(20));preview.setLineSpacing(dp(4),1.1f);panel.addView(preview);RadioGroup choices=new RadioGroup(this);choices.setPadding(dp(20),0,dp(20),dp(8));int[] sizes={14,16,18,20,22};final int[] selected={store.fontSize()};for(int size:sizes){RadioButton choice=new RadioButton(this);choice.setId(View.generateViewId());choice.setText(size+" sp"+(size==16?" · 标准":""));choice.setMinHeight(dp(48));choice.setTextSize(15);choice.setTextColor(ink);choice.setButtonTintList(android.content.res.ColorStateList.valueOf(gold));choices.addView(choice);if(size==store.fontSize())choices.check(choice.getId());choice.setOnClickListener(v->{selected[0]=size;preview.setTextSize(size);});}panel.addView(choices);alert().setTitle("阅读字号").setView(panel).setNegativeButton("取消",null).setPositiveButton("应用",(d,w)->{if(current!=owner)return;store.setFontSize(selected[0]);owner.forceReaderAnchor=true;go(owner,false);}).show();
    }
    private void enableNotifications(){try{NativeNotifications.setEnabled(this,true);NativeNotifications.onForeground(this);go(current,false);}catch(RuntimeException failure){toast("设置未保存，请重试");}}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==704){if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)enableNotifications();else toast("未获得通知权限，可稍后在设置中开启");}}
    private String themeLabel(){return "dark".equals(store.theme())?"夜间模式":"system".equals(store.theme())?"跟随系统":"通透白模式";}
    private String appVersionLabel(){try{return "NGAAA "+getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(android.content.pm.PackageManager.NameNotFoundException unavailable){return "NGAAA";}}
    private void about(){alert().setTitle(appVersionLabel()).setMessage("独立开发的第三方 Android 客户端，与 NGA 官方无隶属关系。\n\n阅读、搜索、登录表单、发帖回复、私信、资料、收藏和图片查看均使用原生界面。短信风控需要时单独加载官方腾讯安全验证控件；论坛阅读不使用网页。帖子中的直链音视频使用原生播放器；外部活动和第三方网页会先确认，再交给系统浏览器。点赞、举报、投票评分、关注、屏蔽、资料和收藏夹操作均提供原生界面与提交确认。无广告 SDK 或开发者统计。\n\n接口连接所选 NGA 域名；图片和附件连接 NGA 的图片服务器。登录凭据由 Android Keystore 加密保存在本机，草稿、本地收藏和历史保存在应用私有目录。没有开发者服务器。\n\n签到和发布只以服务器明确确认结果为准。账号密码支持 NGA 图形验证码，短信登录支持腾讯安全验证。后台签到时间受系统调度影响。\n\n完整源码、构建方法和功能验证范围随安装包提供。").setPositiveButton("知道了",null).show();}
    private void hostDialog(){String[] hosts={"bbs.nga.cn","ngabbs.com","nga.178.com"};alert().setTitle("论坛域名").setItems(hosts,(d,n)->{try{store.setHost(hosts[n]);}catch(IllegalStateException failure){toast(failure.getMessage()==null?"无法安全切换域名，请重试":failure.getMessage());return;}NativeNotifications.schedule(this);toast("已切换至 "+hosts[n]);go(current,false);}).setNegativeButton("取消",null).show();}
    private void checkIn(){if(!store.loggedIn()){login();return;}final int owner=generation;NativeGlassDialog loading=alert().setTitle("每日签到").setMessage("正在等待 NGA 确认…").setProgress(true).setCancelable(false).show();io.execute(()->{try{store.recordCheckInAttempt("手动签到");NgaApi.CheckInResult r=api.checkIn();runOnUiThread(()->{loading.dismiss();if(owner!=generation||isFinishing()||isDestroyed())return;alert().setTitle(r.success?"签到已确认":"签到未完成").setMessage(r.message).setPositiveButton("知道了",(d,w)->{if("settings".equals(current.kind)||"profile".equals(current.kind)||("home".equals(current.kind)&&mainTab==3))go(current,false);}).show();});}catch(Exception e){runOnUiThread(()->{loading.dismiss();if(owner!=generation||isFinishing()||isDestroyed())return;alert().setTitle("签到失败").setMessage(e.getMessage()).setPositiveButton("知道了",null).show();});}});}
    private void closeDrawer(Dialog dialog,GlassPanel material,Runnable action){
        Runnable finish=()->{dialog.dismiss();if(action!=null)action.run();};if(store.motionEnabled()&&Motion.enabled(this))material.animate().alpha(0).setDuration(120).withEndAction(finish).start();else finish.run();
    }
    private Rect visibleWindow(){
        Rect bounds=FoldAdaptiveLayout.visibleSafeBounds(this);
        if(bounds.width()>0&&bounds.height()>0)return bounds;
        if(Build.VERSION.SDK_INT>=30){WindowMetrics metrics=getWindowManager().getCurrentWindowMetrics();Rect frame=metrics.getBounds();android.graphics.Insets insets=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());return new Rect(frame.left+insets.left,frame.top+insets.top,frame.right-insets.right,frame.bottom-insets.bottom);}
        return new Rect(0,0,getResources().getDisplayMetrics().widthPixels,getResources().getDisplayMetrics().heightPixels);
    }
    private void drawer(){
        if(navigationDrawer!=null&&navigationDrawer.isShowing())return;
        Dialog dialog=new Dialog(this);navigationDrawer=dialog;dialog.setOnDismissListener(d->{if(navigationDrawer==dialog)navigationDrawer=null;});GlassPanel material=new GlassPanel(this);material.setNightMode(dark);material.setSurfaceColor(surface);material.setCornerRadiusDp(26);material.setMotionEnabled(store.motionEnabled());material.setTintOpacity(dark?.78f:.76f);material.setGlassEnabled(store.glassEnabled());material.setBlurSource(root);
        LinearLayout panel=vertical();panel.setPadding(dp(8),dp(10),dp(8),dp(16));View handle=new View(this);handle.setBackground(shape(palette.handle,3));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(34),dp(4));hp.gravity=Gravity.CENTER;hp.bottomMargin=dp(16);panel.addView(handle,hp);
        LinearLayout header=horizontal();header.setPadding(dp(16),0,dp(4),dp(12));LinearLayout account=vertical();TextView name=text("快捷菜单",22,ink);name.setTypeface(null,1);account.addView(name);TextView state=text(store.loggedIn()?"NGA 账号已登录":"点此登录 NGA",12,muted);state.setPadding(0,dp(6),0,0);account.addView(state);if(!store.loggedIn())account.setOnClickListener(v->closeDrawer(dialog,material,()->login()));header.addView(account,new LinearLayout.LayoutParams(0,-2,1));header.addView(icon("close","关闭菜单",muted,()->closeDrawer(dialog,material,null)),new LinearLayout.LayoutParams(dp(48),dp(48)));panel.addView(header);
        String[] labels={"本地收藏","浏览历史","收藏版块","每日签到","刷新内容","设置"},glyphs={"heart","refresh","community","calendar","refresh","settings"};Runnable[] actions={()->go(new Screen("saved","","本地收藏",1),true),()->go(new Screen("history","","浏览历史",1),true),this::openFavoriteBoards,()->checkIn(),()->{refreshCurrent();},()->go(new Screen("settings","","设置",1),true)};
        for(int i=0;i<labels.length;i++){final int index=i;LinearLayout row=horizontal();row.setPadding(dp(18),0,dp(14),0);IconView glyph=decoration(glyphs[i],ink);row.addView(glyph,new LinearLayout.LayoutParams(dp(22),dp(22)));TextView title=text(labels[i],16,ink);title.setPadding(dp(16),0,0,0);title.setMinHeight(dp(52));row.addView(title,new LinearLayout.LayoutParams(0,-2,1));IconView arrow=decoration("arrowRight",muted);row.addView(arrow,new LinearLayout.LayoutParams(dp(16),dp(16)));row.setFocusable(true);row.setContentDescription(labels[i]);row.setOnClickListener(v->closeDrawer(dialog,material,actions[index]));panel.addView(row);}
        View edge=new View(this);edge.setBackgroundColor(line);LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,dp(1));ep.setMargins(dp(18),dp(8),dp(18),dp(8));panel.addView(edge,ep);LinearLayout night=horizontal();night.setPadding(dp(18),0,dp(18),0);TextView nightLabel=text("深色外观",15,ink);nightLabel.setMinHeight(dp(52));night.addView(nightLabel,new LinearLayout.LayoutParams(0,-2,1));Switch toggle=new Switch(this);toggle.setChecked(dark);toggle.setContentDescription("夜间模式");toggle.setOnCheckedChangeListener((b,value)->{store.setTheme(value?"dark":"light");closeDrawer(dialog,material,()->go(current,false));});night.addView(toggle);panel.addView(night);
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.addView(panel);material.addView(scroll);dialog.setContentView(material);Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setGravity(Gravity.BOTTOM);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setWindowAnimations(0);WindowManager.LayoutParams attrs=window.getAttributes();attrs.dimAmount=.24f;attrs.y=dp(10);window.setAttributes(attrs);}dialog.setOnKeyListener((d,key,event)->{if(key==KeyEvent.KEYCODE_BACK&&event.getAction()==KeyEvent.ACTION_UP){closeDrawer(dialog,material,null);return true;}return false;});dialog.show();if(window!=null){Rect usable=visibleWindow();int width=Math.max(1,Math.min(dp(448),usable.width()-dp(24)));int maxHeight=Math.max(dp(120),(int)(usable.height()*.86f));panel.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));int height=Math.min(maxHeight,panel.getMeasuredHeight());window.setLayout(width,height);NativeDialogGeometry.place(this,window,NativeDialogGeometry.sheet(usable,dp(448),height,dp(12)));}material.post(()->{if(store.glassEnabled())material.refreshBackdrop();});
    }
    private void menu(){
        boolean compact=mainToolbar instanceof ResponsiveToolbar&&((ResponsiveToolbar)mainToolbar).compact;
        if(current!=null&&"home".equals(current.kind)&&compact){alert().setItems(new String[]{"搜索","消息与私信","刷新内容","设置"},(dialog,index)->{if(index==0)search();else if(index==1)messages();else if(index==2)refreshCurrent();else go(new Screen("settings","","设置",1),true);}).show();return;}
        ArrayList<String> labels=new ArrayList<>();ArrayList<Runnable> actions=new ArrayList<>();
        if(compact){labels.add("搜索");actions.add(this::search);if("forum".equals(current.kind)){View favorite=((ResponsiveToolbar)mainToolbar).favorite;if(favorite!=null){labels.add(store.favoriteForums().contains(current.id)?"取消收藏版块":"收藏版块");actions.add(favorite::performClick);for(ForumFavoriteSyncState.Change change:store.pendingFavoriteChanges(favoriteScope()))if(change.key.equals(current.id)){labels.add("重试收藏同步");actions.add(favorite::performLongClick);break;}}}}
        labels.add("刷新");actions.add(()->{refreshCurrent();});if("forum".equals(current.kind)){try{ForumRoute route=ForumRoute.fromKey(current.id).withParent(current.extra);if(route.stid.isEmpty()){labels.add("发帖");actions.add(()->compose(route.fid,null));}}catch(IllegalArgumentException invalid){/* Invalid routes keep only the generic actions. */}}if("thread".equals(current.kind)){if(compactReaderTools()){labels.add("回复帖子");actions.add(()->compose(current.topic==null?"":current.topic.fid,current.id));}labels.add("收藏帖子 · 本地与云端");actions.add(()->favoriteOptions());labels.add(current.extra.isEmpty()?"只看楼主":"显示全部回复");actions.add(this::onlyAuthor);labels.add("投票与评分");actions.add(()->threadSheets().showPoll(current.id,()->{clearForumData();go(current,false);},this::openRatingEditor));labels.add("阅读字号");actions.add(this::readingFontDialog);labels.add("从头阅读");actions.add(()->{if(current.topic!=null)openThreadFromStart(current.topic);});labels.add("复制帖子链接");actions.add(()->copyText("NGA 帖子",base()+"/read.php?tid="+current.id,"帖子链接已复制"));labels.add("分享帖子");actions.add(()->shareThread());labels.add("加入云端收藏");actions.add(()->cloudSave());}labels.add("设置");actions.add(()->go(new Screen("settings","","设置",1),true));labels.add("返回首页");actions.add(()->{back.clear();go(new Screen("home","","NGA",1),false);});alert().setItems(labels.toArray(new String[0]),(d,n)->actions.get(n).run()).show();}
    private NativeGlassDialog.Builder alert(){pendingPrompt=null;return new NativeGlassDialog.Builder(this,dark).setBackdrop(root).setOnShowListener(d->glassDialogs.add((NativeGlassDialog)d)).setOnDismissListener(d->glassDialogs.remove((NativeGlassDialog)d));}
    private void closeGlassDialogs(){ArrayList<NativeGlassDialog> pending=new ArrayList<>(glassDialogs);glassDialogs.clear();for(NativeGlassDialog dialog:pending)dialog.dismiss();}
    private EditText dialogInput(String hint){EditText input=new EditText(this);input.setTextSize(15);input.setTextColor(ink);input.setHintTextColor(muted);input.setSingleLine();input.setHint(hint);input.setPadding(dp(24),dp(16),dp(24),dp(16));return input;}
    private void customForum(){
        EditText input=dialogInput("例如 414、-7，或 NGA 版块链接");input.setContentDescription("版块 ID 或链接");input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4096)});final Screen owner=current;final String scope=contentScope();
        NativeGlassDialog dialog=alert().setTitle("打开版块或合集").setView(input).setNegativeButton("取消",null).setPositiveButton("打开",null).show();
        dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(!dialog.isShowing()||dialog.isClosing()||current!=owner||!scope.equals(contentScope()))return;String value=input.getText().toString().trim();
            if(ForumRoute.isKey(value)){ForumRoute route=ForumRoute.fromKey(value);dialog.dismiss();openForum(route,route.stid.isEmpty()?findForum(route.fid)[1]:"主题合集 "+route.stid,1,true);return;}
            List<NativeLinkTarget> links=SharedTextLinks.extract(value,store.host());if(links.size()==1&&links.get(0).kind==NativeLinkTarget.FORUM){dialog.dismiss();handleLink(links.get(0).url);return;}input.setError("请输入有效版块 ID、stid:合集编号，或一个 NGA 版块链接");
        });
    }
    private boolean openSharedText(String value,boolean reportMissing){
        List<NativeLinkTarget> links=SharedTextLinks.extract(value,store.host());if(links.isEmpty()){if(reportMissing)toast("没有找到可打开的 NGA 只读链接");return false;}
        if(links.size()==1){handleLink(links.get(0).url);return true;}
        String[] labels=new String[links.size()];for(int i=0;i<links.size();i++){NativeLinkTarget link=links.get(i);labels[i]=link.kind==NativeLinkTarget.TOPIC?"帖子 "+link.topicId+(link.postId.isEmpty()?" · 第 "+link.page+" 页":" · 定位回复"):link.kind==NativeLinkTarget.FORUM?link.collectionId.isEmpty()?"版块 "+findForum(link.forumId)[1]:"主题合集 "+link.collectionId:link.kind==NativeLinkTarget.USER?"用户 "+link.userId:link.kind==NativeLinkTarget.HOME?"NGA 首页":link.kind==NativeLinkTarget.FAVORITE?"云端收藏夹 "+link.favoriteId:link.kind==NativeLinkTarget.MESSAGES?"消息与私信":link.kind==NativeLinkTarget.REMINDERS?"回复提醒":"我的资料";}
        final Screen owner=current;final String scope=contentScope();alert().setTitle("选择要打开的 NGA 链接").setItems(labels,(d,n)->{if(current==owner&&scope.equals(contentScope()))handleLink(links.get(n).url);}).setNegativeButton("取消",null).show();return true;
    }
    private String promptPageIdentity(){
        NavigationState.Entry entry=navigationEntry(current);if(entry==null)return "";
        // Thread fid is response metadata; tid/page/pid/author define the route even before it loads.
        String routeFid="thread".equals(entry.kind)?"":entry.fid;
        StringBuilder key=new StringBuilder();for(String value:new String[]{entry.kind,entry.id,Integer.toString(entry.page),entry.action,routeFid,entry.tid,entry.pid,entry.extra})key.append(value).append('\0');
        if("home".equals(entry.kind))key.append(mainTab).append(':').append(homeTab).append(':').append(ratingCategory).append(':').append(ratingPage);
        try{byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(key.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));char[] hex="0123456789abcdef".toCharArray(),out=new char[64];for(int i=0;i<bytes.length;i++){out[i*2]=hex[(bytes[i]&255)>>>4];out[i*2+1]=hex[bytes[i]&15];}return new String(out);}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private PromptDraftState capturePromptDraft(){
        PromptDraftState state=null;
        if(searchDialog!=null&&searchInput!=null&&searchDialog.isShowing()&&!searchDialog.isClosing())state=PromptDraftState.search(searchScope,searchPageKey,searchInput.getText().toString(),searchInput.getSelectionStart(),searchInput.getSelectionEnd(),selectedSearchForum);
        else if(pageJumpDialog!=null){String[] draft=pageJumpDialog.draftState();if(draft!=null)try{state=PromptDraftState.page(pageJumpScope,pageJumpPageKey,draft[0],Integer.parseInt(draft[1]),Integer.parseInt(draft[2]),pageJumpDialog.knownTotalPages());}catch(RuntimeException malformed){/* A malformed UI draft is never restored. */}}
        else state=pendingPrompt;
        return state!=null&&state.matches(contentScope(),promptPageIdentity())?state:null;
    }
    private void restorePendingPrompt(){
        PromptDraftState state=pendingPrompt;if(state==null||current==null||isFinishing()||isDestroyed())return;
        if(!state.matches(contentScope(),promptPageIdentity())){pendingPrompt=null;return;}
        if("search".equals(state.purpose)){pendingPrompt=null;showSearch(state);return;}
        boolean thread="thread".equals(current.kind);if(!(thread||"forum".equals(current.kind))){pendingPrompt=null;return;}
        // A saved thread total is metadata, never authority. Wait for the current response so
        // removed/added pages and permissions are validated by fresh bounds before showing input.
        if(thread&&current.totalPages<=0)return;pendingPrompt=null;showPageJump(thread,state);
    }
    private void search(){
        showSearch(null);
    }
    /** Share one line only when both complete scope labels fit their 48dp targets. */
    private final class SearchScopeChoices extends RadioGroup {
        SearchScopeChoices(){super(MainActivity.this);setPadding(0,dp(4),0,dp(4));}
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int available=View.MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight(),widest=0,count=0;
            for(int i=0;i<getChildCount();i++){
                View child=getChildAt(i);if(child.getVisibility()==GONE)continue;
                TextView label=(TextView)child;
                int width=(int)Math.ceil(label.getPaint().measureText(label.getText().toString()))+label.getCompoundPaddingLeft()+label.getCompoundPaddingRight()+dp(2);
                widest=Math.max(widest,Math.max(dp(48),width));count++;
            }
            boolean stacked=count>1&&widest*count+dp(8)*Math.max(0,count-1)>available;
            setOrientation(stacked?VERTICAL:HORIZONTAL);int visible=0;
            for(int i=0;i<getChildCount();i++){
                View child=getChildAt(i);if(child.getVisibility()==GONE)continue;
                RadioGroup.LayoutParams params=(RadioGroup.LayoutParams)child.getLayoutParams();
                params.width=stacked?-1:0;params.height=-2;params.weight=stacked?0:1;
                params.leftMargin=!stacked&&visible>0?dp(8):0;params.topMargin=0;params.rightMargin=0;params.bottomMargin=0;visible++;
            }
            super.onMeasure(widthSpec,heightSpec);
        }
    }
    private void showSearch(PromptDraftState restored){
        if(current==null||isFinishing()||isDestroyed())return;pendingPrompt=null;closePageJump();if(searchDialog!=null)searchDialog.dismiss();
        String query=restored==null?("search".equals(current.kind)?current.id:communitySearch==null?"":communitySearch.getText().toString().trim()):restored.text;
        EditText input=dialogInput("关键词或帖子链接");input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(PromptDraftState.SEARCH_LIMIT)});input.setText(query);if(restored==null)input.setSelection(input.length());else input.setSelection(restored.selectionStart,restored.selectionEnd);input.setContentDescription("搜索内容");input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        LinearLayout panel=vertical();panel.addView(input);String forum=searchForumCandidate();selectedSearchForum=restored==null?defaultSearchForum():restored.forumId;
        RadioGroup scopeChoice=new SearchScopeChoices();RadioButton all=new RadioButton(this);all.setText("全站帖子");all.setTextSize(14);all.setTextColor(ink);all.setButtonTintList(android.content.res.ColorStateList.valueOf(gold));all.setId(View.generateViewId());all.setMinHeight(dp(48));all.setContentDescription("搜索范围：全站");scopeChoice.addView(all);RadioButton board=new RadioButton(this);
        if(!forum.isEmpty()){board.setText("版块内："+findForum(forum)[1]);board.setTextSize(14);board.setTextColor(ink);board.setButtonTintList(android.content.res.ColorStateList.valueOf(gold));board.setId(View.generateViewId());board.setMinHeight(dp(48));board.setContentDescription("搜索范围：当前版块");scopeChoice.addView(board);}if(!selectedSearchForum.isEmpty()&&selectedSearchForum.equals(forum))scopeChoice.check(board.getId());else{selectedSearchForum="";scopeChoice.check(all.getId());}scopeChoice.setOnCheckedChangeListener((g,id)->selectedSearchForum=id==board.getId()?forum:"");panel.addView(scopeChoice);
        LinearLayout quick=NativeGlassDialog.actionRow(this);quick.setPadding(0,0,0,dp(4));TextView paste=button("粘贴并打开链接",()->{});quick.addView(paste);TextView direct=button("打开版块 ID",this::customForum);quick.addView(direct);panel.addView(quick);
        LinearLayout recent=vertical();panel.addView(recent);NativeGlassDialog dialog=alert().setTitle("搜索 NGA").setView(panel).setNegativeButton("取消",null).setNeutralButton("查找用户",null).setPositiveButton("搜索帖子",null).create();
        searchDialog=dialog;searchInput=input;searchScope=contentScope();searchPageKey=promptPageIdentity();
        dialog.setOnDismissListener(d->{glassDialogs.remove(dialog);if(searchDialog==dialog){searchDialog=null;searchInput=null;searchScope=null;searchPageKey=null;}});
        direct.setOnClickListener(v->{if(!acceptSearchAction(dialog))return;dialog.dismiss();customForum();});
        paste.setOnClickListener(v->{if(!acceptSearchAction(dialog))return;android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData clip=clipboard==null?null:clipboard.getPrimaryClip();CharSequence raw=clip==null||clip.getItemCount()==0?null:clip.getItemAt(0).getText();if(raw==null||raw.length()>SharedTextLinks.MAX_TEXT||SharedTextLinks.extract(raw.toString(),store.host()).isEmpty()){input.setError("剪贴板里没有可打开的 NGA 链接");return;}String value=raw.toString();dialog.dismiss();openSharedText(value,true);});
        renderRecentSearches(recent,input,dialog);
        dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(!acceptSearchAction(dialog))return;String value=input.getText().toString().trim(),failure=searchValidation(value);if(failure!=null){input.setError(failure);return;}String chosen=selectedSearchForum;dialog.dismiss();searchQuery(value,chosen);});
        dialog.getButton(NativeGlassDialog.BUTTON_NEUTRAL).setOnClickListener(v->{if(!acceptSearchAction(dialog))return;String value=input.getText().toString().trim(),failure=userSearchValidation(value);if(failure!=null){input.setError(failure);return;}dialog.dismiss();findUser(value);});
        input.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE).performClick();return true;}return false;});
        // The first sheet measurement must include the history rows and empty state.
        dialog.show();
    }
    private String defaultSearchForum(){if(current==null)return "";if("forum".equals(current.kind)&&ForumRoute.isForumId(current.id))return current.id;if("search".equals(current.kind)&&ForumRoute.isForumId(current.extra))return current.extra;if("thread".equals(current.kind)&&current.topic!=null&&ForumRoute.isForumId(current.topic.fid))return current.topic.fid;return "";}
    private String searchForumCandidate(){String fid=defaultSearchForum();if(!fid.isEmpty())return fid;for(Screen s:back){if("forum".equals(s.kind)&&ForumRoute.isForumId(s.id))return s.id;if("thread".equals(s.kind)&&s.topic!=null&&ForumRoute.isForumId(s.topic.fid))return s.topic.fid;}return "";}
    private void renderRecentSearches(LinearLayout recent,EditText input,NativeGlassDialog owner){
        recent.removeAllViews();SearchHistory history=new SearchHistory(this,localDataScope());List<String> values;try{values=history.get();}catch(IllegalStateException failed){values=Collections.emptyList();TextView error=text("最近搜索暂时无法读取，可清空后重新记录。",12,muted);error.setPadding(dp(20),dp(8),dp(20),dp(8));recent.addView(error);}
        if(values.isEmpty()&&recent.getChildCount()==0){TextView hint=text("搜索过的关键词会显示在这里",12,muted);hint.setPadding(0,dp(10),0,dp(4));recent.addView(hint);return;}
        LinearLayout heading=NativeGlassDialog.actionRow(this);TextView label=text("最近搜索",13,muted);label.setPadding(0,dp(6),0,dp(6));heading.addView(label);heading.addView(button("清空记录",()->{if(!acceptSearchAction(owner))return;try{history.clear();renderRecentSearches(recent,input,owner);}catch(IllegalStateException failed){toast(failed.getMessage());}}));recent.addView(heading);
        if(values.isEmpty()){TextView hint=text("搜索过的关键词会显示在这里",12,muted);hint.setPadding(dp(20),0,dp(20),dp(10));recent.addView(hint);return;}
        for(String query:values){LinearLayout row=horizontal();row.setPadding(dp(12),0,dp(12),0);TextView value=button(query,()->{if(acceptSearchAction(owner)){input.setText(query);input.setSelection(input.length());}});value.setGravity(Gravity.CENTER_VERTICAL);value.setMaxLines(1);value.setEllipsize(TextUtils.TruncateAt.END);row.addView(value,new LinearLayout.LayoutParams(0,dp(48),1));IconView remove=icon("close","删除搜索记录 "+query,muted,()->{if(!acceptSearchAction(owner))return;try{history.remove(query);renderRecentSearches(recent,input,owner);}catch(IllegalStateException failed){toast(failed.getMessage());}});row.addView(remove,new LinearLayout.LayoutParams(dp(48),dp(48)));recent.addView(row);}
    }
    private boolean acceptSearchAction(NativeGlassDialog dialog){
        if(searchDialog!=dialog||!dialog.isShowing()||dialog.isClosing()||isFinishing()||isDestroyed())return false;
        if(searchScope==null||searchPageKey==null||!searchScope.equals(contentScope())||!searchPageKey.equals(promptPageIdentity())){dialog.dismiss();toast("页面或登录状态已变化，请重新打开搜索");return false;}return true;
    }
    private static boolean inputControls(String value){return value.matches("(?s).*[\\x00-\\x1f\\x7f].*");}
    private String searchValidation(String value){if(value.isEmpty())return "请输入关键词或帖子链接";if(value.length()>PromptDraftState.SEARCH_LIMIT)return "搜索内容过长";if(!SharedTextLinks.extract(value,store.host()).isEmpty())return null;if(value.toLowerCase(Locale.ROOT).contains("://"))return "没有找到可打开的 NGA 只读链接";if(inputControls(value))return "请移除关键词中的控制字符";if(value.length()>PromptDraftState.KEYWORD_LIMIT)return "关键词不能超过 100 字";return SearchHistory.acceptsKeyword(value)?null:"请输入有效关键词，请勿包含登录凭据";}
    private String userSearchValidation(String value){if(value.isEmpty())return "请输入用户名";if(inputControls(value))return "请移除用户名中的控制字符";return value.length()>PromptDraftState.KEYWORD_LIMIT?"用户名不能超过 100 字":null;}
    private void findUser(String name){String query=name==null?"":name.trim(),failure=userSearchValidation(query);if(failure!=null){toast(failure);return;}final int token=generation;pageReads.execute(()->{try{NativeCommunity.UserProfile profile=new NativeCommunity(this).findUser(query);runOnUiThread(()->{if(token==generation&&!isFinishing()&&!isDestroyed())nativeGo("user",profile.uid,"用户资料",1);});}catch(Exception e){runOnUiThread(()->{if(token==generation)toast(e.getMessage());});}});}
    private void searchQuery(String value){searchQuery(value,defaultSearchForum());}
    private void searchQuery(String value,String forum){String q=value==null?"":value.trim();if(q.isEmpty())return;String failure=searchValidation(q);if(failure!=null){toast(failure);return;}View focused=getCurrentFocus();if(focused!=null){android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(focused.getWindowToken(),0);}if(openSharedText(q,false))return;try{new SearchHistory(this,localDataScope()).recordSubmitted(q);}catch(IllegalStateException unavailable){toast("搜索照常进行，最近记录暂未保存");}Screen next=new Screen("search",q,"搜索："+q,1);next.extra=ForumRoute.isForumId(forum)?forum:"";go(next,true);}
    private void messages(){nativeGo("inbox","","消息与私信",1);}
    private void compose(String fid,String tid){openEditor(tid==null?"new":"reply",fid,tid,"");}
    private void openEditor(String action,String fid,String tid,String pid){
        if(!store.loggedIn()){login();return;}Screen next=new Screen("editor","","new".equals(action)?"发表主题":"modify".equals(action)?"编辑内容":"回复主题",1);next.action=action;next.fid=fid==null?"":fid;next.tid=tid==null?"":tid;next.pid=pid==null?"":pid;go(next,true);
    }
    private String base(){return "https://"+store.host();}
    private void login(){nativeGo("auth","","登录 NGA",1);}
    private void nativeGo(String kind,String id,String title,int page){go(new Screen(kind,id,title,page),true);}
    private void nativePage(Screen s){
        if("auth".equals(s.kind)){int method=pendingLoginMethod;pendingLoginMethod=-1;loginView=new NativeLoginView(this,store,()->{CheckInService.schedule(this);CheckInService.checkOnOpen(this);NativeNotifications.schedule(this);NativeNotifications.onForeground(this);refreshCloudBlocks();toast("登录成功");if(!back.isEmpty())go(back.pop(),false);else go(new Screen("home","","NGA",1),false);},method);body.addView(loginView,new FrameLayout.LayoutParams(-1,-1));return;}
        if("editor".equals(s.kind)){
            editorView=new NativeEditorView(this,store,s.action,s.fid,s.tid,s.pid,new NativeEditorView.Listener(){
                public void published(String tid,String pid){clearForumData();back.clear();Screen next=new Screen("thread",tid,"发布的帖子",1);next.pid=pid==null?"":pid;go(next,false);}
                public void chooseAttachment(NativeEditorView editor){attachmentTarget=editor;Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);picker.addCategory(Intent.CATEGORY_OPENABLE);picker.setType("*/*");try{startActivityForResult(picker,701);}catch(ActivityNotFoundException e){attachmentTarget=null;toast("系统文件选择器不可用");}}
            },s.requestRating);body.addView(editorView,new FrameLayout.LayoutParams(-1,-1));return;
        }
        if("following".equals(s.kind)){
            followingView=new NativeFollowingView(this,store,s.page,new NativeFollowingView.Listener(){
                public void page(int page){go(new Screen("following","","关注动态",page),false);}
                public void user(String uid){nativeGo("user",uid,"用户资料",1);}
                public void topic(String tid,String pid,String title){Screen next=new Screen("thread",tid,title,1);next.pid=pid;next.locateRequested=!pid.isEmpty()&&!"0".equals(pid);go(next,true);}
                public void login(){MainActivity.this.login();}
            });followingView.setTopicPreviewFactory(this::addTopicPreview);followingView.setAuthorAvatarSource(authorAvatarSource());body.addView(followingView,new FrameLayout.LayoutParams(-1,-1));currentScroll=followingView.scrollContainer();currentScrollKey=screenKey();currentScroll.setTag(currentScrollKey);Integer saved=readingPositions.get(currentScrollKey);if(saved!=null)followingView.restoreScrollPosition(saved);return;
        }
        communityView=new NativeCommunityView(this,store,s.kind,s.id,s.page,new NativeCommunityView.Listener(){
            public void navigate(String kind,String id,String title,int page){
                if(NativeCommunityView.profileKind(kind)&&NativeCommunityView.profileKind(s.kind))go(new Screen(kind,id,title,page),false);
                else nativeGo(kind,id,title,page);
            }
            public void topic(String tid,String title){NgaApi.Topic t=new NgaApi.Topic();t.tid=tid;t.title=title;openThread(t);}
            @Override public void topic(String tid,String pid,String title){Screen next=new Screen("thread",tid,title,1);if(pid!=null&&pid.matches("[0-9]{1,18}")){next.pid=pid;next.locateRequested=!"0".equals(pid);}go(next,true);}
            public void user(String uid){nativeGo("user",uid,"用户资料",1);}
            public void login(){MainActivity.this.login();}
            @Override public boolean openLink(String url){handleLink(url);return true;}
            @Override public void userActions(String uid,String name,boolean self){userSheets().showUser(uid,name,self);}
            @Override public void manageFolders(){userSheets().showFolders(()->go(new Screen("serverfav","1","云端收藏",1),false));}
        });communityView.setTopicRowFactory(this::addCommunityTopicRow);communityView.setAuthorAvatarSource(authorAvatarSource());body.addView(communityView,new FrameLayout.LayoutParams(-1,-1));
        currentScroll=communityView.scrollContainer();currentScrollKey=screenKey();currentScroll.setTag(currentScrollKey);
        Integer communityY=readingPositions.get(currentScrollKey);if(communityY!=null)communityView.restoreScrollPosition(communityY);
    }
    private void drafts(){LinearLayout list=scrollBody();NativeDrafts drafts=new NativeDrafts(this);List<NativeDrafts.Draft> values=drafts.getAll();if(values.isEmpty()){empty(list,"还没有草稿","离开发帖或回复界面时自动保存到本机。","返回",this::onBackPressed);return;}for(NativeDrafts.Draft d:values){settingRow(list,empty(d.subject)?"未命名回复":d.subject,(d.submissionUnconfirmed?"待核对发布结果 · ":"")+(d.pendingAttachments.isEmpty()?"":d.pendingAttachments.size()+" 个附件需重新选择 · ")+(d.content.length()>60?d.content.substring(0,60)+"…":d.content),()->{if(!d.recoveryIssue.isEmpty())alert().setTitle("草稿需要恢复").setMessage(d.recoveryIssue+"\n\n原始数据仍保留，未覆盖或发布。可以返回，或在草稿箱明确删除这份草稿。").setPositiveButton("知道了",null).show();else openEditor(d.action,d.fid,d.tid,d.pid);});TextView remove=button("删除草稿",()->alert().setTitle("删除这份草稿？").setNegativeButton("取消",null).setPositiveButton("删除",(dialog,w)->{drafts.delete(d);go(current,false);}).show());list.addView(remove);}}
    private void searchResults(Screen s){LinearLayout list=scrollBody();section(list,s.extra.isEmpty()?"全站主题搜索":"版块内主题搜索",s.id);ProgressBar progress=new ProgressBar(this);list.addView(progress);int token=generation;pageReads.execute(()->{try{List<NgaApi.Topic> topics=api.searchTopics(s.id,s.extra,s.page);runOnUiThread(()->{if(token!=generation)return;list.removeView(progress);if(topics.isEmpty())empty(list,"没有找到主题","换一个关键词后重试。","重新搜索",this::search);for(NgaApi.Topic t:topics)topicRow(list,t);LinearLayout paging=horizontal();if(s.page>1)paging.addView(button("上一页",()->searchPage(s,s.page-1)));if(!topics.isEmpty())paging.addView(button("下一页",()->searchPage(s,s.page+1)));list.addView(paging);restoreReadingPosition(list);});}catch(Exception e){runOnUiThread(()->{if(token!=generation)return;list.removeView(progress);networkError(list,e,"");});}});}
    private void searchPage(Screen old,int page){Screen next=new Screen("search",old.id,old.title,page);next.extra=old.extra;go(next,false);}
    private void cloudSave(){if(!store.loggedIn()){login();return;}if(current==null||!"thread".equals(current.kind)||current.topic==null){toast("帖子加载后可收藏");return;}userSheets().chooseFavorite(current.id,"0",()->toast("云端收藏已更新"));}
    private void refreshCloudBlocks(){
        if(!store.loggedIn()||refreshingCloudBlocks)return;
        refreshingCloudBlocks=true;final int owner=generation;final Context app=getApplicationContext();
        io.execute(()->{
            try{NativeUserActions.refreshCloudBlocks(app);}
            catch(Exception unavailable){/* Keep the account's last confirmed offline rules. */}
            loadCloudBlockFilter(app,owner);
        });
    }
    /** Runs on io; a newer local edit/navigation causes a local reread, never another HTTP request. */
    private void loadCloudBlockFilter(Context app,int owner){
        final long revision=userFilterRevision;
        NativeUserActions.UserFilter loaded=null;String loadedScope="";
        try{
            NgaStore.CheckInSession session=new NgaStore(app).captureCheckInSession();
            loaded=NativeUserActions.filter(app,session);
            loadedScope=scopeDigest(session.host,session.cookie);
        }catch(RuntimeException unavailable){/* A later refresh can recover unavailable local rules. */}
        final NativeUserActions.UserFilter filter=loaded;final String scope=loadedScope;
        runOnUiThread(()->{
            if(isDestroyed()||isFinishing()||filter==null){refreshingCloudBlocks=false;return;}
            if(!scope.equals(contentScope())){refreshingCloudBlocks=false;if(contentStarted)refreshCloudBlocks();return;}
            if(revision!=userFilterRevision){
                try{io.execute(()->loadCloudBlockFilter(app,owner));}
                catch(RejectedExecutionException stopped){refreshingCloudBlocks=false;}
                return;
            }
            refreshingCloudBlocks=false;
            NativeUserActions.UserFilter previous=userFilter;
            boolean changed=previous==null||!filter.sameAs(previous);
            userFilter=filter;
            if(changed){
                userFilterRevision++;
                if(previous!=null&&current!=null&&(owner==generation||"forum".equals(current.kind)))go(current,false);
            }
        });
    }
    private NativeThreadActionDialog threadSheets(){if(threadSheets==null)threadSheets=new NativeThreadActionDialog(this,threadActions,io,dark);return threadSheets;}
    private void react(NgaApi.Post post,int direction){
        if(!store.loggedIn()){login();return;}
        final Screen owner=current;final int token=generation;final String scope=contentScope();
        threadSheets().showReaction(owner.id,post.pid,direction,(NativeThreadActionDialog.ReactionListener)result->{
            if(current!=owner||token!=generation||isDestroyed()||!scope.equals(contentScope()))return;
            reactionSelections.confirmed(scope,owner.id,post.pid,result.selectionKnown,result.selected);
            // Keep the actual row and scroll position. A net delta cannot update either independent total.
            post.votes=new VoteMetrics();NativeVoteBar bar=readerVoteViews.get(post.floor+":"+post.pid);
            if(bar!=null)bar.confirmedReaction(result);
            clearForumData();
            if(readerThreadPage!=null){updateReaderVoteDiagnostics(readerThreadPage);loadPostVotes(owner,readerThreadPage,readerVoteViews,token);}
        });
    }
    private NativeUserActionDialogs userSheets(){if(userSheets==null)userSheets=new NativeUserActionDialogs(this,dark,io,NativeUserWrites.executor(),new NativeUserActionDialogs.Host(){
        public void changed(){if(current!=null)go(current,false);}
        public void openUser(String uid,String name){nativeGo("user",uid,name,1);}
        public void chooseAvatar(NativeUserActionDialogs.AvatarSelection selection){
            if(!store.loggedIn()){toast("请登录后重新选择头像");return;}
            // Keep the purpose and session digest; the supplied callback owns the old Activity.
            avatarPicker=AvatarPickerState.begin(contentScope());Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);picker.addCategory(Intent.CATEGORY_OPENABLE);picker.setType("image/*");
            try{startActivityForResult(picker,703);}catch(ActivityNotFoundException missing){avatarPicker=null;toast("系统图片选择器不可用");}
        }
    });return userSheets;}
    private void openRatingEditor(){if(current==null||!"thread".equals(current.kind)||current.topic==null)return;if(!store.loggedIn()){login();return;}Screen next=new Screen("editor","","回复并评分",1);next.action="reply";next.fid=current.topic.fid;next.tid=current.id;next.requestRating=true;go(next,true);}
    private void onlyAuthor(){if(current==null||!"thread".equals(current.kind))return;String author=current.topic==null?"":current.topic.authorId;if(current.extra.isEmpty()&&!author.matches("[1-9][0-9]{0,17}")){toast("楼主信息尚未加载，请刷新后重试");return;}Screen next=new Screen("thread",current.id,current.title,1);next.topic=current.topic;next.extra=current.extra.isEmpty()?author:"";go(next,false);}
    private boolean trusted(Uri u){String h=u.getHost();return "https".equalsIgnoreCase(u.getScheme())&&u.getUserInfo()==null&&(u.getPort()==-1||u.getPort()==443)&&h!=null&&(h.equalsIgnoreCase("bbs.nga.cn")||h.equalsIgnoreCase("ngabbs.com")||h.equalsIgnoreCase("nga.178.com")||h.equalsIgnoreCase("www.nga.cn")||h.equalsIgnoreCase("nga.cn")||h.equalsIgnoreCase("game.nga.cn"));}
    private void handleLink(String url){
        if(NativeMediaPolicy.direct(url)){NativeMediaPlayer.open(this,url,NativeMediaPolicy.audio(url));return;}
        NativeLinkTarget target=NativeLinkTarget.parse(url,store.host());
        if(target.kind==NativeLinkTarget.INVALID){toast("链接格式不正确");return;}
        if(target.kind==NativeLinkTarget.BLOCKED){toast("请从客户端对应功能确认这项操作");return;}
        if(isAttachment(Uri.parse(target.url))){go(new Screen("image",target.url,"查看图片",1),true);return;}
        switch(target.kind){
            case NativeLinkTarget.TOPIC:
                NgaApi.Topic topic=new NgaApi.Topic();topic.tid=target.topicId;topic.title="帖子 "+target.topicId;
                store.recordHistory(topic);Screen next=new Screen("thread",topic.tid,topic.title,target.page);next.topic=topic;
                next.extra=target.authorId;if(!target.postId.isEmpty()){next.pid=target.postId;next.locateRequested=true;}go(next,true);break;
            case NativeLinkTarget.FORUM:
                ForumRoute forumRoute=ForumRoute.fromParts(target.forumId,target.collectionId,target.parentForumId);String title=forumRoute.stid.isEmpty()?findForum(forumRoute.fid)[1]:"主题合集 "+forumRoute.stid;openForum(forumRoute,title,target.page,true);break;
            case NativeLinkTarget.FAVORITE:nativeGo("serverfav",target.favoriteId,"云端收藏",target.page);break;
            case NativeLinkTarget.USER:nativeGo("user",target.userId,"用户资料",1);break;
            case NativeLinkTarget.SELF:nativeGo("self","","我的资料",1);break;
            case NativeLinkTarget.MESSAGES:nativeGo("inbox","","消息与私信",target.page);break;
            case NativeLinkTarget.REMINDERS:nativeGo("reminders","","回复提醒",1);break;
            case NativeLinkTarget.LOGIN:login();break;
            case NativeLinkTarget.EDITOR:openEditor(target.action,target.forumId,target.topicId,target.postId);break;
            case NativeLinkTarget.HOME:go(new Screen("home","","NGA",1),true);break;
            case NativeLinkTarget.BROWSER:external(target.url);break;
            default:toast("链接格式不正确");
        }
    }
    private static boolean isAttachment(Uri u){return NativeImages.allowed(u.toString())&&u.getPath()!=null&&u.getPath().matches("(?i)/attachments/.*\\.(?:jpe?g|png|gif|webp|bmp|avif)");}
    private void openGallery(ImageGalleryState state,String scope){if(state==null||!contentScope().equals(scope))return;Screen screen=new Screen("image",state.currentUrl(),"楼主图片",1);screen.gallery=state;screen.galleryScope=scope;go(screen,true);}
    private void imageGallery(Screen screen){
        if(screen.gallery==null||screen.galleryExpired||!screen.galleryScope.equals(contentScope())){screen.gallery=null;screen.galleryExpired=true;LinearLayout list=scrollBody();empty(list,"图片预览已过期","返回帖子列表，重新点按图片即可打开当前账号的图片集。","返回",this::onBackPressed);return;}
        final int token=generation;final String scope=contentScope();FrameLayout frame=new FrameLayout(this);body.addView(frame,new FrameLayout.LayoutParams(-1,-1));final NativeImageGalleryView[] holder=new NativeImageGalleryView[1];
        NativeImageGalleryView gallery=new NativeImageGalleryView(this,nativeImages,screen.gallery,()->token==generation&&current==screen&&!isDestroyed()&&scope.equals(contentScope()),(state,ready)->{if(token!=generation||current!=screen||!scope.equals(contentScope()))return;screen.gallery=state;pictureUrl=state.currentUrl();imageReady=ready;if(holder[0]!=null)pictureView=holder[0].currentPicture();resetImageSaveButton();},ink,muted,line);
        holder[0]=gallery;galleryView=gallery;pictureView=gallery.currentPicture();pictureUrl=gallery.currentUrl();frame.addView(gallery,new FrameLayout.LayoutParams(-1,-1));
        TextView save=button("保存原图",()->{if(gallery==galleryView&&gallery.ready()&&scope.equals(contentScope()))prepareImageExport(gallery.currentUrl());});imageSaveButton=save;resetImageSaveButton();FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);params.setMargins(dp(16),dp(16),dp(16),dp(16));frame.addView(save,params);
    }
    private void image(String url){
        FrameLayout frame=new FrameLayout(this);body.addView(frame,new FrameLayout.LayoutParams(-1,-1));NativePictureView picture=new NativePictureView(this);pictureView=picture;pictureUrl=url;frame.addView(picture,new FrameLayout.LayoutParams(-1,-1));TextView status=text("正在加载图片…",14,muted);status.setGravity(Gravity.CENTER);frame.addView(status,new FrameLayout.LayoutParams(-1,dp(80),Gravity.CENTER));final int token=generation;
        TextView save=button("保存原图",()->prepareImageExport(url));imageSaveButton=save;save.setEnabled(false);save.setAlpha(.45f);FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);params.setMargins(dp(16),dp(16),dp(16),dp(16));frame.addView(save,params);
        pictureRequest=nativeImages.loadDrawable(url,true,new NativeImages.DrawableCallback(){public void loaded(Drawable drawable){if(token!=generation)return;frame.removeView(status);picture.show(drawable);imageReady=true;boolean enabled=!preparingExport&&savingImage==null;save.setEnabled(enabled);save.setAlpha(enabled?1f:.45f);}public void failed(String message){if(token!=generation)return;status.setText(message);status.setOnClickListener(v->go(current,false));}});
    }
    private void prepareImageExport(String url){
        if(preparingExport||savingImage!=null)return;preparingExport=true;final int token=generation;TextView button=imageSaveButton;if(button!=null){button.setText("下载原图…");button.setEnabled(false);button.setAlpha(.45f);}
        boolean accepted=pageReads.execute(()->{try{NativeImageExport.Image image=NativeImageExport.inspect(NativeImages.bytes(url));runOnUiThread(()->{if(token!=generation||isFinishing()||isDestroyed())return;if(galleryView!=null&&!url.equals(galleryView.currentUrl())){preparingExport=false;resetImageSaveButton();toast("图片已切换，请重新保存当前图片");return;}preparingExport=false;preparedExport=image;savingImage=url;savingImageFormat=image.extension;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(image.mime);intent.putExtra(Intent.EXTRA_TITLE,"NGA-"+System.currentTimeMillis()+"."+image.extension);try{startActivityForResult(intent,702);}catch(ActivityNotFoundException e){savingImage=null;savingImageFormat=null;preparedExport=null;resetImageSaveButton();toast("系统文件选择器不可用");}});}catch(Exception e){runOnUiThread(()->{if(token!=generation||isFinishing()||isDestroyed())return;preparingExport=false;resetImageSaveButton();toast(e.getMessage()==null?"原图下载失败，请重试":e.getMessage());});}});
        if(!accepted){preparingExport=false;resetImageSaveButton();toast("图片任务繁忙，请稍后重试");}
    }
    private void resetImageSaveButton(){if(imageSaveButton!=null){imageSaveButton.setText("保存原图");imageSaveButton.setEnabled(imageReady&&!preparingExport&&savingImage==null);imageSaveButton.setAlpha(imageSaveButton.isEnabled()?1f:.45f);}}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==703){
            AvatarPickerState pending=avatarPicker;avatarPicker=null;
            if(result==RESULT_OK){
                Uri uri=data==null?null:data.getData();
                if(pending==null||!pending.matches(contentScope(),store.loggedIn()))toast("头像选择已失效，请在当前账号重新选择");
                else if(uri==null||!"content".equals(uri.getScheme())||uri.getAuthority()==null||uri.getAuthority().isEmpty())toast("请选择系统图库中的图片");
                else userSheets().resumeAvatarSelection(uri);
            }
        }
        if(request==701){NativeEditorView target=attachmentTarget;attachmentTarget=null;if(result==RESULT_OK&&target!=null&&target==editorView&&data!=null&&data.getData()!=null&&"content".equals(data.getData().getScheme()))target.attach(data.getData());else if(result==RESULT_OK&&target==null)toast("草稿已保留，请在编辑器重新选择附件");}
        if(request==702){String url=savingImage,expectedFormat=savingImageFormat;NativeImageExport.Image buffered=preparedExport;savingImage=null;savingImageFormat=null;preparedExport=null;resetImageSaveButton();if(result==RESULT_OK&&url!=null&&data!=null&&data.getData()!=null&&"content".equals(data.getData().getScheme())){if(!NativeImageSaves.save(getApplicationContext(),data.getData(),url,expectedFormat,buffered))toast("保存任务繁忙，请稍后重试");}}
    }
    private void external(String url){
        NativeLinkTarget target=NativeLinkTarget.parse(url,store.host());
        if(target.kind==NativeLinkTarget.INVALID||target.kind==NativeLinkTarget.BLOCKED){toast("链接格式不正确");return;}
        alert().setTitle(target.official?"打开 NGA 页面":"打开外部链接").setMessage(target.url)
                .setNegativeButton("取消",null).setPositiveButton("浏览器打开",(dialog,which)->{
                    if(!BrowserLinks.open(this,target.url))toast("未找到可用的系统浏览器");
                }).show();
    }
    private static boolean empty(String s){return s==null||s.isEmpty();}
    private String clean(String s){return s==null?"":Html.fromHtml(s,Html.FROM_HTML_MODE_LEGACY).toString().trim();}
    private void toast(String s){if(!isFinishing()&&!isDestroyed())Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    @Override public void onBackPressed(){if(homeGroupPopup!=null&&homeGroupPopup.isShowing()){dismissHomeGroups();return;}if(!back.isEmpty()){go(back.pop(),false,true);return;}if(current!=null&&!"home".equals(current.kind)){go(new Screen("home","","NGA",1),false);return;}super.onBackPressed();}
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(store!=null)store.clearScopeSnapshot();forumSnapshots.clear();if(forumResponseCache!=null)forumResponseCache.trimMemory();if(level>=TRIM_MEMORY_UI_HIDDEN){pageTransition.finish();refreshHomeTopics();}nativeImages.trimMemory(level);}
    @Override public void onLowMemory(){super.onLowMemory();if(store!=null)store.clearScopeSnapshot();forumSnapshots.clear();if(forumResponseCache!=null)forumResponseCache.trimMemory();pageTransition.finish();refreshHomeTopics();nativeImages.trimMemory(TRIM_MEMORY_COMPLETE);}
    @Override public void onDestroy(){forumSnapshots.clear();contentStarted=false;if(sessionPreferences!=null){sessionPreferences.unregisterOnSharedPreferenceChangeListener(sessionObserver);sessionPreferences=null;}navigationHandler.removeCallbacks(sessionContentTask);if(favoriteCoordinator!=null){favoriteCoordinator.onBackground(favoriteForegroundToken);favoriteCoordinator.removeObserver(favoriteObserver);}clearFavoritePageBindings();closeGlassDialogs();closePageJump();pageTransition.finish();if(navigationDrawer!=null){navigationDrawer.dismiss();navigationDrawer=null;}cancelHomeNavigation();dismissHomeGroups();generation++;closeNativePage(isChangingConfigurations()||!isFinishing());pageReads.close();io.shutdownNow();refreshHomeTopics();if(topicPreviewImages!=null)topicPreviewImages.close();nativeImages.close();if(store!=null)store.clearScopeSnapshot();super.onDestroy();}
}
