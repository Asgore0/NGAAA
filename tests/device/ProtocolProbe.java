package cn.ngapure.verify;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import cn.ngapure.app.NgaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Safe protocol exploration; the explicit reset flag only clears two local sign-in cache keys. */
public final class ProtocolProbe extends Instrumentation {
    private static final Set<String> KEYS=new HashSet<>(Arrays.asList("data","error","time","code","result","msg","message","folders","id","name","type","default","opt","count","page","nextPage","currentPage","rowsPerPage","more","uid","folder","public","status","__MESSAGE"));
    private boolean exchangeCurrentSession;
    private boolean remindersOnly;
    private boolean qrRecoveryOnly;
    private boolean acceptanceSnapshot;
    private boolean resetSignInDay;
    private boolean ifloginCurrent;
    @Override public void onCreate(Bundle args){super.onCreate(args);exchangeCurrentSession=flag(args,"sessionExchange");remindersOnly=flag(args,"remindersOnly");qrRecoveryOnly=flag(args,"qrRecoveryOnly");acceptanceSnapshot=flag(args,"acceptanceSnapshot");resetSignInDay=flag(args,"resetSignInDay");ifloginCurrent=flag(args,"ifloginCurrent");start();}
    @Override public void onStart(){JSONObject report=new JSONObject();
        // This must precede all legacy branches: reset never performs a network request,
        // even if another probe flag was accidentally supplied at the same time.
        if(acceptanceSnapshot||resetSignInDay){acceptanceReport();return;}
        if(qrRecoveryOnly){
            try{report.put("qrRecoveryCheckpoint",qrRecoveryCheckpoint());}catch(Exception unavailable){throw new IllegalStateException("Safe checkpoint report unavailable");}
            Bundle result=new Bundle();result.putString("safe_protocol_shapes",report.toString());finish(Activity.RESULT_OK,result);return;
        }
        NgaStore store=new NgaStore(getTargetContext());
        if(remindersOnly){probe(report,store,"remindersRead","nuke.php?__lib=noti&__act=get_all&__output=8&__inchst=UTF8&raw=3&time_limit=0",null);Bundle result=new Bundle();result.putString("safe_protocol_shapes",report.toString());finish(Activity.RESULT_OK,result);return;}
        probe(report,store,"folderPicker","nuke.php?__lib=topic_favor_v2&__act=list_folder","__output=3");
        probe(report,store,"folderManager","nuke.php?__lib=topic_favor_v2&__act=list_folder","__output=3&page=1&uid="+encode(store.accountId()));
        probe(report,store,"favoritesScripts","thread.php?favor=1",null);
        probe(report,store,"ifloginExisting","nuke.php","__lib=login&__act=iflogin&__output=14&app_id=5004&device=&__ngaClientChecksum=");
        if(exchangeCurrentSession){String cid=cookie(store.cookie(),"ngaPassportCid");if(!cid.isEmpty()){
            probe(report,store,"quickExisting","nuke.php?__lib=login&__act=login_set_cookie_quick&__output=14","uid="+encode(store.accountId())+"&cid="+encode(cid));
            exchangeComponent(report,store,cid);
        }}
        Bundle result=new Bundle();result.putString("safe_protocol_shapes",report.toString());finish(Activity.RESULT_OK,result);
    }
    private static boolean flag(Bundle args,String key){return args!=null&&"true".equals(args.getString(key));}

    /** Fixed schema only. No sign-in API, scheduling API, account write or clock change. */
    private void acceptanceReport(){
        JSONObject report=new JSONObject(),snapshot=emptyAcceptanceSnapshot();
        boolean cacheReset=false,snapshotAvailable=false;String errorType="NONE";NgaStore store=null;
        try{
            Context context=getTargetContext();SharedPreferences preferences=context.getSharedPreferences("nga_local",Context.MODE_PRIVATE);
            if(resetSignInDay){
                cacheReset=preferences.edit().remove("check_in_day").remove("check_in_message").commit();
                if(!cacheReset)errorType="RESET_COMMIT_FAILED";
            }
            store=new NgaStore(context);snapshot=acceptanceState(store,preferences);snapshotAvailable=true;
        }catch(Exception unavailable){errorType="SNAPSHOT_UNAVAILABLE";}
        safePut(report,"cacheResetRequested",resetSignInDay);safePut(report,"cacheReset",cacheReset);
        safePut(report,"snapshotAvailable",snapshotAvailable);safePut(report,"snapshot",snapshot);
        safePut(report,"errorType",errorType);
        // Optional, explicit read-only verification. resetSignInDay always disables it.
        JSONObject verification=emptyCurrentVerification();
        safePut(verification,"requested",ifloginCurrent);
        if(ifloginCurrent&&resetSignInDay)safePut(verification,"errorType","DISABLED_FOR_RESET");
        else if(ifloginCurrent&&store!=null)verification=currentVerification(store);
        else if(ifloginCurrent)safePut(verification,"errorType","SNAPSHOT_UNAVAILABLE");
        safePut(report,"ifloginCurrent",verification);
        Bundle result=new Bundle();result.putString("safe_protocol_shapes",report.toString());finish(Activity.RESULT_OK,result);
    }
    private static JSONObject emptyAcceptanceSnapshot(){
        JSONObject result=new JSONObject();safePut(result,"loggedIn",false);safePut(result,"autoCheckIn",false);
        safePut(result,"checkInToday",false);safePut(result,"checkInMessageState","UNCONFIRMED");
        safePut(result,"attemptOrigin","其他");safePut(result,"attemptTime","");
        safePut(result,"qrPending",false);safePut(result,"jobScheduled",false);return result;
    }
    private JSONObject acceptanceState(NgaStore store,SharedPreferences preferences)throws Exception{
        JSONObject result=emptyAcceptanceSnapshot();
        result.put("loggedIn",store.loggedIn());result.put("autoCheckIn",store.autoCheckIn());
        result.put("checkInToday",NgaStore.today().equals(store.lastCheckInDay()));
        // Exact canonical cache messages only; no contains/prefix success guessing or raw message output.
        String message=preferences.getString("check_in_message","");
        result.put("checkInMessageState","今天已签到".equals(message)?"CONFIRMED_ALREADY":
                "签到成功".equals(message)?"CONFIRMED_SUCCESS":"UNCONFIRMED");
        String failure="NONE";
        if(!"今天已签到".equals(message)&&!"签到成功".equals(message)){
            Matcher http=Pattern.compile("HTTP ([1-5][0-9]{2})").matcher(message);
            failure=message.isEmpty()?"EMPTY_CACHE":http.find()?"HTTP_"+http.group(1):
                    message.contains("Unable to resolve host")||message.contains("解析")?"DNS":
                    message.contains("timed out")||message.contains("timeout")?"TIMEOUT":
                    message.contains("网页")||message.contains("无法识别")?"NON_JSON":
                    message.contains("账号已变化")?"ACCOUNT_CHANGED":
                    message.contains("频繁")||message.contains("太快")?"RATE_LIMIT":
                    message.contains("登录")?"AUTH_REQUIRED":
                    message.contains("验证")?"CHALLENGE":
                    message.contains("权限")?"PERMISSION":
                    message.contains("reset")||message.contains("closed")||message.contains("stream")||message.contains("connect")||message.contains("SSL")||message.contains("handshake")||message.contains("ECONN")?"NETWORK_CONNECTION":
                    message.contains("网络")?"NETWORK":
                    message.contains("未明确确认")?"NO_EXPLICIT_CONFIRMATION":"OTHER_UNCONFIRMED";
        }
        result.put("checkInFailureClass",failure);
        if(!"NONE".equals(failure)){
            JSONArray markers=new JSONArray();
            for(String marker:new String[]{"签到未完成：","签到成功","已签到","签过","错误","失败","未能","无法","返回","频繁","登录","账号","验证码","安全","认证","暂时","稍后","需要","权限","过期","非法","请","HTTP","SSL","Connection","connect","timeout","Timed","stream","网络","NGA","null","Forbidden","Exception","json","JSON","不"})if(message.contains(marker))markers.put(marker);
            result.put("checkInFailureMarkers",markers);
        }
        String attempt=store.lastCheckInAttempt();String origin="其他",time="";
        if(attempt!=null){int split=attempt.lastIndexOf(" · ");if(split>=0){
            String candidateOrigin=attempt.substring(0,split),candidateTime=attempt.substring(split+3);
            if("打开应用补签".equals(candidateOrigin)||"后台定时签到".equals(candidateOrigin)||"手动签到".equals(candidateOrigin))origin=candidateOrigin;
            if(candidateTime.matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"))time=candidateTime;
        }}
        result.put("attemptOrigin",origin);result.put("attemptTime",time);
        // Presence only: never decrypt the checkpoint or treat it as valid authorization.
        String blob=getTargetContext().getSharedPreferences("nga_qr_recovery",Context.MODE_PRIVATE).getString("blob","");
        result.put("qrPending",blob!=null&&!blob.isEmpty());
        JobScheduler scheduler=(JobScheduler)getTargetContext().getSystemService(Context.JOB_SCHEDULER_SERVICE);
        // Job existence is not evidence that it ran or that a server request succeeded.
        result.put("jobScheduled",scheduler!=null&&scheduler.getPendingJob(26018)!=null);
        return result;
    }
    private static JSONObject emptyCurrentVerification(){
        JSONObject result=new JSONObject();safePut(result,"requested",false);safePut(result,"performed",false);
        safePut(result,"http",0);safePut(result,"protocolCodePresent",false);safePut(result,"protocolCode",0);
        safePut(result,"verified",false);safePut(result,"errorType","NONE");return result;
    }
    private static JSONObject currentVerification(NgaStore store){
        JSONObject safe=emptyCurrentVerification();safePut(safe,"requested",true);safePut(safe,"performed",true);
        try{
            JSONObject temporary=new JSONObject();probe(temporary,store,"ifloginExisting","nuke.php",
                    "__lib=login&__act=iflogin&__output=14&app_id=5004&device=&__ngaClientChecksum=");
            JSONObject item=temporary.optJSONObject("ifloginExisting");
            if(item==null){safePut(safe,"errorType","PROBE_UNAVAILABLE");return safe;}
            int http=item.optInt("http",0);if(http<100||http>599)http=0;safePut(safe,"http",http);
            Object code=item.opt("protocolCode");boolean valid=false;int normalized=0;
            if(code instanceof Number){double number=((Number)code).doubleValue();valid=Double.isFinite(number)&&number==Math.rint(number)&&number>-65536&&number<65536;if(valid)normalized=(int)number;}
            safePut(safe,"protocolCodePresent",valid);safePut(safe,"protocolCode",normalized);
            boolean verified=http==200&&valid&&normalized==0&&!item.optBoolean("hasError",true)&&item.optBoolean("recognizedByNativeVerifier",false);
            safePut(safe,"verified",verified);if(http!=200)safePut(safe,"errorType","HTTP_UNAVAILABLE");
        }catch(Exception unavailable){safePut(safe,"errorType","PROBE_UNAVAILABLE");}
        return safe;
    }
    private static void safePut(JSONObject target,String key,Object value){try{target.put(key,value);}catch(Exception ignored){}}
    /** Read-only envelope metadata; no key access, decryption, network or preference writes. */
    private JSONObject qrRecoveryCheckpoint()throws Exception{
        boolean present=false,encryptedEnvelope=false,noPlaintextGrantFields=false;
        try{
            android.content.SharedPreferences preferences=getTargetContext().getSharedPreferences("nga_qr_recovery",0);
            java.util.Map<String,?> fields=preferences.getAll();Object value=fields.get("blob");
            present=value instanceof String&&!((String)value).isEmpty();
            encryptedEnvelope=present&&encryptedCheckpointEnvelope((String)value);
            // Production only stores owner/host/blob. Reject any extra top-level plaintext field,
            // including grantUid/grantToken/qrkey/hiddenkey/cookies, without returning its name or value.
            noPlaintextGrantFields=true;
            for(String name:fields.keySet())if(!"owner".equals(name)&&!"host".equals(name)&&!"blob".equals(name)){noPlaintextGrantFields=false;break;}
        }catch(RuntimeException unreadable){present=false;encryptedEnvelope=false;noPlaintextGrantFields=false;}
        JSONObject result=new JSONObject();result.put("present",present);result.put("encryptedEnvelope",encryptedEnvelope);result.put("noPlaintextGrantFields",noPlaintextGrantFields);return result;
    }
    /** Mirrors NativeQrRecoveryStore's v1 envelope syntax only; this is not an authenticity/restore claim. */
    private static boolean encryptedCheckpointEnvelope(String blob){
        if(blob.length()>600000)return false;String[] parts=blob.split(":",-1);
        if(parts.length!=3||!"1".equals(parts[0]))return false;
        byte[] iv=null,ciphertext=null;
        try{iv=java.util.Base64.getDecoder().decode(parts[1]);ciphertext=java.util.Base64.getDecoder().decode(parts[2]);return iv.length==12&&ciphertext.length>=16&&ciphertext.length<=64*1024*6+4096+16;}
        catch(IllegalArgumentException invalid){return false;}
        finally{if(iv!=null)Arrays.fill(iv,(byte)0);if(ciphertext!=null)Arrays.fill(ciphertext,(byte)0);}
    }
    /** Exercises the exact exchange/verify implementation using our retained session, never a claimed QR grant. */
    private static void exchangeComponent(JSONObject report,NgaStore store,String cid){
        cn.ngapure.app.NativeAuth auth=new cn.ngapure.app.NativeAuth(store.host());
        try{
            cn.ngapure.app.NativeAuth.QrSession session=auth.begin();
            for(String name:new String[]{"grantUid","grantToken"}){
                java.lang.reflect.Field field=session.getClass().getDeclaredField(name);field.setAccessible(true);
                field.set(session,"grantUid".equals(name)?store.accountId():cid);
            }
            java.lang.reflect.Method finish=auth.getClass().getDeclaredMethod("finish",session.getClass());finish.setAccessible(true);
            cn.ngapure.app.NativeAuth.PollResult result=(cn.ngapure.app.NativeAuth.PollResult)finish.invoke(auth,session);
            report.put("retainedSessionExchangeComponent",result.state.name());
        }catch(Exception unavailable){try{report.put("retainedSessionExchangeComponent","FAIL "+unavailable.getClass().getSimpleName());}catch(Exception ignored){}}
        finally{try{report.put("exchangeComponentSafeDiagnostics",auth.safeDiagnostics());}catch(Exception ignored){}auth.close();}
    }
    private static void probe(JSONObject report,NgaStore store,String label,String path,String form){
        try{if(!NgaStore.HOSTS.contains(store.host()))throw new IllegalStateException("host rejected");HttpURLConnection c=(HttpURLConnection)new URL("https://"+store.host()+"/"+path).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setRequestProperty("User-Agent","NGA_WP_JW");c.setRequestProperty("Cookie",store.cookie());c.setRequestProperty("Referer","https://"+store.host()+"/");
            try{if(form!=null){byte[] bytes=form.getBytes(StandardCharsets.UTF_8);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
                int status=c.getResponseCode();JSONObject item=new JSONObject();item.put("http",status);String contentType=c.getContentType();item.put("contentType",contentType==null?"unknown":contentType);if(status!=200){report.put(label,item);return;}
                byte[] bytes;try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>8*1024*1024)throw new IllegalStateException("response limit");out.write(buf,0,n);}bytes=out.toByteArray();}
                String raw=new String(bytes,contentType!=null&&contentType.toLowerCase(java.util.Locale.ROOT).contains("utf")?StandardCharsets.UTF_8:Charset.forName("GB18030"));item.put("bytes",bytes.length);
                if("favoritesScripts".equals(label)){JSONArray scripts=new JSONArray();Matcher m=Pattern.compile("<script\\b[^>]*\\bsrc=['\"]([^'\"]+)['\"]",Pattern.CASE_INSENSITIVE).matcher(raw);while(m.find()&&scripts.length()<30){URL url=new URL(new URL("https://"+store.host()+"/"),m.group(1).replace("&amp;","&"));String host=url.getHost().toLowerCase(java.util.Locale.ROOT);if(!host.endsWith(".nga.cn")&&!host.endsWith(".nga.178.com")&&!NgaStore.HOSTS.contains(host))continue;if(url.getUserInfo()!=null||!"https".equals(url.getProtocol()))continue;String query=url.getQuery();scripts.put("https://"+host+url.getPath()+(query!=null&&query.matches("[0-9]{1,12}")?"?"+query:""));}item.put("publicScriptSources",scripts);}
                else{int start=raw.indexOf('{');if(start<0)throw new IllegalStateException("non-object envelope");Object root=new JSONTokener(raw.substring(start)).nextValue();item.put("shape",shape(root,0));if(root instanceof JSONObject){JSONObject object=(JSONObject)root;Object error=object.opt("error");item.put("hasError",error!=null&&error!=JSONObject.NULL);if("remindersRead".equals(label))reminderShape(item,object);Object code=object.opt("code");if(code instanceof Number&&Math.abs(((Number)code).longValue())<65536)item.put("protocolCode",code);if("ifloginExisting".equals(label)){Object values=object.opt("result"),first=values instanceof JSONArray?((JSONArray)values).opt(0):values instanceof JSONObject?((JSONObject)values).opt("0"):null;java.lang.reflect.Method verified=Class.forName("cn.ngapure.app.NativeAuth").getDeclaredMethod("verified",Object.class,String.class);verified.setAccessible(true);item.put("recognizedByNativeVerifier",verified.invoke(null,first,store.accountId()));item.put("firstMatchesAccount",first instanceof String&&first.equals(store.accountId()));}}}
                report.put(label,item);
            }finally{c.disconnect();}
        }catch(Exception e){try{report.put(label,"FAIL "+e.getClass().getSimpleName());}catch(Exception ignored){}}
    }
    private static void reminderShape(JSONObject report,JSONObject root)throws Exception{
        Object data=root.opt("data");Object first=data instanceof JSONArray?((JSONArray)data).opt(0):data instanceof JSONObject?((JSONObject)data).opt("0"):null;
        report.put("data0Type",type(first));report.put("data0EmptyString",first instanceof String&&((String)first).isEmpty());
        if(first instanceof JSONObject){JSONObject block=(JSONObject)first;JSONArray groups=new JSONArray();for(String name:new String[]{"0","1","2","unread"}){JSONObject row=new JSONObject();Object value=block.opt(name);row.put("category",name);row.put("present",block.has(name));row.put("type",type(value));if(value instanceof JSONArray)row.put("length",((JSONArray)value).length());else if(value instanceof JSONObject)row.put("length",((JSONObject)value).length());groups.put(row);}report.put("categoryShapes",groups);}
    }
    private static String type(Object value){return value==null?"absent":value==JSONObject.NULL?"null":value instanceof JSONObject?"object":value instanceof JSONArray?"array":value instanceof Number?"number":value instanceof Boolean?"boolean":"string";}
    private static Object shape(Object value,int depth)throws Exception{
        if(depth>7)return "depth-limit";
        if(value instanceof JSONObject){JSONObject result=new JSONObject();JSONArray indexed=new JSONArray();Iterator<String> keys=((JSONObject)value).keys();int count=0;while(keys.hasNext()&&count++<50){String key=keys.next();Object child=((JSONObject)value).opt(key);if(KEYS.contains(key))result.put(key,shape(child,depth+1));else if(depth==1&&key.matches("[0-3]"))result.put("slot"+key,shape(child,depth+1));else if(indexed.length()<2)indexed.put(shape(child,depth+1));}if(indexed.length()>0)result.put("redactedEntries",indexed);return result;}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;JSONObject result=new JSONObject();result.put("length",array.length());JSONArray samples=new JSONArray();for(int i=0;i<Math.min(2,array.length());i++)samples.put(shape(array.opt(i),depth+1));result.put("sampleTypes",samples);return result;}
        return value==null||value==JSONObject.NULL?"null":value instanceof Number?"number":value instanceof Boolean?"boolean":"string";
    }
    private static String encode(String value){try{return URLEncoder.encode(value,"UTF-8");}catch(Exception e){throw new IllegalStateException(e);}}
    private static String cookie(String header,String name){for(String part:header.split(";")){int at=part.indexOf('=');if(at>0&&part.substring(0,at).trim().equals(name))return part.substring(at+1).trim();}return "";}
}
