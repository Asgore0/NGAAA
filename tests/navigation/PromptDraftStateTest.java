package cn.ngapure.app;

import java.util.Arrays;

public final class PromptDraftStateTest {
    private static int checks;
    private static void ok(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static String repeat(char value,int count){char[] chars=new char[count];Arrays.fill(chars,value);return new String(chars);}
    public static void main(String[] args){
        String scope=repeat('a',64),page=repeat('b',64),different=repeat('c',64);
        PromptDraftState search=PromptDraftState.search(scope,page,"合成搜索 🌟 NGA",2,6);ok(search!=null,"Unicode unsubmitted search retained");PromptDraftState restored=PromptDraftState.decode(search.encode());ok(restored!=null&&Arrays.equals(search.encode(),restored.encode()),"value-only search roundtrip");ok(restored.matches(scope,page),"same session and page accepted");ok(!restored.matches(different,page)&&!restored.matches(scope,different),"different exact session or navigation refused");ok(!restored.matches(null,page)&&!restored.matches(scope,null),"missing current identity refused");
        ok(search.forumId.isEmpty()&&search.encode().length==8,"old search factory defaults to global scope in new format");
        PromptDraftState legacy=PromptDraftState.decode(Arrays.copyOf(search.encode(),7));ok(legacy!=null&&legacy.forumId.isEmpty()&&legacy.text.equals(search.text),"old seven-field search restores as global scope");
        for(String forum:new String[]{"","7","-7","123456789012","-123456789012"}){PromptDraftState scoped=PromptDraftState.search(scope,page,"关键词",1,2,forum);PromptDraftState back=scoped==null?null:PromptDraftState.decode(scoped.encode());ok(back!=null&&back.forumId.equals(forum)&&Arrays.equals(scoped.encode(),back.encode()),"explicit valid forum range survives recreation");}
        for(String forum:new String[]{null,"0","-0","01","-01","stid:123","1234567890123","7&tid=1","https://bbs.nga.cn/"," 7","7\n"}){ok(PromptDraftState.search(scope,page,"关键词",0,3,forum)==null,"invalid or collection scope rejected by factory");String[] row=search.encode();row[7]=forum;ok(PromptDraftState.decode(row)==null,"invalid or collection scope rejected on restore");}
        PromptDraftState pageDraft=PromptDraftState.page(scope,page,"2",0,1,3);ok(pageDraft!=null&&pageDraft.forumId.isEmpty(),"page input never carries a search range");
        String[] pollutedPage=pageDraft.encode();pollutedPage[7]="7";ok(PromptDraftState.decode(pollutedPage)==null,"search range cannot be smuggled into a page prompt");
        PromptDraftState oldPage=PromptDraftState.decode(Arrays.copyOf(pageDraft.encode(),7));ok(oldPage!=null&&oldPage.forumId.isEmpty()&&oldPage.totalPages==3,"old seven-field page prompt remains compatible");
        for(String text:new String[]{"","0","001","99999999"})for(int total:new int[]{0,1,99999}){PromptDraftState draft=PromptDraftState.page(scope,page,text,0,text.length(),total);ok(draft!=null&&Arrays.equals(draft.encode(),PromptDraftState.decode(draft.encode()).encode()),"empty or invalid but bounded numeric draft remains editable without submitting");}
        ok(PromptDraftState.search(scope,page,repeat('x',4096),0,4096)!=null,"maximum pasted URL text accepted");ok(PromptDraftState.search(scope,page,repeat('x',4097),0,0)==null,"oversized search state rejected");
        for(Object raw:new Object[]{null,"search",new Object[]{"search",scope,page,"q","0","0","0"},new String[0],new String[6],new String[8],new String[9]})ok(PromptDraftState.decode(raw)==null,"wrong type and shape refused");
        for(int i=0;i<8;i++){String[] row=search.encode();row[i]=null;ok(PromptDraftState.decode(row)==null,"null field refused");}
        for(String[] row:new String[][]{{"upload",scope,page,"1","0","1","0"},{"search",scope,page,"q","0","1","1"},{"page",scope,page,"x","0","1","0"},{"page",scope,page,"123456789","0","1","0"},{"page",scope,page,"１","0","1","0"},{"page",scope,page,"-1","0","1","0"},{"page",scope,page,"1.0","0","1","0"}})ok(PromptDraftState.decode(row)==null,"unknown purpose and malformed page input refused");
        for(String bad:new String[]{"",repeat('a',63),repeat('A',64),"session-cookie",null}){ok(PromptDraftState.search(bad,page,"q",0,1)==null,"scope must be opaque exact digest");ok(PromptDraftState.search(scope,bad,"q",0,1)==null,"navigation key must be opaque exact digest");}
        for(int[] selection:new int[][]{{-1,0},{0,2},{2,0},{Integer.MAX_VALUE,Integer.MAX_VALUE}})ok(PromptDraftState.search(scope,page,"q",selection[0],selection[1])==null,"cursor range cannot escape draft");ok(PromptDraftState.search(scope,page,"query",4,1)!=null,"reverse selection from dragging a handle is retained");
        for(String bad:new String[]{"x\ny","x\u0000y","x\u007fy"})ok(PromptDraftState.search(scope,page,bad,0,0)==null,"control material refused");
        for(String number:new String[]{"-1","100000","999999999999999999","1.5","NaN"}){String[] row=search.encode();row[6]=number;ok(PromptDraftState.decode(row)==null,"malformed or oversized page bound refused");}
        String[] external=search.encode();external[3]="changed";ok(search.text.equals("合成搜索 🌟 NGA"),"exported array cannot mutate immutable state");
        System.out.println("PromptDraftState: "+checks+" bounded unsubmitted-input/session/navigation assertions PASS; no Android/network/device");
    }
}
