package cn.ngapure.entityprobe;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import cn.ngapure.app.NativeImages;
import cn.ngapure.app.NativeRichText;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/** Actual Android text/spans, exact product JAR, own UID, and no network permission. */
public final class EntityProbe extends Instrumentation {
    private final JSONObject report=new JSONObject();
    private final JSONArray results=new JSONArray();
    private ProbeHostActivity activity;
    private NativeImages images;
    private boolean expectFixed;
    private int assertions,linkOpens;
    @Override public void onCreate(Bundle args){super.onCreate(args);expectFixed=args!=null&&"true".equals(args.getString("expectFixed"));start();}
    @Override public void onStart() {
        boolean passed=false,clean=true;String failure="NONE";
        try {
            require(getTargetContext().checkSelfPermission(Manifest.permission.INTERNET)==PackageManager.PERMISSION_DENIED,"NoInternetPermission");
            require(getTargetContext().getApplicationInfo().uid!=getTargetContext().getPackageManager().getApplicationInfo("cn.ngapure.app",0).uid,"SeparateUid");
            report.put("binding",new JSONObject(new String(asset("binding.json"),java.nio.charset.StandardCharsets.UTF_8)));
            activity=(ProbeHostActivity)startActivitySync(new Intent().setClassName("cn.ngapure.entityprobe","cn.ngapure.entityprobe.ProbeHostActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));images=new NativeImages();
            for(boolean dark:new boolean[]{false,true})for(EntityCases.Case value:EntityCases.all()) {
                NativeRichText rich=onMain(()->{activity.content.removeAllViews();activity.content.setBackgroundColor(dark?0xff0e141f:0xfff4f6fa);
                    NativeRichText next=new NativeRichText(activity,images,value.source,dark,18,url->linkOpens++);activity.content.addView(next,new ViewGroup.LayoutParams(-1,-2));return next;});
                waitForIdleSync();
                onMain(()->inspect(rich,value,dark));
            }
            report.put("lightScreenshot",showcase(false)).put("darkScreenshot",showcase(true));
            require(linkOpens==0,"NoFixtureCanOpenALink");passed=true;
        } catch(Exception|AssertionError issue){failure=issue.getClass().getSimpleName()+":"+issue.getMessage();}
        finally {
            try{onMain(()->{if(activity!=null){activity.content.removeAllViews();activity.finish();}if(images!=null)images.close();return null;});}catch(Exception issue){clean=false;}
            try{report.put("passed",passed&&clean).put("cleanupPassed",clean).put("failure",failure).put("assertionCount",assertions)
                    .put("expectFixed",expectFixed).put("cases",results).put("fixtureCaseCount",EntityCases.all().size()).put("themeCount",2)
                    .put("productionClassesRewritten",false).put("realClientStarted",false).put("realAccountRead",false)
                    .put("realNetworkRequests",0).put("communityWrites",0).put("linkOpenCount",linkOpens);}catch(Exception ignored){ }
        }
        Bundle result=new Bundle();result.putString("safe_entity_report",report.toString());finish(Activity.RESULT_OK,result);
    }
    private JSONObject inspect(NativeRichText rich,EntityCases.Case value,boolean dark)throws Exception {
        List<View> nodes=new ArrayList<>();collect(rich,nodes);List<TextView> body=new ArrayList<>();int urls=0,pictures=0,styles=0,controls=0;
        for(View node:nodes) {
            if(node instanceof Button||node instanceof EditText||node instanceof ImageView||node instanceof android.webkit.WebView)controls++;
            if(!(node instanceof TextView))continue;TextView text=(TextView)node;
            if(text.getClass().getName().endsWith("$RichTextView")||text.isTextSelectable())body.add(text);
            if(text.getText() instanceof Spanned){Spanned spans=(Spanned)text.getText();urls+=spans.getSpans(0,spans.length(),URLSpan.class).length;pictures+=spans.getSpans(0,spans.length(),ImageSpan.class).length;styles+=spans.getSpans(0,spans.length(),StyleSpan.class).length;}
        }
        String id=(dark?"dark/":"light/")+value.name;String expected=expectFixed?value.text:value.baselineText;
        require(body.size()==1,id+"OneActualTextOwner");String actual=body.get(0).getText().toString();
        JSONObject observed=new JSONObject().put("name",value.name).put("dark",dark).put("textMatches",actual.equals(expected))
                .put("codePointCount",actual.codePointCount(0,actual.length())).put("urlSpans",urls).put("imageSpans",pictures)
                .put("controls",controls).put("styleSpans",styles);
        if(!actual.equals(expected))observed.put("actualText",actual).put("expectedText",expected)
                .put("actualCodePoints",codePoints(actual)).put("expectedCodePoints",codePoints(expected));
        results.put(observed); // Preserve the failed case before the instrumentation assertion unwinds.
        require(actual.equals(expected),id+"ExactUnicodeText");require(body.get(0).getLayout()!=null&&body.get(0).getHeight()>0,id+"ActualAndroidLayout");
        require(urls==0&&pictures==0&&controls==0,id+"NoActivatedLinksImagesOrControls");require(styles==0,id+"NoEncodedFormattingActivation");
        return observed;
    }
    private String showcase(boolean dark)throws Exception {
        onMain(()->{activity.content.removeAllViews();activity.content.setBackgroundColor(dark?0xff0e141f:0xfff4f6fa);
            TextView heading=new TextView(activity);heading.setText(expectFixed?"表情实体 · 原生正文":"基线 · 表情实体复现");heading.setTextSize(22);heading.setTextColor(dark?0xffe6edf5:0xff1c2533);heading.setPadding(0,0,0,24);activity.content.addView(heading);
            String source="十进制：&#128517;\n十六进制：&#x1F605;\n变体选择符：&#10084;&#65039;\n肤色与 ZWJ：&#x1F469;&#127997;&#8205;&#x1F4BB;\n服务端包裹：&amp;#128517;\n更深包裹仍是文字：&amp;amp;#128517;\n[code]&amp;#128517; [b]代码仍是原文[/b][/code]";
            activity.content.addView(new NativeRichText(activity,images,source,dark,22,url->linkOpens++),new ViewGroup.LayoutParams(-1,-2));return null;});waitForIdleSync();
        Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)throw new IllegalStateException("OwnUidScreenshotRequired");
        File output=new File(getTargetContext().getFilesDir(),dark?"entities-dark.png":"entities-light.png");
        try(FileOutputStream stream=new FileOutputStream(output)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))throw new IllegalStateException("ScreenshotEncodeRequired");}finally{bitmap.recycle();}
        return output.getAbsolutePath();
    }
    private static void collect(View view,List<View> nodes){nodes.add(view);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collect(group.getChildAt(i),nodes);}}
    private static JSONArray codePoints(String text){JSONArray points=new JSONArray();for(int at=0;at<text.length();){int point=text.codePointAt(at);points.put(String.format(java.util.Locale.ROOT,"U+%04X",point));at+=Character.charCount(point);}return points;}
    private void require(boolean passed,String name){assertions++;if(!passed)throw new AssertionError(name);}
    private <T>T onMain(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get();}
    private byte[] asset(String name)throws Exception{try(InputStream input=getTargetContext().getAssets().open(name);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];for(int count;(count=input.read(buffer))!=-1;)output.write(buffer,0,count);return output.toByteArray();}}
}
