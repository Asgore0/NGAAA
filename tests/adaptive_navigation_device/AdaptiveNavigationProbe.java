package cn.ngapure.adaptivenavprobe;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import cn.ngapure.app.IconView;
import cn.ngapure.app.MainActivity;
import cn.ngapure.app.NativeCommunity;
import cn.ngapure.app.NativeCommunityView;
import cn.ngapure.app.NativeFollowingView;
import cn.ngapure.app.NgaStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact complete product classes in an independent, offline UID; only synthetic local view state. */
@SuppressWarnings("deprecation")
public final class AdaptiveNavigationProbe extends Instrumentation {
    private static final String PACKAGE="cn.ngapure.adaptivenavprobe";
    private final AtomicInteger blocked=new AtomicInteger();
    private final JSONArray cases=new JSONArray();
    private final ArrayList<NativeCommunityView> communities=new ArrayList<>();
    private final ArrayList<NativeFollowingView> following=new ArrayList<>();
    private MainActivity activity;private float desiredFont=1.3f;private boolean authorized,fixed;private int assertions,issues;private String stage="guard";
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticAdaptiveNavigationOnly"));fixed=args!=null&&"true".equals(args.getString("expectFixed"));start();}
    @Override public Activity newActivity(ClassLoader loader,String name,Intent intent)throws InstantiationException,IllegalAccessException,ClassNotFoundException{
        Activity value=super.newActivity(loader,name,intent);if(value instanceof MainActivity){Configuration config=new Configuration();config.densityDpi=160;config.fontScale=desiredFont;value.applyOverrideConfiguration(config);}return value;
    }
    private static Object get(Object value,String name)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    private static void set(Object value,String name,Object data)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);f.set(value,data);}
    private static Object call(Object value,String name,Class<?>[] types,Object... data)throws Exception{Method m=value.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(value,data);}
    private <T>T main(Callable<T> work)throws Exception{FutureTask<T> task=new FutureTask<>(work);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private void check(String label,boolean result)throws Exception{assertions++;if(!result)throw new AssertionError(label);}
    private void invariant(JSONObject row,String label,boolean result)throws Exception{row.put(label,result);if(!result)issues++;if(fixed)assertions++;}
    private Object screen(String kind,String id,String title,int page)throws Exception{Class<?> c=Class.forName("cn.ngapure.app.MainActivity$Screen");Constructor<?> constructor=c.getDeclaredConstructor(String.class,String.class,String.class,int.class);constructor.setAccessible(true);return constructor.newInstance(kind,id,title,page);}
    private void route(String kind,String id,String title,int page,int width)throws Exception{
        main(()->{Object value=screen(kind,id,title,page);call(activity,"go",new Class<?>[]{value.getClass(),boolean.class},value,false);constrain(width);return null;});settle();
    }
    private void constrain(int width)throws Exception{View root=(View)get(activity,"adaptiveLayout");ViewGroup.LayoutParams p=root.getLayoutParams();if(p.width!=width){p.width=width;root.setLayoutParams(p);}root.requestLayout();}
    private void settle()throws Exception{waitForIdleSync();SystemClock.sleep(130);waitForIdleSync();}
    private View decor(){return activity.getWindow().getDecorView();}
    private static View description(View root,String value){if(value.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View found=description(group.getChildAt(i),value);if(found!=null)return found;}}return null;}
    private static TextView text(View root,String value){if(root instanceof TextView&&value.contentEquals(((TextView)root).getText()))return (TextView)root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){TextView found=text(group.getChildAt(i),value);if(found!=null)return found;}}return null;}
    private static boolean textFits(TextView value){if(value==null||value.getLayout()==null)return false;int last=value.getLayout().getLineCount()-1;return last>=0&&value.getLayout().getLineBottom(last)<=value.getHeight()-value.getCompoundPaddingTop()-value.getCompoundPaddingBottom();}
    private static int blankGlyphFocuses(View root){int count=root instanceof IconView&&!root.hasOnClickListeners()&&(root.isFocusable()||root.getImportantForAccessibility()==View.IMPORTANT_FOR_ACCESSIBILITY_YES)?1:0;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++)count+=blankGlyphFocuses(group.getChildAt(i));}return count;}
    private static JSONObject metrics(TextView value)throws Exception{JSONObject row=new JSONObject();if(value==null)return row.put("missing",true);Rect rect=new Rect();value.getGlobalVisibleRect(rect);row.put("text",value.getText()).put("width",value.getWidth()).put("height",value.getHeight()).put("contentWidth",Math.max(0,value.getWidth()-value.getCompoundPaddingLeft()-value.getCompoundPaddingRight())).put("visible",value.isShown()&&!rect.isEmpty()).put("bounds",rect.toShortString()).put("fitsVertically",textFits(value));if(value.getLayout()!=null){int count=value.getLayout().getLineCount();row.put("lineCount",count);if(count>0)row.put("lastLineBottom",value.getLayout().getLineBottom(count-1));}return row;}
    private static JSONObject targetMetrics(View value)throws Exception{JSONObject row=new JSONObject();if(value==null)return row.put("missing",true);Rect rect=new Rect();boolean visible=value.getGlobalVisibleRect(rect);return row.put("description",String.valueOf(value.getContentDescription())).put("width",value.getWidth()).put("height",value.getHeight()).put("visibleWidth",visible?rect.width():0).put("visibleHeight",visible?rect.height():0).put("bounds",rect.toShortString());}
    private static boolean targetFits(View value){if(value==null||!value.isShown()||value.getWidth()<48||value.getHeight()<48)return false;Rect rect=new Rect();return value.getGlobalVisibleRect(rect)&&rect.width()>=value.getWidth()&&rect.height()>=value.getHeight();}
    private void screenshot(String name)throws Exception{Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)throw new IOException("SCREENSHOT_UNAVAILABLE");File folder=new File(getTargetContext().getFilesDir(),fixed?"fixed":"baseline");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("SCREENSHOT_DIRECTORY_UNAVAILABLE");try(FileOutputStream stream=new FileOutputStream(new File(folder,name+".png"))){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))throw new IOException("SCREENSHOT_ENCODE_FAILED");}finally{bitmap.recycle();}}
    private void titleInvariant(JSONObject row)throws Exception{TextView title=main(()->(TextView)decor().findViewWithTag("screen-title"));row.put("toolbarTitle",main(()->metrics(title)));boolean readable=main(()->title!=null&&title.isShown()&&title.getWidth()-title.getCompoundPaddingLeft()-title.getCompoundPaddingRight()>=Math.min(title.getPaint().measureText(title.getText().toString()),title.getPaint().measureText("萌萌…")));invariant(row,"toolbarTitleHasReadableWidth",readable);}
    private void home(int width,String suffix)throws Exception{
        stage="home_"+suffix;main(()->{set(activity,"mainTab",0);set(activity,"homeTab",0);return null;});route("home","","NGA",1,width);JSONObject row=new JSONObject().put("case",stage).put("widthDp",width).put("fontScale",desiredFont);
        TextView title=main(()->text(description(decor(),"打开收藏版块"),"收藏版块"));row.put("favoriteTitle",main(()->metrics(title)));invariant(row,"favoriteTitleFitsAllLines",main(()->textFits(title)));titleInvariant(row);row.put("blankDecorationFocuses",main(()->blankGlyphFocuses(decor())));invariant(row,"decorationsNotFocusable",main(()->blankGlyphFocuses(decor())==0));screenshot(stage);cases.put(row);
    }
    private void forum(int width,String suffix)throws Exception{
        stage="forum_"+suffix;route("forum","414","萌萌宠物",2,width);JSONObject row=new JSONObject().put("case",stage).put("widthDp",width).put("fontScale",desiredFont);titleInvariant(row);
        View paging=main(()->{View value=decor().findViewWithTag("forum-paging");if(value!=null)return value;TextView jump=text(decor(),"跳页 2");return jump==null?null:(View)jump.getParent();});
        boolean single=main(()->{if(!(paging instanceof ViewGroup))return false;ViewGroup group=(ViewGroup)paging;for(int i=0;i<group.getChildCount();i++){View v=group.getChildAt(i);if(v.isShown()&&v instanceof TextView&&(((TextView)v).getLineCount()>1||!textFits((TextView)v)))return false;}return true;});row.put("pagingHeight",main(()->paging==null?-1:paging.getHeight()));invariant(row,"forumPagingStaysOneRow",single);screenshot(stage);cases.put(row);
    }
    private void drawer(int width,String suffix)throws Exception{
        main(()->{set(activity,"mainTab",0);return null;});route("home","","NGA",1,width);main(()->{call(activity,"drawer",new Class<?>[0]);return null;});settle();stage="drawer_"+suffix;Dialog dialog=main(()->(Dialog)get(activity,"navigationDrawer"));View root=main(()->dialog.getWindow().getDecorView());JSONObject row=new JSONObject().put("case",stage).put("widthDp",width).put("fontScale",desiredFont);TextView label=main(()->text(root,"本地收藏"));row.put("menuTitle",main(()->metrics(label))).put("blankDecorationFocuses",main(()->blankGlyphFocuses(root)));invariant(row,"decorationsNotFocusable",main(()->blankGlyphFocuses(root)==0));invariant(row,"menuTitleFitsAllLines",main(()->textFits(label)));screenshot(stage);cases.put(row);main(()->{dialog.dismiss();return null;});
    }
    private void communityPager(int width,String suffix)throws Exception{
        route("home","","NGA",1,width);stage="community_"+suffix;NativeCommunityView view=main(()->{NativeCommunityView value=new NativeCommunityView(activity,new NgaStore(activity),"serverfav","1",2,new NativeCommunityView.Listener(){public void navigate(String k,String i,String t,int p){}public void topic(String t,String n){}public void user(String u){}public void login(){}});set(value,"generation",((Integer)get(value,"generation"))+1);set(value,"pendingUiTasks",0);LinearLayout content=(LinearLayout)get(value,"content");content.removeAllViews();call(value,"pager",new Class<?>[]{LinearLayout.class,int.class,boolean.class,int.class,String.class,String.class,int.class},content,2,true,3,"serverfav","1",5);ViewGroup body=(ViewGroup)get(activity,"body");body.removeAllViews();body.addView(value,new ViewGroup.LayoutParams(-1,-1));return value;});communities.add(view);settle();JSONObject row=new JSONObject().put("case",stage).put("widthDp",width).put("fontScale",desiredFont);TextView previous=main(()->text(view,"上一页"));TextView number=main(()->text(view,"第 2 / 5 页"));TextView next=main(()->text(view,"下一页"));row.put("previous",main(()->metrics(previous))).put("number",main(()->metrics(number))).put("next",main(()->metrics(next)));invariant(row,"communityPagerFitsAllLines",main(()->textFits(previous)&&textFits(number)&&textFits(next)));screenshot(stage);cases.put(row);main(()->{view.close();return null;});
    }
    private void followingPager(int width,String suffix)throws Exception{
        route("home","","NGA",1,width);stage="following_"+suffix;
        NativeFollowingView view=main(()->{NativeFollowingView value=new NativeFollowingView(activity,new NgaStore(activity),2,new NativeFollowingView.Listener(){public void page(int p){}public void user(String uid){}public void topic(String tid,String pid,String title){}public void login(){}});set(value,"loadToken",((Integer)get(value,"loadToken"))+1);set(value,"loadingPage",false);NativeCommunity.FollowActivityPage page=new NativeCommunity.FollowActivityPage();page.page=2;page.totalPages=5;page.hasNext=true;page.nextPage=3;call(value,"render",new Class<?>[]{NativeCommunity.FollowActivityPage.class},page);ViewGroup body=(ViewGroup)get(activity,"body");body.removeAllViews();body.addView(value,new ViewGroup.LayoutParams(-1,-1));return value;});following.add(view);settle();JSONObject row=new JSONObject().put("case",stage).put("widthDp",width).put("fontScale",desiredFont);TextView previous=main(()->text(view,"上一页")),next=main(()->text(view,"下一页"));row.put("previous",main(()->metrics(previous))).put("next",main(()->metrics(next)));invariant(row,"followingPagerFitsAllLines",main(()->textFits(previous)&&textFits(next)));screenshot(stage);cases.put(row);main(()->{view.close();return null;});
    }
    private void directory(int width,String suffix)throws Exception{
        stage="directory_"+suffix;main(()->{set(activity,"mainTab",1);set(activity,"communityCategory","全部版块");return null;});route("home","","NGA",1,width);JSONObject row=new JSONObject().put("case",stage).put("widthDp",width).put("fontScale",desiredFont);View category=main(()->description(decor(),"选择版块分类"));TextView label=main(()->text(category,"全部分类"));View layout=main(()->description(decor(),"排列方式")),more=main(()->description(decor(),"目录更多操作"));row.put("category",main(()->targetMetrics(category))).put("label",main(()->metrics(label))).put("layout",main(()->targetMetrics(layout))).put("more",main(()->targetMetrics(more)));invariant(row,"directoryCategoryReadable",main(()->targetFits(category)&&label!=null&&label.getWidth()-label.getCompoundPaddingLeft()-label.getCompoundPaddingRight()>=label.getPaint().measureText("全部…")));invariant(row,"directoryToolsReachable",main(()->targetFits(layout)&&targetFits(more)));screenshot(stage);cases.put(row);
    }
    private void reader(int width,String suffix)throws Exception{
        stage="reader_"+suffix;route("thread","987654321","离线合成阅读器",2,width);main(()->{Object current=get(activity,"current");set(current,"totalPages",12);TextView page=(TextView)description(decor(),"跳转页码");page.setText("2 / 12");page.setEnabled(true);return null;});settle();JSONObject row=new JSONObject().put("case",stage).put("widthDp",width).put("fontScale",desiredFont);JSONArray controls=new JSONArray();boolean primary=true,secondary=true;String[] names={"上一页","跳转页码","下一页","收藏帖子","回复帖子"};for(int i=0;i<names.length;i++){final String name=names[i];View value=main(()->description(decor(),name));controls.put(main(()->targetMetrics(value)));if(i<3)primary&=main(()->targetFits(value));else secondary&=main(()->targetFits(value));}row.put("controls",controls);TextView page=main(()->(TextView)description(decor(),"跳转页码"));row.put("page",main(()->metrics(page)));invariant(row,"readerPrimaryControlsReachable",primary);invariant(row,"readerPageFitsAllLines",main(()->textFits(page)&&page.getWidth()-page.getCompoundPaddingLeft()-page.getCompoundPaddingRight()>=page.getPaint().measureText("2 / 12")));screenshot(stage);
        if(!secondary){View more=main(()->description(decor(),"更多操作"));boolean hasMore=main(()->targetFits(more));main(()->{call(activity,"menu",new Class<?>[0]);return null;});settle();Dialog dialog=main(()->{for(Object value:(Iterable<?>)get(activity,"glassDialogs"))if(value instanceof Dialog&&((Dialog)value).isShowing())return (Dialog)value;return null;});if(dialog!=null){View menu=main(()->dialog.getWindow().getDecorView());View save=main(()->description(menu,"收藏帖子 · 本地与云端")),reply=main(()->description(menu,"回复帖子"));row.put("menuSave",main(()->targetMetrics(save))).put("menuReply",main(()->targetMetrics(reply)));secondary=hasMore&&main(()->targetFits(save)&&targetFits(reply));screenshot("reader_menu_"+suffix);main(()->{dialog.dismiss();return null;});settle();}else secondary=false;}
        invariant(row,"readerSecondaryActionsReachable",secondary);cases.put(row);
    }
    private void launch(float font)throws Exception{desiredFont=font;activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));settle();}
    @Override public void onStart(){boolean passed=false;String failure="NONE";try{
        check("ExplicitSyntheticOnlyAuthorization",authorized);String[] permissions=getTargetContext().getPackageManager().getPackageInfo(PACKAGE,PackageManager.GET_PERMISSIONS).requestedPermissions;if(permissions!=null)for(String p:permissions)check("NoInternetPermission",!"android.permission.INTERNET".equals(p));check("OwnUid",android.os.Process.myUid()==getTargetContext().getApplicationInfo().uid);
        URL.setURLStreamHandlerFactory(protocol->("http".equals(protocol)||"https".equals(protocol))?new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{blocked.incrementAndGet();throw new IOException("SYNTHETIC_OFFLINE_NAVIGATION");}}:null);
        for(float font:new float[]{1f,1.3f,2f}){launch(font);int available=main(()->activity.getResources().getDisplayMetrics().widthPixels);for(int width:new int[]{220,390,720}){if(width>available)continue;String suffix=width+"_font"+String.valueOf(font).replace('.','_');home(width,suffix);forum(width,suffix);drawer(width,suffix);communityPager(width,suffix);followingPager(width,suffix);directory(width,suffix);reader(width,suffix);}main(()->{activity.finish();return null;});settle();activity=null;}
        if(fixed)check("AllAdaptiveNavigationInvariants",issues==0);else check("BaselineReproducesMeasuredNavigationDefect",issues>0);passed=true;
    }catch(Throwable error){Throwable cause=error;for(int i=0;i<8&&cause.getCause()!=null;i++)cause=cause.getCause();failure=stage+":"+cause.getClass().getSimpleName()+":"+String.valueOf(cause.getMessage());}
    finally{for(NativeCommunityView view:communities)try{main(()->{view.close();return null;});}catch(Exception ignored){}for(NativeFollowingView view:following)try{main(()->{view.close();return null;});}catch(Exception ignored){}if(activity!=null)try{main(()->{activity.finish();return null;});}catch(Exception ignored){}JSONObject report=new JSONObject();try{report.put("purpose","EXACT_PRODUCT_ADAPTIVE_NAVIGATION_MEASUREMENT").put("passed",passed).put("failure",failure).put("expectFixed",fixed).put("assertions",assertions).put("observedCandidateFailures",issues).put("ownUid",true).put("internetPermission",false).put("realNetworkRequests",0).put("blockedOfflineAttempts",blocked.get()).put("realCommunityWrites",0).put("productionClassesRewritten",false).put("cases",cases);File output=new File(getTargetContext().getFilesDir(),fixed?"adaptive-fixed.json":"adaptive-baseline.json");try(FileOutputStream stream=new FileOutputStream(output)){stream.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}}catch(Exception ignored){}Bundle result=new Bundle();result.putString("adaptive_navigation_report",report.toString());finish(passed?0:1,result);}}
}
