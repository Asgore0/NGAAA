package cn.ngapure.app;

/** Standalone regression checks; run with ./test-renderer.sh. */
public final class PostRendererTest {
    private static int checks;

    public static void main(String[] args) {
        String normal = body("正文 [b]粗体 [i]斜体[/i][/b] [u]下划线[/u] [del]删除[/del]\n"
                + "[quote]引用内容[/quote][collapse=配装详情]装备说明[/collapse] [s:ac:good]");
        contains(normal, "<strong>粗体 <em>斜体</em></strong>");
        contains(normal, "<u>下划线</u>");
        contains(normal, "<del>删除</del><br>");
        contains(normal, "<blockquote>引用内容</blockquote>");
        contains(normal, "<summary>配装详情</summary>");
        contains(normal, "装备说明</div></details>");
        contains(normal, "[s:ac:good]");

        equal(body("[align=center][size=150%][b]签到活动[/b][/size][/align]"),
                "<div class=\"align-center\"><span style=\"font-size:24px\"><strong>签到活动</strong></span></div>");
        equal(body("[ALIGN='right']右侧[/ALIGN][align=justify]两端[/align]"),
                "<div class=\"align-right\">右侧</div><div class=\"align-justify\">两端</div>");
        equal(body("[color=Crimson]奖励[/color][color=darkblue]活动[/color]"),
                "<span class=\"bb-color\" style=\"color:crimson\">奖励</span>"
                        + "<span class=\"bb-color\" style=\"color:darkblue\">活动</span>");
        equal(body("[color=#1A2b3C]颜色[/color][color='#abc']短色值[/color]"),
                "<span class=\"bb-color\" style=\"color:#1a2b3c\">颜色</span>"
                        + "<span class=\"bb-color\" style=\"color:#abc\">短色值</span>");
        contains(body("[color=darkblue][url=https://bbs.nga.cn/]活动[/url][/color]"),
                "style=\"color:darkblue\"><a href=\"https://bbs.nga.cn/\"");
        equal(body("[size=500%]外[size=200%]内[/size][/size]"),
                "<span style=\"font-size:80px\">外<span style=\"font-size:32px\">内</span></span>");
        contains(PostRenderer.html("[size=150%]跟随字号[/size]", false, 20),
                "<span style=\"font-size:30px\">跟随字号</span>");
        equal(body("[size=150%]未闭合"), "[size=150%]未闭合");
        equal(body("[quote][align=center][size=150%][b][color=crimson]更多活动[/color][/align]"
                        + "[align=center]下一行[/b][/size][/align][/quote]"),
                "<blockquote><div class=\"align-center\"><span style=\"font-size:24px\"><strong>"
                        + "<span class=\"bb-color\" style=\"color:crimson\">更多活动</span></strong></span></div>"
                        + "<div class=\"align-center\">下一行</div></blockquote>");
        equal(body("[b][i]交叉闭合[/b]后续[/i]"), "<strong><em>交叉闭合</em></strong>后续");
        equal(body("[code][align=center][size=150%][color=crimson]原文[/code]"),
                "<pre class=\"code\"><code>[align=center][size=150%][color=crimson]原文</code></pre>");
        for (String value : new String[]{"center;position:fixed", "center\" onclick=\"alert(1)", "url(https://evil.test)"})
            absent(body("[align=" + value + "]内容[/align]"), "<div class=\"align-");
        for (String value : new String[]{"crimson;background:url(https://evil.test)", "expression(alert(1))", "red\" onmouseover=\"alert(1)", "var(--x)"})
            absent(body("[color=" + value + "]内容[/color]"), "<span class=\"bb-color\"");
        for (String value : new String[]{"49%", "501%", "1000000%", "150%;position:fixed", "calc(150%)", "150%\" onclick=\"alert(1)"})
            absent(body("[size=" + value + "]内容[/size]"), "<span style=\"font-size:");

        String links = body("[url=https://bbs.nga.cn/read.php?tid=123&fav=1][b]讨论主题[/b][/url] "
                + "[url]https://bbs.nga.cn/thread.php?fid=7[/url]");
        contains(links, "href=\"https://bbs.nga.cn/read.php?tid=123&amp;fav=1\"");
        contains(links, "<strong>讨论主题</strong></a>");
        contains(links, ">https://bbs.nga.cn/thread.php?fid=7</a>");
        contains(body("[URL='https://bbs.nga.cn/']社区[/URL]"), "href=\"https://bbs.nga.cn/\"");
        contains(body("[url=https://bbs.nga.cn/][url=https://example.com/]内部[/url][/url]"), "内部</a>");
        equal(count(body("[url=https://bbs.nga.cn/][url=https://example.com/]内部[/url][/url]"), "<a "), 1);

        for (String host : new String[] { "img.nga.178.com", "img4.nga.178.com", "img.nga.cn" }) {
            contains(body("[img]https://" + host + "/attachments/mon_202610/01.jpg[/img]"),
                    "<img src=\"https://" + host + "/attachments/mon_202610/01.jpg\"");
        }
        contains(body("[img]HTTPS://IMG.NGA.178.COM/attachments/a.jpg[/img]"), "<img src=");

        String relativeImage = body("[img]./mon_202610/18/-7Q2g-1i5K1nT1kShs-12h.jpg[/img]");
        contains(relativeImage, "<img src=\"https://img.nga.cn/attachments/mon_202610/18/-7Q2g-1i5K1nT1kShs-12h.jpg\"");
        contains(relativeImage, "href=\"https://img.nga.cn/attachments/mon_202610/18/-7Q2g-1i5K1nT1kShs-12h.jpg\"");
        contains(body("[align=center][img]./mon_202610/14/niQ64-kog1Z13T3cS1hc-u0.webp[/img][/align]"),
                "<div class=\"align-center\"><a class=\"image-link\" href=\"https://img.nga.cn/attachments/mon_202610/14/niQ64-kog1Z13T3cS1hc-u0.webp\"");
        contains(body("[img]./mon_202402/29/a.png.medium.jpg[/img]"), "<img src=");
        contains(body("[img]./mon_200002/29/a.GIF[/img]"), "<img src=");
        for (String host : new String[] { "img.nga.178.com", "img4.nga.178.com", "img.nga.cn" }) {
            contains(body("[img]//" + host + "/attachments/mon_202610/18/a.jpg[/img]"),
                    "<img src=\"https://" + host + "/attachments/mon_202610/18/a.jpg\"");
        }
        for (String image : new String[] {
                "./mon_202610/18/a.jpg?", "./mon_202610/18/a.jpg?size=large",
                "./mon_202610/18/a.jpg#", "./mon_202610/18/a.jpg#fragment",
                "./mon_202610/18/a.jpg?x=1#fragment", "./mon_202610/18/a%3f.jpg",
                "./mon_202610/18/a%23.jpg", "./mon_202610/18/a%2fb.jpg",
                "./mon_202610/18/%2e%2e/a.jpg", "./mon_202610/18/%252e%252e/a.jpg",
                "./mon_202610/18/../a.jpg", "./mon_202610/18/a/../../b.jpg",
                "./mon_202610/18/a/./b.jpg", "./mon_202610/18/.hidden.jpg",
                "../mon_202610/18/a.jpg", "/mon_202610/18/a.jpg", "mon_202610/18/a.jpg",
                "./attachments/mon_202610/18/a.jpg", "./mon_20261/18/a.jpg",
                "./mon_202610/1/a.jpg", "./MON_202610/18/a.jpg", "./mon_000010/18/a.jpg",
                "./mon_202600/18/a.jpg", "./mon_202613/18/a.jpg", "./mon_202610/00/a.jpg",
                "./mon_202610/32/a.jpg", "./mon_202604/31/a.jpg", "./mon_202602/29/a.jpg",
                "./mon_190002/29/a.jpg", "./mon_202610/18/a.svg", "./mon_202610/18/a.html",
                "./mon_202610/18/a.jpg/", "./mon_202610/18/a.jpg\\evil",
                "./mon_202610/18/a.jpg\nattack", "./mon_202610/18/a.jpg\u00A0attack",
                "//evil.test/attachments/mon_202610/18/a.jpg",
                "//img.nga.178.com.evil.test/attachments/a.jpg",
                "//img.nga.178.com@evil.test/attachments/a.jpg",
                "///img.nga.178.com/attachments/a.jpg", "//img.nga.178.com:444/attachments/a.jpg",
                "//img.nga.178.com/attachments/a.jpg?x=1", "//img.nga.178.com/attachments/a.jpg#x",
                "https://img.nga.178.com/attachments/a/../b.jpg",
                "https://img.nga.178.com/attachments/a/./b.jpg",
                "https://img.nga.178.com/attachments/a%2f..%2fb.jpg",
                "https://img.nga.178.com/attachments/a.jpg?x=1",
                "https://img.nga.178.com/attachments/a.jpg#fragment" }) {
            absent(body("[img]" + image + "[/img]"), "<img ");
        }

        String injected = body("<script>alert(1)</script><img src=x onerror=alert(1)> & \"' "
                + "[collapse=<img src=x onerror=alert(2)>]内容[/collapse]");
        absent(injected, "<script");
        absent(injected, "<img");
        contains(injected, "&lt;script&gt;alert(1)&lt;/script&gt;");
        contains(injected, "&amp; &quot;&#39;");
        contains(injected, "<summary>&lt;img src=x onerror=alert(2)&gt;</summary>");

        for (String scheme : new String[] { "javascript:alert(1)", "JaVaScRiPt:alert(1)",
                "data:text/html,<script>alert(1)</script>", "file:///etc/passwd",
                "content://example/secret", "intent://anything", "//example.com/", "https://user@example.com/",
                "https://example.com/\nattack", "https://example.com\\@evil.test/" }) {
            absent(body("[url=" + scheme + "]危险链接[/url]"), "<a ");
            absent(body("[url]" + scheme + "[/url]"), "<a ");
        }
        for (String image : new String[] { "http://external.example/attachments/a.jpg",
                "https://evil.test/attachments/a.jpg", "https://img.nga.178.com.evil.test/attachments/a.jpg",
                "https://img.nga.178.com@evil.test/attachments/a.jpg", "data:image/svg+xml,<svg onload=alert(1)>",
                "file:///sdcard/a.jpg", "content://media/image/1", "https://img.nga.178.com/a.jpg",
                "https://img.nga.178.com:444/attachments/a.jpg",
                "https://img.nga.178.com/attachments/%2e%2e/private.jpg",
                "https://img.nga.178.com/attachments/../../private.jpg" }) {
            absent(body("[img]" + image + "[/img]"), "<img ");
        }

        String code = body("[code][b]保持原文[/b]\n[img]https://img.nga.cn/attachments/a.jpg[/img]"
                + "<script>alert('x')</script>[/code][b]恢复渲染[/b]");
        contains(code, "<pre class=\"code\"><code>[b]保持原文[/b]\n[img]");
        contains(code, "&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;</code></pre>");
        absent(code, "<img ");
        absent(code, "<strong>保持原文");
        contains(code, "<strong>恢复渲染</strong>");
        absent(body("[code]<script>alert(1)</script>"), "<script");
        contains(body("[b]未闭合"), "[b]未闭合");
        contains(body("未知[unsupported=x]标签[/unsupported]"), "[unsupported=x]标签[/unsupported]");
        equal(body(null), "");
        contains(body("a\r\nb\rc"), "a<br>b<br>c");

        equal(body("一<br/>二<br />三<BR>四<Br />五\r\n六"), "一<br>二<br>三<br>四<br>五<br>六");
        equal(body("&lt;br/&gt;"), "&lt;br/&gt;");
        equal(body("空&nbsp;格 &amp; &lt; &gt; &quot; &#39;"),
                "空\u00A0格 &amp; &lt; &gt; &quot; &#39;");
        equal(body("&amp;lt;script&amp;gt; &unknown; &#x3c;"),
                "&amp;lt;script&amp;gt; &amp;unknown; &amp;#x3c;");
        String encodedScript = body("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;"
                + "<br onclick=\"alert(1)\"><img src=x onerror=alert(2)>");
        absent(encodedScript, "<script");
        absent(encodedScript, "<img");
        absent(encodedScript, "<br onclick");
        contains(encodedScript, "&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;");
        contains(encodedScript, "&lt;br onclick=&quot;alert(1)&quot;&gt;");
        equal(body("[code]a&lt;b<br/>c &amp; d[/code]"),
                "<pre class=\"code\"><code>a&lt;b\nc &amp; d</code></pre>");
        contains(body("[collapse=&lt;script&gt;]x&nbsp;y[/collapse]"),
                "<summary>&lt;script&gt;</summary><div class=\"collapse-content\">x\u00A0y");
        contains(body("[url=https://bbs.nga.cn/read.php?tid=123&amp;page=2]帖子 &amp; 讨论[/url]"),
                "href=\"https://bbs.nga.cn/read.php?tid=123&amp;page=2\"");
        contains(body("[url=https://bbs.nga.cn/read.php?tid=123&amp;page=2]帖子 &amp; 讨论[/url]"),
                ">帖子 &amp; 讨论</a>");

        String dark = PostRenderer.html("夜间", true, 999);
        contains(dark, "background:#0E141F");
        contains(dark, "font-size:28px");
        contains(PostRenderer.html("浅色", false, -5), "font-size:12px");
        contains(PostRenderer.html("浅色", false, 16), "background:#F4F6FA");
        contains(dark, "http-equiv=\"Content-Security-Policy\"");
        contains(dark, "default-src &#39;none&#39;");
        contains(dark, "img-src https://img.nga.178.com https://img4.nga.178.com https://img.nga.cn https://img4.nga.cn;");
        contains(dark, "style-src &#39;unsafe-inline&#39;");
        absent(dark, "<script");

        StringBuilder nested = new StringBuilder();
        for (int i = 0; i < 5000; i++) nested.append("[b]");
        nested.append("边界");
        for (int i = 0; i < 5000; i++) nested.append("[/b]");
        contains(body(nested.toString()), "边界");
        officialTagFamilies();
        nativeReferences();
        officialEmoticons();
        malformedLongPosts();
        System.out.println("PostRenderer: " + checks + " checks passed.");
    }

    /** Official core syntax plus the jumpurl content returned by current activity posts. */
    private static void officialTagFamilies() {
        String event="https://bbs.nga.cn/misc/event/20261015zmd/index.html";
        equal(body("[jumpurl]"+event+"[/jumpurl]"),"<a href=\""+event+"\" rel=\"noreferrer noopener\">"+event+"</a>");
        // Exact public activity post 47190819, fetched by the integration owner.
        String screenshotPost = body("[jumpurl][url]https://bbs.nga.cn/misc/event/20261015zmd/index.html[/url][/jumpurl]<br/><br/>"
                + "[img]./mon_202610/14/niQ64-hkfhK2oT3cS1ga-6m.webp[/img]");
        absent(screenshotPost,"[jumpurl]"); absent(screenshotPost,"[/jumpurl]");
        absent(screenshotPost,"[url]"); absent(screenshotPost,"[/url]");
        equal(count(screenshotPost,"<a href=\""+event+"\" rel=\"noreferrer noopener\">"),1);
        equal(count(screenshotPost,"<a "),2); // One activity link plus the existing native image-viewer link.
        equal(count(screenshotPost,"<a class=\"image-link\""),1);
        equal(count(screenshotPost,"<img "),1);
        contains(screenshotPost,"<img src=\"https://img.nga.cn/attachments/mon_202610/14/niQ64-hkfhK2oT3cS1ga-6m.webp\"");
        contains(screenshotPost,">"+event+"</a><br><br><a class=\"image-link\"");
        String titledActivity=body("[jumpurl][url="+event+"][b]报名入口[/b][/url][/jumpurl]");
        equal(count(titledActivity,"<a "),1); contains(titledActivity,"href=\""+event+"\"");
        contains(titledActivity,"><strong>报名入口</strong></a>"); absent(titledActivity,"[jumpurl]"); absent(titledActivity,"[url=");
        String nestedActivity=body("[jumpurl][url="+event+"][url=https://example.com/][b]报名入口[/b][/url][/url][/jumpurl]");
        equal(count(nestedActivity,"<a "),1); contains(nestedActivity,"href=\""+event+"\"");
        contains(nestedActivity,"><strong>报名入口</strong></a>"); absent(nestedActivity,"href=\"https://example.com/");

        contains(body("[JUMPURL='"+event+"?x=1&amp;y=2'][b]报名[/b][/JUMPURL]"),"?x=1&amp;y=2\" rel=\"noreferrer noopener\"><strong>报名</strong></a>");
        contains(body("[jumpurl][url="+event+"]活动入口[/url][/jumpurl]"),">活动入口</a>");
        equal(count(body("[url="+event+"][jumpurl=https://example.com/][b]入口[/b][/jumpurl][/url]"),"<a "),1);
        equal(count(body("[jumpurl="+event+"][url]https://example.com/[/url][/jumpurl]"),"<a "),1);
        equal(body("[jumpurl]未闭合"),"[jumpurl]未闭合");
        equal(body("[jumpurl]javascript:alert(1)[/jumpurl]"),"javascript:alert(1)");
        equal(body("[jumpurl=https://example.com/][b]一[i]二[/jumpurl]三[/i][/b]"),"<a href=\"https://example.com/\" rel=\"noreferrer noopener\"><strong>一<em>二</em></strong></a>三");
        for(String raw:new String[]{"javascript:alert(1)","data:text/html,a","https://user@evil.test/","https://bbs.nga.cn/\nattack","https://bbs.nga.cn\\@evil.test/","//evil.test/"}) {
            absent(body("[jumpurl="+raw+"][b]禁止[/b][/jumpurl]"),"<a ");
            absent(body("[jumpurl]"+raw+"[/jumpurl]"),"<a ");
        }
        contains(body("[url=/read.php?tid=345&amp;page=2]相对链接[/url]"),"href=\"https://bbs.nga.cn/read.php?tid=345&amp;page=2\"");
        contains(body("[jumpurl]//bbs.nga.cn/misc/event/a/index.html[/jumpurl]"),"href=\"https://bbs.nga.cn/misc/event/a/index.html\"");
        String literal="[jumpurl]"+event+"[/jumpurl][img0]./mon_202610/18/a.png[/img][list][*]一[/list]";
        equal(body("[code=js]"+literal+"\n  <script>bad</script>[/code]"),"<pre class=\"code\"><code>"+literal+"\n  &lt;script&gt;bad&lt;/script&gt;</code></pre>");
        equal(body("[CODE=lua][b]x[/b][/CODE]"),"<pre class=\"code\"><code>[b]x[/b]</code></pre>");
        equal(body("[code=xml]<b>[list][*]literal"),"[code=xml]&lt;b&gt;[list][*]literal");
        for(String variant:new String[]{"img0","img1","img100","img-2"}) {
            String actual=body("["+variant+"]./mon_202610/18/a.png[/img]");
            contains(actual,"<img src=\"https://img.nga.cn/attachments/mon_202610/18/a.png\"");
            absent(actual,"["+variant+"]");
        }
        contains(body("[img]https://img4.nga.cn/attachments/mon_202610/18/a.png[/img]"),"<img src=\"https://img4.nga.cn/");
        for(String host:new String[]{"img.nga.178.com","img4.nga.178.com","img.nga.cn","img4.nga.cn"}) {
            String upgraded=body("[img]http://"+host+"/attachments/mon_202610/18/a.png[/img]");
            contains(upgraded,"<img src=\"https://"+host+"/"); absent(upgraded,"src=\"http:");
        }
        absent(body("[img]http://img4.nga.cn.evil.example/attachments/a.png[/img]"),"<img ");
        absent(body("[img]http://img4.nga.cn:80/attachments/a.png[/img]"),"<img ");
        String qr=body("[img-1]https://bbs.nga.cn/read.php?tid=345[/img]");
        absent(qr,"<img "); contains(qr,"二维码内容："); contains(qr,"href=\"https://bbs.nga.cn/read.php?tid=345\"");
        equal(body("[img-1][url]https://bbs.nga.cn/read.php?tid=345[/url][/img]"), qr);
        String outside=body("[img0]https://external.example/a.png[/img]");
        absent(outside,"<img "); absent(outside,"[img0]"); contains(outside,"图片：");
        equal(body("[list][*]一[*][b]二[/b][/list]"),"<div>• 一<br>• <strong>二</strong></div>");
        equal(body("[list=a][*]甲[*]乙[/list]"),"<div>a. 甲<br>b. 乙</div>");
        equal(body("[list=I][*]甲[*]乙[/list]"),"<div>I. 甲<br>II. 乙</div>");
        equal(body("[list=1][*]外一[list][*]内一[*]内二[/list][*]外二[/list]"),"<div>1. 外一<div>• 内一<br>• 内二</div><br>2. 外二</div>");
        equal(body("[*]普通"),"[*]普通");
        equal(body("[list=unsafe][*]普通[/list]"),"[list=unsafe][*]普通[/list]");
        equal(body("[sup]2[/sup][sub]3[/sub][font=Courier New]代码字体[/font]"),"<sup>2</sup><sub>3</sub><font face=\"Courier New\">代码字体</font>");
        equal(body("[font=evil;position:fixed]正文[/font]"),"正文");
        equal(body("[align center][size 150][color bgred]标题[/color][/size][/align]"),"<div class=\"align-center\"><span style=\"font-size:24px\"><span class=\"bb-color\" style=\"background-color:red\">标题</span></span></div>");
        equal(body("[l]左[/l][r]右[/r][h]标题[/h][upup]强调[/upup]"),"<div class=\"align-left\">左</div><div class=\"align-right\">右</div><h4>标题</h4><strong>强调</strong>");
        equal(body("[span aligntable]正文[/span][style color red]更多[/style][randomblock]分组[/randomblock][headline]头条[/headline]"),"<span>正文</span><div>更多</div><div>分组</div><div>头条</div>");
        equal(body("[fixsize 900 1200][comment note]正文[/comment note][stripbr][omit 20]完整正文[/omit][symbol ★]"),"正文<span>完整正文</span>★");
        equal(body("[table][tr][td]名称[/td][td33]数值[/td][/tr][tr th][td colspan2 width50][b]下一行[/b][/td][/tr][/table]"),"<table class=\"nga-table\"><tr><td>名称</td><td>数值</td></tr><tr class=\"header\"><td colspan=\"2\"><strong>下一行</strong></td></tr></table>");
        equal(body("[tr][td]普通[/td][/tr]"),"[tr][td]普通[/td][/tr]");
        for(String tag:new String[]{"flash","flash=video","media=audio","iframe=500,900","attach"}) {
            String kind=tag.split("=")[0], actual=body("["+tag+"]https://example.com/media.mp4?x=1&amp;y=2[/"+kind+"]");
            absent(actual,"["+tag+"]"); contains(actual,"href=\"https://example.com/media.mp4?x=1&amp;y=2\""); absent(actual,"<iframe");
        }
        contains(body("[attach]./mon_202610/18/archive.zip[/attach]"),"href=\"https://img.nga.cn/attachments/mon_202610/18/archive.zip\"");
        absent(body("[attach]./mon_202610/18/../private.zip[/attach]"),"<a ");
        equal(body("[crypt]secret-encoded-blob[/crypt]"),"<section class=\"nga-crypt\">secret-encoded-blob</section>");
        equal(PostRenderer.plainText("[crypt]secret-encoded-blob[/crypt]"),"加密内容");
        contains(body("[dice]2d6+1[/dice]"),"<strong>ROLL：2d6+1</strong>");
        String album=body("[album=活动图片]\n./mon_202610/18/a.png\n[img0]./mon_202610/18/b.png[/img]\nhttps://example.com/a.png[/album]");
        equal(count(album,"<img "),2); contains(album,"<strong>活动图片</strong>"); contains(album,"href=\"https://example.com/a.png\""); absent(album,"[album");
        for(String tag:new String[]{"tid","pid","uid","fid","stid"}) {
            String number=tag.equals("fid")?"-7":"345";
            contains(body("["+tag+"]"+number+"[/"+tag+"]"),"href=\"https://bbs.nga.cn/");
            contains(body("["+tag+"="+number+"][b]引用[/b][/"+tag+"]"),"><strong>引用</strong></a>");
        }
        contains(body("[pid=678]楼层[/pid]"),"read.php?pid=678\"");
        contains(body("[pid=678,345]楼层[/pid]"),"read.php?tid=345&amp;pid=678&amp;to=1\"");
        contains(body("[tid=345,678,2]楼层[/tid]"),"read.php?tid=345&amp;pid=678&amp;page=2\"");
        contains(body("[@论坛用户]"),"username=%E8%AE%BA%E5%9D%9B%E7%94%A8%E6%88%B7\"");
        contains(body("[@UID123]"),"&amp;uid=123\"");
        contains(body("[@123]"),"&amp;uid=123\"");
        equal(body("[uid]anony_0123456789abcdef0123456789abcdef[/uid]"),"匿名用户");
        for(String value:new String[]{"0","-0","--7","-01","-7&admin=1","12345678901"})absent(body("[fid="+value+"]版块[/fid]"),"<a ");
        equal(PostRenderer.plainText("[jumpurl="+event+"][b]活动[/b][/jumpurl][h]列表[/h][list][*]一[*]二[/list]"),"活动\n列表\n• 一\n• 二");
        equal(PostRenderer.plainText("[code=js]  [jumpurl]literal[/jumpurl]\n<b>原始</b>[/code]"),"[jumpurl]literal[/jumpurl]\n<b>原始</b>");
        equal(PostRenderer.plainText("普通[文本] [unknown=1]正文[/unknown] &amp;lt;b&amp;gt;"),"普通[文本] [unknown=1]正文[/unknown] &lt;b&gt;");
        equal(PostRenderer.plainText(null),"");
        equal(body("[br][color nonsense]安全正文[/color][size huge]内容[/size][align javascript]尾[/align]"),"<br>安全正文内容尾");
        for(String tag:new String[]{"flash","media"})contains(body("["+tag+"]诸元[/"+tag+"]"),"诸元");
        contains(body("[media=video]./mon_202610/19/a.mp4[/media]"),"href=\"https://img.nga.cn/attachments/mon_202610/19/a.mp4\"");
        absent(body("[media]./mon_202699/19/a.mp4[/media]"),"<a ");
        equal(body("[spine]{\"untrusted\":\"asset\"}[/spine]"),"<span>Spine 骨骼动画（当前尚未支持）</span>");
        equal(body("[customachieve][title]成就[/title][txt][b]描述[/b][/txt][/customachieve]"),"<div><strong>成就</strong><br><span><strong>描述</strong></span></div>");
        equal(body("普通[title]文字[/title]"),"普通[title]文字[/title]");
        equal(body("[lessernuke1]保留公开正文[/lessernuke1]"),"<blockquote>保留公开正文</blockquote>");
        equal(body("[lessernuke1]保留公开正文[/lessernuke]"),"<blockquote>保留公开正文</blockquote>");
    }

    private static void malformedLongPosts() {
        // Public posts may contain pasted code or damaged BBCode. Rendering
        // must preserve every literal bracket and following valid formatting.
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 100000; i++) raw.append('[');
        equal(body(raw.toString()), raw.toString());
        raw.append("x] tail");
        String expected = raw.toString() + "<strong>有效</strong>";
        raw.append("[b]有效[/b]");
        equal(body(raw.toString()), expected);
        for (String tag : new String[]{"img", "url", "jumpurl", "tid", "album"}) {
            StringBuilder missing = new StringBuilder();
            for (int i = 0; i < 10000; i++) missing.append('[').append(tag).append(']');
            missing.append("终点");
            String expectedMissing = missing.toString() + "<strong>正常</strong>";
            missing.append("[b]正常[/b]");
            equal(body(missing.toString()), expectedMissing);
        }
    }

    private static void nativeReferences() {
        equal(body("[uid=123]用户[/uid]"),
                "<a href=\"https://bbs.nga.cn/nuke.php?__lib=ucp&amp;__act=get&amp;uid=123\" rel=\"noreferrer noopener\">用户</a>");
        equal(body("[pid=678,345,2]Reply[/pid]"),
                "<a href=\"https://bbs.nga.cn/read.php?tid=345&amp;pid=678&amp;page=2\" rel=\"noreferrer noopener\">Reply</a>");
        equal(body("[tid=345]主题[/tid]"),
                "<a href=\"https://bbs.nga.cn/read.php?tid=345\" rel=\"noreferrer noopener\">主题</a>");
        contains(body("[UID=123][b]用户名 &amp; 更多[/b][/UID]"), "><strong>用户名 &amp; 更多</strong></a>");
        contains(body("[PID=678,345,99999]楼层[/PID]"), "&amp;page=99999\"");
        contains(body("[uid=123456789012345678]用户[/uid]"), "uid=123456789012345678\"");
        contains(body("[tid=12345678901234]主题[/tid]"), "tid=12345678901234\"");
        contains(body("[pid=123456789012345678,12345678901234,1]楼层[/pid]"), "pid=123456789012345678&amp;page=1\"");
        equal(body("[uid=123]未闭合"), "[uid=123]未闭合");
        equal(body("[pid=678,345,2]未闭合"), "[pid=678,345,2]未闭合");
        equal(body("[tid=345]未闭合"), "[tid=345]未闭合");
        equal(count(body("[url=https://bbs.nga.cn/][uid=123]用户[/uid][/url]"), "<a "), 1);
        equal(count(body("[uid=123][pid=678,345,2]引用[/pid][/uid]"), "<a "), 1);
        contains(body("[uid=123]<img src=x onerror=alert(1)>[/uid]"), "&lt;img src=x onerror=alert(1)&gt;</a>");
        equal(body("[code][uid=123]用户[/uid] [pid=678,345,2]Reply[/pid] [tid=345]主题[/tid][/code]"),
                "<pre class=\"code\"><code>[uid=123]用户[/uid] [pid=678,345,2]Reply[/pid] [tid=345]主题[/tid]</code></pre>");
        for (String value : new String[]{"0", "-1", "+1", "01", "1234567890123456789", "1e3", "1.0", "１２３", " 123", "123 ", "'123'", "123&uid=456", "123\" onclick=\"x", "https://evil.test/", "1\n2"}) {
            String raw = "[uid=" + value + "]用户[/uid]";
            absent(body(raw), "<a "); contains(body(raw), "[uid="); contains(body(raw), "用户[/uid]");
        }
        for (String value : new String[]{"0", "-1", "123456789012345", "345&pid=1", "345#x", "345/evil"})
            absent(body("[tid=" + value + "]主题[/tid]"), "<a ");
        for (String value : new String[]{"678,345,2,3", "0,345,1", "678,0,1", "678,345,0", "678,345,100000", "678,345,-1", "678,345,01", "678, 345,1", "678,345,1&uid=1", "678,345,1\" onclick=\"x", "678,345,", "678,,1", ",345,1", "678,345,1e3"}) {
            String raw = "[pid=" + value + "]Reply[/pid]";
            absent(body(raw), "<a "); contains(body(raw), "Reply[/pid]");
        }
        contains(body("[gid=123]未知引用[/gid]"), "[gid=123]未知引用[/gid]");
    }

    private static void officialEmoticons() {
        String base = "https://img4.nga.cn/ngabbs/post/smile/";
        String[][] verified = {{"ac:goodjob", "ac1.png"}, {"ac:哭笑", "ac15.png"}, {"ac:茶", "ac39.png"},
                {"ac:blink", "ac0.png"}, {"ac:偷笑", "ac4.png"}, {"ac:羡慕", "ac37.png"}, {"ac:赞同", "ac42.png"},
                {"a2:doge", "a2_27.png"}, {"a2:goodjob", "a2_02.png"}, {"a2:干杯", "a2_54.png"},
                {"a2:jojo立5", "a2_40.png"}, {"1", "smile.gif"}, {"0:2", "mrgreen.gif"}, {":3", "question.gif"}, {"43", "23.gif"}};
        for (String[] entry : verified) {
            String rendered = body("前[s:" + entry[0] + "]后");
            contains(rendered, "前<img class=\"nga-smile\" src=\"" + base + entry[1] + "\"");
            contains(rendered, "alt=\"[s:" + entry[0] + "]\" referrerpolicy=\"no-referrer\">后");
            equal(count(rendered, "<img "), 1);
            equal(count(rendered, "<a "), 0);
            if (!PostRenderer.isEmoticonImage(base + entry[1])) throw new AssertionError("Verified native emoticon source not recognized");
            checks++;
        }
        for (String unknown : new String[]{"ac:good", "ac:GOODJOB", "ac:unknown", "ac:", "unknown:goodjob", "a2:not-a-name", "999", "0", "01", "-1", "ac:goodjob:extra", "ac:../ac1.png", "ac:goodjob?x=1", "ac:%67oodjob"}) {
            String code = "[s:" + unknown + "]";
            equal(body(code), code); absent(body(code), "<img ");
        }
        for (String malicious : new String[]{"ac:goodjob\" onerror=\"x", "ac:<script>alert(1)</script>", "ac:goodjob\nhttps://evil.test"}) {
            String rendered = body("[s:" + malicious + "]");
            absent(rendered, "<img "); absent(rendered, "<script");
        }
        contains(body("[b]好[s:ac:goodjob][/b]"), "<strong>好<img class=\"nga-smile\"");
        contains(body("[S:ac:goodjob]"), "src=\"" + base + "ac1.png\"");
        contains(body("[collapse=表情]内容[s:a2:doge][/collapse]"), "<summary>表情</summary><div class=\"collapse-content\">内容<img class=\"nga-smile\"");
        equal(body("[code][s:ac:goodjob] [s:ac:哭笑] [s:a2:doge] [s:1][/code]"),
                "<pre class=\"code\"><code>[s:ac:goodjob] [s:ac:哭笑] [s:a2:doge] [s:1]</code></pre>");
        absent(body("[img]" + base + "ac1.png[/img]"), "<img ");
        for (String source : new String[]{base + "unknown.png", base + "ac1.png?x=1", base + "ac1.png#x", base + "../smile/ac1.png",
                "http://img4.nga.cn/ngabbs/post/smile/ac1.png", "https://img4.nga.cn.evil.test/ngabbs/post/smile/ac1.png",
                "https://user@img4.nga.cn/ngabbs/post/smile/ac1.png", "https://img4.nga.cn:444/ngabbs/post/smile/ac1.png",
                "https://img.nga.cn/attachments/ac1.png", null}) {
            if (PostRenderer.isEmoticonImage(source)) throw new AssertionError("Unsafe source recognized as native emoticon");
            checks++;
        }
    }

    private static String body(String value) {
        String html = PostRenderer.html(value, false, 16);
        return html.substring(html.indexOf("<body>") + 6, html.lastIndexOf("</body>"));
    }

    private static void contains(String text, String expected) {
        checks++;
        if (!text.contains(expected)) throw new AssertionError("Missing: " + expected + "\nActual: " + text);
    }

    private static void absent(String text, String forbidden) {
        checks++;
        if (text.contains(forbidden)) throw new AssertionError("Unexpected: " + forbidden + "\nActual: " + text);
    }

    private static void equal(String actual, String expected) {
        checks++;
        if (!actual.equals(expected)) throw new AssertionError("Expected: " + expected + "\nActual: " + actual);
    }

    private static void equal(int actual, int expected) {
        checks++;
        if (actual != expected) throw new AssertionError("Expected: " + expected + ", actual: " + actual);
    }

    private static int count(String text, String needle) {
        int total = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            total++;
            from += needle.length();
        }
        return total;
    }
}
