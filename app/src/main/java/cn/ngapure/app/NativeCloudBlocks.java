package cn.ngapure.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Official ucp get_block_word/set_block_word version-1 document, preserved as a whole. */
public final class NativeCloudBlocks {
    private NativeCloudBlocks(){}
    public static final class Snapshot {
        public final String keywords,authors;public final int limit;public final long etag;
        private Snapshot(String content,String users,int maximum,long version){keywords=words(content);authors=words(users);limit=maximum;etag=version;}
        public boolean contains(String uid,String name){for(String token:tokens(authors))if(matches(token,uid,name))return true;return false;}
        public int keywordCount(){return tokens(keywords).size();}public int authorCount(){return tokens(authors).size();}
        String document(){return keywords.isEmpty()&&authors.isEmpty()?"":"1\n"+keywords+"\n"+authors;}
    }
    static Snapshot read(NativeUserActions api) throws Exception {
        JSONObject response=api.request("ucp","get_block_word",NativeUserActions.params("uid",api.accountId()),false);Object raw=NativeUserActions.slot(response,0),maximum=NativeUserActions.slot(response,1),version=NativeUserActions.slot(response,2);
        if(!(raw instanceof String)||((String)raw).length()>200000)throw NativeUserActions.error(NativeCommunity.Code.INVALID_RESPONSE);
        int limit=(int)integer(maximum,0,100000);long etag=integer(version,0,253402300799L);String text=(String)raw;
        if(text.trim().isEmpty())return new Snapshot("","",limit,etag);String[] lines=text.replace("\r\n","\n").split("\n",-1);if(lines.length!=3||!lines[0].equals("1"))throw NativeUserActions.error(NativeCommunity.Code.INVALID_RESPONSE);
        return new Snapshot(lines[1],lines[2],limit,etag);
    }
    static NativeUserActions.Outcome set(NativeUserActions api,String uid,String name,boolean blocked) throws Exception {
        if(uid==null||!uid.matches("[1-9][0-9]{0,17}")||uid.equals(api.accountId()))throw new IllegalArgumentException("不能屏蔽自己或无效用户");
        Snapshot before=read(api);LinkedHashSet<String> authors=new LinkedHashSet<>(tokens(before.authors));boolean exists=before.contains(uid,name);
        if(exists==blocked){api.cacheBlocks(before);return new NativeUserActions.Outcome(blocked?"云端已屏蔽此用户":"云端已解除屏蔽",uid);}
        if(blocked)authors.add(uid);else authors.removeIf(token->matches(token,uid,name));
        return save(api,before,before.keywords,join(authors));
    }
    static NativeUserActions.Outcome save(NativeUserActions api,Snapshot before,String keywords,String authors) throws Exception {
        if(before==null)throw new IllegalArgumentException("请重新读取云端屏蔽列表");validateInput(keywords);validateInput(authors);Snapshot desired=new Snapshot(keywords,authors,before.limit,before.etag);
        if(before.limit>0&&desired.keywordCount()+desired.authorCount()>before.limit)throw new IllegalArgumentException("云端最多允许 "+before.limit+" 个屏蔽词或用户，请减少条目");
        Snapshot fresh=read(api);if(fresh.etag!=before.etag||!fresh.document().equals(before.document()))throw new ConflictException();
        if(fresh.document().equals(desired.document())){api.cacheBlocks(fresh);return new NativeUserActions.Outcome("屏蔽列表与云端一致","");}
        try{api.request("ucp","set_block_word",NativeUserActions.params("data",desired.document()),true);}catch(NativeCommunity.CommunityException failure){if(failure.code!=NativeCommunity.Code.UNCONFIRMED)throw failure;}
        Snapshot confirmed;try{confirmed=read(api);if(!confirmed.document().equals(desired.document()))throw NativeUserActions.error(NativeCommunity.Code.UNCONFIRMED);}catch(Exception uncertain){throw NativeUserActions.error(NativeCommunity.Code.UNCONFIRMED);}
        api.cacheBlocks(confirmed);return new NativeUserActions.Outcome("屏蔽列表已保存，云端内容已确认","");
    }
    public static final class CacheException extends IOException {private static final long serialVersionUID=1L;CacheException(){super("云端屏蔽状态已确认，但本机过滤缓存更新失败。请重新打开云端屏蔽设置刷新；无需重复提交。");}}
    public static final class ConflictException extends IOException {private static final long serialVersionUID=1L;ConflictException(){super("云端屏蔽列表已在其他设备更新，请重新打开后再保存");}}
    private static long integer(Object raw,long min,long max) throws NativeCommunity.CommunityException {if(!(raw instanceof Number)&&!(raw instanceof String))throw NativeUserActions.error(NativeCommunity.Code.INVALID_RESPONSE);try{String text=String.valueOf(raw);if(!text.matches("[0-9]{1,12}"))throw new NumberFormatException();long value=Long.parseLong(text);if(value<min||value>max)throw new NumberFormatException();return value;}catch(NumberFormatException invalid){throw NativeUserActions.error(NativeCommunity.Code.INVALID_RESPONSE);}}
    private static void validateInput(String raw){if(raw==null||raw.length()>100000||raw.indexOf('\0')>=0)throw new IllegalArgumentException("屏蔽列表过长或含无效字符");for(String token:tokens(raw))if(token.length()>1000)throw new IllegalArgumentException("每个屏蔽词或用户不能超过 1000 字");}
    private static String words(String value){return value==null?"":value.trim().replaceAll("\\s+"," ");}
    private static List<String> tokens(String value){List<String> result=new ArrayList<>();String canonical=words(value);if(!canonical.isEmpty())Collections.addAll(result,canonical.split(" "));return result;}
    private static String join(Set<String> values){StringBuilder out=new StringBuilder();for(String value:values){if(out.length()>0)out.append(' ');out.append(value);}return out.toString();}
    private static boolean matches(String token,String uid,String name){return uid!=null&&(token.equals(uid)||token.startsWith(uid+"/"))||name!=null&&!name.isEmpty()&&token.equals(name);}
    static void cache(Context context,String host,String uid,Snapshot data) throws IOException {String key=NativeUserBlockStore.key(host,uid);try{JSONObject object=new JSONObject().put("schema",1).put("document",data.document()).put("etag",data.etag).put("limit",data.limit);if(!context.getApplicationContext().getSharedPreferences("nga_native_cloud_blocks",Context.MODE_PRIVATE).edit().putString(key,object.toString()).commit())throw new CacheException();}catch(org.json.JSONException invalid){throw new CacheException();}}
    static Filter cached(Context context,String host,String uid){Snapshot snapshot=null;try{SharedPreferences prefs=context.getApplicationContext().getSharedPreferences("nga_native_cloud_blocks",Context.MODE_PRIVATE);String raw=prefs.getString(NativeUserBlockStore.key(host,uid),"");if(raw.length()<=256000&&!raw.isEmpty()){JSONObject object=new JSONObject(raw);if(object.optInt("schema")==1){String document=object.getString("document");if(document.isEmpty())snapshot=new Snapshot("","",0,0);else{String[] lines=document.split("\n",-1);if(lines.length==3&&lines[0].equals("1"))snapshot=new Snapshot(lines[1],lines[2],0,0);}}}}catch(Exception invalid){/* A failed or corrupt cache must not hide unrelated authors. */}return new Filter(snapshot);}
    static Filter from(Snapshot data){return new Filter(data);}
    public static final class Filter {
        private final Set<String> users;private final List<String> keywords;
        Filter(Snapshot data){users=data==null?Collections.emptySet():new HashSet<>(tokens(data.authors));keywords=data==null?Collections.emptyList():tokens(data.keywords);}
        boolean sameAs(Filter other){return other!=null&&users.equals(other.users)&&new HashSet<>(keywords).equals(new HashSet<>(other.keywords));}
        public boolean blocks(String uid,String name,String content){for(String token:users)if(matches(token,uid,name))return true;if(content!=null)for(String keyword:keywords)if(content.contains(keyword))return true;return false;}
    }
}
