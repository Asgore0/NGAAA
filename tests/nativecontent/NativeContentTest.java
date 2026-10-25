package cn.ngapure.app;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** Exercises production pure Java blocks/sizing. No Android views or decoders are instantiated. */
public final class NativeContentTest {
    private static int assertions, scenarios;
    private static Path fixtures;
    private NativeContentTest() { }
    public static void main(String[] args) throws Exception {
        fixtures = Paths.get(args[0]);
        codeWhitespace(); nestedCollapse(); codeInCollapse(); quotes(); nativeAlignment();
        entities(); malformed(); imageBounds(); sources(); officialInlineBlocks(); cachedBlockScans();
        System.out.println("PASS native content: " + scenarios + " scenarios, " + assertions + " checks");
    }
    private static void codeWhitespace() throws Exception {
        scenarios++;
        List<NativeContentParser.Block> blocks = NativeContentParser.parse(render(read("code")));
        check(blocks.size() == 3, "code splits ordinary siblings");
        check(blocks.get(0).kind == NativeContentParser.TEXT && blocks.get(2).kind == NativeContentParser.TEXT, "ordinary content remains native spans");
        NativeContentParser.Block code = blocks.get(1);
        check(code.kind == NativeContentParser.CODE, "code rendered separately");
        check(code.body.equals("if (x < 2) {\n    print(\"& [collapse]literal[/collapse]\");\n\treturn 1;\n}\n"), "spaces tabs newline and literals preserved after NGA entity normalization");
        check(!code.body.contains("<pre") && !code.body.contains("<code>"), "code wrapper removed");
    }
    private static void nestedCollapse() throws Exception {
        scenarios++;
        List<NativeContentParser.Block> blocks = NativeContentParser.parse(render(read("nested")));
        check(blocks.size() == 5, "nested collapse does not consume following group");
        NativeContentParser.Block outer = blocks.get(1), sibling = blocks.get(3);
        check(outer.kind == NativeContentParser.COLLAPSE && outer.title.equals("外层 & 标题"), "summary decoded once");
        check(sibling.kind == NativeContentParser.COLLAPSE && sibling.title.equals("第二组"), "sibling collapse remains independent");
        List<NativeContentParser.Block> inner = NativeContentParser.parse(outer.body);
        check(inner.size() == 3 && inner.get(1).kind == NativeContentParser.COLLAPSE, "nested group remains lazy body");
        check(inner.get(1).title.equals("内层") && inner.get(1).body.equals("inner content"), "nested group exact matching closing tag");
        check(inner.get(0).body.contains("outer-before") && inner.get(2).body.contains("outer-after"), "content on both sides of inner group retained");
        check(!outer.body.startsWith("<div class=\"collapse-content\">") && !sibling.body.endsWith("</div>"), "only outer content wrappers stripped");
        check(blocks.get(4).body.contains("bottom"), "following body retained");
    }
    private static void codeInCollapse() throws Exception {
        scenarios++;
        List<NativeContentParser.Block> blocks = NativeContentParser.parse(render(read("code-in-collapse")));
        check(blocks.size() == 2 && blocks.get(0).kind == NativeContentParser.COLLAPSE, "collapse owns complete code body");
        List<NativeContentParser.Block> inside = NativeContentParser.parse(blocks.get(0).body);
        check(inside.size() == 3 && inside.get(1).kind == NativeContentParser.CODE, "code parsed only when body requested");
        check(inside.get(1).body.equals("<details>\n  & [b]literal[/b]\n</details>"), "literal details do not alter collapse nesting");
        check(inside.get(2).body.contains("结尾"), "content after code retained");
    }
    private static void quotes() {
        scenarios++;
        List<NativeContentParser.Block> siblings=NativeContentParser.parse(render("开头[quote]引用前[quote]内层[/quote]引用后[/quote]"
                +"[quote][code]  <blockquote> & [b]字面[/b]\n\tend[/code]代码后"
                +"[collapse=详情][b]折叠原文[/b][/collapse][/quote]结尾"));
        check(siblings.size()==4,"quote siblings keep surrounding text");
        check(siblings.get(0).body.equals("开头")&&siblings.get(3).body.equals("结尾"),"ordinary siblings exact");
        check(siblings.get(1).kind==NativeContentParser.QUOTE&&siblings.get(2).kind==NativeContentParser.QUOTE,"quotes use separate native surfaces");
        List<NativeContentParser.Block> nested=NativeContentParser.parse(siblings.get(1).body);
        check(nested.size()==3&&nested.get(1).kind==NativeContentParser.QUOTE,"nested quote belongs to its parent");
        check(nested.get(0).body.equals("引用前")&&nested.get(1).body.equals("内层")&&nested.get(2).body.equals("引用后"),"no nested quote text lost");
        List<NativeContentParser.Block> mixed=NativeContentParser.parse(siblings.get(2).body);
        check(mixed.size()==3&&mixed.get(0).kind==NativeContentParser.CODE,"quote retains complete code block");
        check(mixed.get(0).body.equals("  <blockquote> & [b]字面[/b]\n\tend"),"code entities and literal quote preserved once");
        check(mixed.get(1).body.equals("代码后")&&mixed.get(2).kind==NativeContentParser.COLLAPSE,"quote retains lazy details after code");
        check(mixed.get(2).title.equals("详情")&&mixed.get(2).body.equals("<strong>折叠原文</strong>"),"quoted collapsed formatting unchanged");
        List<NativeContentParser.Block> collapsed=NativeContentParser.parse(render("[collapse=收起][quote]内部引用[/quote]尾[/collapse]"));
        check(collapsed.size()==1&&collapsed.get(0).kind==NativeContentParser.COLLAPSE,"outer collapse stays lazy with quotes");
        List<NativeContentParser.Block> opened=NativeContentParser.parse(collapsed.get(0).body);
        check(opened.size()==2&&opened.get(0).kind==NativeContentParser.QUOTE&&opened.get(1).body.equals("尾"),"lazy quote parsed only when requested");
        String deep="原文";
        for(int i=0;i<32;i++)deep="<blockquote>"+deep+"</blockquote>";
        for(int i=0;i<32;i++) {
            List<NativeContentParser.Block> level=NativeContentParser.parse(deep);
            check(level.size()==1&&level.get(0).kind==NativeContentParser.QUOTE,"deep quote balances exact closing tag");
            deep=level.get(0).body;
        }
        check(deep.equals("原文"),"deep quote body preserved");
        List<NativeContentParser.Block> styled=NativeContentParser.parse(render("[b]前[quote]引用[/quote]后[/b]"));
        check(styled.size()==3,"styled quote splits independent native blocks");
        check(styled.get(0).body.equals("<strong>前</strong>")&&styled.get(1).body.equals("<strong>引用</strong>")
                &&styled.get(2).body.equals("<strong>后</strong>"),"outer inline formatting retained before inside and after quote");
        List<NativeContentParser.Block> aligned=NativeContentParser.parse(render("[align=center][url=https://bbs.nga.cn/]前[quote]引用[/quote]后[/url][/align]"));
        String start="<div class=\"align-center\"><a href=\"https://bbs.nga.cn/\" rel=\"noreferrer noopener\">";
        check(aligned.size()==3&&aligned.get(1).body.equals(start+"引用</a></div>"),"safe outer link and alignment retained in native quote");
        check(aligned.get(2).body.equals(start+"后</a></div>"),"safe outer link and alignment retained after native quote");
        List<NativeContentParser.Block> styledCollapse=NativeContentParser.parse(render("[i]前[collapse=组]正文[/collapse]后[/i]"));
        check(styledCollapse.size()==3&&styledCollapse.get(1).body.equals("<em>正文</em>")
                &&styledCollapse.get(2).body.equals("<em>后</em>"),"native lazy block retains outer inline formatting");
        String unclosed="<blockquote>未闭合";
        List<NativeContentParser.Block> malformed=NativeContentParser.parse(unclosed);
        check(malformed.size()==1&&malformed.get(0).kind==NativeContentParser.TEXT&&malformed.get(0).body.equals(unclosed),"unclosed quote degrades to readable text");
    }
    private static void cachedBlockScans() {
        scenarios++;
        String code="<pre class=\"code\"><code>code</code></pre>";
        String details="<details class=\"collapse\"><summary>title</summary><div class=\"collapse-content\">body</div></details>";
        String media="<figure class=\"nga-media\" data-kind=\"audio\"><a href=\"https://example.invalid/a.mp3\">audio</a></figure>";
        String crypt="<section class=\"nga-crypt\">%F3</section>";
        String table="<table class=\"nga-table\"><tr><td>cell</td></tr></table>";
        String[] fragments={"first","<blockquote>quote</blockquote>","between",details,code,media,crypt,table,"last"};
        int[] kinds={NativeContentParser.TEXT,NativeContentParser.QUOTE,NativeContentParser.TEXT,NativeContentParser.COLLAPSE,
                NativeContentParser.CODE,NativeContentParser.MEDIA,NativeContentParser.CRYPT,NativeContentParser.TABLE,NativeContentParser.TEXT};
        StringBuilder source=new StringBuilder();for(String fragment:fragments)source.append(fragment);
        List<NativeContentParser.Block> blocks=NativeContentParser.parse(source.toString());
        check(blocks.size()==kinds.length,"mixed opener scan exact block count");
        for(int i=0;i<kinds.length;i++)check(blocks.get(i).kind==kinds[i],"mixed opener ordering preserved "+i);
        check(blocks.get(0).body.equals("first")&&blocks.get(8).body.equals("last"),"text at both edges retained");
        check(blocks.get(3).title.equals("title")&&blocks.get(3).body.equals("body"),"collapse cached position exact");
        check(blocks.get(4).body.equals("code")&&blocks.get(5).body.equals("https://example.invalid/a.mp3"),"code/media cached positions exact");
        check(blocks.get(6).body.equals("%F3")&&blocks.get(7).body.equals("<tr><td>cell</td></tr>"),"crypt/table cached positions exact");
        List<NativeContentParser.Block> nested=NativeContentParser.parse("<blockquote>inside"+code+details+"</blockquote>after"+code+details);
        check(nested.size()==4&&nested.get(0).kind==NativeContentParser.QUOTE&&nested.get(1).body.equals("after"),"cached nested openers skipped after outer quote");
        check(nested.get(0).body.equals("inside"+code+details)&&nested.get(2).body.equals("code")&&nested.get(3).body.equals("body"),"later identical opener remains independent");
        List<NativeContentParser.Block> edges=NativeContentParser.parse("<blockquote>zero</blockquote><blockquote>end</blockquote>");
        check(edges.size()==2&&edges.get(0).body.equals("zero")&&edges.get(1).body.equals("end"),"opening at position zero and final block retained");
        check(NativeContentParser.parse("").isEmpty(),"empty scan remains empty");
        String malformed="tail<blockquote>unclosed";List<NativeContentParser.Block> bad=NativeContentParser.parse(malformed);
        check(bad.size()==2&&bad.get(0).body.equals("tail")&&bad.get(1).body.equals("<blockquote>unclosed"),"unclosed terminal opener keeps literal tail");
        StringBuilder quotes=new StringBuilder();for(int i=0;i<250;i++)quotes.append("<blockquote>q").append(i).append("</blockquote>");
        List<NativeContentParser.Block> many=NativeContentParser.parse(quotes.toString());
        check(many.size()==250,"many sibling quotes no missing or extra blocks");
        for(int i=0;i<many.size();i++)check(many.get(i).kind==NativeContentParser.QUOTE&&many.get(i).body.equals("q"+i),"many sibling quote exact body "+i);
    }
    private static void nativeAlignment() {
        scenarios++;
        String actual=NativeContentParser.nativeMarkup(render("[align=left]左[/align][align=center]中[/align][align=right]右[/align]"));
        check(actual.equals("<div style=\"text-align:left;\">左</div><div style=\"text-align:center;\">中</div><div style=\"text-align:right;\">右</div>"),"native Android resolves fixed alignment styles");
        String escaped=render("<div class=\"align-center\">字面</div>");
        check(NativeContentParser.nativeMarkup(escaped).equals(escaped),"user HTML never promoted to markup");
        String code="<pre class=\"code\"><code>&lt;div class=\"align-center\"&gt;literal&lt;/div&gt;</code></pre>";
        check(NativeContentParser.nativeMarkup(code).equals(code),"escaped literal code not rewritten");
    }
    private static void officialInlineBlocks() {
        scenarios++;
        List<NativeContentParser.Block> code=NativeContentParser.parse(render("前[code=js][jumpurl]literal[/jumpurl]\n  [list][*]code[/list][/code]后"));
        check(code.size()==3&&code.get(1).kind==NativeContentParser.CODE,"language code has native code block instead of leaked label");
        check(code.get(1).body.equals("[jumpurl]literal[/jumpurl]\n  [list][*]code[/list]"),"new tags remain exact literal code with whitespace");
        for(String name:new String[]{"sup","sub","font=Courier New"}) {
            String close=name.startsWith("font")?"font":name;
            List<NativeContentParser.Block> blocks=NativeContentParser.parse(render("["+name+"]前[quote]引用[/quote]后[/"+close+"]"));
            String open=name.startsWith("font")?"<font face=\"Courier New\">":"<"+name+">";
            check(blocks.size()==3&&blocks.get(1).kind==NativeContentParser.QUOTE,"new style splits around quote");
            check(blocks.get(0).body.equals(open+"前</"+close+">")&&blocks.get(1).body.equals(open+"引用</"+close+">")
                    &&blocks.get(2).body.equals(open+"后</"+close+">"),"new style retained on every native text fragment");
        }
        String url="https://bbs.nga.cn/misc/event/20261015zmd/index.html";
        List<NativeContentParser.Block> link=NativeContentParser.parse(render("[jumpurl="+url+"][b]前[collapse=更多]正文[/collapse]后[/b][/jumpurl]"));
        check(link.size()==3&&link.get(1).kind==NativeContentParser.COLLAPSE,"activity link remains complete around lazy block");
        String prefix="<a href=\""+url+"\" rel=\"noreferrer noopener\"><strong>";
        check(link.get(1).body.equals(prefix+"正文</strong></a>")&&link.get(2).body.equals(prefix+"后</strong></a>"),"activity link and nested bold retained in collapse and sibling");
        String table=NativeContentParser.nativeMarkup(render("[table][tr][td][b]A[/b][/td][td]B[/td][/tr][tr][td]C[/td][td]D[/td][/tr][/table]"));
        check(table.contains("<strong>A</strong>")&&table.contains("<td>")&&table.contains("</tr>"),"table preserves rows and cells for native grid");
        List<NativeContentParser.Block> tableBlocks=NativeContentParser.parse(table);
        check(tableBlocks.size()==1&&tableBlocks.get(0).kind==NativeContentParser.TABLE&&!table.contains("[td")&&!table.contains("[tr"),"table bypasses unsupported Android HTML layout with separate native grid");
        List<NativeContentParser.Block> album=NativeContentParser.parse(render("[album=图][img0]./mon_202610/18/a.png[/img][/album]"));
        check(album.size()==1&&album.get(0).body.contains("<img src=\"https://img.nga.cn/attachments/mon_202610/18/a.png\""),"album thumbnail retains native InlineImage pipeline");
    }
    private static void entities() {
        scenarios++;
        check(NativeContentParser.decodeEntities("&amp;lt; &lt;b&gt; &quot;x&quot; &apos; &nbsp;").equals("&lt; <b> \"x\" ' \u00a0"), "entities decoded once");
        check(NativeContentParser.decodeEntities("&#65; &#x4E2D; &#128512;").equals("A 中 😀"), "decimal hex and supplementary code points");
        for (String invalid : new String[]{"&unknown;", "&#0;", "&#xD800;", "&#1114112;", "&#xNO;", "&amp", "& lt;", "&"})
            check(NativeContentParser.decodeEntities(invalid).equals(invalid), "invalid entity retained literally");
        List<NativeContentParser.Block> emoji=NativeContentParser.parse(render("&#128517; &#x1F469;&#8205;&#x1F4BB; &#10084;&#65039;"));
        check(emoji.size()==1&&emoji.get(0).body.equals("😅 👩‍💻 ❤️"),"server emoji entities reach native text as Unicode sequences");
        List<NativeContentParser.Block> literal=NativeContentParser.parse(render("[code]&#128517; &#x1F605;[/code]&#128517;"));
        check(literal.size()==2&&literal.get(0).kind==NativeContentParser.CODE&&literal.get(0).body.equals("&#128517; &#x1F605;"),"code numeric entities remain literal through native block decoding");
        check(literal.get(1).kind==NativeContentParser.TEXT&&literal.get(1).body.equals("😅"),"ordinary text resumes entity conversion after code");
        List<NativeContentParser.Block> nested=NativeContentParser.parse(render("[collapse=&#128517;]&#128517;[/collapse]"));
        check(nested.size()==1&&nested.get(0).title.equals("&#128517;")&&nested.get(0).body.equals("😅"),"token arguments retain literal numeric entities while collapsed body renders emoji");
    }
    private static void malformed() {
        scenarios++;
        check(NativeContentParser.parse(null).isEmpty() && NativeContentParser.parse("").isEmpty(), "empty input bounded");
        for (String text : new String[]{"<details", "<details><summary>title</summary>body", "<details>body</details>", "<pre class=\"code\"><code>unclosed", "text </details> suffix"}) {
            List<NativeContentParser.Block> blocks = NativeContentParser.parse(text);
            check(!blocks.isEmpty() && blocks.get(0).kind == NativeContentParser.TEXT, "malformed markup degrades to readable ordinary text");
            check(blocks.get(0).body.equals(text), "malformed body not lost");
        }
        String deepest = "value";
        for (int i = 0; i < 32; i++) deepest = "<details><summary>level</summary>" + deepest + "</details>";
        for (int i = 0; i < 32; i++) {
            List<NativeContentParser.Block> blocks = NativeContentParser.parse(deepest);
            check(blocks.size() == 1 && blocks.get(0).kind == NativeContentParser.COLLAPSE, "deep nesting remains bounded and balanced");
            deepest = blocks.get(0).body;
        }
        check(deepest.equals("value"), "deep inner body retained");
    }
    private static void imageBounds() {
        scenarios++;
        int[][] sizes = {{1,1},{32,32},{800,1000},{801,1000},{4000,3000},{10000,10000},{100000,1},{1,100000},{Integer.MAX_VALUE,Integer.MAX_VALUE},{Integer.MAX_VALUE,1}};
        for (int[] size : sizes) for (boolean full : new boolean[]{false,true}) {
            int sample = NativeImageSizing.sample(size[0], size[1], full);
            check(sample > 0 && (sample & (sample - 1)) == 0, "decoder sample is positive power of two");
            int width = (int) (((long) size[0] + sample - 1) / sample), height = (int) (((long) size[1] + sample - 1) / sample);
            check(NativeImageSizing.withinBudget(width, height, full), "sampled pixels and edge bounded without overflow");
            if (sample > 1) {
                int smaller = sample / 2;
                int oldWidth = (int) (((long) size[0] + smaller - 1) / smaller), oldHeight = (int) (((long) size[1] + smaller - 1) / smaller);
                check(!NativeImageSizing.withinBudget(oldWidth, oldHeight, full), "highest safe resolution retained");
            }
        }
        for (int[] invalid : new int[][]{{0,1},{1,0},{-1,5},{5,-1}}) {
            try { NativeImageSizing.sample(invalid[0], invalid[1], false); throw new AssertionError("invalid dimensions accepted"); }
            catch (IllegalArgumentException expected) { assertions++; }
        }
        check(!NativeImageSizing.withinBudget(Integer.MAX_VALUE, Integer.MAX_VALUE, true), "decoded overflow dimensions rejected");
    }
    private static void sources() {
        scenarios++;
        for (String valid : new String[]{"https://img.nga.cn/attachments/test.png", "https://img4.nga.178.com/a.gif", "https://img.nga.178.com:443/a.webp", "HTTPS://img8.nga.cn/a.jpg", "https://img.ngacn.cc/a.png"})
            check(NativeImages.allowed(valid), "official HTTPS media origin accepted");
        for (String invalid : new String[]{"http://img.nga.cn/a.png", "https://img.nga.cn.attacker.invalid/a.png", "https://attacker.invalid/?img.nga.cn", "https://user@img.nga.cn/a.png", "https://img.nga.cn:444/a.png", "file:///tmp/a.png", "javascript:alert(1)", "//img.nga.cn/a.png", "", null})
            check(!NativeImages.allowed(invalid), "unsafe media origin rejected");
    }
    private static String read(String name) throws Exception { return new String(Files.readAllBytes(fixtures.resolve(name + ".bbcode")), StandardCharsets.UTF_8); }
    private static String render(String bbcode) { String html = PostRenderer.html(bbcode, false, 16); return html.substring(html.indexOf("<body>") + 6, html.lastIndexOf("</body>")); }
    private static void check(boolean condition, String label) { assertions++; if (!condition) throw new AssertionError(label); }
}
