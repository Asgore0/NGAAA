package cn.ngapure.app;

import java.util.ArrayList;
import java.util.List;

/** Bounded blank-paragraph cleanup for ordinary rendered flow; code blocks never call this. */
public final class FlowTextWhitespace {
    private FlowTextWhitespace() { }
    /** Ascending half-open deletion ranges; apply backwards to a spannable to keep its spans intact. */
    public static int[][] removals(CharSequence value) {
        List<int[]> edits=new ArrayList<>();
        if(value==null)return new int[0][];
        int at=0;
        while(at<value.length()) {
            if(!blank(value.charAt(at))){at++;continue;}
            int start=at,first=-1,second=-1,count=0;
            while(at<value.length()&&blank(value.charAt(at))){if(value.charAt(at)=='\n'){count++;if(first<0)first=at;else if(second<0)second=at;}at++;}
            if(start==0||at==value.length())add(edits,start,at);
            else if(count>2){add(edits,start,first);add(edits,first+1,second);add(edits,second+1,at);}
        }
        return edits.toArray(new int[edits.size()][]);
    }
    private static boolean blank(char value){return value==' '||value=='\t'||value=='\r'||value=='\n'||value=='\u00a0'||value=='\u3000';}
    private static void add(List<int[]> values,int from,int to){if(to>from)values.add(new int[]{from,to});}
    public static String compact(String value){if(value==null)return "";StringBuilder result=new StringBuilder(value);int[][] edits=removals(value);for(int i=edits.length-1;i>=0;i--)result.delete(edits[i][0],edits[i][1]);return result.toString();}
}
