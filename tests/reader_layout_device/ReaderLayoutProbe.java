package cn.ngapure.readerlayoutprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ImageSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import cn.ngapure.app.NativeImages;
import cn.ngapure.app.NativeRichText;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact immutable product classes; synthetic media and own-UID screenshots only. */
@SuppressWarnings("deprecation")
public final class ReaderLayoutProbe extends Instrumentation {
    private static final String PACKAGE="cn.ngapure.readerlayoutprobe";
    private static final String PREFIX="https://img.nga.cn/attachments/mon_202610/23/reader_v201_";
    private static final String NARROW=PREFIX+"narrow.png",WIDE=PREFIX+"wide.png",SLOW=PREFIX+"slow.png";
    private static final String EMOJI="https://img4.nga.cn/ngabbs/post/smile/ac39.png";
    private final Map<String,Resource> resources=new ConcurrentHashMap<>();
    private final AtomicInteger unexpected=new AtomicInteger(),connections=new AtomicInteger();
    private final JSONObject report=new JSONObject(),measurements=new JSONObject(),checks=new JSONObject();
    private final List<String> openedLinks=new ArrayList<>();
    private FixtureActivity activity;
    private NativeImages images;
    private boolean authorized,expectFixed,expectCompactImageSpacing,scenarioFailed;
    private float requestedFontScale=1f;
    private int assertionCount;
    private String stage="guard";
    private static final class Resource {
        final byte[] bytes;final CountDownLatch entered=new CountDownLatch(1),release;
        Resource(byte[] value,boolean delayed){bytes=value;release=new CountDownLatch(delayed?1:0);}
    }
    public static final class FixtureActivity extends Activity {
        FrameLayout root;LinearLayout content;TouchScroll scroll;
        final class TouchScroll extends ScrollView {
            TouchScroll(){super(FixtureActivity.this);}
            @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){NativeRichText.onScrollTouch(this,event);return super.dispatchTouchEvent(event);}
        }
        @Override public void onCreate(Bundle state){super.onCreate(state);root=new FrameLayout(this);root.setBackgroundColor(0xffe8edf3);setContentView(root);}
        void scene(String title,String description){
            root.removeAllViews();LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setBackgroundColor(Color.WHITE);panel.setPadding(16,16,16,16);
            TextView heading=new TextView(this);heading.setText(title);heading.setTextSize(20);heading.setTextColor(0xff1c2533);panel.addView(heading,new LinearLayout.LayoutParams(-1,-2));
            TextView detail=new TextView(this);detail.setText(description);detail.setTextSize(13);detail.setTextColor(0xff697587);detail.setPadding(0,8,0,14);panel.addView(detail,new LinearLayout.LayoutParams(-1,-2));
            scroll=new TouchScroll();scroll.setFillViewport(true);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);scroll.addView(content,new ScrollView.LayoutParams(-1,-2));panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            root.addView(panel,new FrameLayout.LayoutParams(392,680,Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        }
    }
    @Override public Activity newActivity(ClassLoader loader,String name,Intent intent)throws InstantiationException,IllegalAccessException,ClassNotFoundException{
        Activity value=super.newActivity(loader,name,intent);if(value instanceof FixtureActivity){Configuration local=new Configuration();local.densityDpi=160;local.fontScale=requestedFontScale;value.applyOverrideConfiguration(local);}return value;
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticReaderOnly"));expectFixed=args!=null&&"true".equals(args.getString("expectFixed"));expectCompactImageSpacing=args!=null&&"true".equals(args.getString("expectCompactImageSpacing"));try{requestedFontScale=Float.parseFloat(args==null?"1.0":args.getString("fontScale","1.0"));}catch(NumberFormatException invalid){requestedFontScale=Float.NaN;}start();}
    private <T>T main(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private void await(String label,Callable<Boolean> condition)throws Exception{long limit=SystemClock.elapsedRealtime()+7000;while(SystemClock.elapsedRealtime()<limit){if(condition.call()){waitForIdleSync();return;}SystemClock.sleep(25);}throw new IllegalStateException(label);}
    private void check(String name,boolean value)throws Exception{assertionCount++;checks.put(name,value);if(!value)scenarioFailed=true;}
    private static Object field(Object object,String name)throws Exception{Field value=object.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(object);}
    @SuppressWarnings("unchecked") private static List<Object> media(NativeRichText rich)throws Exception{return (List<Object>)field(rich,"inlineImages");}
    private static boolean loaded(NativeRichText rich)throws Exception{List<Object> items=media(rich);if(items.isEmpty())return false;for(Object item:items)if(field(item,"actual")==null)return false;return true;}
    private static List<TextView> texts(View root){List<TextView> values=new ArrayList<>();collect(root,values);return values;}
    private static void collect(View view,List<TextView> values){if(view instanceof TextView)values.add((TextView)view);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collect(group.getChildAt(i),values);}}
    private static final class Marker {final TextView owner;final int offset;Marker(TextView view,int value){owner=view;offset=value;}}
    private static Marker marker(NativeRichText rich,String text){for(TextView view:texts(rich)){int at=view.getText().toString().indexOf(text);if(at>=0)return new Marker(view,at);}throw new IllegalStateException("SYNTHETIC_MARKER_MISSING");}
    private static int baseline(Marker marker){int[] location=new int[2];marker.owner.getLocationOnScreen(location);Layout layout=marker.owner.getLayout();return location[1]+marker.owner.getTotalPaddingTop()-marker.owner.getScrollY()+layout.getLineBaseline(layout.getLineForOffset(marker.offset));}
    private NativeRichText mount(String title,String description,String raw,int font)throws Exception{
        NativeRichText rich=main(()->{activity.scene(title,description);NativeRichText value=new NativeRichText(activity,images,raw,false,font,openedLinks::add);activity.content.addView(value,new LinearLayout.LayoutParams(-1,-2));return value;});
        await("RICH_LAYOUT_REQUIRED",()->main(()->rich.getWidth()>0&&rich.getHeight()>0));return rich;
    }
    private void stable()throws Exception{waitForIdleSync();SystemClock.sleep(180);waitForIdleSync();}
    private JSONObject inlineSnapshot(NativeRichText rich,String before,String after)throws Exception{
        Marker prefix=marker(rich,before),suffix=marker(rich,after);TextView imageOwner=null;ImageSpan image=null;int imageOffset=-1,count=0;
        JSONArray owners=new JSONArray();
        for(TextView view:texts(rich)){CharSequence value=view.getText();if(!(value instanceof Spanned))continue;Spanned text=(Spanned)value;ImageSpan[] spans=text.getSpans(0,text.length(),ImageSpan.class);JSONObject owner=new JSONObject().put("text",text.toString()).put("width",view.getWidth()).put("height",view.getHeight()).put("imageCount",spans.length);owners.put(owner);for(ImageSpan span:spans){imageOwner=view;image=span;imageOffset=text.getSpanStart(span);count++;}}
        if(count!=1||imageOwner==null||image==null||imageOwner.getLayout()==null)throw new IllegalStateException("ONE_MEASURED_IMAGE_SPAN_REQUIRED");
        Layout layout=imageOwner.getLayout();int line=layout.getLineForOffset(imageOffset),prefixLine=prefix.owner.getLayout().getLineForOffset(prefix.offset),suffixLine=suffix.owner.getLayout().getLineForOffset(suffix.offset);
        boolean beforeShares=prefix.owner==imageOwner&&prefixLine==line,afterShares=suffix.owner==imageOwner&&suffixLine==line;Drawable drawable=image.getDrawable();
        JSONObject value=new JSONObject().put("beforeSharesImageLine",beforeShares).put("afterSharesImageLine",afterShares).put("imageStandalone",!beforeShares&&!afterShares).put("imageSpanCount",count).put("imageLine",line).put("beforeLine",prefixLine).put("afterLine",suffixLine).put("beforeAndImageSameOwner",prefix.owner==imageOwner).put("afterAndImageSameOwner",suffix.owner==imageOwner).put("imageOffset",imageOffset).put("imageWidth",drawable.getBounds().width()).put("imageHeight",drawable.getBounds().height()).put("imageLineTop",layout.getLineTop(line)).put("imageLineBottom",layout.getLineBottom(line)).put("beforeBaselineScreenY",baseline(prefix)).put("afterBaselineScreenY",baseline(suffix)).put("scrollY",activity.scroll.getScrollY()).put("textOwners",owners);
        int lineHeight=layout.getLineBottom(line)-layout.getLineTop(line),height=drawable.getBounds().height();
        int extra=layout.getLineBottom(line)-layout.getLineBottom(line,false),previousBottom=line==0?0:layout.getLineBottom(line-1);
        return value.put("imageLineHeightPx",lineHeight).put("imageLineOverheadPx",lineHeight-height)
                .put("imageLineExtraPx",extra).put("lineSpacingMultiplier",imageOwner.getLineSpacingMultiplier())
                .put("lineSpacingExtraPx",imageOwner.getLineSpacingExtra())
                .put("beforeTextLineExtraPx",prefix.owner.getLayout().getLineBottom(prefixLine)-prefix.owner.getLayout().getLineBottom(prefixLine,false))
                .put("beforeTextLineHeightPx",prefix.owner.getLayout().getLineBottom(prefixLine)-prefix.owner.getLayout().getLineTop(prefixLine))
                .put("spanVerticalAlignment",image.getVerticalAlignment())
                .put("imageBottomAlignedDrawTopLayoutPx",layout.getLineBottom(line)-height)
                .put("previousLineBottomLayoutPx",previousBottom)
                .put("bottomAlignedGapFromPreviousLinePx",layout.getLineBottom(line)-height-previousBottom);
    }
    private void imageCase(String name,String source,String before,String after,boolean emoji)throws Exception{
        NativeRichText rich=mount(emoji?"表情仍在正文行内":"普通附件与正文的行边界","本机合成图片 · 真实 Android TextView · "+name,before+(emoji?"[s:ac:茶]":"[img]"+source+"[/img]")+after,16);
        await("SYNTHETIC_IMAGE_DECODE_REQUIRED",()->main(()->loaded(rich)));stable();JSONObject value=main(()->inlineSnapshot(rich,before,after));value.put("screenshot",screenshot(name));measurements.put(name,value);
        if(emoji)check("emojiBeforeAndAfterRemainInline",value.getBoolean("beforeSharesImageLine")&&value.getBoolean("afterSharesImageLine"));
        else {value.put("defectReproduced",!value.getBoolean("imageStandalone"));if(expectFixed)check(name+"OrdinaryAttachmentStandalone",value.getBoolean("imageStandalone"));}
    }
    private JSONObject sizeSample(NativeRichText rich,String text,float expectedRatio)throws Exception{
        Marker target=marker(rich,text);Spanned value=(Spanned)target.owner.getText();TextPaint paint=new TextPaint(target.owner.getPaint());MetricAffectingSpan[] spans=value.getSpans(target.offset,target.offset+1,MetricAffectingSpan.class);JSONArray types=new JSONArray();
        for(MetricAffectingSpan span:spans){types.put(new JSONObject().put("class",span.getClass().getName()).put("start",value.getSpanStart(span)).put("end",value.getSpanEnd(span)));span.updateMeasureState(paint);}
        float base=target.owner.getTextSize(),ratio=paint.getTextSize()/base;return new JSONObject().put("marker",text).put("baseTextSizePx",base).put("measuredTextSizePx",paint.getTextSize()).put("ratio",ratio).put("expectedRatio",expectedRatio).put("ratioCorrect",Math.abs(ratio-expectedRatio)<.035f).put("nativeMetricSpans",types);
    }
    private void sizeCase(int font)throws Exception{
        String name="size-"+font;NativeRichText rich=mount("BBCode 字号的真实绘制尺寸","基字号 "+font+"sp · 200% / 50% / 嵌套按基字号解析","普通甲\n[size=200]双倍乙[/size]\n[size=50]半倍丙[/size]\n[size=500]外层丁[size=200]内层戊[/size][/size]",font);stable();
        JSONObject value=main(()->{JSONObject samples=new JSONObject().put("ordinary",sizeSample(rich,"普通甲",1)).put("double",sizeSample(rich,"双倍乙",2)).put("half",sizeSample(rich,"半倍丙",.5f)).put("outer",sizeSample(rich,"外层丁",5)).put("inner",sizeSample(rich,"内层戊",2));boolean correct=true;for(String key:new String[]{"ordinary","double","half","outer","inner"})correct&=samples.getJSONObject(key).getBoolean("ratioCorrect");return new JSONObject().put("fontSp",font).put("samples",samples).put("allRatiosCorrect",correct).put("defectReproduced",!correct);});
        value.put("screenshot",screenshot(name));measurements.put(name,value);if(expectFixed)check(name+"NativeFontRatios",value.getBoolean("allRatiosCorrect"));
    }
    private void scopedCase(String kind)throws Exception{
        String before="块前正文甲",after="块后正文乙",body=before+"[img]"+NARROW+"[/img]"+after;
        if(kind.equals("quote"))body="[quote]"+body+"[/quote]";
        else if(kind.equals("table"))body="[table][tr][td]"+body+"[/td][/tr][/table]";
        else body="[collapse=合成折叠内容]"+body+"[/collapse]";
        NativeRichText rich=mount("原生内容块的格式与附件边界","外层150%与粗体 · "+kind,"[size=150][b]"+body+"[/b][/size]",16);
        if(kind.equals("collapse")) {
            check("collapseStillLazyBeforeExpansion",main(()->media(rich).isEmpty()));
            main(()->{for(TextView view:texts(rich))if(view.getText().toString().contains("合成折叠内容")){view.performClick();return null;}throw new IllegalStateException("SYNTHETIC_COLLAPSE_HEADER_REQUIRED");});
        }
        await("SCOPED_IMAGE_DECODE_REQUIRED",()->main(()->loaded(rich)));stable();
        JSONObject value=main(()->{
            JSONObject result=inlineSnapshot(rich,before,after).put("beforeSize",sizeSample(rich,before,1.5f)).put("afterSize",sizeSample(rich,after,1.5f));
            Marker target=marker(rich,before);Spanned text=(Spanned)target.owner.getText();boolean bold=false;
            for(StyleSpan span:text.getSpans(target.offset,target.offset+1,StyleSpan.class))bold|=(span.getStyle()&android.graphics.Typeface.BOLD)!=0;
            boolean imageLink=false;openedLinks.clear();
            for(TextView owner:texts(rich))if(owner.getText() instanceof Spanned) {
                Spanned source=(Spanned)owner.getText();for(ImageSpan image:source.getSpans(0,source.length(),ImageSpan.class)) {
                    int offset=source.getSpanStart(image);
                    for(URLSpan link:source.getSpans(offset,offset+1,URLSpan.class))if(NARROW.equals(link.getURL())){imageLink=true;link.onClick(owner);}
                }
            }
            return result.put("outerBoldRetained",bold).put("attachmentClickRetained",imageLink&&openedLinks.size()==1&&NARROW.equals(openedLinks.get(0)));
        });
        value.put("screenshot",screenshot("scope-"+kind));measurements.put("scope-"+kind,value);
        check(kind+"AttachmentStandalone",value.getBoolean("imageStandalone"));
        check(kind+"OuterSizeRetained",value.getJSONObject("beforeSize").getBoolean("ratioCorrect")&&value.getJSONObject("afterSize").getBoolean("ratioCorrect"));
        check(kind+"OuterBoldRetained",value.getBoolean("outerBoldRetained"));check(kind+"AttachmentClickRetained",value.getBoolean("attachmentClickRetained"));
    }
    private void sizeEdges()throws Exception{
        NativeRichText rich=mount("字号作用域的边界","同范围嵌套、颜色交错、标题与字体、范围尾部","[size=500][size=200]同范围甲[/size][/size]\n[size=200][color=crimson]颜色甲[/color][size=50]内小乙[/size]尾丙[/size]\n[h][size=200]标题丁[/size][/h]\n[size=150][font=monospace]字体戊[/font][/size]",16);stable();
        JSONObject value=main(()->new JSONObject().put("sameRange",sizeSample(rich,"同范围甲",2)).put("color",sizeSample(rich,"颜色甲",2)).put("inner",sizeSample(rich,"内小乙",.5f)).put("tail",sizeSample(rich,"尾丙",2)).put("heading",sizeSample(rich,"标题丁",2)).put("font",sizeSample(rich,"字体戊",1.5f)));
        for(String key:new String[]{"sameRange","color","inner","tail","heading","font"})check("sizeEdge"+key,value.getJSONObject(key).getBoolean("ratioCorrect"));
        value.put("screenshot",screenshot("size-scopes"));measurements.put("size-scopes",value);
    }
    private void delayedCase()throws Exception{
        NativeRichText rich=mount("迟到图片的文字基线","先记录占位，再释放120×600合成图；仅测量，不扩充生产修复","前置一\n前置二\n前置三\n前置四\n迟到前甲[img]"+SLOW+"[/img]迟到后乙\n后续一\n后续二\n后续三\n后续四\n后续五\n后续六\n后续七\n后续八",16);
        Resource delayed=resources.get(SLOW);await("DELAYED_CONNECTION_ENTERED",()->delayed.entered.getCount()==0);stable();main(()->{Marker reading=marker(rich,"迟到前甲");int[] owner=new int[2],viewport=new int[2];reading.owner.getLocationInWindow(owner);activity.scroll.getLocationInWindow(viewport);int line=reading.owner.getLayout().getLineForOffset(reading.offset);activity.scroll.scrollTo(0,Math.max(0,owner[1]-viewport[1]+reading.owner.getTotalPaddingTop()+reading.owner.getLayout().getLineTop(line)-80));return null;});stable();
        JSONObject before=main(()->inlineSnapshot(rich,"迟到前甲","迟到后乙"));before.put("screenshot",screenshot("delayed-before"));delayed.release.countDown();await("DELAYED_DECODE_REQUIRED",()->main(()->loaded(rich)));stable();JSONObject after=main(()->inlineSnapshot(rich,"迟到前甲","迟到后乙"));after.put("screenshot",screenshot("delayed-after"));measurements.put("delayed-image",new JSONObject().put("before",before).put("after",after).put("sameCharacterBaselineDeltaPx",after.getInt("beforeBaselineScreenY")-before.getInt("beforeBaselineScreenY")).put("diagnosticOnly",true));
    }
    /** Compare proportional growth, not a hardcoded pixel gap; fontScale can change the text metrics. */
    private void spacingAudit()throws Exception{
        JSONObject narrow=measurements.getJSONObject("mixed-image"),wide=measurements.getJSONObject("wide-image");
        JSONObject delayed=measurements.getJSONObject("delayed-image"),before=delayed.getJSONObject("before"),after=delayed.getJSONObject("after");
        int decodedGrowth=Math.abs(wide.getInt("imageLineExtraPx")-narrow.getInt("imageLineExtraPx"));
        int delayedGrowth=Math.abs(after.getInt("imageLineExtraPx")-before.getInt("imageLineExtraPx"));
        JSONObject value=new JSONObject().put("decodedImageHeightDifferencePx",wide.getInt("imageHeight")-narrow.getInt("imageHeight"))
                .put("decodedImageExtraGrowthPx",decodedGrowth).put("delayedImageHeightGrowthPx",after.getInt("imageHeight")-before.getInt("imageHeight"))
                .put("delayedImageExtraGrowthPx",delayedGrowth).put("roundingTolerancePx",1)
                .put("inflationReproduced",decodedGrowth>1||delayedGrowth>1).put("expectedCompactImageSpacing",expectCompactImageSpacing);
        measurements.put("image-spacing-audit",value);
        if(expectCompactImageSpacing){check("decodedImageSpacingIndependentOfImageHeight",decodedGrowth<=1);check("lateDecodedImageSpacingIndependentOfImageHeight",delayedGrowth<=1);}
    }
    private String screenshot(String name)throws Exception{
        stable();Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)throw new IllegalStateException("SCREENSHOT_REQUIRED");File directory=new File(getTargetContext().getFilesDir(),"synthetic-reader-screenshots");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("SCREENSHOT_DIRECTORY_REQUIRED");File output=new File(directory,name+".png");try(FileOutputStream stream=new FileOutputStream(output)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))throw new IOException("SCREENSHOT_ENCODE_REQUIRED");}finally{bitmap.recycle();}return output.getAbsolutePath();
    }
    private static byte[] png(int width,int height,int color,String label)throws IOException{
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(color);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.WHITE);paint.setTextSize(Math.min(width/7f,36));canvas.drawText(label,8,Math.min(height-4,48),paint);try(ByteArrayOutputStream output=new ByteArrayOutputStream()){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,output))throw new IOException("SYNTHETIC_ENCODE_REQUIRED");return output.toByteArray();}finally{bitmap.recycle();}
    }
    private void transport()throws Exception{
        resources.put(NARROW,new Resource(png(120,180,0xff347bb4,"120x180"),false));resources.put(WIDE,new Resource(png(1200,800,0xff538768,"1200x800"),false));resources.put(EMOJI,new Resource(png(24,24,0xffdd9a24,":)"),false));resources.put(SLOW,new Resource(png(120,600,0xff8561a5,"120x600"),true));
        URL.setURLStreamHandlerFactory(protocol->("https".equals(protocol)||"http".equals(protocol))?new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{
            connections.incrementAndGet();Resource resource=resources.get(url.toExternalForm());if(resource==null){unexpected.incrementAndGet();throw new IOException("ONLY_EXPLICIT_SYNTHETIC_MEDIA_ALLOWED");}
            return new HttpURLConnection(url){volatile boolean stopped;@Override public void connect(){}@Override public boolean usingProxy(){return false;}@Override public void disconnect(){stopped=true;resource.release.countDown();}@Override public int getResponseCode()throws IOException{if(getRequestProperty("Cookie")!=null||getRequestProperty("Authorization")!=null)throw new IOException("ANONYMOUS_SYNTHETIC_TRANSPORT_REQUIRED");resource.entered.countDown();try{if(!resource.release.await(12,TimeUnit.SECONDS))throw new IOException("SYNTHETIC_RELEASE_TIMEOUT");}catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new IOException("SYNTHETIC_CANCELLED",cancelled);}if(stopped)throw new IOException("SYNTHETIC_DISCONNECTED");return HTTP_OK;}@Override public long getContentLengthLong(){return resource.bytes.length;}@Override public InputStream getInputStream(){return new ByteArrayInputStream(resource.bytes);}};
        }}:null);
    }
    private interface Scenario {void run()throws Exception;}
    private void scenario(String name,Scenario action)throws Exception{stage=name;try{action.run();}catch(Exception|AssertionError issue){scenarioFailed=true;measurements.put(name,new JSONObject().put("errorType",issue.getClass().getSimpleName()).put("errorMessage",issue.getMessage()==null?"NONE":issue.getMessage()));}}
    private byte[] asset(String name)throws IOException{try(InputStream input=getContext().getAssets().open(name);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);return output.toByteArray();}}
    @Override public void onStart(){try{
        if(!authorized||!PACKAGE.equals(getTargetContext().getPackageName())||getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED||(requestedFontScale!=1f&&requestedFontScale!=1.3f&&requestedFontScale!=2f))throw new IllegalStateException("OWN_UID_NO_INTERNET_SYNTHETIC_READER_REQUIRED");
        check("separateUidFromInstalledClient",getTargetContext().getApplicationInfo().uid!=getTargetContext().getPackageManager().getApplicationInfo("cn.ngapure.app",0).uid);report.put("binding",new JSONObject(new String(asset("binding.json"),StandardCharsets.UTF_8)));transport();
        activity=(FixtureActivity)startActivitySync(new Intent().setClassName(PACKAGE,FixtureActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));await("OWN_ACTIVITY_FOCUS_REQUIRED",()->main(()->activity.hasWindowFocus()));check("localFontScaleApplied",Math.abs(activity.getResources().getConfiguration().fontScale-requestedFontScale)<.001f);images=new NativeImages();
        scenario("mixed-image",()->imageCase("mixed-image",NARROW,"图前正文甲","图后正文乙",false));scenario("wide-image",()->imageCase("wide-image",WIDE,"图前正文甲","图后正文乙",false));scenario("emoji-inline",()->imageCase("emoji-inline",EMOJI,"表情前甲","表情后乙",true));scenario("size-16",()->sizeCase(16));scenario("size-22",()->sizeCase(22));scenario("delayed-image",this::delayedCase);
        scenario("image-spacing-audit",this::spacingAudit);
        if(expectFixed){scenario("scope-quote",()->scopedCase("quote"));scenario("scope-table",()->scopedCase("table"));scenario("scope-collapse",()->scopedCase("collapse"));scenario("size-scopes",this::sizeEdges);}
        boolean imageDefect=measurements.has("mixed-image")&&measurements.getJSONObject("mixed-image").optBoolean("defectReproduced"),sizeDefect=measurements.has("size-16")&&measurements.getJSONObject("size-16").optBoolean("defectReproduced");report.put("imageDefectReproduced",imageDefect).put("sizeDefectReproduced",sizeDefect).put("defectReproduced",imageDefect||sizeDefect);if(!expectFixed)check("baselineReproducesAtLeastOneCandidate",imageDefect||sizeDefect);check("allConnectionsExplicitSynthetic",unexpected.get()==0);report.put("completed",true);
    }catch(Exception|Error issue){scenarioFailed=true;try{report.put("completed",false).put("failureStage",stage).put("failureType",issue.getClass().getSimpleName()).put("failureMessage",issue.getMessage()==null?"NONE":issue.getMessage());}catch(Exception ignored){}}
    finally{for(Resource resource:resources.values())resource.release.countDown();try{if(activity!=null)main(()->{activity.root.removeAllViews();activity.finish();return null;});if(images!=null)images.close();report.put("measurementReport",measurements).put("checks",checks).put("expectFixed",expectFixed).put("assertionCount",assertionCount).put("passed",!scenarioFailed).put("productionClassesRewritten",false).put("realClientStarted",false).put("realAccountAccessed",false).put("realCommunityWrites",0).put("realNetworkRequests",0).put("syntheticConnectionAttempts",connections.get()).put("unexpectedMediaUrls",unexpected.get()).put("localDensityDpi",160).put("localFontScale",requestedFontScale).put("sizeRatioTarget","EXACT_RATIO_TO_ACTUAL_READER_BASE_PAINT_AFTER_ANDROID_FONT_SCALING").put("globalDisplaySettingsChanged",false).put("privateInputsExported",false);File saved=new File(getTargetContext().getFilesDir(),"reader-layout-report.json");try(FileOutputStream stream=new FileOutputStream(saved)){stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}report.put("savedReport",saved.getAbsolutePath());}catch(Exception issue){try{report.put("passed",false).put("cleanupError",issue.getClass().getSimpleName());}catch(Exception ignored){}}Bundle result=new Bundle();result.putString("safe_reader_layout_report",report.toString());finish(Activity.RESULT_OK,result);}
    }
}
