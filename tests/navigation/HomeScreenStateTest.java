package cn.ngapure.app;

import java.util.Arrays;

/** Home tab identities and board-list anchors must survive recreation together. */
public final class HomeScreenStateTest {
    private static int checks;
    private static void ok(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static boolean defaults(HomeScreenState state){return "2".equals(state.ratingCategory)&&state.ratingPage==1&&"收藏版块".equals(state.boardCategory)&&state.boardQuery.isEmpty()&&state.boardPosition==0&&state.boardOffset==0;}
    private static void rejected(Object raw,String label){ok(defaults(HomeScreenState.decode(raw)),label);}
    public static void main(String[] args){
        HomeScreenState source=HomeScreenState.capture("8",4,"全部版块","合成筛选",35,-47);
        HomeScreenState restored=HomeScreenState.decode(source.encode());
        ok(restored.ratingCategory.equals("8")&&restored.ratingPage==4,"nondefault rating category and page retained together");
        ok(restored.boardCategory.equals("全部版块")&&restored.boardQuery.equals("合成筛选"),"board mode and synthetic query retained together");
        ok(restored.boardPosition==35&&restored.boardOffset==-47,"signed first-row anchor retained");
        for(String rating:new String[]{"2","4","8"})for(int page:new int[]{1,2,NavigationState.MAX_PAGE})for(int position:new int[]{0,47,HomeScreenState.MAX_POSITION})for(int offset:new int[]{-NavigationState.MAX_SCROLL,-80,0,12,NavigationState.MAX_SCROLL}){
            HomeScreenState value=HomeScreenState.capture(rating,page,"手机游戏","合成🌟筛选",position,offset);
            HomeScreenState decoded=HomeScreenState.decode(value.encode());
            ok(Arrays.equals(value.encode(),decoded.encode()),"rating identity, Unicode filter and relative list anchor roundtrip");
        }
        rejected(null,"missing legacy state gets defaults");rejected(new Object(),"wrong Bundle value type gets defaults");rejected(new Object[]{"8","2","全部版块","",2,0},"untyped array cannot impersonate String schema");
        rejected(new String[0],"missing fields rejected");rejected(new String[7],"unknown schema rejected");
        String[] valid=source.encode();
        for(int i=0;i<valid.length;i++){String[] row=valid.clone();row[i]=null;rejected(row,"null field rejected");}
        for(String rating:new String[]{"0","3","-1","8&extra=1"}){String[] row=valid.clone();row[0]=rating;rejected(row,"unknown rating category rejected");}
        for(String page:new String[]{"0","-1","100000","999999999999999999","1.5","garbage"}){String[] row=valid.clone();row[1]=page;rejected(row,"invalid page rejected");}
        for(String position:new String[]{"-1","100001","999999999999999999","1.5"}){String[] row=valid.clone();row[4]=position;rejected(row,"invalid board position rejected");}
        for(String offset:new String[]{"-10000001","10000001","999999999999999999","1.5"}){String[] row=valid.clone();row[5]=offset;rejected(row,"invalid signed list offset rejected");}
        String[] row=valid.clone();row[2]="";rejected(row,"empty board mode rejected");
        row=valid.clone();row[2]="bad\ncategory";rejected(row,"control character in category rejected");
        row=valid.clone();row[2]=repeat('x',81);rejected(row,"oversized category rejected");
        row=valid.clone();row[3]="bad\u0000query";rejected(row,"control character in query rejected");
        row=valid.clone();row[3]=repeat('x',HomeScreenState.QUERY_LIMIT+1);rejected(row,"oversized filter rejected");
        HomeScreenState clamped=HomeScreenState.capture(null,Integer.MAX_VALUE,null,null,Integer.MAX_VALUE,Integer.MIN_VALUE);
        ok(clamped.ratingCategory.equals("2")&&clamped.ratingPage==NavigationState.MAX_PAGE,"capture fallback and page cap");
        ok(clamped.boardCategory.equals("收藏版块")&&clamped.boardQuery.isEmpty(),"capture missing text defaults");
        ok(clamped.boardPosition==HomeScreenState.MAX_POSITION&&clamped.boardOffset==-NavigationState.MAX_SCROLL,"capture limits prevent oversized state");
        HomeScreenState captured=HomeScreenState.capture("4",0,"x\ny",repeat('x',300),-1,Integer.MAX_VALUE);
        ok(captured.ratingPage==1&&captured.boardPosition==0&&captured.boardOffset==NavigationState.MAX_SCROLL,"capture negative indices and positive offset bounded");
        ok(captured.boardCategory.equals("x y")&&captured.boardQuery.length()==HomeScreenState.QUERY_LIMIT,"capture control-free and bounded filter text");
        String[] independent=source.encode();independent[0]="2";ok(source.ratingCategory.equals("8"),"encoded array cannot mutate state");
        ok(!defaults(HomeScreenState.decode(HomeScreenState.capture("4",1,"收藏版块","",0,0).encode())),"valid nondefault tab does not silently fall back");
        System.out.println("HomeScreenState: "+checks+" bounded home identity and list anchor assertions PASS; no Android, network or device");
    }
    private static String repeat(char value,int count){char[] chars=new char[count];Arrays.fill(chars,value);return new String(chars);}
}
