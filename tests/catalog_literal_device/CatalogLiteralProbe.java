package cn.ngapure.app;

import android.app.Instrumentation;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Debug;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Html;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Unmodified full production JAR, its bundled catalog, and the actual platform Html oracle. */
public final class CatalogLiteralProbe extends Instrumentation {
    private static final String PACKAGE="cn.ngapure.catalogliteralprobe";
    private final JSONObject report=new JSONObject();
    private final JSONArray samples=new JSONArray(),checks=new JSONArray(),textResults=new JSONArray();
    private boolean authorized,optimized;private int assertions;private String stage="guard";
    private byte[] canonicalBytes;
    private static final class Sample { List<NgaApi.Forum> rows;long wall,cpu;boolean main; }
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);
        authorized=arguments!=null&&"true".equals(arguments.getString("bundledCatalogOnly"))
                &&("true".equals(arguments.getString("expectOptimized"))||"false".equals(arguments.getString("expectOptimized")));
        optimized=arguments!=null&&"true".equals(arguments.getString("expectOptimized"));start();}
    private <T>T main(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get(30,TimeUnit.SECONDS);}
    private void check(String label,boolean value){assertions++;checks.put(label);if(!value)throw new AssertionError(label);}
    private static String sha(byte[] bytes)throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder text=new StringBuilder();for(byte b:hash)text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return text.toString();}
    private byte[] asset(String name)throws Exception{try(InputStream in=getTargetContext().getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}}
    private static byte[] canonical(List<NgaApi.Forum> rows)throws Exception{
        Field[] fields=NgaApi.Forum.class.getFields();Arrays.sort(fields,Comparator.comparing(Field::getName));JSONArray names=new JSONArray(),values=new JSONArray();
        for(Field f:fields)names.put(f.getName());for(NgaApi.Forum row:rows){JSONArray cells=new JSONArray();for(Field f:fields){Object value=f.get(row);
            if(value instanceof String)cells.put(value);else if(value instanceof List)cells.put(new JSONArray((List<?>)value));else throw new AssertionError("UnknownPublicField");}values.put(cells);}
        return new JSONArray().put(names).put(values).toString().getBytes(StandardCharsets.UTF_8);
    }
    private Sample sample(NgaApi api)throws Exception{return main(()->{Sample s=new Sample();s.main=Looper.myLooper()==Looper.getMainLooper();long cpu=Debug.threadCpuTimeNanos(),wall=SystemClock.elapsedRealtimeNanos();s.rows=api.forums();s.wall=SystemClock.elapsedRealtimeNanos()-wall;s.cpu=Debug.threadCpuTimeNanos()-cpu;return s;});}
    /** Deliberately the prior behavior using real Android Html, not a test replacement. */
    private static String oracle(String raw,int maximum){String clean=Html.fromHtml(raw==null?"":raw,Html.FROM_HTML_MODE_LEGACY).toString().trim().replaceAll("[\\x00-\\x1f\\x7f]"," ").trim();if(clean.length()<=maximum)return clean;int end=Character.isHighSurrogate(clean.charAt(maximum-1))?maximum-1:maximum;return clean.substring(0,end);}
    private static String repeat(String value,int count){StringBuilder text=new StringBuilder();for(int i=0;i<count;i++)text.append(value);return text.toString();}
    private static String units(String value){StringBuilder text=new StringBuilder();for(int i=0;i<value.length();i++)text.append(String.format(java.util.Locale.ROOT,"%04x",(int)value.charAt(i)));return text.toString();}
    private void textCases()throws Exception{
        Method text=NgaApi.class.getDeclaredMethod("catalogText",String.class,int.class);text.setAccessible(true);
        Map<String,String> cases=new LinkedHashMap<>();cases.put("null",null);cases.put("empty","");cases.put("ascii","NGA123-_=+?/.>\"'`!@#$%^*()[]{}:;,\\|");
        cases.put("cjk","明日方舟：终末地，中文『标题』！");cases.put("latin","éÅÜß");cases.put("cyrillic","Кириллица");cases.put("arabic","العربية");cases.put("kana","かなカナ");cases.put("korean","한글");cases.put("selectors","A\ufe0fB");
        for(int i=0;i<160;i++)cases.put("codeunit-"+i,"A"+(char)i+"B");
        int[] special={0xa0,0x1680,0x180e,0x2000,0x2001,0x2002,0x2003,0x2004,0x2005,0x2006,0x2007,0x2008,0x2009,0x200a,0x200b,0x200c,0x200d,0x2028,0x2029,0x202f,0x205f,0x2060,0x2066,0x2069,0x3000,0xfeff,0xfffd,0xfffe,0xffff};
        for(int c:special)cases.put("unicode-"+c,"A"+(char)c+"B");
        String[] edge={"  leading and trailing  ","A  B","A\n\nB","A\r\nB","A\t B","<b>Name</b>","<p>One</p><p>Two</p>","<br>Line<br>","<div><i>Nested</i></div>","<script>raw</script>","<unknown>Name</unknown>","A<!-- hidden -->B","a < b","&amp;","&amp;amp;","&#39;","&#92;","&#128512;","&#x1F600;","&#xD800;","&nbsp;","&unknown;","&;","A&","<","😀","A😀B","\ud800","A\udc00B","\ud800\ud800",repeat("x",79)+"😀",repeat("x",159)+"😀",repeat("y",511)+"😀"};
        for(int i=0;i<edge.length;i++)cases.put("edge-"+i,edge[i]);
        for(Map.Entry<String,String> row:cases.entrySet())for(int maximum:new int[]{1,2,3,80,160,512}){
            String expected=oracle(row.getValue(),maximum),actual=(String)text.invoke(null,row.getValue(),maximum);
            check("AndroidHtmlEquivalent-"+row.getKey()+"-"+maximum,expected.equals(actual));
            textResults.put(new JSONObject().put("case",row.getKey()).put("maximum",maximum).put("utf16Output",units(actual)));
        }
        if(optimized){Method literal=NgaApi.class.getDeclaredMethod("catalogLiteral",String.class);literal.setAccessible(true);
            for(String value:new String[]{"NGA123","明日方舟：终末地","Кириллица","かなカナ","éÅÜß"})check("literalBypassSelected",Boolean.TRUE.equals(literal.invoke(null,value)));
            for(String value:new String[]{"A B","A\nB","&amp;","<b>NGA</b>","😀","A\u0080B","A\u200bB","A\ufeffB","A\uffffB"})check("normalizationSensitiveFallback",Boolean.FALSE.equals(literal.invoke(null,value)));
        }
        report.put("adversarialCaseCount",cases.size()).put("adversarialComparisons",textResults.length()).put("androidOracleUsesPriorAlgorithm",true);
    }
    @Override public void onStart(){boolean passed=false;String failure="NONE";
        try{
            if(!authorized||!PACKAGE.equals(getTargetContext().getPackageName())||getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("OwnUidNoInternetRequired");
            check("OwnUidSeparateFromClient",getTargetContext().getApplicationInfo().uid!=getTargetContext().getPackageManager().getApplicationInfo("cn.ngapure.app",0).uid);
            URL.setURLStreamHandlerFactory(protocol->("https".equals(protocol)||"http".equals(protocol))?new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{throw new IOException("BundledCatalogOnly");}}:null);
            report.put("binding",new JSONObject(new String(asset("binding.json"),StandardCharsets.UTF_8)));
            NgaApi api=main(()->new NgaApi(getTargetContext()));String canonicalHash="";
            for(int i=0;i<8;i++){stage="sample-"+i;Sample s=sample(api);byte[] output=canonical(s.rows);String hash=sha(output);if(i==0){canonicalBytes=output;canonicalHash=hash;}
                check("SampleOnMain-"+i,s.main);check("All658Destinations-"+i,s.rows.size()==658);check("CompleteFieldsStable-"+i,canonicalHash.equals(hash));
                samples.put(new JSONObject().put("index",i).put("firstCatalogInvocation",i==0).put("wallNanos",s.wall).put("mainThreadCpuNanos",s.cpu).put("rowCount",s.rows.size()).put("canonicalSha256",hash));waitForIdleSync();}
            stage="android-text-oracle";textCases();
            stage="parallel-read";FutureTask<byte[]> background=new FutureTask<>(()->canonical(api.forums()));Thread worker=new Thread(background,"catalog-fixture-worker");worker.start();Sample foreground=sample(api);
            check("SameInstanceParallelCatalogReads",canonicalHash.equals(sha(background.get(30,TimeUnit.SECONDS)))&&canonicalHash.equals(sha(canonical(foreground.rows))));
            Files.write(new File(getTargetContext().getFilesDir(),"public-catalog-output.json").toPath(),canonicalBytes);
            byte[] textBytes=textResults.toString().getBytes(StandardCharsets.UTF_8);Files.write(new File(getTargetContext().getFilesDir(),"adversarial-text-output.json").toPath(),textBytes);
            report.put("canonicalSha256",canonicalHash).put("canonicalBytes",canonicalBytes.length).put("adversarialOutputSha256",sha(textBytes));passed=true;
        }catch(Throwable error){Throwable cause=error;for(int i=0;i<10&&cause.getCause()!=null;i++)cause=cause.getCause();failure=cause.getClass().getSimpleName();}
        try{report.put("passed",passed).put("failure",failure).put("failureStage",stage).put("expectOptimized",optimized).put("assertions",assertions).put("checks",checks).put("samples",samples)
            .put("firstInvocationSamples",1).put("subsequentSamples",7).put("actualAndroidHtml",true).put("allPublicCatalogFieldsIncluded",true)
            .put("ownUidOnly",true).put("internetPermission",false).put("realClientStarted",false).put("realAccountRead",false).put("realNetworkRequests",0).put("communityWrites",0).put("productionClassesRewritten",false).put("productFieldsInjected",false)
            .put("limitations",new JSONArray().put("One first catalog invocation and seven repeated calls per process, not seven cold starts.").put("Only production bundled-asset reading/parse on main is timed; canonical encoding and oracle comparisons are outside samples.").put("Android oracle verifies exact prior Html cleanup and truncation, including whitespace, entities, controls and UTF-16 boundaries.").put("No claim about server latency, full startup, physical touch, GPU or FPS; root measures actual startup separately."));}catch(Exception ignored){}
        Bundle result=new Bundle();result.putString("safe_catalog_literal_report",report.toString());finish(passed?0:1,result);
    }
}
