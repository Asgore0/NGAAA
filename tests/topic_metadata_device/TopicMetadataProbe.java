package cn.ngapure.metadataprobe;

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
import cn.ngapure.app.MainActivity;
import cn.ngapure.app.VoteMetrics;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real private topic metadata layout, measured without rewriting production classes or voting. */
@SuppressWarnings("deprecation")
public final class TopicMetadataProbe extends Instrumentation {
    private static final String PACKAGE="cn.ngapure.metadataprobe";
    private final JSONArray cases=new JSONArray();
    private final AtomicInteger blocked=new AtomicInteger();
    private MainActivity activity;
    private boolean authorized;
    private float desiredFont;
    private int assertions,issues;
    private String stage="guard";
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticTopicMetadataOnly"));start();}
    @Override public Activity newActivity(ClassLoader loader,String name,Intent intent)throws InstantiationException,IllegalAccessException,ClassNotFoundException{
        Activity value=super.newActivity(loader,name,intent);if(value instanceof MainActivity){Configuration config=new Configuration();config.densityDpi=160;config.fontScale=desiredFont;value.applyOverrideConfiguration(config);}return value;
    }
    private static Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static void set(Object owner,String name,Object value)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    private <T>T main(Callable<T> work)throws Exception{FutureTask<T> task=new FutureTask<>(work);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private void check(JSONObject row,String name,boolean value)throws Exception{assertions++;row.put(name,value);if(!value)issues++;}
    private static Rect bounds(View root,View target){Rect value=new Rect();target.getDrawingRect(value);((ViewGroup)root).offsetDescendantRectToMyCoords(target,value);return value;}
    private static boolean fits(TextView text){if(text.getLayout()==null)return false;int n=text.getLayout().getLineCount(),width=text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight();if(n<1||text.getLayout().getLineBottom(n-1)>text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom())return false;for(int i=0;i<n;i++)if(text.getLayout().getLineWidth(i)>width||text.getLayout().getEllipsisCount(i)>0)return false;return true;}
    private static boolean in(View root,Rect rect){return rect.left>=0&&rect.top>=0&&rect.right<=root.getWidth()&&rect.bottom<=root.getHeight();}
    private static JSONObject textMetrics(View root,TextView text)throws Exception{JSONObject result=new JSONObject().put("text",text.getText()).put("bounds",bounds(root,text).toShortString()).put("textInternalScrollX",text.getScrollX()).put("fullyVisibleText",fits(text));if(text.getLayout()!=null)result.put("ellipsis",text.getLayout().getEllipsisCount(0));return result;}
    private void measure(int width,boolean dark,int mode)throws Exception{
        stage=width+"_font"+desiredFont+"_dark"+dark+"_mode"+mode;
        VoteMetrics values=new VoteMetrics();if(mode>0){values.likesKnown=true;values.dislikesKnown=true;values.likes=mode==1?0:Integer.MAX_VALUE;values.dislikes=mode==1?0:Integer.MAX_VALUE;}
        final int replies=mode==2?Integer.MAX_VALUE:6;
        LinearLayout meta=main(()->{
            set(activity,"dark",dark);
            Class<?> type=Class.forName("cn.ngapure.app.MainActivity$TopicListMeta");Constructor<?> constructor=type.getDeclaredConstructor(MainActivity.class,String.class,String.class,String.class,int.class,VoteMetrics.class);constructor.setAccessible(true);
            LinearLayout value=(LinearLayout)constructor.newInstance(activity,"987654321","0","10-23 20:46",replies,values);
            FrameLayout root=new FrameLayout(activity);root.setBackgroundColor(dark?0xff101721:0xfff4f6fa);root.addView(value,new FrameLayout.LayoutParams(width,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL));activity.setContentView(root);return value;
        });
        waitForIdleSync();SystemClock.sleep(35);waitForIdleSync();
        JSONObject row=main(()->{
            JSONObject result=new JSONObject().put("case",stage).put("widthDp",width).put("fontScale",desiredFont).put("dark",dark).put("countMode",mode==0?"unknown":mode==1?"zero":"max-int");
            TextView time=(TextView)field(meta,"time"),count=(TextView)field(meta,"replies");
            Rect t=bounds(meta,time),c=bounds(meta,count);
            result.put("height",meta.getHeight()).put("stackInfo",field(meta,"stackInfo")).put("replies",textMetrics(meta,count)).put("time",textMetrics(meta,time));
            check(result,"actualRequestedWidthAndFont",meta.getWidth()==width&&Math.abs(activity.getResources().getConfiguration().fontScale-desiredFont)<.001f);
            check(result,"previewHasNoReactionTargets",meta.findViewWithTag("vote-like")==null&&meta.findViewWithTag("vote-dislike")==null);
            check(result,"replyAndTimeBoundsDoNotOverlap",in(meta,c)&&in(meta,t)&&!Rect.intersects(c,t));
            check(result,"replyAndTimeRemainFullyReadable",fits(count)&&fits(time));
            check(result,"timeKeepsRightAlignment",t.right==meta.getWidth()-meta.getPaddingRight()&&(time.getGravity()&Gravity.HORIZONTAL_GRAVITY_MASK)==Gravity.RIGHT);
            check(result,"replyCountRemainsExact",(replies+" 回复").contentEquals(count.getText()));
            return result;
        });cases.put(row);
    }
    @Override public void onStart(){boolean passed=false;String failure="NONE";try{
        if(!authorized||!PACKAGE.equals(getTargetContext().getPackageName())||getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("OWN_UID_NO_INTERNET_SYNTHETIC_METADATA_REQUIRED");
        URL.setURLStreamHandlerFactory(protocol->("http".equals(protocol)||"https".equals(protocol))?new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{blocked.incrementAndGet();throw new IOException("SYNTHETIC_OFFLINE_METADATA");}}:null);
        for(float font:new float[]{1,1.3f,2}){desiredFont=font;activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();int available=main(()->activity.getResources().getDisplayMetrics().widthPixels);if(available<720)throw new IllegalStateException("PHYSICAL_WINDOW_AT_LEAST_720PX_REQUIRED");for(int width:new int[]{220,390,720})for(boolean dark:new boolean[]{false,true})for(int mode=0;mode<3;mode++)measure(width,dark,mode);main(()->{activity.finish();return null;});activity=null;waitForIdleSync();}
        passed=issues==0;
    }catch(Throwable error){Throwable cause=error;for(int i=0;i<8&&cause.getCause()!=null;i++)cause=cause.getCause();failure=stage+":"+cause.getClass().getSimpleName()+":"+String.valueOf(cause.getMessage());}
    finally{if(activity!=null)try{main(()->{activity.finish();return null;});}catch(Exception ignored){}JSONObject report=new JSONObject();try{report.put("passed",passed).put("failure",failure).put("assertions",assertions).put("observedFailures",issues).put("cases",cases).put("ownUid",true).put("internetPermission",false).put("blockedOfflineAttempts",blocked.get()).put("realNetworkRequests",0).put("realCommunityWrites",0).put("realAccountAccessed",false).put("productionClassesRewritten",false).put("physicalFoldPoseTested",false).put("coordinateMethod","getDrawingRectThenOffsetDescendantRectToMyCoords");try(FileOutputStream output=new FileOutputStream(new File(getTargetContext().getFilesDir(),"topic-metadata-report.json"))){output.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}}catch(Exception ignored){}Bundle result=new Bundle();result.putString("safe_topic_metadata_report",report.toString());finish(passed?0:1,result);}}
}
