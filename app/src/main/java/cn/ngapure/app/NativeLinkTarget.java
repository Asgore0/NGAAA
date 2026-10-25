package cn.ngapure.app;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure navigation policy. It never requests a URL, reads a session or performs an action. */
public final class NativeLinkTarget {
    public static final int INVALID=0,TOPIC=1,FORUM=2,FAVORITE=3,USER=4,SELF=5,
            MESSAGES=6,REMINDERS=7,LOGIN=8,EDITOR=9,HOME=10,BROWSER=11,BLOCKED=12;
    private static final int MAX_URL=4096,MAX_PARAMETERS=128;
    private static final Set<String> CRITICAL=set("tid","pid","fid","uid","favor","page","stid",
            "__lib","__act","func","action","act","authorid","searchpost","key","step","ff");
    private static final Set<String> TRANSPORT=set("from","from_source","rand","__inchst","__output","lite","raw");
    private static final Set<String> MUTATIONS=set("add","set","del","delete","remove","new","reply",
            "send","edit","modify","vote","votesettle","settle","move","lock","unlock","upload",
            "save","clear","login","logout","register","check_in","signin","sign_in","leave_topic","del_topic","report","recommend","follow","unfollow",
            "add_block","del_block","locktopic","recountreply","set_user_reputation");

    public final int kind,page;
    /** Resolved HTTP(S) URL; exact official legacy HTTP origins are upgraded to HTTPS. */
    public final String url,topicId,postId,forumId,collectionId,parentForumId,userId,favoriteId,action,authorId;
    public final boolean official;

    private NativeLinkTarget(int kind,String url,boolean official,Map<String,String> query,int page) {
        this.kind=kind;this.url=url;this.official=official;this.page=page;
        topicId=get(query,"tid");postId=get(query,"pid");forumId=get(query,"fid");
        collectionId=get(query,"stid");parentForumId=get(query,"ff");
        userId=get(query,"uid");favoriteId=get(query,"favor");action=get(query,"action");
        authorId=get(query,"authorid");
    }
    public static NativeLinkTarget parse(String raw) { return parse(raw,"bbs.nga.cn"); }

    /** Unknown display semantics remain in the browser URL instead of being silently discarded. */
    public static NativeLinkTarget parse(String raw,String baseHost) {
        try {
            if(raw==null||raw.length()>MAX_URL||controls(raw))return invalid();
            String value=raw.trim();
            if(value.isEmpty()||value.indexOf('\\')>=0||containsWhitespace(value))return invalid();
            String base=communityHost(baseHost)?baseHost.toLowerCase(Locale.ROOT):"bbs.nga.cn";
            URI input=new URI(value);
            boolean opaquePath=validateDisplayPart(input.getRawPath())|validateDisplayPart(input.getRawFragment());
            URI uri=new URI("https://"+base+"/").resolve(input).normalize();
            String scheme=lower(uri.getScheme()),host=lower(uri.getHost());
            if((!"https".equals(scheme)&&!"http".equals(scheme))||host.isEmpty()
                    ||uri.getRawUserInfo()!=null||uri.getRawAuthority()==null
                    ||(uri.getPort()!=-1&&uri.getPort()!=("https".equals(scheme)?443:80)))return invalid();
            // URI accepts opaque/empty authorities and some Unicode forms that browser parsers differ on.
            if(uri.isOpaque()||uri.getRawAuthority().indexOf('%')>=0)return invalid();
            boolean official=officialHost(host);
            if(official&&"http".equals(scheme)) {
                String authority=host+(uri.getPort()==80?"":uri.getPort()==-1?"":":"+uri.getPort());
                uri=new URI("https://"+authority+(uri.getRawPath()==null?"":uri.getRawPath())
                        +(uri.getRawQuery()==null?"":"?"+uri.getRawQuery())
                        +(uri.getRawFragment()==null?"":"#"+uri.getRawFragment()));
            }
            if(uri.toASCIIString().length()>MAX_URL)return invalid();
            opaquePath|=validateDisplayPart(uri.getRawPath())|validateDisplayPart(uri.getRawFragment());
            Query parsed=query(uri.getRawQuery(),official);
            Map<String,String> query=parsed.values;
            String url=uri.toASCIIString(),path=uri.getPath()==null?"":uri.getPath();
            int page=page(get(query,"page"));
            if(!communityHost(host))return target(BROWSER,url,official,query,Math.max(1,page));
            if("/nuke.php".equals(path)&&mutation(query))return target(BLOCKED,url,true,query,Math.max(1,page));
            if("/post.php".equals(path)&&postMutation(query))return target(BLOCKED,url,true,query,Math.max(1,page));
            // Do not interpret encoded path delimiters, unknown anchors or filtered views as ordinary native pages.
            if(opaquePath||parsed.browserOnly||!uri.getRawPath().equals(path)||!getFragment(uri).isEmpty()||page==0)
                return target(BROWSER,url,true,query,Math.max(1,page));
            if(("/".equals(path)||path.isEmpty()||"/index.php".equals(path))&&query.isEmpty())
                return target(HOME,url,true,query,1);
            if("/read.php".equals(path)) {
                if(!only(query,"tid","pid","page","authorid"))return target(BROWSER,url,true,query,page);
                String tid=get(query,"tid"),pid=get(query,"pid");
                if(!get(query,"authorid").isEmpty()&&!positive(get(query,"authorid")))return invalid();
                if(!get(query,"authorid").isEmpty()&&!pid.isEmpty()&&!"0".equals(pid))return target(BROWSER,url,true,query,page);
                if(!tid.isEmpty()&&!positive(tid))return invalid();
                if(!pid.isEmpty()&&!postId(pid))return invalid();
                if(positive(tid))return target(TOPIC,url,true,query,page);
                return target(BROWSER,url,true,query,page); // Official pid&to needs a redirect lookup, not a fabricated tid.
            }
            if("/thread.php".equals(path)) {
                String fid=get(query,"fid"),favorite=get(query,"favor"),stid=get(query,"stid"),parent=get(query,"ff");
                if(!fid.isEmpty()&&!forumId(fid))return invalid();
                if(!favorite.isEmpty()&&!positive(favorite)||!stid.isEmpty()&&!positive(stid)||!parent.isEmpty()&&!forumId(parent))return invalid();
                if(!fid.isEmpty()&&only(query,"fid","page","ff"))return target(FORUM,url,true,query,page);
                if(!stid.isEmpty()&&only(query,"stid","page"))return target(FORUM,url,true,query,page);
                if(!favorite.isEmpty()&&only(query,"favor","page"))return target(FAVORITE,url,true,query,page);
                return target(BROWSER,url,true,query,page);
            }
            if("/nuke.php".equals(path)) {
                String lib=get(query,"__lib"),func=get(query,"func"),act=get(query,"__act"),uid=get(query,"uid");
                boolean profile=("ucp".equals(lib)&&func.isEmpty()&&(act.isEmpty()||"get".equals(act)))
                        ||("ucp".equals(func)&&lib.isEmpty()&&act.isEmpty());
                if(profile&&only(query,"__lib","__act","func","uid")) {
                    if(uid.isEmpty())return target(SELF,url,true,query,1);
                    return positive(uid)?target(USER,url,true,query,1):invalid();
                }
                boolean messageList=get(query,"act").isEmpty()||"list".equals(get(query,"act"));
                if(messageList&&(("message".equals(lib)&&func.isEmpty()&&(act.isEmpty()||"list".equals(act)
                        ||"message".equals(act)&&"list".equals(get(query,"act"))))
                        ||("message".equals(func)&&lib.isEmpty()&&act.isEmpty()))) {
                    if(only(query,"__lib","__act","func","act","page"))return target(MESSAGES,url,true,query,page);
                }
                if("noti".equals(lib)&&func.isEmpty()&&(act.isEmpty()||"get_all".equals(act))
                        &&only(query,"__lib","__act"))return target(REMINDERS,url,true,query,1);
                if("login".equals(lib)&&func.isEmpty()&&(act.isEmpty()||"login_ui".equals(act))
                        &&only(query,"__lib","__act"))return target(LOGIN,url,true,query,1);
                return target(BROWSER,url,true,query,page);
            }
            if("/post.php".equals(path)) {
                String action=get(query,"action"),fid=get(query,"fid"),tid=get(query,"tid"),pid=get(query,"pid");
                if(!only(query,"action","fid","tid","pid","article","_newui"))return target(BROWSER,url,true,query,page);
                if(!fid.isEmpty()&&!forumId(fid)||!tid.isEmpty()&&!positive(tid)||!pid.isEmpty()&&!postId(pid))return invalid();
                if("new".equals(action)&&forumId(fid)&&tid.isEmpty()&&pid.isEmpty()
                        ||("reply".equals(action)||"quote".equals(action)||"modify".equals(action))&&positive(tid))
                    return target(EDITOR,url,true,query,1);
                return target(BROWSER,url,true,query,page);
            }
            return target(BROWSER,url,true,query,page);
        } catch(Exception malformed) { return invalid(); }
    }

    public static boolean communityHost(String host) {
        return host!=null&&("bbs.nga.cn".equalsIgnoreCase(host)||"ngabbs.com".equalsIgnoreCase(host)
                ||"nga.178.com".equalsIgnoreCase(host));
    }
    private static boolean officialHost(String host) {
        return communityHost(host)||"www.nga.cn".equals(host)||"nga.cn".equals(host)||"game.nga.cn".equals(host);
    }
    private static boolean mutation(Map<String,String> query) {
        return mutationWord(get(query,"__act"))||mutationWord(get(query,"act"))
                ||mutationWord(get(query,"func"))||mutationWord(get(query,"action"));
    }
    private static boolean mutationWord(String value) {
        String word=lower(value);
        return MUTATIONS.contains(word)||word.startsWith("set_")||word.startsWith("del_")
                ||word.startsWith("delete_")||word.startsWith("add_")||word.startsWith("remove_")
                ||word.startsWith("send_")||word.startsWith("modify_")||word.endsWith("_send")
                ||word.endsWith("_set")||word.endsWith("_add")||word.endsWith("_del");
    }
    private static boolean postMutation(Map<String,String> query) {
        String step=get(query,"step");
        return !step.isEmpty()&&!"1".equals(step)||query.containsKey("content")||query.containsKey("subject")
                ||query.containsKey("topic_vote")||query.containsKey("voteid")
                ||(!set("new","reply","quote","modify").contains(get(query,"action"))&&mutation(query));
    }
    private static boolean only(Map<String,String> query,String... allowed) {
        Set<String> keys=set(allowed);keys.addAll(TRANSPORT);
        for(String key:query.keySet())if(!keys.contains(key))return false;
        return true;
    }
    private static final class Query {
        final Map<String,String> values=new HashMap<>();boolean browserOnly;
    }
    private static Query query(String raw,boolean official) throws CharacterCodingException {
        Query parsed=new Query();Map<String,String> query=parsed.values;
        if(raw==null||raw.isEmpty())return parsed;
        String[] pairs=raw.split("&",-1);if(pairs.length>MAX_PARAMETERS)throw new IllegalArgumentException("query limit");
        for(String pair:pairs) {
            if(pair.isEmpty())continue;
            int equals=pair.indexOf('=');
            String key=decode(equals<0?pair:pair.substring(0,equals),true);
            String rawValue=equals<0?"":pair.substring(equals+1),value;
            boolean routing=official&&CRITICAL.contains(key)&&!"key".equals(key);
            try {value=decode(rawValue,true);}
            catch(CharacterCodingException legacyEncoding) {
                if(routing)throw legacyEncoding;
                // GBK/legacy search bytes have no native meaning; keep the original URI for the browser.
                value=rawValue;parsed.browserOnly=true;
            }
            if(controls(key)||controls(value))throw new IllegalArgumentException("query controls");
            if(official&&!ascii(key)||routing&&!ascii(value))throw new IllegalArgumentException("non-ASCII routing");
            if(official&&CRITICAL.contains(key)&&query.containsKey(key))throw new IllegalArgumentException("ambiguous route");
            query.put(key,value);
        }
        return parsed;
    }
    /** Display-only path/anchor bytes may use legacy encodings, but never encoded ASCII controls. */
    private static boolean validateDisplayPart(String part) {
        if(part==null)return false;
        try {if(controls(decode(part,false)))throw new IllegalArgumentException("URL controls");return false;}
        catch(CharacterCodingException legacyEncoding) {return true;}
    }
    /** URLDecoder replaces malformed UTF-8; routing needs rejection instead of replacement characters. */
    private static String decode(String value,boolean plus) throws CharacterCodingException {
        // Inspect every escaped byte first: an earlier legacy byte must not hide a later control byte.
        for(int i=0;i<value.length();i++)if(value.charAt(i)=='%') {
            if(i+2>=value.length())throw new IllegalArgumentException("percent escape");
            int high=Character.digit(value.charAt(i+1),16),low=Character.digit(value.charAt(i+2),16);
            if(high<0||low<0)throw new IllegalArgumentException("percent escape");
            int code=high*16+low;if(code<0x20||code==0x7f)throw new IllegalArgumentException("encoded URL controls");
            i+=2;
        }
        StringBuilder out=new StringBuilder(value.length());
        for(int i=0;i<value.length();) {
            char c=value.charAt(i);
            if(c=='%') {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                while(i<value.length()&&value.charAt(i)=='%') {
                    if(i+2>=value.length())throw new IllegalArgumentException("percent escape");
                    int high=Character.digit(value.charAt(i+1),16),low=Character.digit(value.charAt(i+2),16);
                    if(high<0||low<0)throw new IllegalArgumentException("percent escape");
                    int code=high*16+low;
                    if(code<0x20||code==0x7f)throw new IllegalArgumentException("encoded URL controls");
                    bytes.write(code);i+=3;
                }
                out.append(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())));
            } else {out.append(plus&&c=='+'?' ':c);i++;}
        }
        return out.toString();
    }
    private static boolean controls(String value) {
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c<0x20||c==0x7f)return true;}
        return false;
    }
    private static boolean containsWhitespace(String value) {
        for(int i=0;i<value.length();i++)if(Character.isWhitespace(value.charAt(i))||Character.isSpaceChar(value.charAt(i)))return true;
        return false;
    }
    private static boolean ascii(String value) {
        for(int i=0;i<value.length();i++)if(value.charAt(i)>0x7f)return false;
        return true;
    }
    private static boolean positive(String value){return value.matches("[1-9][0-9]{0,17}");}
    private static boolean postId(String value){return "0".equals(value)||positive(value);}
    private static boolean forumId(String value){return value.matches("-?[1-9][0-9]{0,11}");}
    private static int page(String value) {
        if(value.isEmpty())return 1;
        if(!value.matches("[1-9][0-9]{0,4}"))return 0;
        return Integer.parseInt(value);
    }
    private static String getFragment(URI uri){return uri.getRawFragment()==null?"":uri.getRawFragment();}
    private static String get(Map<String,String> query,String key){String value=query.get(key);return value==null?"":value;}
    private static String lower(String value){return value==null?"":value.toLowerCase(Locale.ROOT);}
    private static Set<String> set(String... values){return new HashSet<>(Arrays.asList(values));}
    private static NativeLinkTarget target(int kind,String url,boolean official,Map<String,String> query,int page) {
        return new NativeLinkTarget(kind,url,official,query,page);
    }
    private static NativeLinkTarget invalid(){return target(INVALID,"",false,new HashMap<>(),1);}
}
