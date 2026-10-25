package cn.ngapure.app;

import java.util.List;

/** Bounded, account-scoped list position. No topic content or credentials are persisted. */
final class ForumListAnchor {
    final String scope,route,parent,topicId;
    final int page,index,top;
    private ForumListAnchor(String scope,String route,String parent,int page,String topicId,int index,int top){
        this.scope=scope;this.route=route;this.parent=parent;this.page=page;this.topicId=topicId;this.index=index;this.top=top;
    }
    static ForumListAnchor create(String scope,String route,String parent,int page,String topicId,int index,int top){
        if(scope==null||!scope.matches("[0-9a-f]{64}")||!ForumRoute.isKey(route)||parent==null||(!parent.isEmpty()&&!ForumRoute.isForumId(parent))||page<1||page>99999||topicId==null||index< -1||index>100000||top< -10000000||top>10000000)return null;
        if(index==-1?!topicId.isEmpty():!topicId.matches("[1-9][0-9]{0,17}"))return null;
        return new ForumListAnchor(scope,route,parent,page,topicId,index,top);
    }
    boolean matches(String currentScope,String currentRoute,String currentParent,int currentPage){return scope.equals(currentScope)&&route.equals(currentRoute)&&parent.equals(currentParent)&&page==currentPage;}
    /** Prefer identity after fresh data/filter changes; a removed topic falls back near its former index. */
    int position(List<String> ids,int headers){
        if(index<0)return 0;
        int found=ids.indexOf(topicId);return Math.max(0,headers)+(found>=0?found:Math.min(index,Math.max(0,ids.size()-1)));
    }
    String[] encode(){return new String[]{"1",scope,route,parent,Integer.toString(page),topicId,Integer.toString(index),Integer.toString(top)};}
    static ForumListAnchor decode(Object raw){
        if(!(raw instanceof String[])||((String[])raw).length!=8)return null;String[] values=(String[])raw;
        if(!"1".equals(values[0]))return null;
        try{return create(values[1],values[2],values[3],Integer.parseInt(values[4]),values[5],Integer.parseInt(values[6]),Integer.parseInt(values[7]));}catch(RuntimeException malformed){return null;}
    }
}
