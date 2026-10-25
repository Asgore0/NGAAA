package cn.ngapure.directoryprobe;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import cn.ngapure.app.BoardDirectory;
import cn.ngapure.app.FoldAdaptiveLayout;
import cn.ngapure.app.ForumRoute;
import cn.ngapure.app.MainActivity;
import cn.ngapure.app.NativeGlassDialog;
import cn.ngapure.app.NgaStore;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Actual final Main/Android widgets in a separate UID. All public assets are byte-bound. */
public final class BoardDirectoryProbe extends Instrumentation {
    private static final String PACKAGE="cn.ngapure.directoryprobe";
    private static final AtomicInteger BLOCKED_HTTP=new AtomicInteger(),FORUM_ATTEMPTS=new AtomicInteger(),SEARCH_ATTEMPTS=new AtomicInteger();
    private volatile MainActivity activity;
    private boolean authorized;
    private int assertions,recreations,homeSnapshots,anchorSnapshots,desiredWidthDp=520,syntheticFavoriteTaps,lastExpectedColumns=2;
    private String stage="guard";
    private final JSONObject report=new JSONObject();
    private final Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){
        public void onActivityResumed(Activity value){if(value instanceof MainActivity){activity=(MainActivity)value;try{applyWidth();}catch(Exception failure){throw new IllegalStateException("FIXTURE_VIEWPORT_SETUP",failure);}}}
        public void onActivitySaveInstanceState(Activity value,Bundle state){}
        @Override public void onActivityPostSaveInstanceState(Activity value,Bundle state){if(value instanceof MainActivity){recreations++;String[] home=state.getStringArray("ngapure_home_v1"),anchor=state.getStringArray("ngapure_board_directory_anchor_v1");if(home!=null&&home.length==6)homeSnapshots++;if(anchor!=null&&anchor.length==2)anchorSnapshots++;}}
        public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivityDestroyed(Activity a){}
    };
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticDirectoryOnly"));start();}
    @Override public Activity newActivity(ClassLoader loader,String name,Intent intent)throws InstantiationException,IllegalAccessException,ClassNotFoundException{
        Activity value=super.newActivity(loader,name,intent);
        if(value instanceof MainActivity){Configuration configuration=new Configuration();configuration.densityDpi=160;configuration.fontScale=1;value.applyOverrideConfiguration(configuration);}
        return value;
    }
    private static Object field(Object value,String name)throws Exception{Field result=value.getClass().getDeclaredField(name);result.setAccessible(true);return result.get(value);}
    private static Method method(String name,Class<?>... types)throws Exception{Method result=MainActivity.class.getDeclaredMethod(name,types);result.setAccessible(true);return result;}
    private <T>T main(Callable<T> work)throws Exception{FutureTask<T> task=new FutureTask<>(work);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private void check(String label,boolean value)throws Exception{report.put(label,value);assertions++;if(!value)throw new AssertionError(label);}
    private void invoke(String name,Class<?>[] types,Object... values)throws Exception{main(()->{method(name,types).invoke(activity,values);applyWidth();return null;});waitForIdleSync();}
    private ListView list()throws Exception{return main(()->(ListView)field(activity,"communityList"));}
    private EditText query()throws Exception{return main(()->(EditText)field(activity,"communitySearch"));}
    @SuppressWarnings("unchecked") private List<BoardDirectory.Row> rows()throws Exception{return main(()->new ArrayList<>((List<BoardDirectory.Row>)field(activity,"communityRows")));}
    @SuppressWarnings("unchecked") private List<String[]> catalog()throws Exception{return main(()->new ArrayList<>((List<String[]>)field(activity,"catalog")));}
    private String kind()throws Exception{return main(()->(String)field(field(activity,"current"),"kind"));}
    private static View described(View view,String label){if(label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View result=described(group.getChildAt(i),label);if(result!=null)return result;}}return null;}
    private static View describedPrefix(View view,String prefix){if(view.getContentDescription()!=null&&view.getContentDescription().toString().startsWith(prefix))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View result=describedPrefix(group.getChildAt(i),prefix);if(result!=null)return result;}}return null;}
    private static boolean hasText(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(hasText(group.getChildAt(i),text))return true;}return false;}
    private void click(View view)throws Exception{if(view==null)throw new IllegalStateException("FIXTURE_CONTROL_MISSING");main(()->{view.performClick();applyWidth();return null;});waitForIdleSync();}
    private View control(String label)throws Exception{return main(()->described(activity.getWindow().getDecorView(),label));}
    @SuppressWarnings("unchecked") private NativeGlassDialog dialog(String title)throws Exception{return main(()->{for(NativeGlassDialog value:(Set<NativeGlassDialog>)field(activity,"glassDialogs"))if(value.isShowing()&&!value.isClosing()&&hasText(value.getWindow().getDecorView(),title))return value;return null;});}
    private void applyWidth()throws Exception{
        View root=(View)field(activity,"adaptiveLayout");int available=activity.getResources().getDisplayMetrics().widthPixels;
        ViewGroup.LayoutParams params=root.getLayoutParams();int width=Math.min(available,desiredWidthDp);if(params.width!=width){params.width=width;root.setLayoutParams(params);root.requestLayout();}
    }
    private void resize(int widthDp,int expectedColumns)throws Exception{desiredWidthDp=widthDp;main(()->{applyWidth();return null;});awaitDirectory(expectedColumns);}
    private void awaitDirectory(int expectedColumns)throws Exception{
        lastExpectedColumns=expectedColumns;
        // Main.go replaces adaptiveLayout without an Activity resume. Constrain the
        // actual new root before its first measured directory restore, too.
        main(()->{applyWidth();return null;});
        for(int i=0;i<160;i++){
            boolean ready=main(()->{ListView value=(ListView)field(activity,"communityList");return value!=null&&value.getWidth()>0&&value.getChildCount()>0&&value.getTag()==null&&Integer.valueOf(expectedColumns).equals(field(activity,"communityColumns"))&&method("directoryIdentity").invoke(activity).equals(field(activity,"communityDataIdentity"));});
            if(ready){waitForIdleSync();SystemClock.sleep(130);if(main(()->Integer.valueOf(expectedColumns).equals(field(activity,"communityColumns"))&&((ListView)field(activity,"communityList")).getTag()==null))return;}
            SystemClock.sleep(25);
        }
        throw new IllegalStateException("DIRECTORY_LAYOUT_TIMEOUT");
    }
    private int expectedColumns()throws Exception{return main(()->BoardDirectory.columns(Math.max(1,Math.min(desiredWidthDp,activity.getResources().getDisplayMetrics().widthPixels)-24),1,1,true));}
    private void all()throws Exception{click(control("全部版块"));awaitDirectory(expectedColumns());}
    private JSONObject layoutDiagnostics()throws Exception{return main(()->{JSONObject result=new JSONObject();result.put("desiredWidthDp",desiredWidthDp).put("expectedColumns",lastExpectedColumns);if(activity!=null){View root=(View)field(activity,"adaptiveLayout");ListView value=(ListView)field(activity,"communityList");result.put("actualRootWidthPx",root==null?-1:root.getWidth()).put("actualListWidthPx",value==null?-1:value.getWidth()).put("actualColumns",field(activity,"communityColumns")).put("mainTab",field(activity,"mainTab")).put("density",activity.getResources().getDisplayMetrics().density);if(value!=null)result.put("pendingRestore",value.getTag()!=null).put("adapterCount",value.getCount()).put("visibleChildren",value.getChildCount());}return result;});}
    private static String safeFailureMessage(Throwable failure){Throwable cause=failure;for(int i=0;i<6&&cause.getCause()!=null;i++)cause=cause.getCause();String message=cause.getMessage();return message!=null&&message.matches("[A-Z0-9_]{1,100}")?message:cause.getClass().getSimpleName();}
    private void type(String text)throws Exception{main(()->{((EditText)field(activity,"communitySearch")).setText(text);return null;});SystemClock.sleep(180);waitForIdleSync();awaitDirectory(expectedColumns());}
    private int[] anchor()throws Exception{return main(()->{ListView value=(ListView)field(activity,"communityList");View first=value.getChildAt(0);if(first==null)throw new IllegalStateException("FIRST_ROW_REQUIRED");return new int[]{value.getFirstVisiblePosition(),first.getTop()-value.getListPaddingTop()};});}
    private String middleAnchor()throws Exception{
        List<BoardDirectory.Row> current=rows();int index=Math.min(35,Math.max(1,current.size()/3));while(index<current.size()&&current.get(index).isHeader())index++;
        if(index>=current.size())throw new IllegalStateException("TILE_ANCHOR_REQUIRED");final int target=index;
        main(()->{((ListView)field(activity,"communityList")).setSelectionFromTop(target,-13);return null;});waitForIdleSync();SystemClock.sleep(140);invoke("captureCatalogPosition",new Class<?>[0]);
        return main(()->(String)field(activity,"communityAnchorKey"));
    }
    private boolean visibleKey(String key)throws Exception{return main(()->{View tile=activity.getWindow().getDecorView().findViewWithTag("directory-board:"+key);Rect rect=new Rect();return tile!=null&&tile.isShown()&&tile.getGlobalVisibleRect(rect)&&rect.width()>0&&rect.height()>0;});}
    private int tileCount()throws Exception{int count=0;for(BoardDirectory.Row row:rows())count+=row.entries.size();return count;}
    private void catalogChecks()throws Exception{
        stage="bundledDirectory";invoke("navigateHome",new Class<?>[]{int.class},1);all();resize(520,2);
        List<String[]> entries=catalog();Set<String> keys=new HashSet<>();int collections=0;for(String[] row:entries){keys.add(row[0]);if(row[0].startsWith("stid:"))collections++;}
        check("actualMainLoads658BundledCanonicalKeys",entries.size()==658&&keys.size()==658);
        check("bundledDirectoryIncludesTopicCollections",collections>0);
        check("allDirectoryActuallyPacksAll658Tiles",tileCount()==658);
        check("fixtureDensityIsLocalOneDpPerPixel",main(()->Math.abs(activity.getResources().getDisplayMetrics().density-1)<.001f&&Math.abs(activity.getResources().getConfiguration().fontScale-1)<.001f));
        String[] collection=null;for(String[] row:entries)if(row[0].startsWith("stid:")){collection=row;break;}if(collection==null)throw new IllegalStateException("COLLECTION_REQUIRED");String key=collection[0];
        int before=FORUM_ATTEMPTS.get(),searches=SEARCH_ATTEMPTS.get();type(key.toUpperCase(java.util.Locale.ROOT));
        boolean found=false;for(BoardDirectory.Row row:rows())for(String[] item:row.entries)if(key.equals(item[0]))found=true;
        check("collectionCanonicalIdFiltersInActualNativeDirectory",found&&tileCount()>=1&&"home".equals(kind()));
        check("localDirectoryFilteringDoesNotFetchForumOrPostSearch",FORUM_ATTEMPTS.get()==before&&SEARCH_ATTEMPTS.get()==searches);
        main(()->{EditText input=(EditText)field(activity,"communitySearch");input.onEditorAction(EditorInfo.IME_ACTION_DONE);input.onEditorAction(EditorInfo.IME_ACTION_SEARCH);return null;});
        check("directoryImeDoneAndSearchDoNotOpenPostSearch", "home".equals(kind())&&main(()->Integer.valueOf(1).equals(field(activity,"mainTab"))&&field(activity,"searchDialog")==null)&&SEARCH_ATTEMPTS.get()==searches);
        all();
    }
    private void reflowChecks()throws Exception{
        stage="stableKeyReflow";resize(520,2);String key=middleAnchor();check("reflowFixtureAnchorsAnActualBoardKey",ForumRoute.isKey(key)&&visibleKey(key));
        resize(760,3);check("twoToThreeColumnsKeepsStableBoardVisible",visibleKey(key));
        resize(320,1);check("threeToOneColumnKeepsStableBoardVisible",visibleKey(key));
        resize(520,2);check("oneToTwoColumnsKeepsStableBoardVisible",visibleKey(key));
        check("directoryTilesStayWithinSafeRootBounds",main(()->{Rect safe=FoldAdaptiveLayout.visibleSafeBounds(activity);ListView value=(ListView)field(activity,"communityList");Rect visible=new Rect();return value.getGlobalVisibleRect(visible)&&visible.left>=safe.left&&visible.right<=safe.right&&visible.top>=safe.top&&visible.bottom<=safe.bottom;}));
    }
    private void armRestore(ListView value)throws Exception{method("restoreCatalogPosition",ListView.class,int.class,int.class,int.class).invoke(activity,value,Math.min(45,Math.max(1,value.getCount()-1)),-19,(Integer)field(activity,"generation"));}
    private boolean pending(ListView value)throws Exception{return value.getTag()!=null&&field(value,"cancelPendingRestore")!=null;}
    private void cancellationChecks()throws Exception{
        stage="filterCancelsOldRestore";all();ListView value=list();String collection=null;for(String[] row:catalog())if(row[0].startsWith("stid:")){collection=row[0];break;}final String target=collection;
        boolean[] filter=main(()->{armRestore(value);boolean before=pending(value);((EditText)field(activity,"communitySearch")).setText(target);return new boolean[]{before,!pending(value)&&value.getTag()==null};});
        check("filterRestoreFixtureWasActuallyPending",filter[0]);check("typingFilterCancelsOldPendingRestoreInSameUiTurn",filter[1]);SystemClock.sleep(180);awaitDirectory(expectedColumns());
        check("oldRestoreCannotOverrideNewFilterPosition",main(()->((ListView)field(activity,"communityList")).getFirstVisiblePosition()==0));
        all();stage="categoryCancelsOldRestore";click(control("选择版块分类"));NativeGlassDialog chooser=dialog("选择版块分类");if(chooser==null)throw new IllegalStateException("CATEGORY_DIALOG_REQUIRED");String category=catalog().get(0)[2];View categoryItem=main(()->describedPrefix(chooser.getWindow().getDecorView(),category+" · "));
        ListView categories=list();boolean[] selected=main(()->{armRestore(categories);boolean before=pending(categories);if(categoryItem==null)throw new IllegalStateException("CATEGORY_CHOICE_REQUIRED");categoryItem.performClick();return new boolean[]{before,!pending(categories)&&categories.getTag()==null};});
        check("categoryRestoreFixtureWasActuallyPending",selected[0]);check("choosingCategoryCancelsOldPendingRestore",selected[1]);awaitDirectory(expectedColumns());
        check("newCategoryRemainsSelectedAtFirstRow",main(()->category.equals(field(activity,"communityCategory"))&&((ListView)field(activity,"communityList")).getFirstVisiblePosition()==0));
        all();stage="touchAndDetachCancelRestore";ListView touched=list();boolean touch=main(()->{armRestore(touched);boolean was=pending(touched);long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,20,20,0),cancel=MotionEvent.obtain(now,now+1,MotionEvent.ACTION_CANCEL,20,20,0);try{touched.dispatchTouchEvent(down);touched.dispatchTouchEvent(cancel);}finally{down.recycle();cancel.recycle();}return was&&!pending(touched)&&touched.getTag()==null;});check("listTouchCancelsPendingRestore",touch);
        main(()->{armRestore(touched);method("navigateHome",int.class).invoke(activity,0);return null;});waitForIdleSync();check("leavingDirectoryReleasesPendingRestore",main(()->!touched.isAttachedToWindow()&&touched.getTag()==null));invoke("navigateHome",new Class<?>[]{int.class},1);all();
    }
    private void recreate()throws Exception{MainActivity before=activity;int saved=recreations;main(()->{before.recreate();return null;});for(int i=0;i<160;i++){if(activity!=null&&activity!=before&&!activity.isDestroyed()){waitForIdleSync();awaitDirectory(2);if(recreations<=saved)throw new AssertionError("ACTUAL_FRAMEWORK_SAVE_REQUIRED");return;}SystemClock.sleep(25);}throw new IllegalStateException("ACTUAL_RECREATE_TIMEOUT");}
    private void returnAndRecreateChecks()throws Exception{
        stage="directoryReturn";all();resize(520,2);click(control("选择版块分类"));NativeGlassDialog chooser=dialog("选择版块分类");String category=catalog().get(0)[2];click(main(()->describedPrefix(chooser.getWindow().getDecorView(),category+" · ")));type("游戏");
        check("nonemptyQueryProducesScrollableActualDirectory",tileCount()>80);String key=middleAnchor();int[] before=anchor();View tile=main(()->activity.getWindow().getDecorView().findViewWithTag("directory-board:"+key));click(tile);
        check("tileOpensNativeBoardRoute","forum".equals(kind()));invoke("onBackPressed",new Class<?>[0]);awaitDirectory(2);int[] returned=anchor();
        check("returnRetainsCategoryAndFilter",main(()->category.equals(field(activity,"communityCategory"))&&"游戏".equals(field(activity,"communityQuery"))));
        check("returnRetainsStableVisibleBoardKey",visibleKey(key));check("sameWidthReturnRetainsExactAdapterAnchor",before[0]==returned[0]&&Math.abs(before[1]-returned[1])<=1);
        stage="directoryActualRecreation";String anchored=middleAnchor();int[] saved=anchor();recreate();int[] restored=anchor();
        check("actualRecreateRetainsCategoryQueryAndSearchField",main(()->category.equals(field(activity,"communityCategory"))&&"游戏".equals(field(activity,"communityQuery"))&&"游戏".contentEquals(((EditText)field(activity,"communitySearch")).getText())));
        check("actualRecreateRetainsStableVisibleBoard",visibleKey(anchored));check("actualRecreateRetainsSignedPixelAnchor",saved[0]==restored[0]&&Math.abs(saved[1]-restored[1])<=1);
        check("frameworkSavesCompatibleHomeStateAndStableAnchor",recreations>=1&&homeSnapshots>=1&&anchorSnapshots>=1);all();
    }
    private void tap(View view)throws Exception{Rect rect=main(()->{Rect value=new Rect();if(view==null||!view.getGlobalVisibleRect(value)||value.isEmpty())throw new IllegalStateException("VISIBLE_STAR_REQUIRED");return value;});long time=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,rect.exactCenterX(),rect.exactCenterY(),0),up=MotionEvent.obtain(time,time+60,MotionEvent.ACTION_UP,rect.exactCenterX(),rect.exactCenterY(),0);try{sendPointerSync(down);sendPointerSync(up);}finally{down.recycle();up.recycle();}waitForIdleSync();SystemClock.sleep(100);}
    private void starChecks(NgaStore store)throws Exception{
        stage="starChildHitTarget";all();String key=middleAnchor();View tile=main(()->activity.getWindow().getDecorView().findViewWithTag("directory-board:"+key));View star=main(()->(View)field(tile,"star"));boolean before=store.favoriteForums().contains(key);int requests=FORUM_ATTEMPTS.get();
        tap(star);syntheticFavoriteTaps++;check("actualStarTapTogglesOnlySyntheticFavorite",store.favoriteForums().contains(key)!=before);
        check("starTapDoesNotOpenParentBoardTile","home".equals(kind())&&main(()->Integer.valueOf(1).equals(field(activity,"mainTab")))&&FORUM_ATTEMPTS.get()==requests);
        View updated=main(()->activity.getWindow().getDecorView().findViewWithTag("directory-board:"+key));tap(main(()->(View)field(updated,"star")));syntheticFavoriteTaps++;
        check("secondStarTapReturnsSyntheticFavoriteState",store.favoriteForums().contains(key)==before);
        check("secondStarTapStillDoesNotOpenBoardTile","home".equals(kind())&&FORUM_ATTEMPTS.get()==requests);
    }
    private void renderSnapshotChecks(NgaStore store)throws Exception{
        stage="recycledFavoriteSnapshot";all();
        main(()->{
            ListView value=(ListView)field(activity,"communityList");android.widget.ListAdapter adapter=value.getAdapter();int first=-1,second=-1;
            for(int i=0;i<adapter.getCount();i++)if(!((BoardDirectory.Row)adapter.getItem(i)).isHeader()){if(first<0)first=i;else{second=i;break;}}
            if(second<0)throw new IllegalStateException("TWO_REAL_DIRECTORY_ROWS_REQUIRED");
            String[] a=((BoardDirectory.Row)adapter.getItem(first)).entries.get(0),b=((BoardDirectory.Row)adapter.getItem(second)).entries.get(0);
            String scope=cn.ngapure.app.ForumFavoritesCoordinator.scope(activity);
            store.queueFavorite(scope,a[0],a[1],"",true);method("refreshFavoriteBindings").invoke(activity);
            View row=adapter.getView(first,null,value);ViewGroup tiles=(ViewGroup)row;TextView star=(TextView)field(tiles.getChildAt(0),"star");
            check("pendingFavoriteSnapshotPaintsSelectedState",star.isSelected()&&star.getText().toString().contains("同步")&&star.getAlpha()<1);
            View recycled=adapter.getView(second,row,value);TextView reused=(TextView)field(((ViewGroup)recycled).getChildAt(0),"star");
            check("recyclingReusesRowAndClearsAllPreviousFavoriteState",recycled==row&&star==reused&&!reused.isSelected()&&reused.getAlpha()==1&&"☆".contentEquals(reused.getText())&&("forum-favorite:"+b[0]).equals(reused.getTag())&&!reused.getContentDescription().toString().contains("同步"));
            int generation=(Integer)field(activity,"generation");reused.performClick();
            check("recycledStarClickTargetsOnlyItsNewBoard",store.favoriteForums().contains(a[0])&&store.favoriteForums().contains(b[0])&&Integer.valueOf(generation).equals(field(activity,"generation")));
            java.util.Map<String,String[]> remote=new java.util.HashMap<>();for(String key:store.favoriteForums())remote.put(key,new String[]{store.favoriteForumName(key),store.favoriteForumIcon(key)});
            for(cn.ngapure.app.ForumFavoriteSyncState.Change change:store.pendingFavoriteChanges(scope))if(change.key.equals(b[0]))store.confirmFavorite(scope,b[0],change.revision,true,remote);
            method("refreshFavoriteBindings").invoke(activity);
            check("pendingOnlyRefreshClearsRecycledStarStatus",reused.isSelected()&&reused.getAlpha()==1&&"★".contentEquals(reused.getText())&&!reused.getContentDescription().toString().contains("同步"));return null;
        });waitForIdleSync();
    }
    private void staleSessionChecks(NgaStore store)throws Exception{
        for(int mode=0;mode<4;mode++)for(int action=0;action<3;action++){
            final int change=mode,click=action;stage="staleSession"+mode+"Action"+action;
            main(()->{store.setHost("bbs.nga.cn");store.setCookie("ngaPassportUid=79801; ngaPassportCid=synthetic-directory-guard-base");return null;});waitForIdleSync();
            invoke("navigateHome",new Class<?>[]{int.class},1);all();String key=middleAnchor();
            main(()->{
                View tile=activity.getWindow().getDecorView().findViewWithTag("directory-board:"+key);TextView star=(TextView)field(tile,"star");
                if(change==0)store.setCookie("ngaPassportUid=79802; ngaPassportCid=synthetic-directory-other");
                else if(change==1)store.setCookie("ngaPassportUid=79801; ngaPassportCid=synthetic-directory-refreshed");
                else if(change==2)store.setHost("ngabbs.com");else store.clearLogin();
                int generation=(Integer)field(activity,"generation");Object screen=field(activity,"current");long revision=store.favoriteForumRevision();Set<String> favorites=store.favoriteForums();
                String scope=cn.ngapure.app.ForumFavoritesCoordinator.scope(activity);int pending=store.pendingFavoriteChanges(scope).size();
                if(click==0)tile.performClick();else if(click==1)star.performClick();else star.performLongClick();
                check("staleSession"+change+"Action"+click+"RejectedBeforePostedRefresh",generation==(Integer)field(activity,"generation")&&screen==field(activity,"current")&&revision==store.favoriteForumRevision()&&favorites.equals(store.favoriteForums())&&pending==store.pendingFavoriteChanges(scope).size());return null;
            });waitForIdleSync();
        }
        main(()->{store.setHost("bbs.nga.cn");store.setCookie("ngaPassportUid=79801; ngaPassportCid=synthetic-directory-empty");return null;});waitForIdleSync();
        stage="emptyFavoriteColdDirectory";String scope=cn.ngapure.app.ForumFavoritesCoordinator.scope(activity);store.activateFavoriteScope(scope);
        // Start a fresh synthetic account whose remote projection is empty.
        main(()->{store.setCookie("ngaPassportUid=79803; ngaPassportCid=synthetic-directory-empty-fresh");return null;});waitForIdleSync();
        String emptyScope=cn.ngapure.app.ForumFavoritesCoordinator.scope(activity);store.activateFavoriteScope(emptyScope);store.mergeFavoriteForums(emptyScope,java.util.Collections.emptyMap());
        invoke("navigateHome",new Class<?>[]{int.class},3);invoke("openFavoriteBoards",new Class<?>[0]);
        for(int i=0;i<80;i++){if(main(()->method("directoryIdentity").invoke(activity).equals(field(activity,"communityDataIdentity"))))break;SystemClock.sleep(25);}
        check("emptyFavoriteDirectorySettlesWithoutWaitingForGoneList",main(()->method("directoryIdentity").invoke(activity).equals(field(activity,"communityDataIdentity"))&&((ListView)field(activity,"communityList")).getAdapter().getCount()==0&&hasText(activity.getWindow().getDecorView(),"还没有收藏版块")));
    }
    private void blockHttp(){URL.setURLStreamHandlerFactory(protocol->("https".equals(protocol)||"http".equals(protocol))?new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{BLOCKED_HTTP.incrementAndGet();String query=url.getQuery()==null?"":url.getQuery();if("/thread.php".equals(url.getPath())&&(query.contains("fid=")||query.contains("stid=")))FORUM_ATTEMPTS.incrementAndGet();if(query.contains("__lib=search")||query.contains("searchpost")||"/search.php".equals(url.getPath()))SEARCH_ATTEMPTS.incrementAndGet();throw new IOException("LOCAL_DIRECTORY_FIXTURE_NETWORK_DISABLED");}}:null);}
    @Override public void onStart(){Application app=null;try{
        if(!authorized||android.os.Build.VERSION.SDK_INT<29||!PACKAGE.equals(getTargetContext().getPackageName())||getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("OWN_UID_NO_INTERNET_API29_SYNTHETIC_DIRECTORY_REQUIRED");
        blockHttp();NgaStore store=new NgaStore(getTargetContext());store.setAutoCheckIn(false);store.setMotionEnabled(false);store.setTheme("light");store.setCookie("ngaPassportUid=79801; ngaPassportCid=synthetic-directory-a");getTargetContext().getSharedPreferences("nga_unread_notifications",0).edit().putBoolean("enabled",false).commit();getTargetContext().getSharedPreferences("nga_board_directory_ui",0).edit().putBoolean("grid",true).commit();
        app=(Application)getTargetContext().getApplicationContext();app.registerActivityLifecycleCallbacks(lifecycle);activity=(MainActivity)startActivitySync(new Intent().setClassName(PACKAGE,MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
        catalogChecks();reflowChecks();cancellationChecks();returnAndRecreateChecks();starChecks(store);renderSnapshotChecks(store);staleSessionChecks(store);
        report.put("completed",true).put("passed",true).put("assertions",assertions).put("actualMainRecreations",recreations);
    }catch(Exception|Error failure){try{report.put("completed",false).put("passed",false).put("failureStage",stage).put("failureType",failure.getClass().getSimpleName()).put("failureMessage",safeFailureMessage(failure)).put("assertions",assertions);if(failure instanceof AssertionError)report.put("failedAssertion",failure.getMessage());report.put("failureLayout",layoutDiagnostics());}catch(Exception ignored){}
    }finally{try{if(activity!=null&&!activity.isDestroyed())main(()->{activity.finish();return null;});if(app!=null)app.unregisterActivityLifecycleCallbacks(lifecycle);report.put("ownUidOnly",true).put("internetPermission",false).put("realNetworkRequests",0).put("blockedHttpConnectionAttempts",BLOCKED_HTTP.get()).put("realClientStarted",false).put("realAccountAccessed",false).put("syntheticFavoriteToggleCount",syntheticFavoriteTaps).put("realCommunityWrites",0).put("localActivityDensityOverride",true).put("actualRootMeasuredWidthChanges",true).put("globalDisplaySettingsChanged",false).put("physicalFoldPoseTested",false).put("privateInputsExported",false);}catch(Exception ignored){}Bundle result=new Bundle();result.putString("safe_board_directory_report",report.toString());finish(Activity.RESULT_OK,result);}}
}
