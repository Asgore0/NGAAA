package cn.ngapure.forumlistprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.WrapperListAdapter;
import cn.ngapure.app.ForumRoute;
import cn.ngapure.app.MainActivity;
import cn.ngapure.app.NgaApi;
import cn.ngapure.app.TopicPreviewImages;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Own-UID Android layouts from the complete immutable production jar; never accesses the real app. */
@SuppressWarnings("deprecation")
public final class ForumListProbe extends Instrumentation {
    private static final String PACKAGE="cn.ngapure.forumlistprobe";
    private MainActivity activity;private float font=1;private boolean authorized;
    private int assertions,issues;private String stage="guard";
    private final JSONArray cases=new JSONArray();private final AtomicInteger blocked=new AtomicInteger();
    private final Class<?> screenClass;
    public ForumListProbe(){try{screenClass=Class.forName("cn.ngapure.app.MainActivity$Screen");}catch(ClassNotFoundException error){throw new IllegalStateException(error);}}
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticForumListOnly"));start();}
    @Override public Activity newActivity(ClassLoader loader,String name,Intent intent)throws InstantiationException,IllegalAccessException,ClassNotFoundException{Activity value=super.newActivity(loader,name,intent);if(value instanceof MainActivity){Configuration c=new Configuration();c.densityDpi=160;c.fontScale=font;value.applyOverrideConfiguration(c);}return value;}
    private static Field field(Object owner,String name)throws Exception{Class<?> type=owner.getClass();while(type!=null){try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException absent){type=type.getSuperclass();}}throw new NoSuchFieldException(name);}
    private static Object get(Object owner,String name)throws Exception{return field(owner,name).get(owner);}
    private static void set(Object owner,String name,Object value)throws Exception{field(owner,name).set(owner,value);}
    private static Object invoke(Object owner,String name,Class<?>[] types,Object...args)throws Exception{Method method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(owner,args);}
    private <T>T main(Callable<T> work)throws Exception{FutureTask<T> task=new FutureTask<>(work);runOnMainSync(task);return task.get(15,TimeUnit.SECONDS);}
    private void check(JSONObject row,String label,boolean value)throws Exception{assertions++;row.put(label,value);if(!value)issues++;}
    private Object screen()throws Exception{Constructor<?> c=screenClass.getDeclaredConstructor(String.class,String.class,String.class,int.class);c.setAccessible(true);return c.newInstance("forum","-7","Fixture board",1);}
    private NgaApi.ForumPage page(int count,boolean header){NgaApi.ForumPage page=new NgaApi.ForumPage();page.fid="-7";page.key="-7";page.name="Fixture board";for(int i=0;i<count;i++){NgaApi.Topic t=new NgaApi.Topic();t.tid=String.valueOf(900000+i);t.title="主题 "+i+" — "+(i%3==0?"长标题用于验证换行、首屏边缘和不同字体下完整可读内容，末尾仍应存在":"可见内容保持一致");t.author="Fixture";t.authorId="100";t.time="10-25 12:34";t.replies=i;page.topics.add(t);}if(header)for(int i=0;i<4;i++){NgaApi.Forum f=new NgaApi.Forum();f.key=f.fid=String.valueOf(500+i);f.name="子版块 "+i;f.category="版块";f.parentFid="-7";page.subForums.add(f);}return page;}
    private static void layout(View view,int width,int height){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);}
    private static List<String> visibleText(ViewGroup root){ArrayList<String> values=new ArrayList<>();collect(root,root,values);return values;}
    private static void collect(ViewGroup root,View view,List<String> values){if(view.getVisibility()!=View.VISIBLE)return;if(view instanceof TextView){Rect r=new Rect();view.getDrawingRect(r);root.offsetDescendantRectToMyCoords(view,r);if(r.right>0&&r.left<root.getWidth()&&r.bottom>0&&r.top<root.getHeight())values.add(((TextView)view).getText()+"@"+r.flattenToString());}if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collect(root,group.getChildAt(i),values);}}
    private static int countTagged(View view,String prefix){int count=view.getTag() instanceof String&&((String)view.getTag()).startsWith(prefix)?1:0;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)count+=countTagged(((ViewGroup)view).getChildAt(i),prefix);return count;}
    private static TextView findText(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView found=findText(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}return null;}
    private FrameLayout prepare(Object owner,NgaApi.ForumPage page,boolean previews)throws Exception{
        invoke(activity,"closeNativePage",new Class<?>[0]);Object reads=get(activity,"pageReads");invoke(reads,"cancelAll",new Class<?>[0]);set(activity,"generation",((Integer)get(activity,"generation"))+1);set(activity,"current",owner);set(activity,"userFilter",null);
        Object store=get(activity,"store");invoke(store,"setTopicPreviewsEnabled",new Class<?>[]{boolean.class},previews);
        String scope=(String)invoke(activity,"contentScope",new Class<?>[0]);set(activity,"activeContentScope",scope);
        FrameLayout target=new FrameLayout(activity);set(activity,"body",target);
        invoke(activity,"renderForumTopics",new Class<?>[]{FrameLayout.class,screenClass,ForumRoute.class,String.class,NgaApi.ForumPage.class,String.class},target,owner,ForumRoute.fromKey("-7"),scope,page,"");return target;
    }
    private final class CountingAdapter extends BaseAdapter{
        final ListAdapter delegate;int binds,reusedConversions,reboundIdentities;final IdentityHashMap<View,Long> identities=new IdentityHashMap<>();final Set<View> created=Collections.newSetFromMap(new IdentityHashMap<View,Boolean>());
        CountingAdapter(ListAdapter delegate){this.delegate=delegate;}
        public int getCount(){return delegate.getCount();}public Object getItem(int p){return delegate.getItem(p);}public long getItemId(int p){return delegate.getItemId(p);}public boolean hasStableIds(){return delegate.hasStableIds();}
        public View getView(int p,View reuse,ViewGroup parent){binds++;View view=delegate.getView(p,reuse,parent);long id=getItemId(p);Long previous=identities.put(view,id);if(reuse!=null&&view==reuse){reusedConversions++;if(previous!=null&&previous.longValue()!=id)reboundIdentities++;}created.add(view);return view;}
    }
    private void layoutCase(int width,int count,boolean header)throws Exception{
        stage="layout_"+width+"_font"+font+"_rows"+count+"_header"+header;
        JSONObject row=main(()->{
            Object owner=screen();NgaApi.ForumPage page=page(count,header);FrameLayout frame=prepare(owner,page,false);ListView list=(ListView)get(activity,"forumList");
            ListAdapter original=list.getAdapter();if(original instanceof WrapperListAdapter)original=((WrapperListAdapter)original).getWrappedAdapter();CountingAdapter measured=new CountingAdapter(original);list.setAdapter(measured);
            long begin=System.nanoTime();layout(frame,width,580);long layoutNs=System.nanoTime()-begin;List<String> actual=visibleText(frame);
            ScrollView reference=new ScrollView(activity);reference.setFillViewport(true);reference.setVerticalScrollBarEnabled(false);LinearLayout eager=new LinearLayout(activity);eager.setOrientation(LinearLayout.VERTICAL);eager.setPadding(0,0,0,20);reference.addView(eager);
            if(header)invoke(activity,"forumHeader",new Class<?>[]{LinearLayout.class,NgaApi.ForumPage.class,ForumRoute.class,String.class},eager,page,ForumRoute.fromKey("-7"),"");
            for(NgaApi.Topic topic:page.topics)invoke(activity,"topicRow",new Class<?>[]{LinearLayout.class,NgaApi.Topic.class},eager,topic);
            layout(reference,width,580);List<String> expected=visibleText(reference);JSONObject r=new JSONObject().put("case",stage).put("pageSize",count).put("widthDp",width).put("fontScale",font).put("header",header).put("visibleText",new JSONArray(actual)).put("referenceText",new JSONArray(expected)).put("initialProductionRowViews",measured.created.size()).put("initialGetViewCalls",measured.binds).put("diagnosticDetachedLayoutNs",layoutNs);
            check(r,"allLogicalTopicsSubmittedAtOnce",list.getCount()-list.getHeaderViewsCount()==count);
            check(r,"entireVisibleTextAndGeometryEqualLegacy",actual.equals(expected));
            check(r,"onlyViewportRowsConstructed",measured.created.size()<count&&measured.created.size()<=list.getChildCount()+2);
            View last=list.getChildAt(list.getChildCount()-1);check(r,"viewportFullyCovered",last!=null&&last.getBottom()>=580-list.getPaddingBottom());
            list.setSelectionFromTop(list.getHeaderViewsCount()+count/2,-23);layout(frame,width,580);list.setSelectionFromTop(list.getHeaderViewsCount()+count-4,-10);layout(frame,width,580);list.setSelectionFromTop(0,0);layout(frame,width,580);
            check(r,"rowViewsReusedAcrossDistantScrolls",measured.reusedConversions>0&&measured.reboundIdentities>0&&measured.created.size()<measured.binds);r.put("convertViewsActuallyReused",measured.reusedConversions).put("reusedViewsBoundToDifferentTopics",measured.reboundIdentities).put("totalGetViewCalls",measured.binds);check(r,"returnToTopTextMatches",actual.equals(visibleText(frame)));r.put("totalDistinctRowViewsAfterScroll",measured.created.size());
            if(header){TextView expand=findText(frame,"展开全部 4 个");if(expand!=null)expand.performClick();layout(frame,width,580);View expandedLast=list.getChildAt(list.getChildCount()-1);check(r,"headerExpandsAllFourWithoutMissingViewport",countTagged(frame,"subforum:")==4&&expandedLast!=null&&expandedLast.getBottom()>=580-list.getPaddingBottom()&&list.getCount()-list.getHeaderViewsCount()==count);TextView collapse=findText(frame,"收起子版块");if(collapse!=null)collapse.performClick();layout(frame,width,580);check(r,"headerCollapsesToOriginalGeometry",countTagged(frame,"subforum:")==2&&actual.equals(visibleText(frame)));}
            return r;
        });cases.put(row);
    }
    private void anchorCase()throws Exception{
        stage="anchor_restore_scope_and_reflow";JSONObject row=main(()->{
            Object owner=screen();NgaApi.ForumPage page=page(100,false);FrameLayout frame=prepare(owner,page,false);ListView list=(ListView)get(activity,"forumList");layout(frame,390,580);list.setSelectionFromTop(42,-23);layout(frame,390,580);invoke(activity,"captureForumPosition",new Class<?>[0]);Object anchor=get(owner,"forumAnchor");JSONObject r=new JSONObject().put("case",stage);
            check(r,"captureTopicAndOffset",anchor!=null&&"900042".equals(get(anchor,"topicId"))&&((Integer)get(anchor,"top"))==-23);
            Bundle saved=new Bundle();invoke(activity,"saveNavigation",new Class<?>[]{Bundle.class},saved);Bundle navigation=saved.getBundle("ngapure_navigation_v1");check(r,"bundleContainsBoundedAnchor",navigation!=null&&navigation.getStringArray("current_forum_anchor")!=null);
            page.topics.remove(0);FrameLayout fresh=prepare(owner,page,false);ListView restored=(ListView)get(activity,"forumList");layout(fresh,390,580);check(r,"sameTopicSurvivesFreshReorderedData",restored.getFirstVisiblePosition()==41&&restored.getChildAt(0).getTop()==-23);
            layout(fresh,720,580);check(r,"widthChangeKeepsItemAndOffset",restored.getFirstVisiblePosition()==41&&restored.getChildAt(0).getTop()==-23);
            Object other=screen();set(other,"forumAnchor",anchor);Object store=get(activity,"store");invoke(store,"setHost",new Class<?>[]{String.class},"ngabbs.com");FrameLayout isolated=prepare(other,page,false);ListView different=(ListView)get(activity,"forumList");layout(isolated,390,580);check(r,"differentScopeCannotRestoreAnchor",different.getFirstVisiblePosition()==0&&different.getChildAt(0).getTop()==0);invoke(store,"setHost",new Class<?>[]{String.class},"bbs.nga.cn");
            return r;
        });cases.put(row);
    }
    private void previewCase()throws Exception{
        final Object[] expectedOwner=new Object[1];final FrameLayout[] attached=new FrameLayout[1];stage="preview_recycle_and_gallery";JSONObject row=main(()->{
            Object owner=screen();NgaApi.ForumPage page=page(100,false);String scope=(String)invoke(activity,"contentScope",new Class<?>[0]);TopicPreviewImages previews=(TopicPreviewImages)get(activity,"topicPreviewImages");
            previews.seed(scope,"900000",java.util.Arrays.asList("https://img.nga.178.com/attachments/mon_202610/25/test_a.png","https://img.nga.178.com/attachments/mon_202610/25/test_b.png"));
            FrameLayout frame=prepare(owner,page,true);ListView list=(ListView)get(activity,"forumList");layout(frame,390,580);JSONObject r=new JSONObject().put("case",stage);View image=frame.findViewWithTag("topic-preview-image:900000:0");check(r,"knownOriginalPostGalleryBound",image!=null);
            View first=list.getChildAt(0);Object oldPreview=first.findViewWithTag("topic-preview:900000");list.setSelectionFromTop(50,-17);layout(frame,390,580);check(r,"recycledPreviewClosed",oldPreview!=null&&Boolean.TRUE.equals(get(oldPreview,"closed")));
            check(r,"noOldImageOnReboundRows",frame.findViewWithTag("topic-preview-image:900000:0")==null);int pos=list.getFirstVisiblePosition(),top=list.getChildAt(0).getTop();
            Object visiblePreview=list.getChildAt(0).findViewWithTag("topic-preview:"+(900000+pos));
            Constructor<?> result=TopicPreviewImages.Result.class.getDeclaredConstructor(List.class,boolean.class);result.setAccessible(true);Object metadata=result.newInstance(Collections.singletonList("https://img.nga.178.com/attachments/mon_202610/25/test_c.png"),false);
            invoke(visiblePreview,"acceptMetadata",new Class<?>[]{TopicPreviewImages.Result.class},metadata);layout(frame,390,580);check(r,"lateImageHeightKeepsFirstItemAndTop",list.getFirstVisiblePosition()==pos&&list.getChildAt(0).getTop()==top);
            List<?> pictures=(List<?>)get(visiblePreview,"pictures");ImageView picture=(ImageView)pictures.get(0);picture.setImageBitmap(Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888));Object pixels=picture.getDrawable();
            for(int i=0;i<5;i++){((View)visiblePreview).requestLayout();layout(frame,390,580);}
            check(r,"requestLayoutRetainsPreviewAndPixels",list.getChildAt(0).findViewWithTag("topic-preview:"+(900000+pos))==visiblePreview&&picture.getDrawable()==pixels&&!Boolean.TRUE.equals(get(visiblePreview,"closed")));
            list.setSelectionFromTop(0,0);layout(frame,390,580);View returnImage=frame.findViewWithTag("topic-preview-image:900000:0");check(r,"recycledRowRebindsCorrectImage",returnImage!=null);
            // Exercise the actual descendant touch target and real MainActivity gallery/back navigation.
            Object snapshots=get(activity,"forumSnapshots");check(r,"sameScreenBackSnapshotSeeded",Boolean.TRUE.equals(invoke(snapshots,"put",new Class<?>[]{Object.class,String.class,String.class,String.class,int.class,NgaApi.ForumPage.class,long.class},owner,scope,"-7","",1,page,SystemClock.elapsedRealtime())));
            activity.setContentView(frame);attached[0]=frame;expectedOwner[0]=owner;return r;
        });
        // Do not inject into a detached/manual layout. Let ViewRoot attach and lay out the real window.
        waitForIdleSync();main(()->{((ListView)get(activity,"forumList")).setSelectionFromTop(0,-19);return null;});waitForIdleSync();
        // Add before injection so an input failure retains its exact target/geometry diagnostics.
        cases.put(row);
        int[] hit=main(()->{
            View image=attached[0].findViewWithTag("topic-preview-image:900000:0");Rect global=new Rect(),local=new Rect();boolean target=image!=null&&image.isAttachedToWindow()&&image.hasWindowFocus()&&image.getGlobalVisibleRect(global)&&image.getLocalVisibleRect(local)&&local.width()>0&&local.height()>0;
            check(row,"galleryTouchTargetAttachedFocusedVisible",target);if(!target)throw new IllegalStateException("NO_ATTACHED_VISIBLE_GALLERY_TOUCH_TARGET");
            // Local visible pixels plus the actual screen origin avoid global/root-coordinate ambiguity.
            int[] imageOrigin=new int[2],rootOrigin=new int[2],windowOrigin=new int[2];image.getLocationOnScreen(imageOrigin);attached[0].getLocationOnScreen(rootOrigin);View decor=activity.getWindow().getDecorView();decor.getLocationOnScreen(windowOrigin);
            Rect visibleScreen=new Rect(local);visibleScreen.offset(imageOrigin[0],imageOrigin[1]);Rect rootScreen=new Rect(rootOrigin[0],rootOrigin[1],rootOrigin[0]+attached[0].getWidth(),rootOrigin[1]+attached[0].getHeight());Rect windowScreen=new Rect(windowOrigin[0],windowOrigin[1],windowOrigin[0]+decor.getWidth(),windowOrigin[1]+decor.getHeight());Rect displayFrame=new Rect();decor.getWindowVisibleDisplayFrame(displayFrame);
            Rect safe=new Rect(visibleScreen);boolean inside=safe.intersect(rootScreen)&&safe.intersect(windowScreen)&&safe.intersect(displayFrame)&&safe.width()>2&&safe.height()>2;
            row.put("touchGlobalVisibleBounds",global.flattenToString()).put("touchLocalVisibleBounds",local.flattenToString()).put("touchVisibleScreenBounds",visibleScreen.flattenToString()).put("touchRootScreenBounds",rootScreen.flattenToString()).put("touchWindowScreenBounds",windowScreen.flattenToString()).put("touchWindowVisibleDisplayFrame",displayFrame.flattenToString()).put("touchSafeScreenBounds",safe.flattenToString()).put("targetUid",getTargetContext().getApplicationInfo().uid).put("attachedRootWidth",attached[0].getWidth()).put("attachedRootHeight",attached[0].getHeight());
            check(row,"galleryTouchInsideVisibleOwnWindow",inside);if(!inside)throw new IllegalStateException("NO_VISIBLE_OWN_WINDOW_TOUCH_INTERSECTION");row.put("touchScreenX",safe.centerX()).put("touchScreenY",safe.centerY());return new int[]{safe.centerX(),safe.centerY()};
        });
        long downAt=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(downAt,downAt,MotionEvent.ACTION_DOWN,hit[0],hit[1],0);sendPointerSync(down);down.recycle();SystemClock.sleep(35);MotionEvent up=MotionEvent.obtain(downAt,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,hit[0],hit[1],0);sendPointerSync(up);up.recycle();
        // View's pressed-state cleanup may post the click after the current traversal.
        for(int i=0;i<20;i++){waitForIdleSync();if(main(()->"image".equals(get(get(activity,"current"),"kind"))))break;SystemClock.sleep(25);}
        main(()->{Object destination=get(activity,"current");row.put("screenAfterActualPointer",get(destination,"kind"));check(row,"imageTouchOpensGalleryNotThread","image".equals(get(destination,"kind"))&&get(destination,"gallery")!=null);activity.onBackPressed();return null;});waitForIdleSync();
        main(()->{ListView returned=(ListView)get(activity,"forumList");check(row,"galleryBackRestoresTopicOffset",get(activity,"current")==expectedOwner[0]&&returned!=null&&returned.getFirstVisiblePosition()==0&&returned.getChildAt(0).getTop()==-19);invoke(activity,"closeNativePage",new Class<?>[0]);check(row,"pageCloseReleasesAllRegisteredPreviews",((Set<?>)get(activity,"topicPreviews")).isEmpty());return null;});
    }
    @Override public void onStart(){boolean passed=false;String failure="NONE";try{
        if(!authorized||!PACKAGE.equals(getTargetContext().getPackageName())||getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("OWN_UID_NO_INTERNET_SYNTHETIC_FORUM_REQUIRED");
        URL.setURLStreamHandlerFactory(protocol->("http".equals(protocol)||"https".equals(protocol))?new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{blocked.incrementAndGet();throw new IOException("SYNTHETIC_FORUM_OFFLINE");}}:null);
        getTargetContext().getSharedPreferences("nga_local",0).edit().putBoolean("auto_check_in",false).apply();
        for(float scale:new float[]{1,2}){font=scale;activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();for(int width:new int[]{220,390,720})for(int count:new int[]{20,100,200})layoutCase(width,count,false);layoutCase(390,100,true);anchorCase();previewCase();main(()->{activity.finish();return null;});activity=null;waitForIdleSync();}passed=issues==0;
    }catch(Throwable error){Throwable cause=error;for(int i=0;i<10&&cause.getCause()!=null;i++)cause=cause.getCause();failure=stage+":"+cause.getClass().getSimpleName()+":"+String.valueOf(cause.getMessage());}
    finally{if(activity!=null)try{main(()->{activity.finish();return null;});}catch(Exception ignored){}JSONObject report=new JSONObject();try{try(InputStream input=getTargetContext().getAssets().open("binding.json");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int length;while((length=input.read(buffer))!=-1)bytes.write(buffer,0,length);report.put("binding",new JSONObject(new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8)));}report.put("passed",passed).put("failure",failure).put("assertions",assertions).put("observedFailures",issues).put("cases",cases).put("ownUid",true).put("internetPermission",false).put("blockedOfflineAttempts",blocked.get()).put("realNetworkRequests",0).put("realCommunityWrites",0).put("realAccountAccessed",false).put("productionClassesRewritten",false).put("limitations","Synthetic Android layout evidence, not real-account startup performance. No network or physical fold pose. Legacy exact topicRow reference, forwarding adapter counters, detached EXACTLY-sized layout.");try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),"forum-list-report.json"))){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}}catch(Exception ignored){}Bundle result=new Bundle();result.putString("safe_forum_list_report",report.toString());finish(passed?0:1,result);}}
}
