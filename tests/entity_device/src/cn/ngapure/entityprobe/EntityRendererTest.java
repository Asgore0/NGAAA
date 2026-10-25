package cn.ngapure.entityprobe;

import cn.ngapure.app.PostRenderer;

public final class EntityRendererTest {
    private EntityRendererTest(){ }
    public static void main(String[] args) {
        int checks=0;
        for(EntityCases.Case value:EntityCases.all()) {
            String actual=body(value.source);
            if(!actual.equals(value.html))throw new AssertionError(value.name+": renderer output differs from explicit fixture");checks++;
        }
        String after=body("[code]&#128517;[/code]&#128517;");
        if(!after.equals("<pre class=\"code\"><code>&amp;#128517;</code></pre>😅"))throw new AssertionError("normalization resumes after literal code");checks++;
        String quoted=body("[quote]&#128517;[/quote][collapse=原有标题]&#x1F469;&#x200D;&#x1F4BB;[/collapse]");
        if(!quoted.contains("<blockquote>😅</blockquote>")||!quoted.contains("👩‍💻</div></details>"))throw new AssertionError("nested ordinary text still decodes");checks++;
        StringBuilder bracketRun=new StringBuilder();for(int i=0;i<20000;i++)bracketRun.append('[');bracketRun.append("&#128517;]");
        long started=System.nanoTime();String result=body(bracketRun.toString());
        if(!result.endsWith("&amp;#128517;]"))throw new AssertionError("malformed bracket token remains literal");checks++;
        bracketRun.setLength(bracketRun.length()-1);
        result=body(bracketRun.toString());
        if(!result.endsWith("😅")||result.contains("&amp;#128517;"))throw new AssertionError("unmatched brackets cannot suppress ordinary emoji text");checks++;
        System.out.println("PASS entity renderer: "+checks+" checks; malformed bracket observation nanos="+(System.nanoTime()-started));
    }
    private static String body(String source){String html=PostRenderer.html(source,false,16);return html.substring(html.indexOf("<body>")+6,html.lastIndexOf("</body>"));}
}
