package cn.ngapure.app;
import java.util.Arrays;
import java.util.Collections;
public final class ForumListAnchorTest{
    private static int checks;
    private static final String A=String.join("",Collections.nCopies(64,"a")),B=String.join("",Collections.nCopies(64,"b"));
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static ForumListAnchor anchor(){return ForumListAnchor.create(A,"stid:123","-7",2,"42",2,-37);}
    public static void main(String[] args){
        ForumListAnchor value=anchor(),restored=ForumListAnchor.decode(value.encode());
        check(restored!=null&&restored.matches(A,"stid:123","-7",2),"valid scoped anchor round trip");
        check(restored.top==-37&&restored.index==2&&restored.topicId.equals("42"),"identity and offset survive navigation");
        check(!value.matches(B,"stid:123","-7",2),"other account/host/token scope rejected");
        check(!value.matches(A,"123","-7",2),"forum versus collection isolated");
        check(!value.matches(A,"stid:124","-7",2),"different route rejected");
        check(!value.matches(A,"stid:123","7",2),"different parent rejected");
        check(!value.matches(A,"stid:123","-7",3),"different page rejected");
        check(value.position(Arrays.asList("41","42","43"),1)==2,"stable ID wins after reorder");
        check(value.position(Arrays.asList("42","41","43"),0)==0,"stable ID wins after insert/filter");
        check(value.position(Arrays.asList("31","32","33","34"),1)==3,"removed topic retains nearby index");
        check(value.position(Arrays.asList("31"),1)==1,"short fresh page clamps index");
        check(value.position(Collections.emptyList(),0)==0,"all blocked page position remains bounded");
        ForumListAnchor header=ForumListAnchor.create(A,"-7","",1,"",-1,-210);
        check(header!=null&&header.position(Arrays.asList("1","2"),1)==0&&header.top==-210,"expanded header offset restored");
        check(ForumListAnchor.create(A,"-7","",1,"",0,0)==null,"topic rows require identity");
        check(ForumListAnchor.create(A,"-7","",1,"1",-1,0)==null,"header cannot smuggle topic identity");
        check(ForumListAnchor.decode(null)==null&&ForumListAnchor.decode(new int[8])==null,"wrong state type rejected");
        check(ForumListAnchor.decode(new String[7])==null&&ForumListAnchor.decode(new String[9])==null,"wrong schema rejected");
        String[][] bad={{"0","2"},{"1",B.substring(1)},{"1",A.toUpperCase()},{"2",""},{"2","javascript:1"},{"2","stid:-1"},{"2","1234567890123"},{"3","bad"},{"4","0"},{"4","100000"},{"4","bad"},{"5","-1"},{"5","1x"},{"6","-2"},{"6","100001"},{"7","-10000001"},{"7","10000001"},{"7","NaN"}};
        for(String[] change:bad){String[] raw=value.encode();raw[Integer.parseInt(change[0])]=change[1];check(ForumListAnchor.decode(raw)==null,"malformed field "+Arrays.toString(change));}
        for(int i=0;i<8;i++){String[] raw=value.encode();raw[i]=null;check(ForumListAnchor.decode(raw)==null,"null field "+i);}
        for(int top:new int[]{-10000000,-900,-1,0,34,10000000}){ForumListAnchor a=ForumListAnchor.create(A,"-7","",99999,"999999999999999999",100000,top);check(a!=null&&ForumListAnchor.decode(a.encode()).top==top,"bounded offset "+top);}
        System.out.println("ForumListAnchor: "+checks+" assertions PASS; pure Java, no Android or network");
    }
}
