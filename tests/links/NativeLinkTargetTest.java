package cn.ngapure.app;

import java.util.Random;

import static cn.ngapure.app.NativeLinkTarget.*;

/** Public official menu examples and adversarial URLs; no Android, account, I/O or network. */
public final class NativeLinkTargetTest {
    private static int checks;
    private static void check(boolean value,String message) {
        checks++;if(!value)throw new AssertionError(message);
    }
    private static NativeLinkTarget route(String raw,int kind) {
        NativeLinkTarget value=NativeLinkTarget.parse(raw,"ngabbs.com");
        check(value.kind==kind,"route "+raw+": wanted "+kind+", got "+value.kind);
        return value;
    }
    private static String repeat(String value,int count) {
        StringBuilder out=new StringBuilder();for(int i=0;i<count;i++)out.append(value);return out.toString();
    }
    public static void main(String[] args) {
        NativeLinkTarget topic=route("https://bbs.nga.cn/read.php?tid=91001&pid=92001&page=4",TOPIC);
        check(topic.topicId.equals("91001")&&topic.postId.equals("92001")&&topic.page==4,"topic location retained");
        check(topic.official&&topic.url.equals("https://bbs.nga.cn/read.php?tid=91001&pid=92001&page=4"),"resolved original URL retained");
        route("read.php?tid=91001",TOPIC);route("/read.php?tid=91001",TOPIC);
        route("//nga.178.com/read.php?tid=91001",TOPIC);
        NativeLinkTarget old=route("http://ngabbs.com:80/read.php?tid=91001",TOPIC);
        check(old.url.equals("https://ngabbs.com/read.php?tid=91001"),"legacy community HTTP upgraded");
        check(route("HTTP://BBS.NGA.CN/read.php?tid=91001",TOPIC).url.equals("https://bbs.nga.cn/read.php?tid=91001"),"case insensitive origin upgrade");
        route("https://bbs.nga.cn:443/read.php?tid=91001",TOPIC);
        route("/read.php?%74id=%39%31%30%30%31&pid=0",TOPIC);
        route("/read.php?tid=91001&__output=8&lite=js&from=share",TOPIC);
        NativeLinkTarget filtered=route("/read.php?tid=91001&authorid=70001&page=3",TOPIC);
        check(filtered.authorId.equals("70001")&&filtered.page==3,"author filter and page retained natively");
        route("/read.php?tid=91001&authorid=0",INVALID);
        route("/read.php?tid=91001&authorid=-1",INVALID);
        route("/read.php?tid=91001&authorid=70001&pid=92001",BROWSER);
        route("/read.php?tid=91001&authorid=70001&pid=0",TOPIC);
        route("/read.php?pid=92001&to",BROWSER);
        route("/read.php?tid=91001&to",BROWSER);
        route("/read.php?tid=91001#pid92001Anchor",BROWSER);
        route("/read.php?tid=91001#unimplemented-anchor",BROWSER);
        route("/read.php?tid=91001&opt=1",BROWSER);
        route("/read.php?tid=91001&key=someword",BROWSER);
        route("/read.php?tid=91001&stid=40001",BROWSER);
        route("/read.php?tid=91001&searchpost=1",BROWSER);
        route("/read.php?tid=91001&unknown=meaning",BROWSER);
        route("/read.php?tid=91001&page=100000",BROWSER);
        route("/read.php?tid=91001&page=-1",BROWSER);
        route("/read.php?tid=91001&page=0",BROWSER);
        route("/read.php?tid=91001&page=99999",TOPIC);
        route("/read.php?tid=91001&page=999999999999999999999",BROWSER);
        route("/read.php?tid=0",INVALID);route("/read.php?tid=-1",INVALID);
        route("/read.php?tid=word",INVALID);route("/read.php?tid=91001&pid=-1",INVALID);
        route("/read.php?tid=999999999999999999",TOPIC);
        route("/read.php?tid=9999999999999999999",INVALID);

        NativeLinkTarget forum=route("/thread.php?fid=-7&page=3",FORUM);
        check(forum.forumId.equals("-7")&&forum.page==3,"negative forum and page retained");
        NativeLinkTarget favorite=route("/thread.php?favor=70001&page=2",FAVORITE);
        check(favorite.favoriteId.equals("70001")&&favorite.page==2,"favorite and page retained");
        route("/thread.php?fid=7&stid=40001",BROWSER);
        NativeLinkTarget collection=route("/thread.php?stid=23673143&page=2",FORUM);
        check(collection.forumId.isEmpty()&&collection.collectionId.equals("23673143")&&collection.page==2,"collection stid and page retained without inventing a parent board");
        NativeLinkTarget child=route("/thread.php?fid=754&ff=650&page=3",FORUM);
        check(child.forumId.equals("754")&&child.parentForumId.equals("650")&&child.page==3,"official child board and parent backlink retained");
        route("/thread.php?stid=0",INVALID);route("/thread.php?stid=-1",INVALID);
        route("/thread.php?stid=23673143&stid=26640400",INVALID);
        route("/thread.php?fid=754&ff=650&ff=181",INVALID);
        route("/thread.php?fid=754&ff=0",INVALID);
        route("/thread.php?fid=754&parent=650",BROWSER);route("/thread.php?fid=650&__tab=1",BROWSER);
        route("/thread.php?stid=23673143&key=filter",BROWSER);
        ForumRoute childRoute=ForumRoute.fromParts(child.forumId,child.collectionId,child.parentForumId);
        check(childRoute.key.equals("754")&&childRoute.query().equals("fid=754&ff=650"),"child route preserves real fid plus official backlink");
        ForumRoute collectionRoute=ForumRoute.fromKey("stid:23673143").withParent("650");
        check(collectionRoute.key.equals("stid:23673143")&&collectionRoute.fid.isEmpty()&&collectionRoute.query().equals("stid=23673143"),"collection never becomes parent fid or invented filter");
        check(collectionRoute.url("bbs.nga.cn",2).equals("https://bbs.nga.cn/thread.php?stid=23673143&page=2"),"collection page URL retains stid");
        check(childRoute.iconUrl().endsWith("/app/754.png")&&collectionRoute.iconUrl().endsWith("/23673143v.png"),"separate official board and collection artwork endpoints");
        for(String bad:new String[]{"0","-0","01","-01","","stid:0","stid:-1","stid:01","stid:1&fid=2","9999999999999","stid:9999999999999999999"})check(!ForumRoute.isKey(bad),"malformed route key rejected");
        boolean conflicting=false;try{ForumRoute.fromParts("650","23673143","");}catch(IllegalArgumentException expected){conflicting=true;}check(conflicting,"ambiguous fid plus stid is rejected");
        route("/thread.php?key=hello&fid=7",BROWSER);
        route("/thread.php?searchpost=1&authorid=70001&fid=7",BROWSER);
        route("/thread.php?fid=7&authorid=70001",BROWSER);
        route("/thread.php?fid=7&order_by=postdatedesc",BROWSER);
        route("/thread.php?fid=7&favor=70001",BROWSER);
        route("/thread.php?fid=0",INVALID);route("/thread.php?fid=-0",INVALID);
        route("/thread.php?favor=-1",INVALID);

        NativeLinkTarget user=route("/nuke.php?__lib=ucp&__act=get&uid=70001",USER);
        check(user.userId.equals("70001"),"new user route retained");
        route("/nuke.php?__lib=%75cp&uid=70001",USER);
        route("/nuke.php?func=ucp&uid=70001",USER);
        route("/nuke.php?func=ucp&__inchst=UTF-8&uid=70001",USER);
        route("/nuke.php?__lib=ucp&__act=get",SELF);route("/nuke.php?func=ucp",SELF);
        route("/nuke.php?__lib=ucp&__act=get&username=somebody",BROWSER);
        route("/nuke.php?__lib=ucp&__act=get&uid=0",INVALID);
        route("/nuke.php?__lib=ucp&__act=self_login_log&raw=3",BROWSER);
        route("/nuke.php?__lib=message",MESSAGES);route("/nuke.php?func=message",MESSAGES);
        route("/nuke.php?__lib=message&__act=message&act=list&raw=1&page=2&rand=99",MESSAGES);
        route("/nuke.php?__lib=message&__act=message&act=read&mid=80001",BROWSER);
        route("/nuke.php?__lib=message&act=read",BROWSER);
        route("/nuke.php?func=message&act=read",BROWSER);
        route("/nuke.php?func=message#to=70001",BROWSER);
        route("/nuke.php?__lib=message&mid=80001",BROWSER);
        route("/nuke.php?__lib=noti&__act=get_all",REMINDERS);
        route("/nuke.php?__lib=login&__act=login_ui",LOGIN);
        route("/nuke.php?__lib=login",LOGIN);
        route("/nuke.php?__lib=login&__act=account&resetpass",BROWSER);
        route("/nuke.php?__lib=unknown&__act=get",BROWSER);

        NativeLinkTarget editor=route("/post.php?action=reply&_newui&fid=7&tid=91001&pid=92001&article=2",EDITOR);
        check(editor.action.equals("reply")&&editor.topicId.equals("91001")&&editor.postId.equals("92001"),"editor opens draft without losing target");
        route("/post.php?action=new&_newui&fid=-7",EDITOR);
        route("/post.php?action=quote&fid=7&tid=91001&pid=92001",EDITOR);
        route("/post.php?action=modify&fid=7&tid=91001&pid=92001",EDITOR);
        route("/post.php?action=reply&tid=91001",EDITOR);
        route("/post.php?action=new",BROWSER);
        route("/post.php?action=new&fid=7&tid=91001",BROWSER);
        route("/post.php?action=reply&tid=91001&content=body",BLOCKED);
        route("/post.php?action=reply&tid=91001&step=2",BLOCKED);
        route("/post.php?action=new&fid=7&subject=subject",BLOCKED);
        route("/post.php?action=del&tid=91001",BLOCKED);
        route("/post.php?action=reply&tid=91001&voteid=1",BLOCKED);
        route("/post.php?action=reply&tid=91001&topic_vote=1",BLOCKED);
        route("/post.php?action=reply&tid=91001&step=1",BROWSER);

        route("https://bbs.nga.cn/",HOME);route("/index.php",HOME);route("/",HOME);
        route("/?other=meaning",BROWSER);route("/#target",BROWSER);
        String event="https://bbs.nga.cn/misc/event/20261015zmd/index.html";
        check(route(event,BROWSER).url.equals(event),"screenshot event remains openable");
        route(event+"?tid=91001",BROWSER);
        route("/misc/legal/1/privacy.html",BROWSER);
        route("https://www.nga.cn/",BROWSER);route("https://nga.cn/some-page",BROWSER);
        route("https://game.nga.cn/",BROWSER);
        check(route("http://www.nga.cn/a?q=hello%20world#part",BROWSER).url.equals("https://www.nga.cn/a?q=hello%20world#part"),"official web legacy URL upgraded and complete");
        route("/new-feature.php?tid=91001",BROWSER);
        route("/nuke.php.evil?tid=91001",BROWSER);
        route("/read.php/extra?tid=91001",BROWSER);
        route("/%72ead.php?tid=91001",BROWSER);
        route("/dir%2F..%2Fread.php?tid=91001",BROWSER);
        route("https://example.org/read.php?tid=91001",BROWSER);
        route("http://example.org/a",BROWSER);
        route("https://bbs.nga.cn.evil.example/read.php?tid=91001",BROWSER);
        route("https://img.nga.cn/attachments/test.png",BROWSER);
        check(!route("https://example.org/",BROWSER).official,"external origin cannot become official");
        check(route("relative.html?q=one%20two#part",BROWSER).url.equals("https://ngabbs.com/relative.html?q=one%20two#part"),"relative browser URL is complete");
        check(NativeLinkTarget.parse("read.php?tid=91001","evil.example").url.startsWith("https://bbs.nga.cn/"),"untrusted base never used");
        check(NativeLinkTarget.parse("read.php?tid=91001",null).kind==TOPIC,"null base uses official default");

        String[] mutations={"add","set","del","delete","remove","new","reply","send","edit","modify","vote",
                "voteSettle","settle","move","lock","unlock","upload","save","clear","login","logout","register",
                "check_in","signin","sign_in","leave_topic","del_topic","add_block","del_block","locktopic",
                "recountreply","set_user_reputation","set_rule","auth_code_send","auth_unactive_send","remove_item"};
        for(String mutation:mutations) {
            route("/nuke.php?__lib=anything&__act="+mutation+"&tid=91001",BLOCKED);
            route("/nuke.php?__lib=message&__act=message&act="+mutation,BLOCKED);
        }
        route("/nuke.php?__lib=topic_recommend&__act=add&tid=91001&pid=92001&value=1",BLOCKED);
        route("/nuke.php?__lib=forum_favor2&__act=forum_favor&action=add&fid=7",BLOCKED);
        route("/nuke.php?__lib=topic_favor_v2&__act=add&folder=1&tid=91001",BLOCKED);
        route("/nuke.php?func=locktopic&edit_lock&tid=91001&pid=92001&lock=1",BLOCKED);
        // Browser-owned third-party endpoints do not acquire NGA RPC semantics.
        route("https://example.org/nuke.php?__act=add&tid=91001",BROWSER);
        route("/misc/event/index.html?__act=add",BROWSER);

        String[] duplicate={"tid","pid","fid","uid","favor","page","stid","__lib","__act","func","action","act","authorid","searchpost","key","step"};
        for(String key:duplicate)route("/read.php?"+key+"=1&"+key+"=2",INVALID);
        route("/read.php?tid=91001&%74id=91002",INVALID);
        route("/read.php?tid=&tid=91001",INVALID);
        route("/nuke.php?__lib=ucp&uid=70001&uid=70002",INVALID);
        route("https://example.org/?tid=1&tid=2",BROWSER);
        String[] invalid={null,""," ","javascript:alert(1)","data:text/html,hello","intent://example.org/#Intent;scheme=https;end",
                "file:///data/private","content://private/item","mailto:a@example.org","ftp://example.org/", 
                "https://user:secret@bbs.nga.cn/read.php?tid=91001","https://bbs.nga.cn:8443/read.php?tid=91001",
                "http://bbs.nga.cn:443/read.php?tid=91001","https://example.org:80/","https:///read.php?tid=91001",
                "https://","//","https://bbs.nga.cn\\@evil.example/","https://%62bs.nga.cn/","https://example.org/a b",
                "https://example.org/a\nb","https://example.org/\r","https://example.org/\t","https://example.org/\u007f",
                "https://example.org/%","https://example.org/%Q0",
                "https://example.org/?q=%00","https://example.org/#%0A","https://example.org/%0D",
                "https://example.org/?%0Akey=value","https://example.org/\u2003space"};
        for(String value:invalid)route(value,INVALID);
        route("https://example.org/"+repeat("a",4097),INVALID);
        route("https://example.org/?"+repeat("q=1&",129),INVALID);
        route("https://example.org/"+repeat("中",500),INVALID); // ASCII expansion remains bounded.
        route("https://example.org/?q=%E4%B8%AD%E6%96%87",BROWSER);
        route("https://example.org/?q=hello+world",BROWSER);
        String gbk="https://bbs.nga.cn/thread.php?fid=7&key=%D6%D0%CE%C4";
        check(route(gbk,BROWSER).url.equals(gbk),"legacy GBK search bytes preserved exactly");
        String utf8="https://bbs.nga.cn/thread.php?fid=7&key=%E4%B8%AD%E6%96%87";
        check(route(utf8,BROWSER).url.equals(utf8),"UTF-8 search bytes preserved exactly");
        route("/read.php?tid=91001&unknown=%FF",BROWSER);
        route("/read.php?tid=91001&from=%D6%D0%CE%C4",BROWSER);
        route("/misc/event/%D6%D0%CE%C4/index.html",BROWSER);
        route("/misc/event/%FF/index.html",BROWSER);
        route("/read.php?tid=91001#%D6%D0%CE%C4",BROWSER);
        route("https://example.org/?q=%FF",BROWSER);
        route("https://example.org/?q=%C0%AF",BROWSER);
        route("https://example.org/?q=%E2%82",BROWSER);
        route("/read.php?tid=%D6%D0%CE%C4",INVALID);
        route("/read.php?tid=%E4%B8%AD",INVALID);
        route("/nuke.php?__lib=%D6%D0%CE%C4",INVALID);
        route("/nuke.php?__lib=message&__act=%FF",INVALID);
        route("/post.php?action=%D6%D0%CE%C4",INVALID);
        route("/read.php?%D6%D0%CE%C4=1",INVALID);
        route("/read.php?%E4%B8%AD=1",INVALID);
        route("/thread.php?fid=7&key=%FF%00",INVALID);
        route("/thread.php?fid=7&key=%FFx%0A",INVALID);
        route("/misc/%FFx%0D/index.html",INVALID);
        route("/misc/%FFx%7F/index.html",INVALID);
        route("/misc/%00/../read.php?tid=91001",INVALID);
        route("/misc/%D6%D0%CE%C4/../read.php?tid=91001",BROWSER);
        route("/nuke.php?__lib=topic_recommend&__act=add&unknown=%FF",BLOCKED);
        for(String action:new String[]{"report","recommend","follow","unfollow","set_block_word"})route("/nuke.php?__lib=log_post&__act="+action+"&uid=70001",BLOCKED);
        route("/nuke.php?__lib=follow_v2&__act=follow&uid=70001&type=1",BLOCKED);

        Random random=new Random(131L);
        String[] filters={"authorid","stid","searchpost","key","unknown"};
        for(int i=0;i<2000;i++) {
            int tid=1+random.nextInt(999999),fid=1+random.nextInt(99999);
            String extra=filters[random.nextInt(filters.length)]+"="+(1+random.nextInt(999999));
            route("/read.php?tid="+tid+"&"+extra,extra.startsWith("authorid=")?TOPIC:BROWSER);
            route("/thread.php?fid="+fid+"&"+extra,BROWSER);
            route("/nuke.php?__lib=unknown&__act=set&tid="+tid,BLOCKED);
            route("/read.php?tid="+tid+"&tid="+tid,INVALID);
        }
        long started=System.nanoTime();
        for(int i=0;i<10000;i++)route("https://bbs.nga.cn/misc/event/20261015zmd/index.html?q="+i,BROWSER);
        long elapsed=(System.nanoTime()-started)/1000000L;
        System.out.println("NativeLinkTarget: "+checks+" route/semantic/safety assertions PASS; 10000 bounded parses "+elapsed+" ms; no Android or network");
    }
}
