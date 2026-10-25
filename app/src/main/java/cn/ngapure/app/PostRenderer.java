package cn.ngapure.app;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, dependency-free NGA BBCode renderer. It emits its own allowlisted HTML;
 * no HTML supplied by a forum post is copied into markup. This class deliberately
 * has no Android dependency so its security boundaries can be tested on the JVM.
 */
public final class PostRenderer {
    private static final int MAX_NESTING = 32;
    private static final Pattern RELATIVE_ATTACHMENT = Pattern.compile(
            "\\./mon_([0-9]{4})([0-9]{2})/([0-9]{2})/"
                    + "[A-Za-z0-9_-][A-Za-z0-9._-]*\\.(?i:jpe?g|png|gif|webp|bmp|avif)");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-f]{3}(?:[0-9a-f]{3})?"
            + "|black|white|gray|grey|silver|red|green|blue|yellow|aqua|cyan|fuchsia|magenta"
            + "|navy|maroon|olive|orange|skyblue|royalblue|darkblue|orangered|crimson"
            + "|firebrick|darkred|lime|limegreen|seagreen|teal|deeppink|tomato|coral"
            + "|purple|indigo|burlywood|sandybrown|sienna|chocolate|brown|gold|pink");
    private static final String CSP = "default-src 'none'; "
            + "img-src https://img.nga.178.com https://img4.nga.178.com https://img.nga.cn https://img4.nga.cn; "
            + "style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'";

    private static final String SMILE_BASE = "https://img4.nga.cn/ngabbs/post/smile/";
    private static final Map<String, String> SMILES = createSmiles();
    private static final Set<String> SMILE_FILES = Collections.unmodifiableSet(new HashSet<>(SMILES.values()));

    private PostRenderer() { }

    /** Identifies only fixed official emotion assets, so native layout never shrinks ordinary attachments. */
    public static boolean isEmoticonImage(String url) {
        return url != null && url.startsWith(SMILE_BASE) && SMILE_FILES.contains(url.substring(SMILE_BASE.length()));
    }

    private static String smileImage(String key) {
        if (key.indexOf(':') < 0) key = "0:" + key;
        else if (key.startsWith(":")) key = "0" + key;
        String file = SMILES.get(key);
        return file == null ? null : SMILE_BASE + file;
    }

    /** Name/file facts from the public official js_bbscode_core.js?1342409 table.
     * Official HTTPS page sets __IMGPATH to img4.nga.cn/ngabbs; no official assets are bundled.
     * Core SHA256: 90ef6ebbf7bbc688610ff132beaf1893d07bcdbdfd52fa801f1dea1f906d13de.
     */
    private static Map<String, String> createSmiles() {
        String[][] entries = {
            {"0:1", "smile.gif"},
            {"0:2", "mrgreen.gif"},
            {"0:3", "question.gif"},
            {"0:4", "wink.gif"},
            {"0:5", "redface.gif"},
            {"0:6", "sad.gif"},
            {"0:7", "cool.gif"},
            {"0:8", "crazy.gif"},
            {"0:32", "12.gif"},
            {"0:33", "13.gif"},
            {"0:34", "14.gif"},
            {"0:30", "10.gif"},
            {"0:29", "09.gif"},
            {"0:28", "08.gif"},
            {"0:27", "07.gif"},
            {"0:26", "06.gif"},
            {"0:25", "05.gif"},
            {"0:24", "04.gif"},
            {"0:35", "15.gif"},
            {"0:36", "16.gif"},
            {"0:37", "17.gif"},
            {"0:38", "18.gif"},
            {"0:39", "19.gif"},
            {"0:40", "20.gif"},
            {"0:41", "21.gif"},
            {"0:42", "22.gif"},
            {"0:43", "23.gif"},
            {"ac:blink", "ac0.png"},
            {"ac:goodjob", "ac1.png"},
            {"ac:上", "ac2.png"},
            {"ac:中枪", "ac3.png"},
            {"ac:偷笑", "ac4.png"},
            {"ac:冷", "ac5.png"},
            {"ac:凌乱", "ac6.png"},
            {"ac:吓", "ac8.png"},
            {"ac:吻", "ac9.png"},
            {"ac:呆", "ac10.png"},
            {"ac:咦", "ac11.png"},
            {"ac:哦", "ac12.png"},
            {"ac:哭", "ac13.png"},
            {"ac:哭1", "ac14.png"},
            {"ac:哭笑", "ac15.png"},
            {"ac:喘", "ac17.png"},
            {"ac:心", "ac23.png"},
            {"ac:囧", "ac21.png"},
            {"ac:晕", "ac33.png"},
            {"ac:汗", "ac34.png"},
            {"ac:瞎", "ac35.png"},
            {"ac:羞", "ac36.png"},
            {"ac:羡慕", "ac37.png"},
            {"ac:委屈", "ac22.png"},
            {"ac:忧伤", "ac24.png"},
            {"ac:怒", "ac25.png"},
            {"ac:怕", "ac26.png"},
            {"ac:惊", "ac27.png"},
            {"ac:愁", "ac28.png"},
            {"ac:抓狂", "ac29.png"},
            {"ac:哼", "ac16.png"},
            {"ac:喷", "ac18.png"},
            {"ac:嘲笑", "ac19.png"},
            {"ac:嘲笑1", "ac20.png"},
            {"ac:抠鼻", "ac30.png"},
            {"ac:无语", "ac32.png"},
            {"ac:衰", "ac40.png"},
            {"ac:黑枪", "ac44.png"},
            {"ac:花痴", "ac38.png"},
            {"ac:闪光", "ac43.png"},
            {"ac:擦汗", "ac31.png"},
            {"ac:茶", "ac39.png"},
            {"ac:计划通", "ac41.png"},
            {"ac:反对", "ac7.png"},
            {"ac:赞同", "ac42.png"},
            {"a2:goodjob", "a2_02.png"},
            {"a2:诶嘿", "a2_05.png"},
            {"a2:偷笑", "a2_03.png"},
            {"a2:怒", "a2_04.png"},
            {"a2:笑", "a2_07.png"},
            {"a2:那个…", "a2_08.png"},
            {"a2:哦嗬嗬嗬", "a2_09.png"},
            {"a2:舔", "a2_10.png"},
            {"a2:鬼脸", "a2_14.png"},
            {"a2:冷", "a2_16.png"},
            {"a2:大哭", "a2_15.png"},
            {"a2:哭", "a2_17.png"},
            {"a2:恨", "a2_21.png"},
            {"a2:中枪", "a2_23.png"},
            {"a2:囧", "a2_24.png"},
            {"a2:你看看你", "a2_25.png"},
            {"a2:doge", "a2_27.png"},
            {"a2:自戳双目", "a2_28.png"},
            {"a2:偷吃", "a2_30.png"},
            {"a2:冷笑", "a2_31.png"},
            {"a2:壁咚", "a2_32.png"},
            {"a2:不活了", "a2_33.png"},
            {"a2:不明觉厉", "a2_36.png"},
            {"a2:是在下输了", "a2_51.png"},
            {"a2:你为猴这么", "a2_53.png"},
            {"a2:干杯", "a2_54.png"},
            {"a2:干杯2", "a2_55.png"},
            {"a2:异议", "a2_47.png"},
            {"a2:认真", "a2_48.png"},
            {"a2:你已经死了", "a2_45.png"},
            {"a2:你这种人…", "a2_49.png"},
            {"a2:妮可妮可妮", "a2_18.png"},
            {"a2:惊", "a2_19.png"},
            {"a2:抢镜头", "a2_52.png"},
            {"a2:yes", "a2_26.png"},
            {"a2:有何贵干", "a2_11.png"},
            {"a2:病娇", "a2_12.png"},
            {"a2:lucky", "a2_13.png"},
            {"a2:poi", "a2_20.png"},
            {"a2:囧2", "a2_22.png"},
            {"a2:威吓", "a2_42.png"},
            {"a2:jojo立", "a2_37.png"},
            {"a2:jojo立2", "a2_38.png"},
            {"a2:jojo立3", "a2_39.png"},
            {"a2:jojo立4", "a2_41.png"},
            {"a2:jojo立5", "a2_40.png"},
        };
        Map<String, String> result = new HashMap<>();
        for (String[] entry : entries) result.put(entry[0], entry[1]);
        return Collections.unmodifiableMap(result);
    }

    public static String html(String bbcode, boolean dark, int fontSize) {
        return html(bbcode, dark, fontSize, 2110032L);
    }

    /** Exact official arithmetic; never derive dice from a Java hash or fresh randomness. */
    public static long diceSeed(String authorId, String topicId, String postId, int seedOffset) {
        try {
            long author = numericId(authorId,true), topic = numericId(topicId,false), post = numericId(postId,false);
            long seed=author + topic + post + (topic > 10246184L || post > 200188932L ? seedOffset : 0);
            return seed==0?2110032L:seed;
        } catch (NumberFormatException invalid) { return 2110032L; }
    }
    private static long numericId(String value,boolean signed) { return value!=null&&value.matches(signed?"-?[0-9]{1,15}":"[0-9]{1,15}")?Long.parseLong(value):0; }
    public static String html(String bbcode, boolean dark, int fontSize, String authorId, String topicId, String postId) {
        long seed = diceSeed(authorId,topicId,postId,0);
        boolean modern = diceSeed(authorId,topicId,postId,1) != seed;
        return document(bbcode,dark,fontSize,seed,modern);
    }
    /** Seed is the official authorId + topicId + postId (+ seedOffset for newer posts). */
    public static String html(String bbcode, boolean dark, int fontSize, long diceSeed) {
        return document(bbcode,dark,fontSize,diceSeed,true);
    }
    private static String document(String bbcode, boolean dark, int fontSize, long diceSeed, boolean modernDice) {
        String text = normalizeContent(bbcode == null ? "" : bbcode);
        int size = Math.max(12, Math.min(28, fontSize));
        String background = dark ? "#0E141F" : "#F4F6FA";
        String foreground = dark ? "#E6EDF5" : "#1C2533";
        String secondary = dark ? "#A4B1C3" : "#697587";
        String panel = dark ? "#1C2634" : "#E8EDF4";
        String border = dark ? "#40536B" : "#D4DFED";
        String link = dark ? "#82B5FF" : "#0066D6";
        return "<!doctype html><html lang=\"zh-CN\"><head>"
                + "<meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"referrer\" content=\"no-referrer\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"" + escape(CSP) + "\">"
                + "<style>html,body{margin:0;padding:0;background:" + background + ";color:" + foreground + ";}"
                + "body{padding:14px 16px;font-family:system-ui,sans-serif;font-size:" + size
                + "px;line-height:1.75;overflow-wrap:anywhere;word-break:break-word;}"
                + "a{color:" + link + ";text-decoration:underline;}"
                + "blockquote{margin:10px 0;padding:9px 12px;border-left:3px solid " + border
                + ";background:" + panel + ";}"
                + "pre.code{margin:10px 0;padding:12px;border:1px solid " + border
                + ";border-radius:4px;background:" + panel
                + ";white-space:pre-wrap;overflow-wrap:anywhere;}"
                + "code{font-family:monospace;font-size:.9em;}"
                + ".collapse{margin:10px 0;padding:8px 12px;border:1px solid " + border
                + ";border-radius:4px;background:" + panel + ";}"
                + "summary{cursor:pointer;color:" + link + ";}"
                + ".collapse-content{padding-top:8px;}"
                + "img{display:block;max-width:100%;height:auto;margin:10px 0;border-radius:3px;color:" + secondary + ";}"
                + ".image-link{display:block;}"
                + ".align-left{text-align:left;}.align-center{text-align:center;}"
                + ".align-right{text-align:right;}.align-justify{text-align:justify;}"
                + ".align-center img{margin-left:auto;margin-right:auto;}"
                + ".align-right img{margin-left:auto;margin-right:0;}"
                + ".bb-color a{color:inherit;}"
                + "</style></head><body>" + new Parser(text, size, diceSeed, modernDice).parse(null, false, 0).value
                + "</body></html>";
    }

    /** Shared signature/summary text from the same parser; never strip arbitrary user brackets. */
    public static String plainText(String bbcode) {
        String markup = new Parser(normalizeContent(bbcode == null ? "" : bbcode),16).parse(null,false,0).value;
        markup=markup.replaceAll("(?s)<section class=\"nga-crypt\">.*?</section>","加密内容");
        StringBuilder text = new StringBuilder(markup.length());
        for(int i=0;i<markup.length();) {
            if(markup.charAt(i)=='<') {
                int end=markup.indexOf('>',i+1); if(end<0)break;
                String tag=markup.substring(i+1,end);
                if(tag.equals("/tr") || tag.equals("/table") || tag.equals("br") || tag.equals("div") || tag.equals("blockquote") || tag.startsWith("pre ")
                        || tag.equals("h4") || tag.equals("summary") || tag.equals("/div") || tag.equals("/blockquote") || tag.equals("/pre")
                        || tag.equals("/h4") || tag.equals("/summary")) {
                    if(text.length()>0 && text.charAt(text.length()-1)!='\n')text.append('\n');
                } else if(tag.equals("/td")) text.append('\t');
                else if(tag.startsWith("img ")) {
                    int alt=tag.indexOf(" alt=\"");
                    if(alt>=0) { int from=alt+6,stop=tag.indexOf('"',from); if(stop>=from)text.append(tag.substring(from,stop)); }
                }
                i=end+1;
            } else if(markup.charAt(i)=='&') {
                String[] escaped={"&amp;","&lt;","&gt;","&quot;","&#39;"};
                char[] decoded={'&','<','>','"','\''}; boolean found=false;
                for(int n=0;n<escaped.length;n++)if(markup.startsWith(escaped[n],i)) {
                    text.append(decoded[n]);i+=escaped[n].length();found=true;break;
                }
                if(!found)text.append(markup.charAt(i++));
            } else text.append(markup.charAt(i++));
        }
        return text.toString().trim();
    }

    private static final class Part {
        final String value;
        final boolean closed;

        Part(String value, boolean closed) {
            this.value = value;
            this.closed = closed;
        }
    }

    private static final class Parser {
        final String source;
        final int baseFontSize;
        final String[] openTags = new String[MAX_NESTING + 1];
        final Map<String, Integer> automaticallyClosed = new HashMap<>();
        final Set<String> missingClosers = new HashSet<>();
        final int[] itemCounts = new int[MAX_NESTING + 1];
        final int[] cellCounts = new int[MAX_NESTING + 1];
        final String[] listKinds = new String[MAX_NESTING + 1];
        final long initialSeed;
        final boolean modernDice;
        int collapseCount;
        double randomSeed;
        int cursor;
        int nextTokenEnd = -1;

        Parser(String source, int baseFontSize) { this(source, baseFontSize, 2110032L, false); }
        Parser(String source, int baseFontSize, long seed, boolean modern) {
            this.source = source; this.baseFontSize = baseFontSize; this.randomSeed = seed; this.initialSeed = seed; this.modernDice = modern;
        }

        Part parse(String expected, boolean insideLink, int depth) {
            openTags[depth] = expected;
            itemCounts[depth] = 0; cellCounts[depth] = 0;
            StringBuilder output = new StringBuilder();
            while (cursor < source.length()) {
                if (source.charAt(cursor) != '[') {
                    int next = source.indexOf('[', cursor);
                    if (next < 0) next = source.length();
                    plain(output, source.substring(cursor, next), insideLink, depth);
                    cursor = next;
                    continue;
                }
                // Keep the next delimiter when many literal '[' characters
                // precede it; rescanning the whole remaining post for every
                // bracket makes malformed long posts quadratic on the UI thread.
                int end = nextTokenEnd >= cursor + 1 ? nextTokenEnd : source.indexOf(']', cursor + 1);
                nextTokenEnd = end;
                if (end < 0) {
                    appendText(output, source.substring(cursor)); cursor = source.length(); break;
                }
                if (end - cursor > 4096) {
                    output.append('[');
                    cursor++;
                    continue;
                }
                String literal = source.substring(cursor, end + 1);
                String token = source.substring(cursor + 1, end);
                String lower = token.toLowerCase(Locale.ROOT);
                // Official ubi accepts either whitespace or = before an argument.
                // Normalize only that delimiter, never the URL query/body.
                String name = tagName(lower);
                String argument = tagArgument(token);
                if (!argument.isEmpty() && !name.equals(lower)) lower = name + "=" + argument.toLowerCase(Locale.ROOT);
                cursor = end + 1;
                if (lower.matches("/lessernuke[0-9]?")) lower="/lessernuke";

                if (expected != null && lower.equals("/" + expected)) {
                    return new Part(output.toString(), true);
                }
                if (lower.startsWith("/")) {
                    String closing = lower.substring(1);
                    for (int ancestor = 1; ancestor < depth; ancestor++) {
                        if (closing.equals(openTags[ancestor])) {
                            // Real posts sometimes close an outer alignment
                            // before its size/bold tags. Finish only active
                            // descendants and let their ancestor consume this
                            // token. Discard their later redundant closes.
                            cursor -= literal.length();
                            Integer count = automaticallyClosed.get(expected);
                            automaticallyClosed.put(expected, count == null ? 1 : count + 1);
                            return new Part(output.toString(), true);
                        }
                    }
                    Integer count = automaticallyClosed.get(closing);
                    if (count != null && count > 0) {
                        if (count == 1) automaticallyClosed.remove(closing);
                        else automaticallyClosed.put(closing, count - 1);
                        continue;
                    }
                }

                if (lower.startsWith("s:")) {
                    String image = smileImage(token.substring(2));
                    if (image == null) appendText(output, literal);
                    else output.append("<img class=\"nga-smile\" src=\"").append(image)
                            .append("\" alt=\"").append(escape(literal)).append("\" referrerpolicy=\"no-referrer\">");
                    continue;
                }

                if (name.equals("br")) { output.append("<br>"); continue; }
                if ((name.equals("fixsize") && !argument.isEmpty()) || name.equals("stripbr")
                        || ((name.equals("comment") || name.equals("/comment")) && !argument.isEmpty())) continue;
                if (name.equals("symbol") && !argument.isEmpty()) { appendText(output, argument); continue; }
                if (name.equals("*") && nearest("list", depth) >= 0) {
                    int owner = nearest("list", depth), number = ++itemCounts[owner];
                    if (number > 1) output.append("<br>");
                    appendText(output, listMarker(listKinds[owner], number) + " ");
                    continue;
                }
                if (token.startsWith("@") && token.length()>2 && token.length()<=21) {
                    String user = token.substring(1);
                    if (!containsControl(user) && user.indexOf('[')<0) {
                        String numeric = user.regionMatches(true,0,"UID",0,3) ? user.substring(3) : user;
                        String target = "https://bbs.nga.cn/nuke.php?__lib=ucp&__act=get&__inchst=UTF-8&"
                                + (positiveId(numeric,18) ? "uid="+numeric : "username="+encodeQuery(user));
                        link(output, target, "@" + user, insideLink); continue;
                    }
                }
                if (name.equals("code")) {
                    int close = findClosing("[/code]");
                    if (close < 0) {
                        appendText(output, literal + source.substring(cursor));
                        cursor = source.length();
                    } else {
                        output.append("<pre class=\"code\"><code>")
                                .append(escape(source.substring(cursor, close)))
                                .append("</code></pre>");
                        cursor = close + 7;
                    }
                    continue;
                }

                if (name.matches("img-?[0-9]{0,3}")) {
                    int close = findClosing("[/img]");
                    if (close < 0) {
                        appendText(output, literal);
                    } else {
                        String body = source.substring(cursor, close);
                        String imageUrl = name.equals("img-1") ? null : safeImage(body);
                        if (imageUrl == null) {
                            // Image/QR declarations from old clients remain readable.
                            // Only trusted attachment images are fetched automatically.
                            String display = body.trim();
                            if (name.equals("img-1") && display.regionMatches(true,0,"[url]",0,5)
                                    && display.regionMatches(true,display.length()-6,"[/url]",0,6)) display=display.substring(5,display.length()-6);
                            String target = safeLink(display);
                            if (target != null) link(output, target, name.equals("img-1") ? "二维码内容：" + display : "图片：" + display, insideLink);
                            else appendText(output, body);
                        } else {
                            String attribute = escape(imageUrl);
                            if (!insideLink) output.append("<a class=\"image-link\" href=\"").append(attribute).append("\">");
                            output.append("<img src=\"").append(attribute)
                                    .append("\" alt=\"NGA 附件\" referrerpolicy=\"no-referrer\">");
                            if (!insideLink) output.append("</a>");
                        }
                        cursor = close + 6;
                    }
                    continue;
                }

                if (name.equals("attach") || name.equals("flash") || name.equals("media") || name.equals("iframe")) {
                    String closeTag = "[/" + name + "]";
                    int close = findClosing(closeTag);
                    if (close < 0) { appendText(output, literal); continue; }
                    String body = source.substring(cursor, close), target = safeLink(body);
                    if (target == null && !name.equals("iframe")) target = safeAttachment(body);
                    String label = name.equals("attach") ? "附件" : name.equals("iframe") ? "嵌入页面" : argument.equalsIgnoreCase("audio") ? "音频" : "视频";
                    boolean directMedia = target != null && !name.equals("iframe")
                            && (argument.equalsIgnoreCase("audio") || argument.equalsIgnoreCase("video") || directMedia(target));
                    if (directMedia && !insideLink) {
                        String type = argument.equalsIgnoreCase("audio") || audioMedia(target) ? "audio" : "video";
                        output.append("<figure class=\"nga-media\" data-kind=\"").append(type).append("\">");
                        link(output, target, label + "：" + body.trim(), false); output.append("</figure>");
                    } else if (target != null) link(output, target, label + "：" + body.trim(), insideLink);
                    else { appendText(output, label + "："); appendText(output, body); }
                    cursor = close + closeTag.length(); continue;
                }
                if (name.equals("crypt") || name.equals("dice") || name.equals("spine")) {
                    int close = findClosing("[/" + name + "]");
                    if (close < 0) { appendText(output, literal); continue; }
                    if (name.equals("crypt")) output.append("<section class=\"nga-crypt\">").append(escape(source.substring(cursor, close))).append("</section>");
                    else if (name.equals("spine")) output.append("<span>Spine 骨骼动画（当前尚未支持）</span>");
                    else output.append(dice(source.substring(cursor, close)));
                    cursor = close + name.length() + 3; continue;
                }

                String kind = null;
                String prefix = null;
                String suffix = null;
                String destination = null;
                if ((name.equals("url") || name.equals("jumpurl")) && argument.isEmpty()) {
                    int close = findClosing("[/" + name + "]");
                    if (close < 0) { appendText(output, literal); continue; }
                    kind = name; destination = insideLink ? null : safeLink(source.substring(cursor, close));
                    prefix = destination == null ? "" : "<a href=\"" + escape(destination) + "\" rel=\"noreferrer noopener\">";
                    suffix = destination == null ? "" : "</a>";
                } else if (name.equals("sup") || name.equals("sub")) {
                    kind = name; prefix = "<" + name + ">"; suffix = "</" + name + ">";
                } else if (name.equals("font")) {
                    kind = name; String face = safeFont(unquote(argument));
                    prefix = face == null ? "" : "<font face=\"" + face + "\">"; suffix = face == null ? "" : "</font>";
                } else if (name.equals("span") || name.equals("style") || name.equals("randomblock") || name.equals("headline") || name.equals("urlreplace") || name.equals("chartradar") || name.matches("omit[0-9]*")) {
                    kind = name.startsWith("omit") ? "omit" : name;
                    prefix = name.equals("span") || name.startsWith("omit") ? "<span>" : "<div>";
                    suffix = name.equals("span") || name.startsWith("omit") ? "</span>" : "</div>";
                } else if (name.equals("customachieve")) {
                    kind = name; prefix = "<div>"; suffix = "</div>";
                } else if ((name.equals("title") || name.equals("txt")) && nearest("customachieve",depth)>=0) {
                    kind = name; prefix = name.equals("title") ? "<strong>" : "<span>"; suffix = name.equals("title") ? "</strong><br>" : "</span>";
                } else if (name.matches("lessernuke[0-9]?")) {
                    kind = "lessernuke"; prefix = "<blockquote>"; suffix = "</blockquote>";
                } else if (name.equals("upup")) {
                    kind = name; prefix = "<strong>"; suffix = "</strong>";
                } else if (name.equals("h")) {
                    kind = name; prefix = "<h4>"; suffix = "</h4>";
                } else if ((name.equals("l") || name.equals("r")) && argument.isEmpty()) {
                    kind = name; prefix = "<div class=\"align-" + (name.equals("l") ? "left" : "right") + "\">"; suffix = "</div>";
                } else if (name.equals("list") && (argument.isEmpty() || argument.matches("[1aAiI]"))) {
                    kind = name; listKinds[Math.min(depth + 1, MAX_NESTING)] = argument;
                    prefix = "<div>"; suffix = "</div>";
                } else if (name.equals("table")) {
                    kind = name; prefix = "<table class=\"nga-table\">"; suffix = "</table>";
                } else if (name.equals("tr") && nearest("table", depth)>=0) {
                    kind = name; prefix = "<tr" + (argument.matches("(?i).*\\bth\\b.*") ? " class=\"header\"" : "") + ">"; suffix = "</tr>";
                } else if ((name.equals("td") || name.matches("td[0-9]{1,3}")) && nearest("table", depth)>=0) {
                    kind = "td"; prefix = "<td" + tableSpan(argument, "colspan") + tableSpan(argument, "rowspan") + ">"; suffix = "</td>";
                } else if (name.equals("album")) {
                    kind = name; prefix = "<div><strong>" + escape(argument.isEmpty() ? "相册" : unquote(argument)) + "</strong><br>"; suffix = "</div>";
                } else if (lower.equals("b")) {
                    kind = "b"; prefix = "<strong>"; suffix = "</strong>";
                } else if (lower.equals("i")) {
                    kind = "i"; prefix = "<em>"; suffix = "</em>";
                } else if (lower.equals("u")) {
                    kind = "u"; prefix = "<u>"; suffix = "</u>";
                } else if (lower.equals("del")) {
                    kind = "del"; prefix = "<del>"; suffix = "</del>";
                } else if (lower.equals("quote")) {
                    kind = "quote"; prefix = "<blockquote>"; suffix = "</blockquote>";
                } else if (name.equals("align")) {
                    kind = "align"; prefix = ""; suffix = "";
                    String alignment = unquote(argument).toLowerCase(Locale.ROOT);
                    if (alignment.equals("left") || alignment.equals("center")
                            || alignment.equals("right") || alignment.equals("justify")) {
                        kind = "align"; prefix = "<div class=\"align-" + alignment + "\">"; suffix = "</div>";
                    }
                } else if (name.equals("size")) {
                    kind = "size"; prefix = ""; suffix = "";
                    String fontSize = safeFontSize(unquote(argument), baseFontSize);
                    if (fontSize != null) {
                        kind = "size"; prefix = "<span style=\"font-size:" + fontSize + "\">"; suffix = "</span>";
                    }
                } else if (name.equals("color")) {
                    kind = "color"; prefix = ""; suffix = "";
                    String color = unquote(argument).toLowerCase(Locale.ROOT);
                    boolean background = color.startsWith("bg") && COLOR.matcher(color.substring(2)).matches();
                    if (COLOR.matcher(color).matches() || background) {
                        kind = "color"; prefix = "<span class=\"bb-color\" style=\"" + (background ? "background-color:" + color.substring(2) : "color:" + color) + "\">"; suffix = "</span>";
                    }
                } else if (lower.equals("collapse") || lower.startsWith("collapse=")) {
                    kind = "collapse";
                    String title = argument.isEmpty() ? "展开内容" : argument;
                    if (title.trim().isEmpty()) title = "展开内容";
                    prefix = "<details class=\"collapse\"><summary>" + escape(title)
                            + "</summary><div class=\"collapse-content\">";
                    suffix = "</div></details>";
                } else if ((name.equals("url") || name.equals("jumpurl")) && !argument.isEmpty()) {
                    kind = name;
                    destination = insideLink ? null : safeLink(unquote(argument));
                    prefix = destination == null ? "" : "<a href=\"" + escape(destination)
                            + "\" rel=\"noreferrer noopener\">";
                    suffix = destination == null ? "" : "</a>";
                } else if (name.equals("uid") || name.equals("pid") || name.equals("tid") || name.equals("fid") || name.equals("stid")) {
                    String referenceValue = token.length()>name.length() && token.charAt(name.length())=='=' ? token.substring(name.length()+1) : argument;
                    if (referenceValue.isEmpty()) {
                        int close = findClosing("[/" + name + "]");
                        if (close >= 0) referenceValue = source.substring(cursor, close);
                    }
                    if (name.equals("uid") && argument.isEmpty() && referenceValue.matches("anony_[a-f0-9]{32}")) {
                        int close=findClosing("[/uid]"); output.append("匿名用户"); cursor=close+6; continue;
                    }
                    String reference = nativeReference(name, referenceValue);
                    if (reference != null) {
                        kind = name;
                        destination = insideLink ? null : reference;
                        prefix = destination == null ? "" : "<a href=\"" + escape(destination)
                                + "\" rel=\"noreferrer noopener\">";
                        suffix = destination == null ? "" : "</a>";
                    }
                }

                // A run of unclosed structural containers must not consume the
                // depth budget and turn a later valid bold/link into literal code.
                if (kind != null && (kind.equals("album") || kind.equals("list") || kind.equals("table")
                        || kind.equals("randomblock") || kind.equals("headline") || kind.equals("urlreplace"))
                        && findClosing("[/" + kind + "]") < 0) { appendText(output,literal); continue; }
                if (kind == null || depth >= MAX_NESTING) {
                    appendText(output, literal);
                } else {
                    double previousSeed=randomSeed;
                    if (kind.equals("collapse")) randomSeed=initialSeed+(modernDice?++collapseCount:0);
                    Part child = parse(kind, insideLink || destination != null, depth + 1);
                    if (kind.equals("collapse")) randomSeed=previousSeed;
                    if (child.closed) {
                        output.append(prefix).append(child.value).append(suffix);
                    } else {
                        appendText(output, literal);
                        output.append(child.value);
                    }
                }
            }
            return new Part(output.toString(), expected == null);
        }
        private String dice(String expression) {
            String clean = expression.replaceAll("\\s+", "");
            if (!clean.matches("[0-9dD+]+") || clean.length() > 256) return "<span>骰子表达式无效：" + escape(expression) + "</span>";
            StringBuilder detail = new StringBuilder(); long sum = 0; int rolls = 0;
            for (String term : clean.toLowerCase(Locale.ROOT).split("\\+", -1)) {
                if (term.isEmpty()) continue;
                try {
                    int split = term.indexOf('d');
                    if (split < 0) { long number = Long.parseLong(term); if (number > 9999999999L) throw new NumberFormatException(); sum += number; if (detail.length() > 0) detail.append(" + "); detail.append(number); }
                    else {
                        int count = split == 0 ? 1 : Integer.parseInt(term.substring(0, split));
                        int faces = Integer.parseInt(term.substring(split + 1));
                        if (count < 1 || count > 10 || faces < 1 || faces > 100000 || rolls + count > 100) throw new NumberFormatException();
                        for (int n = 0; n < count; n++) { randomSeed = ((randomSeed * 9301d + 49297d) % 233280d + 233280d) % 233280d; int value = (int) Math.floor(randomSeed / 233280d * faces) + 1; sum += value; rolls++; if (detail.length() > 0) detail.append(" + "); detail.append('d').append(faces).append('(').append(value).append(')'); }
                    }
                } catch (NumberFormatException invalid) { return "<span>骰子表达式超出限制：" + escape(expression) + "</span>"; }
            }
            return "<blockquote><strong>ROLL：" + escape(expression) + "</strong><br>" + detail + " = <strong>" + sum + "</strong></blockquote>";
        }
        private int nearest(String kind, int depth) {
            for (int i = depth; i > 0; i--) if (kind.equals(openTags[i])) return i;
            return -1;
        }
        private void plain(StringBuilder output, String value, boolean insideLink, int depth) {
            if (nearest("album", depth) < 0) { appendText(output, value); return; }
            String[] lines = value.split("\n", -1);
            for (int i=0;i<lines.length;i++) {
                if (i>0) output.append("<br>");
                String image = safeImage(lines[i]);
                if (image != null) {
                    if (!insideLink) output.append("<a class=\"image-link\" href=\"").append(escape(image)).append("\">");
                    output.append("<img src=\"").append(escape(image)).append("\" alt=\"NGA 附件\" referrerpolicy=\"no-referrer\">");
                    if (!insideLink) output.append("</a>");
                } else {
                    String target = safeLink(lines[i]);
                    if (target != null) link(output,target,lines[i].trim(),insideLink);
                    else appendText(output,lines[i]);
                }
            }
        }
        private int findClosing(String tag) {
            if (missingClosers.contains(tag)) return -1;
            int close = indexOfIgnoreCase(source, tag, cursor);
            if (close < 0) missingClosers.add(tag);
            return close;
        }
    }

    private static String tableSpan(String argument, String kind) {
        Matcher match = Pattern.compile("(?i)(?:^|\\s)" + kind + "\\s*=?\\s*([0-9]{1,2})(?:\\s|$)").matcher(argument);
        if (!match.find()) return "";
        int number = Integer.parseInt(match.group(1)); return number >= 1 && number <= 32 ? " " + kind + "=\"" + number + "\"" : "";
    }
    private static boolean directMedia(String url) {
        try { return new URI(url).getPath().toLowerCase(Locale.ROOT).matches(".*\\.(mp4|m4v|webm|3gp|mkv|m3u8|mp3|m4a|aac|ogg|oga|opus|wav|flac)$"); }
        catch (URISyntaxException | NullPointerException invalid) { return false; }
    }
    private static boolean audioMedia(String url) {
        try { return new URI(url).getPath().toLowerCase(Locale.ROOT).matches(".*\\.(mp3|m4a|aac|ogg|oga|opus|wav|flac)$"); }
        catch (URISyntaxException | NullPointerException invalid) { return false; }
    }

    private static String safeFontSize(String raw, int baseSize) {
        // NGA uses percentage BBCode. A strict numeric grammar excludes CSS
        // functions, injected declarations and huge values. Resolve relative to
        // the reader's base size, so nested tags cannot multiply to giant text.
        if (!raw.matches("[0-9]{1,3}%?")) return null;
        int percent = Integer.parseInt(raw.endsWith("%") ? raw.substring(0, raw.length() - 1) : raw);
        if (percent < 50 || percent > 500) return null;
        int hundredths = baseSize * percent;
        return (hundredths % 100 == 0 ? Integer.toString(hundredths / 100)
                : Double.toString(hundredths / 100d)) + "px";
    }

    /** Fixed NGA references; no argument can inject a host, path or query name. */
    private static String nativeReference(String name, String value) {
        if (name.equals("uid")) return positiveId(value, 18) ? "https://bbs.nga.cn/nuke.php?__lib=ucp&__act=get&uid=" + value : null;
        if (name.equals("fid")) return value.matches("-?[1-9][0-9]{0,9}") ? "https://bbs.nga.cn/thread.php?fid=" + value : null;
        if (name.equals("stid")) return positiveId(value,14) ? "https://bbs.nga.cn/thread.php?stid=" + value : null;
        String[] parts = value.split(",", -1);
        if (parts.length == 1) return positiveId(value,name.equals("pid")?18:14) ? "https://bbs.nga.cn/read.php?" + name + "=" + value : null;
        if (parts.length > 3 || !positiveId(parts[0],18) || !positiveId(parts[1],18)
                || (parts.length == 3 && !positiveId(parts[2],5))) return null;
        // Official postLinkTag accepts both tid,pid and older pid,tid order.
        String tid = Long.parseLong(parts[0]) < Long.parseLong(parts[1]) ? parts[0] : parts[1];
        String pid = tid.equals(parts[0]) ? parts[1] : parts[0];
        if (!positiveId(tid,14)) return null;
        return "https://bbs.nga.cn/read.php?tid=" + tid + "&pid=" + pid + (parts.length == 3 ? "&page=" + parts[2] : "&to=1");
    }
    private static boolean positiveId(String value, int digits) { return value.matches("[1-9][0-9]{0," + (digits - 1) + "}"); }

    private static String tagName(String token) {
        int i=0; while(i<token.length() && token.charAt(i)!='=' && !Character.isWhitespace(token.charAt(i))) i++;
        return token.substring(0,i);
    }
    private static String tagArgument(String token) {
        int i=tagName(token).length();
        if(i==token.length())return "";
        while(i<token.length() && (token.charAt(i)=='=' || Character.isWhitespace(token.charAt(i))))i++;
        return token.substring(i);
    }
    private static boolean containsControl(String value) {
        for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))return true;
        return false;
    }
    private static String encodeQuery(String value) {
        try { return URLEncoder.encode(value,"UTF-8"); }
        catch(UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
    private static String safeFont(String value) {
        if (!value.matches("(?i:simsun|simhei|Arial|Arial Black|Book Antiqua|Century Gothic|Comic Sans MS|Courier New|Georgia|Impact|Tahoma|Times New Roman|Trebuchet MS|Script MT Bold|Stencil|Verdana|Lucida Console|serif|sans-serif|monospace)")) return null;
        return value;
    }
    private static String listMarker(String mode,int number) {
        if (mode==null || mode.isEmpty()) return "•";
        if ((mode.equals("a") || mode.equals("A")) && number<=26) return String.valueOf((char)((mode.equals("a")?'a':'A')+number-1))+".";
        if ((mode.equals("i") || mode.equals("I")) && number<=3999) {
            int[] values={1000,900,500,400,100,90,50,40,10,9,5,4,1};
            String[] signs={"M","CM","D","CD","C","XC","L","XL","X","IX","V","IV","I"};
            StringBuilder roman=new StringBuilder();for(int i=0;i<values.length;i++)while(number>=values[i]){roman.append(signs[i]);number-=values[i];}
            return (mode.equals("i")?roman.toString().toLowerCase(Locale.ROOT):roman.toString())+".";
        }
        return number+".";
    }
    private static void link(StringBuilder output,String target,String label,boolean insideLink) {
        if(!insideLink)output.append("<a href=\"").append(escape(target)).append("\" rel=\"noreferrer noopener\">");
        appendText(output,label); if(!insideLink)output.append("</a>");
    }
    private static String safeAttachment(String raw) {
        String image = safeImage(raw); if(image!=null)return image;
        String value=raw.trim();
        Matcher attachment = Pattern.compile("\\./mon_([0-9]{4})([0-9]{2})/([0-9]{2})/[A-Za-z0-9_-][A-Za-z0-9._-]*").matcher(value);
        if(attachment.matches() && validDate(Integer.parseInt(attachment.group(1)),Integer.parseInt(attachment.group(2)),Integer.parseInt(attachment.group(3))))return "https://img.nga.cn/attachments/"+value.substring(2);
        return null;
    }

    private static String safeLink(String raw) {
        if(raw==null)return null;
        String value=raw.trim();
        if(value.startsWith("/") && !value.startsWith("//")) value="https://bbs.nga.cn"+value;
        else if(value.startsWith("//")) {
            URI relative=parseWebUri("https:"+value);
            if(relative==null || !(relative.getHost().equalsIgnoreCase("bbs.nga.cn")
                    || relative.getHost().equalsIgnoreCase("ngabbs.com") || relative.getHost().equalsIgnoreCase("nga.178.com")))return null;
            value="https:"+value;
        }
        URI uri = parseWebUri(value);
        return uri == null ? null : uri.toASCIIString();
    }

    private static String safeImage(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.startsWith("./")) {
            Matcher relative = RELATIVE_ATTACHMENT.matcher(value);
            if (!relative.matches() || !validDate(Integer.parseInt(relative.group(1)),
                    Integer.parseInt(relative.group(2)), Integer.parseInt(relative.group(3)))) return null;
            // Current NGA responses advertise img.nga.cn as _ATTACH_BASE_VIEW.
            // Keep this fixed to the existing image-host allowlist.
            value = "https://img.nga.cn/attachments/" + value.substring(2);
        } else if (value.startsWith("//")) {
            value = "https:" + value;
        }
        URI uri = parseWebUri(value);
        if (uri == null) return null;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!host.equals("img.nga.178.com") && !host.equals("img4.nga.178.com")
                && !host.equals("img.nga.cn") && !host.equals("img4.nga.cn")) return null;
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            // Old official posts use http attachment URLs. Upgrade only exact
            // image hosts before emitting any native image request.
            uri=parseWebUri("https"+uri.toASCIIString().substring(uri.getScheme().length()));
        }
        if (uri==null || !"https".equalsIgnoreCase(uri.getScheme())) return null;
        if (uri.getPort() != -1 && uri.getPort() != 443) return null;
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
        String path = uri.getPath();
        if (path == null || !path.startsWith("/attachments/") || path.indexOf('\\') >= 0) return null;
        // Inspect the original path, before normalization could hide a dot segment.
        // NGA attachment filenames need no percent escapes; rejecting them also
        // prevents encoded separators or multiple rounds of traversal decoding.
        if (uri.getRawPath().indexOf('%') >= 0) return null;
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) return null;
        }
        return uri.toASCIIString();
    }

    private static boolean validDate(int year, int month, int day) {
        if (year < 1 || month < 1 || month > 12 || day < 1) return false;
        int days;
        if (month == 2) {
            boolean leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
            days = leap ? 29 : 28;
        } else {
            days = month == 4 || month == 6 || month == 9 || month == 11 ? 30 : 31;
        }
        return day <= days;
    }

    private static URI parseWebUri(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c) || Character.isSpaceChar(c)
                    || c == '\\') return null;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || uri.isOpaque() || uri.getHost() == null || uri.getUserInfo() != null) return null;
            if (uri.getPort() < -1 || uri.getPort() > 65535) return null;
            return uri;
        } catch (URISyntaxException invalid) {
            return null;
        }
    }

    private static String unquote(String text) {
        String value = text.trim();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' || first == '"') && first == last) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        int limit = haystack.length() - needle.length();
        for (int i = from; i <= limit; i++) {
            if (haystack.charAt(i) == '[' && haystack.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    private static void appendText(StringBuilder output, String text) {
        output.append(escape(text).replace("\n", "<br>"));
    }

    /** Decode the small server text dialect once; the parser still escapes all text. */
    private static String normalizeContent(String source) {
        StringBuilder text = new StringBuilder(source.length());
        int literalUntil=-1,nextTokenEnd=-1;
        boolean noMoreTokenEnds=false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if(c=='['&&i>literalUntil&&!noMoreTokenEnds) {
                // Do not let a decoded space change a literal token into BBCode.
                // Cache the closing bracket to keep malformed bracket runs linear.
                if(nextTokenEnd<i+1)nextTokenEnd=source.indexOf(']',i+1);
                if(nextTokenEnd<0)noMoreTokenEnds=true;
                else literalUntil=nextTokenEnd;
                if(nextTokenEnd>=0&&nextTokenEnd-i<=4096
                        &&tagName(source.substring(i+1,nextTokenEnd).toLowerCase(Locale.ROOT)).equals("code")) {
                    int close=indexOfIgnoreCase(source,"[/code]",nextTokenEnd+1);
                    // The parser also treats an unclosed code tail as literal content.
                    literalUntil=close<0?source.length():close+6;
                }
            }
            if (c == '\r') {
                if (i + 1 < source.length() && source.charAt(i + 1) == '\n') i++;
                text.append('\n');
                continue;
            }
            // Only exact line-break tokens become formatting. Other raw HTML,
            // including script elements and br tags with attributes, stays text.
            int breakLength = source.regionMatches(true, i, "<br>", 0, 4) ? 4
                    : source.regionMatches(true, i, "<br/>", 0, 5) ? 5
                    : source.regionMatches(true, i, "<br />", 0, 6) ? 6 : 0;
            if (breakLength != 0) {
                text.append('\n');
                i += breakLength - 1;
                continue;
            }
            if (c == '&') {
                if(i>literalUntil) {
                    int end=appendNumericTextEntity(source,i,text);
                    if(end>i) {
                        i=end;continue;
                    }
                }
                String[] entities = { "&nbsp;", "&amp;", "&lt;", "&gt;", "&quot;", "&#39;" };
                char[] characters = { '\u00A0', '&', '<', '>', '"', '\'' };
                boolean decoded = false;
                for (int n = 0; n < entities.length; n++) {
                    if (source.startsWith(entities[n], i)) {
                        text.append(characters[n]);
                        i += entities[n].length() - 1;
                        decoded = true;
                        break;
                    }
                }
                if (decoded) continue;
            }
            text.append(c);
        }
        return text.toString();
    }

    /** Accept the server's one amp envelope, never recursive or ASCII syntax decoding. */
    private static int appendNumericTextEntity(String source,int start,StringBuilder output) {
        int cursor;
        if(source.startsWith("&#",start))cursor=start+2;
        else if(source.startsWith("&amp;#",start))cursor=start+6;
        else return -1;
        if(cursor>=source.length())return -1;
        int radix=10,maxDigits=7;
        if(source.charAt(cursor)=='x'||source.charAt(cursor)=='X'){cursor++;radix=16;maxDigits=6;}
        int digits=0,point=0;
        while(cursor<source.length()&&digits<maxDigits) {
            char ch=source.charAt(cursor);
            int digit=ch>='0'&&ch<='9'?ch-'0':radix==16&&ch>='a'&&ch<='f'?ch-'a'+10
                    :radix==16&&ch>='A'&&ch<='F'?ch-'A'+10:-1;
            if(digit<0)break;
            point=point*radix+digit;cursor++;digits++;
        }
        if(digits==0||cursor>=source.length()||source.charAt(cursor)!=';'||point<0xa0
                ||!Character.isValidCodePoint(point)||(point>=0xd800&&point<=0xdfff))return -1;
        output.appendCodePoint(point);
        return cursor;
    }

    private static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': escaped.append("&amp;"); break;
                case '<': escaped.append("&lt;"); break;
                case '>': escaped.append("&gt;"); break;
                case '"': escaped.append("&quot;"); break;
                case '\'': escaped.append("&#39;"); break;
                case '\0': escaped.append("\uFFFD"); break;
                default: escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
