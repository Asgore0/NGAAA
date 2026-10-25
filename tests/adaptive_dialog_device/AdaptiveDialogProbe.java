package cn.ngapure.adaptivedialogprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import cn.ngapure.app.FoldAdaptiveLayout;
import cn.ngapure.app.ImageGalleryState;
import cn.ngapure.app.NativeDrafts;
import cn.ngapure.app.NativeEditorView;
import cn.ngapure.app.NativeImageGalleryView;
import cn.ngapure.app.NativeImages;
import cn.ngapure.app.NativeLoginView;
import cn.ngapure.app.NativePageJumpDialog;
import cn.ngapure.app.NgaStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Unmodified full product classes, own UID, no INTERNET, explicit local fixture transport only. */
@SuppressWarnings("deprecation")
public final class AdaptiveDialogProbe extends Instrumentation {
    private static final String PACKAGE="cn.ngapure.adaptivedialogprobe";
    private static final AtomicInteger CONNECTIONS=new AtomicInteger(),UNEXPECTED=new AtomicInteger();
    private final JSONObject report=new JSONObject();
    private final JSONArray measurements=new JSONArray(),screenshots=new JSONArray();
    private FixtureActivity activity;
    private String kind="all",tag="baseline";
    private int paneWidth=220;
    private float font=2f;
    private boolean dark,authorized,fixed;
    private NativeLoginView login;
    private NativeEditorView editor;
    private NativeImageGalleryView gallery;
    private NativeImages images;
    private NativePageJumpDialog page;
    private int clipped;
    private String stage="guard";
    public static final class FixtureActivity extends Activity {
        FrameLayout root;
        FoldAdaptiveLayout pane;
        @Override public void onCreate(Bundle state){super.onCreate(state);root=new FrameLayout(this);root.setBackgroundColor(0xffe0e4eb);setContentView(root);}
    }
    @Override public Activity newActivity(ClassLoader loader,String name,Intent intent)throws InstantiationException,IllegalAccessException,ClassNotFoundException{
        Activity value=super.newActivity(loader,name,intent);if(value instanceof FixtureActivity){Configuration local=new Configuration();local.densityDpi=160;local.fontScale=font;value.applyOverrideConfiguration(local);}return value;
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);if(args!=null){authorized="true".equals(args.getString("syntheticDialogsOnly"));kind=args.getString("kind","all");tag=args.getString("tag","baseline");paneWidth=Integer.parseInt(args.getString("width","220"));font=Float.parseFloat(args.getString("font","2.0"));dark="true".equals(args.getString("dark"));fixed="true".equals(args.getString("fixed"));}start();}
    private <T>T main(Callable<T> callable)throws Exception{FutureTask<T> task=new FutureTask<>(callable);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private static Object field(Object value,String name)throws Exception{Field member=value.getClass().getDeclaredField(name);member.setAccessible(true);return member.get(value);}
    private interface Condition {boolean ready()throws Exception;}
    private void await(String name,Condition test)throws Exception{for(int i=0;i<200;i++){if(test.ready()){waitForIdleSync();return;}SystemClock.sleep(25);}throw new IllegalStateException(name);}
    private void transport()throws Exception{
        Bitmap bitmap=Bitmap.createBitmap(60,90,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff4285c9);ByteArrayOutputStream bytes=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes);bitmap.recycle();final byte[] image=bytes.toByteArray();
        URL.setURLStreamHandlerFactory(protocol->new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{
            final byte[] body;
            if("https".equals(url.getProtocol())&&"img.nga.cn".equals(url.getHost())&&url.getPath().startsWith("/attachments/mon_202610/23/adaptive_"))body=image;
            else if("https".equals(url.getProtocol())&&"bbs.nga.cn".equals(url.getHost())&&"/post.php".equals(url.getPath()))body="{\"data\":{\"action\":\"new\",\"fid\":7,\"tid\":0,\"pid\":0,\"subject\":\"\",\"content\":\"\",\"auth\":\"synthetic-only\",\"attach_url\":\"https://img8.nga.cn/attach.php\",\"__CU\":{\"uid\":123},\"__F\":{\"fid\":7,\"name\":\"离线样例\",\"bit_data\":0},\"__T\":{\"topic_misc_var\":{}},\"__ST\":{\"tid\":0}}}".getBytes(StandardCharsets.UTF_8);
            else if("https".equals(url.getProtocol())&&"bbs.nga.cn".equals(url.getHost())&&"/nuke.php".equals(url.getPath()))body="{\"code\":0,\"result\":[\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa,bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"]}".getBytes(StandardCharsets.UTF_8);
            else{UNEXPECTED.incrementAndGet();throw new IOException("UNEXPECTED_FIXTURE_URL");}
            CONNECTIONS.incrementAndGet();return new HttpURLConnection(url){@Override public void connect(){connected=true;}@Override public void disconnect(){}@Override public boolean usingProxy(){return false;}@Override public int getResponseCode(){return 200;}@Override public String getContentType(){return "application/json; charset=UTF-8";}@Override public long getContentLengthLong(){return body.length;}@Override public OutputStream getOutputStream(){return new ByteArrayOutputStream();}@Override public InputStream getInputStream(){return new ByteArrayInputStream(body);}};
        }});
    }
    private void clear()throws Exception{boolean savedEditor=editor!=null;main(()->{if(page!=null){page.close();page=null;}if(login!=null){login.close();login=null;}if(editor!=null){editor.close();editor=null;}if(gallery!=null){gallery.close();gallery=null;}if(images!=null){images.close();images=null;}activity.root.removeAllViews();activity.pane=new FoldAdaptiveLayout(activity);activity.pane.setBackgroundColor(dark?0xff0e141f:0xfff4f6fa);activity.root.addView(activity.pane,new FrameLayout.LayoutParams(paneWidth,-1,Gravity.TOP|Gravity.CENTER_HORIZONTAL));return null;});waitForIdleSync();if(savedEditor)SystemClock.sleep(4000);}
    private void mounted(View view)throws Exception{main(()->{activity.pane.addView(view,new ViewGroup.LayoutParams(-1,-1));return null;});await("PANE_LAYOUT",()->main(()->view.getWidth()==paneWidth&&view.getHeight()>0));SystemClock.sleep(150);waitForIdleSync();}
    private void shot(String name)throws Exception{waitForIdleSync();SystemClock.sleep(80);Bitmap screenshot=getUiAutomation().takeScreenshot();if(screenshot==null)throw new IllegalStateException("SCREENSHOT_REQUIRED");File output=new File(activity.getFilesDir(),tag+"-"+name+"-w"+paneWidth+"-f"+font+(dark?"-dark":"-light")+".png");try(FileOutputStream stream=new FileOutputStream(output)){screenshot.compress(Bitmap.CompressFormat.PNG,100,stream);}finally{screenshot.recycle();}screenshots.put(output.getAbsolutePath());}
    private boolean measured(String name,TextView text)throws Exception{return main(()->{
        Layout layout=text.getLayout();int availableHeight=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom();int availableWidth=text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight();boolean fits=layout!=null&&layout.getHeight()<=availableHeight;boolean ellipsis=false;
        if(layout!=null)for(int i=0;i<layout.getLineCount();i++){fits&=layout.getLineWidth(i)<=availableWidth+1;ellipsis|=layout.getEllipsisCount(i)>0;}fits&=!ellipsis;
        JSONArray lines=new JSONArray();if(layout!=null)for(int i=0;i<layout.getLineCount();i++)lines.put(new JSONObject().put("width",layout.getLineWidth(i)).put("left",layout.getLineLeft(i)).put("right",layout.getLineRight(i)).put("baseline",layout.getLineBaseline(i)).put("paragraphDirection",layout.getParagraphDirection(i)));
        android.graphics.Rect glyph=new android.graphics.Rect();String value=text.getText().toString();text.getPaint().getTextBounds(value,0,value.length(),glyph);
        measurements.put(new JSONObject().put("lines",lines).put("paintMeasureText",text.getPaint().measureText(value)).put("layoutPaintTextSize",layout==null?-1:layout.getPaint().getTextSize()).put("layoutWidth",layout==null?-1:layout.getWidth()).put("glyphLeft",glyph.left).put("glyphRight",glyph.right).put("glyphTop",glyph.top).put("glyphBottom",glyph.bottom).put("viewBaseline",text.getBaseline()).put("scrollX",text.getScrollX()).put("selectionStart",text.getSelectionStart()).put("selectionEnd",text.getSelectionEnd()).put("name",name).put("text",text instanceof EditText?"SYNTHETIC_INPUT":text.getText().toString()).put("width",text.getWidth()).put("height",text.getHeight()).put("textSizePx",text.getTextSize()).put("layoutHeight",layout==null?-1:layout.getHeight()).put("availableHeight",availableHeight).put("availableWidth",availableWidth).put("lineCount",layout==null?0:layout.getLineCount()).put("fits",fits));if(!fits)clipped++;return fits;
    });}
    private void measuredGlyph(String name,TextView text)throws Exception{main(()->{
        android.graphics.Rect bounds=new android.graphics.Rect();String label=text.getText().toString();text.getPaint().getTextBounds(label,0,label.length(),bounds);
        float x=text.getCompoundPaddingLeft()+(text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight()-text.getPaint().measureText(label))/2f;
        int baseline=text.getBaseline();boolean fits=baseline+bounds.top>=0&&baseline+bounds.bottom<=text.getHeight()&&x+bounds.left>=0&&x+bounds.right<=text.getWidth();
        measurements.put(new JSONObject().put("name",name).put("text",label).put("width",text.getWidth()).put("height",text.getHeight()).put("layoutHeight",text.getLayout().getHeight()).put("glyphTop",baseline+bounds.top).put("glyphBottom",baseline+bounds.bottom).put("usesVisibleGlyphBounds",true).put("fits",fits));if(!fits)clipped++;return null;
    });}
    private void walkButtons(String prefix,View root)throws Exception{if(root instanceof Button&&root.getVisibility()==View.VISIBLE)measured(prefix,((TextView)root));else if(root instanceof TextView&&("用户协议".contentEquals(((TextView)root).getText())||"隐私协议".contentEquals(((TextView)root).getText())))measured("login_legal_link",(TextView)root);if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++)if(group.getChildAt(i).getVisibility()==View.VISIBLE)walkButtons(prefix,group.getChildAt(i));}}
    private void scrollBottom(View view)throws Exception{main(()->{((ScrollView)view).fullScroll(View.FOCUS_DOWN);return null;});waitForIdleSync();}
    private void login()throws Exception{
        stage="login";clear();NgaStore store=new NgaStore(activity);store.setTheme(dark?"dark":"light");login=main(()->new NativeLoginView(activity,store,()->{throw new AssertionError("NO_LOGIN_SUCCESS_EXPECTED");},2));mounted(login);walkButtons("login_method_or_sms",login);shot("login-sms-top");scrollBottom(login.getChildAt(0));shot("login-sms-bottom");
        main(()->{login.restoreSelectedMethod(0);return null;});SystemClock.sleep(300);waitForIdleSync();main(()->{((ScrollView)login.getChildAt(0)).fullScroll(View.FOCUS_UP);return null;});waitForIdleSync();shot("login-qr-top");
        ViewGroup qr=(ViewGroup)field(login,"qrContent");View card=qr.getChildAt(2);boolean bounded=main(()->card.getWidth()<=qr.getWidth());measurements.put(new JSONObject().put("name","qr_card_inside_actual_pane_content").put("cardWidth",card.getWidth()).put("parentWidth",qr.getWidth()).put("displayWidth",activity.getResources().getDisplayMetrics().widthPixels).put("fits",bounded));if(!bounded)clipped++;
        measured("qr_confirm",(TextView)field(login,"checkButton"));measured("qr_refresh",(TextView)field(login,"refreshButton"));scrollBottom(login.getChildAt(0));shot("login-qr-bottom");
    }
    private void editor()throws Exception{
        stage="editor";clear();NgaStore store=new NgaStore(activity);store.setTheme(dark?"dark":"light");store.setCookie("ngaPassportUid=123; ngaPassportCid=synthetic-adaptive-only");
        main(()->{activity.getSharedPreferences("nga_native_drafts",0).edit().clear().commit();NativeDrafts drafts=new NativeDrafts(activity);Method save=NativeDrafts.class.getDeclaredMethod("save",String.class,String.class,String.class,String.class,String.class,String.class,java.util.List.class,boolean.class);save.setAccessible(true);NativeDrafts.PendingAttachment pending=new NativeDrafts.PendingAttachment("离线附件.png","image/png","[img]./mon_202610/23/adaptive_a.png[/img]");save.invoke(drafts,"new","7","","","离线布局样例","仅用于本机量测，不会发布。\n[img]./mon_202610/23/adaptive_a.png[/img]",Collections.singletonList(pending),false);return null;});
        editor=main(()->new NativeEditorView(activity,store,"new","7","","",new NativeEditorView.Listener(){@Override public void published(String tid,String pid){throw new AssertionError("NO_PUBLISH_EXPECTED");}@Override public void chooseAttachment(NativeEditorView owner){throw new AssertionError("NO_PICKER_EXPECTED");}}));mounted(editor);SystemClock.sleep(300);waitForIdleSync();
        for(Object tool:(Iterable<?>)field(editor,"tools"))measured("editor_tool",(TextView)tool);for(Object action:(Iterable<?>)field(editor,"recoveryActions"))measured("editor_recovery",(TextView)action);measured("editor_preview",(TextView)field(editor,"previewButton"));measured("editor_submit",(TextView)field(editor,"submit"));shot("editor-top");scrollBottom(editor.getChildAt(0));shot("editor-bottom");
    }
    private void gallery()throws Exception{
        stage="gallery";clear();images=new NativeImages();gallery=main(()->new NativeImageGalleryView(activity,images,ImageGalleryState.create(Arrays.asList("https://img.nga.cn/attachments/mon_202610/23/adaptive_a.png","https://img.nga.cn/attachments/mon_202610/23/adaptive_b.png","https://img.nga.cn/attachments/mon_202610/23/adaptive_c.png"),1,3,false),()->true,(state,ready)->{},dark?0xffe6edf5:0xff1c2533,dark?0xffa4b1c3:0xff697587,dark?0xff344356:0xffdce3ed));mounted(gallery);await("GALLERY_READY",()->main(()->gallery.ready()));measuredGlyph("gallery_previous",(TextView)field(gallery,"previous"));measuredGlyph("gallery_next",(TextView)field(gallery,"next"));measured("gallery_counter",(TextView)field(gallery,"counter"));shot("gallery");
    }
    private void page()throws Exception{
        stage="page";clear();page=main(()->{NativePageJumpDialog dialog=new NativePageJumpDialog(activity,activity.pane,dark,true,false,1,0,new NativePageJumpDialog.Listener(){@Override public void onPageSelected(int selected){throw new AssertionError("NO_PAGE_NAVIGATION_EXPECTED");}@Override public void onDismiss(NativePageJumpDialog ignored){}});dialog.show();return dialog;});waitForIdleSync();SystemClock.sleep(200);for(TextView chip:(TextView[])field(page,"chips"))measured("page_chip",chip);shot("page");main(()->{page.restoreDraft("99999",0,5);return null;});waitForIdleSync();measured("page_five_digit_input",(TextView)field(page,"input"));shot("page-five-digits");
        main(()->{page.restoreDraft("1",0,1);return null;});waitForIdleSync();measured("page_short_value_after_long",(TextView)field(page,"input"));
        main(()->{page.restoreDraft("99999",0,5);return null;});waitForIdleSync();measured("page_long_value_after_short",(TextView)field(page,"input"));String[] draft=main(()->page.draftState());boolean restored=draft!=null&&"99999".equals(draft[0])&&"0".equals(draft[1])&&"5".equals(draft[2]);report.put("pageLongShortLongPreservesDraftSelection",restored);if(!restored)throw new AssertionError("PAGE_DRAFT_SELECTION_CHANGED");
    }
    @Override public void onStart(){boolean passed=false;String failure="NONE";try{
        if(!authorized||!PACKAGE.equals(getTargetContext().getPackageName())||getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED||paneWidth<160||paneWidth>1000||font<1||font>2||!tag.matches("[a-zA-Z0-9_-]+"))throw new IllegalStateException("OWN_UID_NO_INTERNET_SYNTHETIC_DIALOGS_REQUIRED");
        transport();activity=(FixtureActivity)startActivitySync(new Intent().setClassName(PACKAGE,FixtureActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();await("WINDOW_FOCUS",()->main(()->activity.hasWindowFocus()));if("all".equals(kind)||"login".equals(kind))login();if("all".equals(kind)||"editor".equals(kind))editor();if("all".equals(kind)||"gallery".equals(kind))gallery();if("all".equals(kind)||"page".equals(kind))page();passed=!fixed||clipped==0;
    }catch(Exception|Error error){failure=error.getClass().getSimpleName()+":"+stage;}
    finally{try{report.put("passed",passed).put("failure",failure).put("stage",stage).put("fixedAssertions",fixed).put("clippedTargetCount",clipped).put("measurements",measurements).put("screenshots",screenshots).put("widthDp",paneWidth).put("fontScale",font).put("dark",dark).put("kind",kind).put("ownUidOnly",true).put("internetPermission",false).put("realNetworkRequests",0).put("syntheticConnections",CONNECTIONS.get()).put("unexpectedUrlsBlocked",UNEXPECTED.get()).put("productionClassesRewritten",false).put("physicalFoldPoseTested",false).put("globalDisplaySettingsChanged",false);if(activity!=null){File output=new File(activity.getFilesDir(),tag+"-"+kind+"-report.json");try(FileOutputStream stream=new FileOutputStream(output)){stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}report.put("reportFile",output.getAbsolutePath());clear();main(()->{activity.finish();return null;});}}catch(Exception ignored){}Bundle result=new Bundle();result.putString("safe_adaptive_dialog_report",report.toString());finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);}
    }
}
