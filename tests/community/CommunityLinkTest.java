package cn.ngapure.app;

/** Exercises the actual private-message routing policy without creating Android views or intents. */
public final class CommunityLinkTest {
    private static int checks;
    private static void route(String raw,int expected,String value,String label) {
        NativeLinkTarget target=NativeLinkTarget.parse(raw,"ngabbs.com");
        checks++;if(target.kind!=expected||!value.equals(target.kind==NativeLinkTarget.TOPIC?target.topicId:target.kind==NativeLinkTarget.USER?target.userId:target.kind==NativeLinkTarget.FORUM?target.forumId:target.kind==NativeLinkTarget.BROWSER?target.url:""))throw new AssertionError(label);
    }
    public static void main(String[] args) {
        route("https://bbs.nga.cn/read.php?tid=91001&pid=92001",1,"91001","topic stays native");
        route("read.php?tid=91001",1,"91001","relative uses chosen NGA host");
        route("/read.php?tid=91001",1,"91001","root relative");
        route("//nga.178.com/read.php?tid=91001",1,"91001","protocol relative NGA");
        route("http://ngabbs.com/read.php?tid=91001",1,"91001","legacy HTTP routes to secure native API");
        route("https://bbs.nga.cn/nuke.php?__lib=ucp&__act=get&uid=70001",NativeLinkTarget.USER,"70001","user stays native");
        route("/nuke.php?__lib=%75cp&uid=70001",NativeLinkTarget.USER,"70001","encoded known query");
        route("https://example.org/path?q=hello%20world",NativeLinkTarget.BROWSER,"https://example.org/path?q=hello%20world","ordinary external needs confirmation");
        route("https://bbs.nga.cn.evil.example/read.php?tid=91001",NativeLinkTarget.BROWSER,"https://bbs.nga.cn.evil.example/read.php?tid=91001","lookalike cannot be native");
        route("https://img.nga.cn/attachments/test.png",NativeLinkTarget.BROWSER,"https://img.nga.cn/attachments/test.png","media link cannot impersonate topic");
        route("/thread.php?fid=7",NativeLinkTarget.FORUM,"7","board stays native");
        route("/nuke.php?__lib=message&mid=88001",NativeLinkTarget.BROWSER,"https://ngabbs.com/nuke.php?__lib=message&mid=88001","conversation semantics preserved in confirmed browser");
        route("/read.php?tid=-1",0,"","invalid ID is not native");
        route("/nuke.php?__lib=ucp&uid=0",0,"","zero profile rejected");
        route("/read.php?tid=91001&tid=91002",0,"","duplicate routing parameter rejected");
        route("/nuke.php?__lib=ucp&uid=70001&uid=70002",0,"","duplicate user rejected");
        route("javascript:alert(1)",0,"","script rejected");
        route("intent://example.org/#Intent;scheme=https;end",0,"","arbitrary intent rejected");
        route("file:///data/private",0,"","file rejected");
        route("content://private/item",0,"","content provider rejected");
        route("https://user:secret@bbs.nga.cn/read.php?tid=91001",0,"","userinfo rejected");
        route("https://bbs.nga.cn:8443/read.php?tid=91001",0,"","nonstandard port rejected");
        route("https://example.org/a\nb",0,"","control character rejected");
        route("https://example.org/a b",0,"","unencoded whitespace rejected");
        route("",0,"","empty rejected");route(null,0,"","null rejected");
        StringBuilder large=new StringBuilder("https://example.org/");while(large.length()<=4096)large.append('a');
        route(large.toString(),0,"","unbounded URI rejected");
        System.out.println("CommunityLink: "+checks+" native/external routing assertions PASS; no Android UI or network");
    }
}
