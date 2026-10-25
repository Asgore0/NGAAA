package cn.ngapure.app;

import java.util.List;
import java.util.Random;

/** Share strings are synthetic. No URLs are requested and no Android objects are created. */
public final class SharedTextLinksTest {
    private static int checks;
    private static void ok(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static void empty(String text,String label){ok(SharedTextLinks.extract(text).isEmpty(),label);}
    private static String repeat(String value,int count){StringBuilder out=new StringBuilder();for(int i=0;i<count;i++)out.append(value);return out.toString();}
    public static void main(String[] args){
        List<NativeLinkTarget> links=SharedTextLinks.extract("[讨论] 新分享\nhttps://bbs.nga.cn/read.php?tid=91001&pid=92001&page=4\n来自 NGA");
        ok(links.size()==1,"title/newline/link share extracted");NativeLinkTarget post=links.get(0);
        ok(post.kind==NativeLinkTarget.TOPIC&&post.topicId.equals("91001")&&post.postId.equals("92001")&&post.page==4,"topic, post and page are preserved");
        ok(SharedTextLinks.extract("看这里（HTTP://BBS.NGA.CN:80/read.php?tid=91001）。").get(0).url.equals("https://bbs.nga.cn/read.php?tid=91001"),"legacy HTTP and Chinese trailing punctuation");
        ok(SharedTextLinks.extract("<https://ngabbs.com/thread.php?fid=-7&page=2>").get(0).forumId.equals("-7"),"angle wrapped negative board");
        ok(SharedTextLinks.extract("合集：https://nga.178.com/thread.php?stid=12067303！").get(0).collectionId.equals("12067303"),"collection retained");
        ok(SharedTextLinks.extract("用户 \"https://bbs.nga.cn/nuke.php?__lib=ucp&uid=70001\"").get(0).userId.equals("70001"),"user profile is read only");
        ok(SharedTextLinks.extract("https://bbs.nga.cn/thread.php?favor=70001").get(0).kind==NativeLinkTarget.FAVORITE,"read only favorites route");
        ok(SharedTextLinks.extract("https://bbs.nga.cn/nuke.php?__lib=message&__act=list").get(0).kind==NativeLinkTarget.MESSAGES,"read only messages route");
        ok(SharedTextLinks.extract("https://bbs.nga.cn/nuke.php?__lib=noti&__act=get_all").get(0).kind==NativeLinkTarget.REMINDERS,"read only reminders route");
        ok(SharedTextLinks.extract("https://bbs.nga.cn/").get(0).kind==NativeLinkTarget.HOME,"native home route");
        ok(SharedTextLinks.extract("看：https://bbs.nga.cn/read.php?tid=91001。后面还有说明。").get(0).topicId.equals("91001"),"sentence punctuation terminates URL before trailing prose");
        ok(SharedTextLinks.extract("https://bbs.nga.cn/read.php?tid=1，https://bbs.nga.cn/read.php?tid=2").size()==2,"Chinese comma separates adjacent links");
        ok(SharedTextLinks.extract("[帖子](https://bbs.nga.cn/read.php?tid=91001) 后文").get(0).topicId.equals("91001"),"markdown wrapping punctuation");
        empty(null,"null input");empty("普通关键词 明日方舟","plain keywords are not links");empty("read.php?tid=91001","relative URL is not imported from arbitrary prose");
        empty("https://bbs.nga.cn.evil.example/read.php?tid=91001","suffix fake host");
        empty("https://bbs.nga.cn。evil.example/read.php?tid=91001","Unicode dot cannot truncate a fake host into official home");
        empty("https://bbs.nga.cn)evil.example/read.php?tid=91001","closing punctuation inside fake host does not create official home");
        empty("https://bbs.nga.cn@evil.example/read.php?tid=91001","official host as credentials");
        empty("https://alice:secret@bbs.nga.cn/read.php?tid=91001","credentials even on official authority");
        empty("https://evil.example/?url=https://bbs.nga.cn/read.php?tid=91001","nested official URL is not opened out of an external URL");
        empty("https://evil.example/?url=(https://bbs.nga.cn/read.php?tid=91001)","nested parenthesized official URL remains part of foreign token");
        empty("https://bbs.nga.cn:444/read.php?tid=91001","unexpected port");
        empty("https://bbs.nga.cn/read.php?tid=91001&tid=91002","ambiguous duplicate parameter");
        empty("https://bbs.nga.cn/read.php?tid=91001#unknown-anchor","unsupported semantics are not silently dropped");
        empty("https://bbs.nga.cn/nuke.php?__lib=login&__act=login_ui","imported text never opens login authorization");
        empty("https://bbs.nga.cn/post.php?action=reply&tid=91001","imported text never opens editor");
        empty("https://bbs.nga.cn/nuke.php?__lib=ucp&__act=set&uid=70001","mutation link blocked");
        empty("https://bbs.nga.cn/post.php?action=reply&tid=91001&step=2&content=test","publishing link blocked");
        empty("https://bbs.nga.cn/misc/event/example/index.html","external activity does not become a native route");
        empty("https://bbs.nga.cn/read.php?tid=91001\u0000","NUL rejects share");
        empty("https://bbs.nga.cn/read.php?tid=91001\u202e","bidi override rejects share");
        empty(repeat("a",SharedTextLinks.MAX_TEXT+1),"oversize text rejected before scanning");
        links=SharedTextLinks.extract("http://bbs.nga.cn/read.php?tid=91001 https://ngabbs.com/read.php?tid=91001&from=share https://bbs.nga.cn/read.php?tid=91001&page=2");
        ok(links.size()==2&&links.get(1).page==2,"same destination aliases/transport deduplicate, different page remains");
        StringBuilder many=new StringBuilder();for(int i=1;i<=20;i++)many.append("https://bbs.nga.cn/read.php?tid=").append(i).append('\n');
        links=SharedTextLinks.extract(many.toString());ok(links.size()==8&&links.get(7).topicId.equals("8"),"at most eight distinct destinations in order");
        try{links.clear();throw new AssertionError("mutable result");}catch(UnsupportedOperationException expected){checks++;}
        ok(SharedTextLinks.extract("https://evil.example/read.php?tid=1\nhttps://bbs.nga.cn/read.php?tid=2").get(0).topicId.equals("2"),"invalid candidate does not hide a later independent official link");
        Random random=new Random(9627);for(int n=0;n<1000;n++){StringBuilder raw=new StringBuilder();int count=random.nextInt(100);for(int i=0;i<count;i++)raw.append((char)random.nextInt(128));ok(SharedTextLinks.extract(raw.toString()).size()<=8,"bounded arbitrary input "+n);}
        System.out.println("SharedTextLinks offline checks passed: "+checks);
    }
}
