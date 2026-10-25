package cn.ngapure.app;

import android.graphics.Bitmap;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Official signed avatar upload. No account Cookie is forwarded to the image service. */
public final class NativeAvatarUpload {
    public interface Transport {NativeCommunity.Response upload(URI url,Map<String,String> form) throws IOException;}
    private final NativeUserActions actions;private final Transport transport;
    public NativeAvatarUpload(NativeUserActions actions){this(actions,new Https());}
    NativeAvatarUpload(NativeUserActions actions,Transport transport){if(actions==null||transport==null)throw new IllegalArgumentException("缺少头像上传配置");this.actions=actions;this.transport=transport;}
    /** Bitmap must be a user-previewed crop. Preparing or opening the picker never uploads. */
    public NativeUserActions.Outcome upload(Bitmap crop) throws Exception {
        if(crop==null||crop.getWidth()!=180||crop.getHeight()!=180)throw new IllegalArgumentException("请先裁剪头像");
        ByteArrayOutputStream out=new ByteArrayOutputStream();if(!crop.compress(Bitmap.CompressFormat.JPEG,92,out))throw new IOException("无法编码头像");
        return uploadEncoded("data:image/jpeg;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP));
    }
    NativeUserActions.Outcome uploadEncoded(String data) throws Exception {
        if(data==null||!data.startsWith("data:image/jpeg;base64,")||data.length()<=23||data.length()>2*1024*1024||(data.length()-23)%4!=0||!data.substring(23).matches("[A-Za-z0-9+/]*={0,2}"))throw new IllegalArgumentException("头像编码不正确");
        actions.requireCurrent();JSONObject prepare=actions.avatarPrepare();String uid=actions.accountId();
        Object checksum=NativeUserActions.slot(prepare,1),upload=NativeUserActions.slot(prepare,2);
        if(!(checksum instanceof String)||!((String)checksum).matches("[A-Za-z0-9_-]{8,256}")||!(upload instanceof String))throw NativeUserActions.error(NativeCommunity.Code.INVALID_RESPONSE);
        URI url=validateUpload((String)upload);Map<String,String> form=new LinkedHashMap<>();form.put("n"+uid+"_0",data);form.put("func","upload");form.put("avatar","1");form.put("uid",uid);form.put("lite","js");form.put("checksum",(String)checksum);
        actions.requireCurrent();NativeCommunity.Response response;
        try{response=transport.upload(url,form);}catch(IOException interrupted){throw NativeUserActions.error(NativeCommunity.Code.UNCONFIRMED);}
        actions.requireCurrent();if(response==null||response.status>=300&&response.status<400)throw NativeUserActions.error(NativeCommunity.Code.UNCONFIRMED);
        JSONObject decoded;try{decoded=NativeCommunity.decodeUserActionResponse(response);}catch(NativeCommunity.CommunityException failure){if(failure.code==NativeCommunity.Code.INVALID_RESPONSE)throw NativeUserActions.error(NativeCommunity.Code.UNCONFIRMED);throw failure;}
        Object dataBlock=decoded.opt("data");if(dataBlock instanceof JSONObject&&((JSONObject)dataBlock).has("url")){}else dataBlock=NativeUserActions.first(decoded);
        JSONObject result=NativeUserActions.object(dataBlock);Object reference=result.opt("url");
        if(!(reference instanceof String)||!validReference((String)reference,uid))throw NativeUserActions.error(NativeCommunity.Code.UNCONFIRMED);
        return actions.saveAvatarReference((String)reference);
    }
    static boolean validReference(String reference,String uid){return !avatarCandidate(reference,uid).isEmpty();}
    static String avatarCandidate(String reference,String uid){
        if(reference==null||reference.length()>16000)return "";
        String candidate=AvatarSources.normalize(reference);
        if(candidate.isEmpty()) {
            java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("\\.a/"+uid+"_[0-9]{1,2}\\.(?:jpg|jpeg|png|gif|webp\\.jpg|webp)(?:\\?[0-9]+)?").matcher(reference);
            if(!matcher.find())return "";String shortForm=matcher.group();String remainder=reference.substring(0,matcher.start())+reference.substring(matcher.end());if(matcher.find()||!remainder.matches("(?s)[\\s|]*(?:(?:new|keep|t2|[0-9]+)[\\s|]*)*"))return "";candidate=AvatarSources.normalize(shortForm);
        }
        try{URI uri=new URI(candidate);return uri.getPath()!=null&&uri.getPath().matches("/avatars/2002/[0-9a-z]{3}/[0-9a-z]{3}/[0-9a-z]{3}/"+uid+"_[0-9]{1,2}\\.(?:jpg|jpeg|png|gif|webp\\.jpg|webp)")?candidate:"";}catch(Exception invalid){return "";}
    }
    static URI validateUpload(String value) throws Exception {
        URI initial=new URI(value);String scheme=initial.getScheme(),host=initial.getHost(),path=initial.getPath();
        if((!"https".equals(scheme)&&!"http".equals(scheme))||host==null||!host.matches("(?:img|img[0-9]{1,2})\\.nga\\.cn")||initial.getUserInfo()!=null||initial.getPort()!=-1||initial.getFragment()!=null||path==null||!path.matches("/(?:attachments/)?(?:attach|upload)\\.php"))throw NativeUserActions.error(NativeCommunity.Code.INVALID_RESPONSE);
        String query=initial.getRawQuery();if(query!=null){StringBuilder kept=new StringBuilder();for(String part:query.split("&")){String key=part.split("=",2)[0];if(key.equals("checksum"))continue;if(!key.matches("[A-Za-z_][A-Za-z0-9_]{0,40}")||part.length()>2048)throw NativeUserActions.error(NativeCommunity.Code.INVALID_RESPONSE);if(kept.length()>0)kept.append('&');kept.append(part);}query=kept.length()==0?null:kept.toString();}
        return new URI("https://"+host+path+(query==null?"":"?"+query));
    }
    private static String form(Map<String,String> values) throws IOException {StringBuilder text=new StringBuilder();for(Map.Entry<String,String> pair:values.entrySet()){if(text.length()>0)text.append('&');text.append(URLEncoder.encode(pair.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(pair.getValue(),"UTF-8"));}return text.toString();}
    private static final class Https implements Transport {
        public NativeCommunity.Response upload(URI uri,Map<String,String> values) throws IOException {
            HttpURLConnection connection=(HttpURLConnection)uri.toURL().openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(25000);connection.setRequestMethod("POST");connection.setRequestProperty("User-Agent","NGA_WP_JW");connection.setRequestProperty("Accept","application/json,text/plain");connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
            byte[] bytes=form(values).getBytes(StandardCharsets.UTF_8);connection.setDoOutput(true);connection.setFixedLengthStreamingMode(bytes.length);
            try{try(OutputStream output=connection.getOutputStream()){output.write(bytes);}int status=connection.getResponseCode();InputStream stream=status>=400?connection.getErrorStream():status>=300?null:connection.getInputStream();byte[] body=new byte[0];if(stream!=null)try(InputStream in=stream;ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))!=-1;){if(output.size()+n>2*1024*1024)throw new IOException("头像上传响应过大");output.write(buffer,0,n);}body=output.toByteArray();}return new NativeCommunity.Response(status,body,connection.getContentType());}finally{connection.disconnect();}
        }
    }
}
