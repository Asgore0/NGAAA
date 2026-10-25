package cn.ngapure.app;

/** Only unsubmitted local input, bound to an exact session and navigation identity. */
final class PromptDraftState {
    static final int SEARCH_LIMIT=4096,KEYWORD_LIMIT=100,PAGE_INPUT_LIMIT=8;
    final String purpose,scope,pageKey,text,forumId;
    final int selectionStart,selectionEnd,totalPages;
    private PromptDraftState(String purpose,String scope,String pageKey,String text,int start,int end,int total,String forumId){this.purpose=purpose;this.scope=scope;this.pageKey=pageKey;this.text=text;selectionStart=start;selectionEnd=end;totalPages=total;this.forumId=forumId;}
    static PromptDraftState search(String scope,String pageKey,String text,int start,int end){return search(scope,pageKey,text,start,end,"");}
    static PromptDraftState search(String scope,String pageKey,String text,int start,int end,String forumId){return create("search",scope,pageKey,text,start,end,0,forumId);}
    static PromptDraftState page(String scope,String pageKey,String text,int start,int end,int total){return create("page",scope,pageKey,text,start,end,total,"");}
    boolean matches(String currentScope,String currentPageKey){return scope.equals(currentScope)&&pageKey.equals(currentPageKey);}
    String[] encode(){return new String[]{purpose,scope,pageKey,text,Integer.toString(selectionStart),Integer.toString(selectionEnd),Integer.toString(totalPages),forumId};}
    static PromptDraftState decode(Object raw){
        if(!(raw instanceof String[])||(((String[])raw).length!=7&&((String[])raw).length!=8))return null;String[] values=(String[])raw;
        try{return create(values[0],values[1],values[2],values[3],Integer.parseInt(values[4]),Integer.parseInt(values[5]),Integer.parseInt(values[6]),values.length==8?values[7]:"");}catch(RuntimeException malformed){return null;}
    }
    private static PromptDraftState create(String purpose,String scope,String pageKey,String text,int start,int end,int total,String forumId){
        boolean search="search".equals(purpose),page="page".equals(purpose);
        if(!(search||page)||!digest(scope)||!digest(pageKey)||!text(text,search?SEARCH_LIMIT:PAGE_INPUT_LIMIT)||start<0||end<0||start>text.length()||end>text.length()||total<0||total>NavigationState.MAX_PAGE||search&&total!=0||page&&!text.matches("[0-9]*")||forumId==null||!forumId.isEmpty()&&!ForumRoute.isForumId(forumId)||page&&!forumId.isEmpty())return null;
        return new PromptDraftState(purpose,scope,pageKey,text,start,end,total,forumId);
    }
    private static boolean digest(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static boolean text(String value,int limit){return value!=null&&value.length()<=limit&&!value.matches("(?s).*[\\x00-\\x1f\\x7f].*");}
}
