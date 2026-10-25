package cn.ngapure.entityprobe;

import java.util.ArrayList;
import java.util.List;

/** Synthetic text shared by the JVM and real Android TextView probes. */
public final class EntityCases {
    private EntityCases(){ }
    public static final class Case {
        public final String name,source,text,baselineText,html;
        public final boolean code;
        Case(String name,String source,String text,String baselineText,String html,boolean code) {
            this.name=name;this.source=source;this.text=text;this.baselineText=baselineText;this.html=html;this.code=code;
        }
    }
    public static List<Case> all() {
        List<Case> cases=new ArrayList<>();
        plain(cases,"reported-decimal-emoji","前&#128517;后","前😅后","前&#128517;后");
        plain(cases,"hex-supplementary-emoji","&#x1F605; &#X1f605;","😅 😅","&#x1F605; &#X1f605;");
        plain(cases,"variation-selector","&#10084;&#65039;","❤️","&#10084;&#65039;");
        plain(cases,"zwj-and-skin-tone","&#x1F469;&#127997;&#8205;&#x1F4BB;","👩🏽‍💻","&#x1F469;&#127997;&#8205;&#x1F4BB;");
        plain(cases,"mixed-existing-unicode","现有😅 &#x4E2D; &#160;尾","现有😅 中 \u00a0尾","现有😅 &#x4E2D; &#160;尾");
        plain(cases,"bounded-amp-envelope","&amp;#128517; &amp;amp;#x1F605;","😅 &amp;#x1F605;","&#128517; &amp;#x1F605;");
        plain(cases,"public-floor16-exact","我家别人送了两只牡丹，太能叫了，声音尖锐。而且很凶，&amp;#128517;，手指伸过去就咬。",
                "我家别人送了两只牡丹，太能叫了，声音尖锐。而且很凶，😅，手指伸过去就咬。",
                "我家别人送了两只牡丹，太能叫了，声音尖锐。而且很凶，&#128517;，手指伸过去就咬。");
        plain(cases,"amp-hex-emoji","&amp;#x1F605; &amp;#X1f605;","😅 😅","&#x1F605; &#X1f605;");
        plain(cases,"amp-variation-selector","&amp;#10084;&amp;#65039;","❤️","&#10084;&#65039;");
        plain(cases,"amp-zwj-and-skin-tone","&amp;#x1F469;&amp;#127997;&amp;#8205;&amp;#x1F4BB;","👩🏽‍💻","&#x1F469;&#127997;&#8205;&#x1F4BB;");
        plain(cases,"deeper-envelopes-stop","&amp;amp;#128517; &amp;amp;amp;#128517;","&amp;#128517; &amp;amp;#128517;","&amp;#128517; &amp;amp;#128517;");
        plain(cases,"amp-ascii-brackets","&amp;#91;b&amp;#93;字面&amp;#91;/b&amp;#93;","&#91;b&#93;字面&#91;/b&#93;","&#91;b&#93;字面&#91;/b&#93;");
        plain(cases,"amp-ascii-html","&amp;#60;img src=x onerror=evil&amp;#62;","&#60;img src=x onerror=evil&#62;","&#60;img src=x onerror=evil&#62;");
        plain(cases,"amp-token-space-cannot-activate","[code&amp;#8192;java]&amp;#128517;[/code]","[code&#8192;java]😅[/code]","[code&#8192;java]&#128517;[/code]");
        plain(cases,"amp-after-unmatched-bracket","正文 [未配对 &amp;#128517;","正文 [未配对 😅","正文 [未配对 &#128517;");
        plain(cases,"encoded-bbcode-brackets","&#91;b&#93;字面&#91;/b&#93;","&#91;b&#93;字面&#91;/b&#93;","&#91;b&#93;字面&#91;/b&#93;");
        plain(cases,"encoded-image-token","&#x5B;img&#x5D;https://img4.nga.cn/attachments/a.png&#x5B;/img&#x5D;",
                "&#x5B;img&#x5D;https://img4.nga.cn/attachments/a.png&#x5B;/img&#x5D;","&#x5B;img&#x5D;https://img4.nga.cn/attachments/a.png&#x5B;/img&#x5D;");
        plain(cases,"encoded-html-numeric","&#60;img src=x onerror=evil&#62;","&#60;img src=x onerror=evil&#62;","&#60;img src=x onerror=evil&#62;");
        plain(cases,"encoded-html-named","&lt;a href=&quot;javascript:evil&quot;&gt;字面&lt;/a&gt;","<a href=\"javascript:evil\">字面</a>","<a href=\"javascript:evil\">字面</a>");
        plain(cases,"raw-html-is-text","<img src=x onerror=evil>","<img src=x onerror=evil>","<img src=x onerror=evil>");
        plain(cases,"token-space-cannot-activate","[code&#8192;java]&#128517;[/code]","[code&#8192;java]😅[/code]","[code&#8192;java]&#128517;[/code]");
        plain(cases,"token-title-remains-literal","[unknown=&#128517;]正文","[unknown=&#128517;]正文","[unknown=&#128517;]正文");
        plain(cases,"ordinary-unmatched-bracket","正文 [尚未闭合 &#128517;","正文 [尚未闭合 😅","正文 [尚未闭合 &#128517;");
        plain(cases,"repeated-unmatched-brackets","[[[[ &#x1F469;&#8205;&#x1F4BB;","[[[[ 👩‍💻","[[[[ &#x1F469;&#8205;&#x1F4BB;");
        for(String source:new String[]{"&#0;","&#9;","&#31;","&#127;","&#128;","&#159;","&#xD800;","&#xDFFF;","&#1114112;","&#x110000;",
                "&#9999999;","&#xFFFFFF;","&#-128517;","&#+128517;","&#x-1;","&#;","&#x;","&#128517","&#xNO;","&#１２８５１７;","&#128 517;","&#x1F60Z;","&#0128517X;","&#000128517;","&#x0001F605;"})
            plain(cases,"malformed-"+cases.size(),source,source,source);
        for(String entity:new String[]{"#0;","#159;","#xD800;","#1114112;","#x110000;","#xFFFFFF;","#xNO;","#128517","#000128517;","#x0001F605;","#-128517;","#+128517;","#;","#x;"})
            plain(cases,"amp-malformed-"+cases.size(),"&amp;"+entity,"&"+entity,"&"+entity);
        StringBuilder longEntity=new StringBuilder("&#");for(int i=0;i<2048;i++)longEntity.append('0');longEntity.append("128517;");
        plain(cases,"overlong-entity",longEntity.toString(),longEntity.toString(),longEntity.toString());
        for(String link:new String[]{"javascript&#58;alert(1)","java&#115;cript:alert(1)","jav&#x61;script:alert(1)","&#106;avascript:alert(1)","javascript&#x200D;:alert(1)","data&#58;text/html,test",
                "javascript&amp;#58;alert(1)","java&amp;#115;cript:alert(1)","javascript&amp;#x200D;:alert(1)"})
            cases.add(new Case("unsafe-link-"+cases.size(),"[url="+link+"]禁止链接[/url]","禁止链接","禁止链接","禁止链接",false));
        code(cases,"code-literal","[code]&#128517; &#x1F605; [b]原文[/b] &lt;x&gt; &amp;#128517;[/code]",
                "&#128517; &#x1F605; [b]原文[/b] <x> &#128517;");
        code(cases,"language-code-literal","[CODE=java]&#128517; &#x200D; &#65039;[/CODE]","&#128517; &#x200D; &#65039;");
        code(cases,"amp-code-literal","[code]&amp;#128517; &amp;#x1F469;&amp;#8205;&amp;#x1F4BB;[/code]","&#128517; &#x1F469;&#8205;&#x1F4BB;");
        plain(cases,"unclosed-code-literal","[code]&#128517;","[code]&#128517;","[code]&#128517;");
        plain(cases,"amp-unclosed-code-literal","[code]&amp;#128517;","[code]&#128517;","[code]&#128517;");
        return cases;
    }
    private static void plain(List<Case> cases,String name,String source,String text,String baseline){cases.add(new Case(name,source,text,baseline,escaped(text),false));}
    private static void code(List<Case> cases,String name,String source,String text){cases.add(new Case(name,source,text,text,"<pre class=\"code\"><code>"+escaped(text)+"</code></pre>",true));}
    public static String escaped(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
