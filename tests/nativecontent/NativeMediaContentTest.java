package cn.ngapure.app;

import java.util.List;

/** JVM regression of production media routing, grid spans, legacy crypto and official deterministic dice. */
public final class NativeMediaContentTest {
    private static int checks;
    private NativeMediaContentTest() { }
    public static void main(String[] args) {
        policies(); blocks(); tables(); crypt(); dice(); folds(); flowWhitespace();
        System.out.println("PASS native media/content/adaptive: " + checks + " checks");
    }
    private static void policies() {
        for (String url : new String[]{"https://img.nga.cn/attachments/mon_202610/19/a.mp4", "https://media.example.com/a.MP3?token=public", "https://cdn.example.com/stream/live.m3u8", "https://example.com:8443/a.webm"}) { check(NativeMediaPolicy.source(url) != null, "valid source"); check(NativeMediaPolicy.direct(url), "direct stream"); }
        for (String url : new String[]{null, "", "https://user:secret@example.com/a.mp4", "https://example.com/a.mp4#x", "https://example.com\\@evil.test/a.mp4", "javascript:video()", "file:///tmp/video.mp4", "content://media/private", "//example.com/a.mp4", "https://example.com/a.mp4\n", " https://example.com/a.mp4", "https://example.com:0/a.mp4", "https://example.com:65536/a.mp4"}) check(NativeMediaPolicy.source(url) == null, "unsafe scheme/source rejected");
        check(!NativeMediaPolicy.direct("https://www.bilibili.com/video/BV1example"), "HTML embed never misrepresented as direct stream");
        check(NativeMediaPolicy.source("http://img.nga.cn/attachments/a.mp4").equals("https://img.nga.cn/attachments/a.mp4"), "official HTTP attachment upgraded to HTTPS");
        check(NativeMediaPolicy.audio("https://example.com/a.flac"), "FLAC audio"); check(!NativeMediaPolicy.audio("https://example.com/a.mp4"), "MP4 remains video");
        check(NativeMediaPolicy.originLabel("https://example.com/a.mp3?publicToken=a").equals("example.com"), "UI label never exposes token query");
    }
    private static void blocks() {
        List<NativeContentParser.Block> result = NativeContentParser.parse(body("前[media=audio]https://example.com/a.mp3?x=1&amp;y=2[/media][flash]https://example.com/b.mp4[/flash][attach]./mon_202610/19/c.mp4[/attach]后"));
        check(result.size() == 5, "media separates siblings");
        check(result.get(1).kind == NativeContentParser.MEDIA && result.get(1).title.equals("audio") && result.get(1).body.equals("https://example.com/a.mp3?x=1&y=2"), "audio native model decoded exactly once");
        check(result.get(2).kind == NativeContentParser.MEDIA && result.get(2).title.equals("video"), "direct video native model");
        check(result.get(3).body.equals("https://img.nga.cn/attachments/mon_202610/19/c.mp4"), "relative attachment playable natively");
        check(result.get(0).body.equals("前") && result.get(4).body.equals("后"), "surrounding text preserved");
        List<NativeContentParser.Block> collapse = NativeContentParser.parse(body("[collapse=录音][media=audio]https://example.com/a.mp3[/media][/collapse]"));
        check(collapse.size() == 1 && collapse.get(0).kind == NativeContentParser.COLLAPSE, "collapsed media lazy");
        check(NativeContentParser.parse(collapse.get(0).body).get(0).kind == NativeContentParser.MEDIA, "lazy expansion gains native controls");
        List<NativeContentParser.Block> literal = NativeContentParser.parse(body("<figure class=\"nga-media\">not markup</figure>[code][media]https://example.com/a.mp4[/media][/code]"));
        check(literal.size() == 2 && literal.get(0).kind == NativeContentParser.TEXT && literal.get(1).kind == NativeContentParser.CODE, "literal user HTML and code never become playback");
        for (String bad : new String[]{"<figure class=\"nga-media\"", "<figure class=\"nga-media\">orphan", "<table class=\"nga-table\">unclosed", "<section class=\"nga-crypt\">unclosed"}) { List<NativeContentParser.Block> values = NativeContentParser.parse(bad); check(values.size() == 1 && values.get(0).kind == NativeContentParser.TEXT && values.get(0).body.equals(bad), "malformed structural model preserves text"); }
    }
    private static void tables() {
        String raw = "前[table][tr th][td rowspan2][b]A[/b][/td][td colspan2]B[/td][/tr][tr][td]C[/td][td]D[table][tr][td]inner[/td][/tr][/table][/td][/tr][/table]后";
        List<NativeContentParser.Block> blocks = NativeContentParser.parse(body(raw));
        check(blocks.size() == 3 && blocks.get(1).kind == NativeContentParser.TABLE, "nested tables retain outer block boundaries");
        NativeTableModel grid = NativeTableModel.parse(blocks.get(1).body);
        check(grid != null && grid.cells.size() == 4 && grid.rows == 2 && grid.columns == 3, "span-aware grid size");
        check(grid.cells.get(0).rowSpan == 2 && grid.cells.get(0).header && grid.cells.get(0).html.equals("<strong>A</strong>"), "rowspan header formatting");
        check(grid.cells.get(1).columnSpan == 2 && grid.cells.get(1).column == 1, "colspan represented");
        check(grid.cells.get(2).row == 1 && grid.cells.get(2).column == 1, "rowspan reserves subsequent row column");
        List<NativeContentParser.Block> inner = NativeContentParser.parse(grid.cells.get(3).html);
        check(inner.size() == 2 && inner.get(1).kind == NativeContentParser.TABLE, "nested table stays separately renderable");
        check(NativeTableModel.parse("<tr><td rowspan=\"32\">a</td></tr>").rows == 32, "rowspan extends grid bounds");
        check(NativeTableModel.parse("<tr><td colspan=\"32\">a</td><td>b</td></tr>") == null, "overwide grid selects readable fallback");
        check(NativeTableModel.parse("<tr><td>orphan") == null, "malformed grid fallback");
        NativeContentParser.Block styled=NativeContentParser.parse(body("[b]before[table][tr][td]cell[/td][/tr][/table]after[/b]")).get(1);
        check(styled.prefix.equals("<strong>")&&styled.suffix.equals("</strong>"),"outer inline format retained separately for every native cell");
        check(body("[table][tr][td colspan2 onclick=evil]safe[/td][/tr][/table]").contains("<td colspan=\"2\">safe"), "only numeric span attributes survive");
        check(!body("[table][tr][td colspan999]safe[/td][/tr][/table]").contains("colspan="), "excessive span excluded");
    }
    private static void crypt() {
        // Independent published RC4 Known Answer Test: Key/Plaintext => BBF316E8D940AF0AD3.
        String cipher = "%BB%F3%16%E8%D9%40%AF%0A%D3";
        check(NativeEncryptedContent.decrypt("Key", cipher).equals("Plaintext"), "RC4 known answer vector");
        // UTF-16 envelope nibble mapping can represent any code unit without executing HTML.
        check(NativeEncryptedContent.decrypt("Key", "~~,," + "%F3%16%E8%D9%40%AF%0A%D3").equals("Plaintext"), "custom nibble envelope decoded before RC4");
        List<NativeContentParser.Block> block = NativeContentParser.parse(body("前[crypt]" + cipher + "[/crypt]后"));
        check(block.size() == 3 && block.get(1).kind == NativeContentParser.CRYPT && block.get(1).body.equals(cipher), "encrypted text has native password form model");
        check(PostRenderer.plainText("前[crypt]" + cipher + "[/crypt]后").equals("前加密内容后"), "cipher hidden in preview");
        for (String invalid : new String[]{"", null}) { try { NativeEncryptedContent.decrypt(invalid, cipher); throw new AssertionError("empty password accepted"); } catch (IllegalArgumentException expected) { checks++; } }
        try { NativeEncryptedContent.decrypt("p", "<script>x</script>"); throw new AssertionError("malformed envelope accepted"); } catch (IllegalArgumentException expected) { checks++; }
    }
    private static void dice() {
        check(PostRenderer.diceSeed("123", "40000000", "800000000", 1) == 840000124L, "modern official arithmetic");
        check(PostRenderer.diceSeed("123", "100", "200", 4) == 423L, "legacy ignores collapse offset");
        check(PostRenderer.diceSeed("anony_bad", "1", "2", 0) == 3L, "official anonymous numeric identity normalizes to zero");
        String first = seeded("[dice]2d6+1[/dice]", "123", "40000000", "800000000");
        check(first.contains("d6(2) + d6(2) + 1 = <strong>5</strong>"), "matches independent official JS PRNG fixture");
        check(first.equals(seeded("[dice]2d6+1[/dice]", "123", "40000000", "800000000")), "same post re-render stable");
        String collapsed = seeded("[dice]d6[/dice][collapse=one][dice]d6[/dice][/collapse][dice]d6[/dice]", "124", "40000000", "800000000");
        check(collapsed.indexOf("d6(2)") < collapsed.indexOf("d6(3)") && collapsed.lastIndexOf("d6(1)") > collapsed.indexOf("d6(3)"), "collapse uses independent offset and preserves outer generator sequence");
        check(body("[dice]11d6[/dice]").contains("超出限制"), "official roll count limit");
        check(body("[dice]d100001[/dice]").contains("超出限制"), "official face limit");
        check(body("[dice]<script>x</script>[/dice]").contains("&lt;script&gt;"), "dice malformed text escaped");
    }
    private static void folds() {
        equal(FoldGeometry.safePane(2200, 1800, 1080, 0, 1120, 1800, true), new int[]{0, 0, 1080, 1800}, "vertical book hinge avoids occlusion");
        equal(FoldGeometry.safePane(1800, 2200, 0, 1080, 1800, 1120, true), new int[]{0, 0, 1800, 1080}, "tabletop horizontal hinge");
        equal(FoldGeometry.safePane(2200, 1800, 800, 0, 840, 1800, true), new int[]{840, 0, 2200, 1800}, "larger pane selected");
        equal(FoldGeometry.safePane(2200, 1800, 1100, 0, 1100, 1800, true), new int[]{0, 0, 1100, 1800}, "zero-width half-open crease separates");
        equal(FoldGeometry.safePane(2200, 1800, 1100, 0, 1100, 1800, false), new int[]{0, 0, 2200, 1800}, "flat crease retains full screen");
        equal(FoldGeometry.safePane(320, 700, 0, 0, 0, 0, true), new int[]{0, 0, 320, 700}, "cover display window fallback");
        equal(FoldGeometry.safePane(0, 0, -1, -1, -1, -1, true), new int[]{0, 0, 0, 0}, "unmeasured window bounded");
        equal(FoldGeometry.sheet(1120,24,2200,1776,480,700,12),new int[]{1420,1064,480,700},"right hinge pane sheet positioned inside actual pane");
        equal(FoldGeometry.sheet(0,24,1080,900,480,700,12),new int[]{300,188,480,700},"tabletop upper pane sheet never crosses horizontal hinge");
        equal(FoldGeometry.sheet(100,80,420,360,480,900,12),new int[]{112,92,296,256},"split window with keyboard constrains all sheet edges");
        equal(FoldGeometry.sheet(0,0,10,8,480,900,12),new int[]{3,3,4,2},"very small available pane uses bounded margins");
        equal(FoldGeometry.paneInsets(2200,1800,0,0,2200,1200,0,96,0,63),new int[]{0,96,0,0},"upper pane never repeats navigation inset at interior hinge");
        equal(FoldGeometry.paneInsets(2200,1800,0,642,2200,1800,0,96,0,63),new int[]{0,0,0,63},"lower pane never repeats status inset at interior hinge");
        equal(FoldGeometry.paneInsets(2200,1800,775,0,2200,1800,40,96,0,63),new int[]{0,96,0,63},"right pane excludes global left cutout inset");
        equal(FoldGeometry.paneInsets(2200,1800,0,0,2200,1200,0,96,0,800),new int[]{0,96,0,200},"IME inset contributes only actual upper-pane overlap");
    }
    private static void flowWhitespace() {
        check(FlowTextWhitespace.compact("\n\n平台\n\n\n\n\n\nPC\n\n\n开发商\n\n\n\nStudio\n\n").equals("平台\n\nPC\n\n开发商\n\nStudio"),"rating-style six-line block margins compact to one blank paragraph");
        check(FlowTextWhitespace.compact("前\n \t\n\u00a0\n　\n后").equals("前\n\n后"),"whitespace-only blank paragraphs compact");
        check(FlowTextWhitespace.compact("\n图\ufffc\n\n\n后\n").equals("图\ufffc\n\n后"),"image replacement anchor retained");
        check(FlowTextWhitespace.compact("词  间\n单行\n\n一段").equals("词  间\n单行\n\n一段"),"ordinary spacing single line and one blank paragraph unchanged");
        check(FlowTextWhitespace.compact("　\t\n\n").isEmpty(),"empty leading and trailing content trimmed");
        String code="  if (x) {\n\n\n    return;\n}\n";List<NativeContentParser.Block> parsed=NativeContentParser.parse(body("[code]"+code+"[/code]"));
        check(parsed.size()==1&&parsed.get(0).kind==NativeContentParser.CODE&&parsed.get(0).body.equals(code),"code whitespace remains exact independent path");
        StringBuilder many=new StringBuilder("A");for(int i=0;i<100000;i++)many.append('\n');many.append('B');check(FlowTextWhitespace.compact(many.toString()).equals("A\n\nB"),"large blank runs processed without regex recursion");
    }
    private static String seeded(String value, String author, String tid, String pid) { return PostRenderer.html(value, false, 16, author, tid, pid); }
    private static String body(String value) { String html = PostRenderer.html(value, false, 16); return html.substring(html.indexOf("<body>") + 6, html.lastIndexOf("</body>")); }
    private static void equal(int[] a, int[] b, String message) { check(java.util.Arrays.equals(a, b), message); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
