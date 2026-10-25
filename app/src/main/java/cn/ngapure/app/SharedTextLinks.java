package cn.ngapure.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts bounded, read-only NGA destinations from user-submitted share or pasted text. */
public final class SharedTextLinks {
    public static final int MAX_TEXT=16384,MAX_LINKS=8;
    private static final Pattern URL=Pattern.compile("https?://[^\\s\\p{Z}<>\\\"'“”《》「」『』)\\]}，。；：！？、）】〕〉…]+",Pattern.CASE_INSENSITIVE);
    private static final String TRAILING=".,;:!?)]}，。；：！？、）】〕〉》…";
    private SharedTextLinks() { }

    public static List<NativeLinkTarget> extract(String text){return extract(text,"bbs.nga.cn");}
    /** Does not request a URL, inspect the clipboard, read credentials or authorize any write. */
    public static List<NativeLinkTarget> extract(String text,String baseHost){
        if(text==null||text.length()>MAX_TEXT||unsafeText(text))return Collections.emptyList();
        List<NativeLinkTarget> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        Matcher matches=URL.matcher(text);
        while(matches.find()&&result.size()<MAX_LINKS){
            String value=matches.group();
            while(!value.isEmpty()&&TRAILING.indexOf(value.charAt(value.length()-1))>=0)value=value.substring(0,value.length()-1);
            NativeLinkTarget target=NativeLinkTarget.parse(value,baseHost);
            if(!target.official||!readOnly(target.kind))continue;
            // A sentence delimiter inside a host must not turn a lookalike domain into the official home.
            // In particular browsers may treat U+3002 as a domain separator rather than punctuation.
            if(target.kind==NativeLinkTarget.HOME&&bareAuthority(value)&&matches.end()+1<text.length()
                    &&Character.isLetterOrDigit(text.charAt(matches.end()+1)))continue;
            // Display-only transport parameters and official domain aliases do not create another destination.
            String identity=target.kind+"|"+target.topicId+"|"+target.postId+"|"+target.forumId+"|"
                    +target.collectionId+"|"+target.parentForumId+"|"+target.userId+"|"+target.favoriteId+"|"+target.authorId+"|"+target.page;
            if(seen.add(identity))result.add(target);
        }
        return Collections.unmodifiableList(result);
    }
    private static boolean readOnly(int kind){
        return kind==NativeLinkTarget.TOPIC||kind==NativeLinkTarget.FORUM||kind==NativeLinkTarget.FAVORITE
                ||kind==NativeLinkTarget.USER||kind==NativeLinkTarget.SELF||kind==NativeLinkTarget.MESSAGES
                ||kind==NativeLinkTarget.REMINDERS||kind==NativeLinkTarget.HOME;
    }
    private static boolean bareAuthority(String value){int scheme=value.indexOf("://");return scheme>=0&&value.indexOf('/',scheme+3)<0;}
    private static boolean unsafeText(String text){
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if((c<0x20&&c!='\n'&&c!='\r'&&c!='\t')||c==0x7f||Character.getType(c)==Character.FORMAT)return true;
        }
        return false;
    }
}
