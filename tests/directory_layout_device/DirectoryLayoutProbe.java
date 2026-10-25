package cn.ngapure.directorylayoutprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import cn.ngapure.app.BoardDirectory;
import cn.ngapure.app.MainActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual DirectoryTile layout with the product's own column policy; no favorite clicks. */
@SuppressWarnings("deprecation")
public final class DirectoryLayoutProbe extends Instrumentation {
    private static final String PACKAGE="cn.ngapure.directorylayoutprobe";
    private final JSONArray cases=new JSONArray();
    private final AtomicInteger blocked=new AtomicInteger();
    private MainActivity activity;
    private boolean authorized;
    private float desiredFont;
    private int assertions,issues;
    private String stage="guard";
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticDirectoryLayoutOnly"));start();}
    @Override public Activity newActivity(ClassLoader loader,String name,Intent intent)throws InstantiationException,IllegalAccessException,ClassNotFoundException{
        Activity value=super.newActivity(loader,name,intent);if(value instanceof MainActivity){Configuration config=new Configuration();config.densityDpi=160;config.fontScale=desiredFont;value.applyOverrideConfiguration(config);}return value;
    }
    private static Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static void set(Object owner,String name,Object value)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    private <T>T main(Callable<T> work)throws Exception{FutureTask<T> task=new FutureTask<>(work);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private void check(JSONObject row,String name,boolean value)throws Exception{assertions++;row.put(name,value);if(!value)issues++;}
    private static Rect bounds(View root,View target){Rect value=new Rect();target.getDrawingRect(value);((ViewGroup)root).offsetDescendantRectToMyCoords(target,value);return value;}
    private static boolean textFits(TextView text,boolean requireWhole){if(text.getLayout()==null)return false;int n=text.getLayout().getLineCount(),width=text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight();if(n<1||text.getLayout().getLineBottom(n-1)>text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom())return false;for(int i=0;i<n;i++)if(text.getLayout().getLineMax(i)>width||(requireWhole&&text.getLayout().getEllipsisCount(i)>0))return false;return true;}
    private static boolean inside(View root,Rect rect){return rect.left>=0&&rect.top>=0&&rect.right<=root.getWidth()&&rect.bottom<=root.getHeight();}
    private static JSONObject textMetrics(View root,TextView text)throws Exception{JSONObject result=new JSONObject().put("text",text.getText()).put("bounds",bounds(root,text).toShortString()).put("textSizePx",text.getTextSize()).put("contentWidth",text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight()).put("wholeTextFits",textFits(text,true));JSONArray lines=new JSONArray();if(text.getLayout()!=null)for(int i=0;i<text.getLayout().getLineCount();i++)lines.put(new JSONObject().put("widthWithoutTrailingWhitespace",text.getLayout().getLineMax(i)).put("bottom",text.getLayout().getLineBottom(i)).put("ellipsis",text.getLayout().getEllipsisCount(i)));return result.put("lines",lines);}
    private void measure(int paneWidth,boolean dark,String title)throws Exception{
        int available=paneWidth-24,columns=BoardDirectory.columns(available,1,desiredFont,true),tileWidth=(available-8*(columns-1))/columns;boolean compact=columns>1;
        stage=paneWidth+"_font"+desiredFont+"_dark"+dark+"_"+title;
        LinearLayout tile=main(()->{
            set(activity,"dark",dark);Class<?> type=Class.forName("cn.ngapure.app.MainActivity$DirectoryTile");Constructor<?> constructor=type.getDeclaredConstructor(MainActivity.class,boolean.class);constructor.setAccessible(true);LinearLayout value=(LinearLayout)constructor.newInstance(activity,compact);
            Method bind=type.getDeclaredMethod("bind",String[].class,Runnable.class);bind.setAccessible(true);bind.invoke(value,new String[]{"414",title,"网事杂谈","N"},(Runnable)()->{});
            FrameLayout root=new FrameLayout(activity);root.setBackgroundColor(dark?0xff101721:0xfff4f6fa);root.addView(value,new FrameLayout.LayoutParams(tileWidth,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL));activity.setContentView(root);return value;
        });waitForIdleSync();SystemClock.sleep(30);waitForIdleSync();
        JSONObject row=main(()->{
            JSONObject result=new JSONObject().put("case",stage).put("paneWidthDp",paneWidth).put("fontScale",desiredFont).put("darkConstructorFlag",dark).put("columnsFromProductPolicy",columns).put("tileWidthDp",tileWidth).put("tileHeightDp",tile.getHeight());
            TextView name=(TextView)field(tile,"name"),detail=(TextView)field(tile,"detail"),star=(TextView)field(tile,"star");View avatar=(View)field(tile,"image");Rect n=bounds(tile,name),d=bounds(tile,detail),s=bounds(tile,star),a=bounds(tile,avatar);
            result.put("title",textMetrics(tile,name)).put("category",textMetrics(tile,detail)).put("star",textMetrics(tile,star)).put("avatarBounds",a.toShortString());
            check(result,"actualRequestedTileWidth",tile.getWidth()==tileWidth);
            check(result,"titleFullyReadableWithinTwoLines",textFits(name,true)&&name.getLayout().getLineCount()<=2&&inside(tile,n));
            check(result,"categoryFullyReadable",textFits(detail,true)&&inside(tile,d));
            check(result,"starTargetAtLeast48AndInside",s.width()>=48&&s.height()>=48&&inside(tile,s));
            check(result,"starDoesNotOverlapTitleCategoryOrAvatar",!Rect.intersects(s,n)&&!Rect.intersects(s,d)&&!Rect.intersects(s,a));
            check(result,"avatarRetainsNativeSize",a.width()==(compact?28:36)&&a.height()==(compact?28:36)&&inside(tile,a));
            check(result,"titleAndAvatarDoNotOverlap",!Rect.intersects(n,a));
            check(result,"favoriteAndTileSemanticsRemainBound",String.valueOf(star.getTag()).equals("forum-favorite:414")&&String.valueOf(tile.getTag()).equals("directory-board:414")&&star.getContentDescription().toString().contains(title)&&tile.getContentDescription().toString().contains(title)&&star.hasOnClickListeners()&&tile.hasOnClickListeners());
            if(compact&&desiredFont==1)check(result,"ordinaryCompactTileAtMost100Dp",tile.getHeight()<=100);
            return result;
        });cases.put(row);
    }
    @Override public void onStart(){boolean passed=false;String failure="NONE";try{
        if(!authorized||!PACKAGE.equals(getTargetContext().getPackageName())||getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("OWN_UID_NO_INTERNET_SYNTHETIC_DIRECTORY_LAYOUT_REQUIRED");
        URL.setURLStreamHandlerFactory(protocol->("http".equals(protocol)||"https".equals(protocol))?new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{blocked.incrementAndGet();throw new IOException("SYNTHETIC_OFFLINE_DIRECTORY_LAYOUT");}}:null);
        for(float font:new float[]{1,1.3f,2}){desiredFont=font;activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();if(main(()->activity.getResources().getDisplayMetrics().widthPixels)<760)throw new IllegalStateException("PHYSICAL_WINDOW_AT_LEAST_760PX_REQUIRED");for(int width:new int[]{390,520,760})for(boolean dark:new boolean[]{false,true})for(String title:new String[]{"晴风村","消费电子IT新闻","二次元国家地理","明日方舟终末地"})measure(width,dark,title);main(()->{activity.finish();return null;});activity=null;waitForIdleSync();}passed=issues==0;
    }catch(Throwable error){Throwable cause=error;for(int i=0;i<8&&cause.getCause()!=null;i++)cause=cause.getCause();failure=stage+":"+cause.getClass().getSimpleName()+":"+String.valueOf(cause.getMessage());}
    finally{if(activity!=null)try{main(()->{activity.finish();return null;});}catch(Exception ignored){}JSONObject report=new JSONObject();try{report.put("passed",passed).put("failure",failure).put("assertions",assertions).put("observedFailures",issues).put("cases",cases).put("ownUid",true).put("internetPermission",false).put("blockedOfflineAttempts",blocked.get()).put("realNetworkRequests",0).put("realCommunityWrites",0).put("favoriteClicks",0).put("realAccountAccessed",false).put("productionClassesRewritten",false).put("physicalFoldPoseTested",false).put("coordinateMethod","getDrawingRectThenOffsetDescendantRectToMyCoords");try(FileOutputStream output=new FileOutputStream(new File(getTargetContext().getFilesDir(),"directory-layout-report.json"))){output.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}}catch(Exception ignored){}Bundle result=new Bundle();result.putString("safe_directory_layout_report",report.toString());finish(passed?0:1,result);}}
}
