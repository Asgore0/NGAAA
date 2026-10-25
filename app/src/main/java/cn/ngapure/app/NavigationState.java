package cn.ngapure.app;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Bounded navigation identities only: no page content, session objects or authorization state. */
final class NavigationState {
    static final int VERSION=1,MAX_BACK=32,MAX_PAGE=99999,MAX_SCROLL=10000000,COLUMNS=10;
    static final class Entry {
        final String kind,id,title,action,fid,tid,pid,extra;
        final int page,scrollY;
        private Entry(String kind,String id,String title,int page,String action,String fid,String tid,String pid,String extra,int scrollY){
            this.kind=kind;this.id=id;this.title=title;this.page=page;this.action=action;this.fid=fid;this.tid=tid;this.pid=pid;this.extra=extra;this.scrollY=scrollY;
        }
    }
    static final class Snapshot {
        final Entry current;
        /** Nearest previous page first, matching ArrayDeque.pop(). */
        final List<Entry> back;
        Snapshot(Entry current,List<Entry> back){this.current=current;this.back=Collections.unmodifiableList(new ArrayList<>(back));}
    }
    private NavigationState(){}
    static int tab(int value,int fallback){return value>=0&&value<4?value:fallback;}
    static int offset(int value){return Math.max(0,Math.min(MAX_SCROLL,value));}
    /** -1 on a frame means it was saved before a scroll container existed. */
    static boolean locatePost(String pid,Integer savedOffset){return pid!=null&&!pid.isEmpty()&&savedOffset==null;}
    static Snapshot restoreOrResumeAuthorization(Snapshot saved,boolean pendingAuthorization){
        // An explicit saved auth screen can be a new account attempt while an old account remains logged in.
        if(saved!=null)return saved;
        if(!pendingAuthorization)return null;
        Entry home=entry("home","","NGA",1,"","","","","",-1);
        return new Snapshot(entry("auth","","登录 NGA",1,"","","","","",-1),Collections.singletonList(home));
    }
    static String title(String raw){String value=raw==null?"":raw.replaceAll("[\\x00-\\x1f\\x7f]"," ");return value.length()>200?value.substring(0,200):value;}
    static Entry entry(String kind,String id,String title,int page,String action,String fid,String tid,String pid,String extra,int y){
        if(!text(kind,24)||!text(id,4096)||!text(title,200)||!text(action,12)||!text(fid,13)||!text(tid,18)||!text(pid,18)||!text(extra,18)||page<1||page>MAX_PAGE||y< -1||y>MAX_SCROLL)return null;
        boolean editor="editor".equals(kind),thread="thread".equals(kind),search="search".equals(kind);
        if(!action.isEmpty()&&!editor)return null;
        if(!fid.isEmpty()&&(!(editor||thread)||!forumId(fid)))return null;
        if(!tid.isEmpty()&&(!(editor||thread)||!positive(tid)))return null;
        if(!pid.isEmpty()&&(!(editor||thread)||!("0".equals(pid)||positive(pid))))return null;
        if(!extra.isEmpty()&&!(thread?positive(extra):(search||"forum".equals(kind))&&forumId(extra)))return null;
        if(thread&&!extra.isEmpty()&&!pid.isEmpty()&&!"0".equals(pid))return null;
        if("forum".equals(kind)){if(!ForumRoute.isKey(id))return null;}
        else if(thread){if(!positive(id)||(!tid.isEmpty()&&!tid.equals(id)))return null;}
        else if("conversation".equals(kind)||"user".equals(kind)||"userreplies".equals(kind)||"serverfav".equals(kind)){if(!positive(id))return null;}
        else if("image".equals(kind)){if(page!=1||!imageUrl(id))return null;}
        else if(search){if(id.trim().isEmpty()||id.length()>100)return null;}
        else if(editor){
            if(!id.isEmpty()||page!=1)return null;
            if("new".equals(action)){if(!forumId(fid)||!tid.isEmpty()||!pid.isEmpty())return null;}
            else if("reply".equals(action)||"quote".equals(action)||"modify".equals(action)){if(!positive(tid))return null;}
            else return null;
        }else if("inbox".equals(kind)||"following".equals(kind)||"self".equals(kind)||"selfreplies".equals(kind)){if(!id.isEmpty())return null;}
        else if("home".equals(kind)||"settings".equals(kind)||"profile".equals(kind)||"saved".equals(kind)||"history".equals(kind)||"drafts".equals(kind)||"auth".equals(kind)||"reminders".equals(kind)){
            if(!id.isEmpty()||page!=1)return null;
        }else return null;
        // Message subjects need not be retained just to return to a known conversation.
        if("conversation".equals(kind))title="私信会话";
        return new Entry(kind,id,title,page,action,fid,tid,pid,extra,y);
    }
    static String[] encode(Entry entry){return new String[]{entry.kind,entry.id,entry.title,Integer.toString(entry.page),entry.action,entry.fid,entry.tid,entry.pid,entry.extra,Integer.toString(entry.scrollY)};}
    static Entry decode(Object raw){
        if(!(raw instanceof String[]))return null;String[] values=(String[])raw;if(values.length!=COLUMNS)return null;
        try{return entry(values[0],values[1],values[2],Integer.parseInt(values[3]),values[4],values[5],values[6],values[7],values[8],Integer.parseInt(values[9]));}
        catch(RuntimeException malformed){return null;}
    }
    static Snapshot decode(Object current,List<?> nearestFirst){
        Entry top=decode(current);if(top==null)return null;List<Entry> restored=new ArrayList<>();
        if(nearestFirst!=null)for(int i=0;i<Math.min(nearestFirst.size(),MAX_BACK);i++){Entry previous=decode(nearestFirst.get(i));if(previous!=null)restored.add(previous);}
        return new Snapshot(top,restored);
    }
    static boolean imageUrl(String value){
        if(!text(value,4096))return false;
        try{URI uri=new URI(value);String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT),path=uri.getPath();
            return "https".equalsIgnoreCase(uri.getScheme())&&uri.getUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443)
                    &&(host.equals("img.nga.cn")||host.equals("img4.nga.cn")||host.equals("img8.nga.cn")||host.equals("img.nga.178.com")||host.equals("img4.nga.178.com")||host.equals("img.ngacn.cc"))
                    &&path!=null&&path.startsWith("/attachments/")&&uri.normalize().getPath().startsWith("/attachments/")&&!path.contains("/../")&&!path.contains("/./")&&!path.endsWith("/..")&&!path.endsWith("/.")&&text(path,4096);
        }catch(Exception malformed){return false;}
    }
    private static boolean text(String value,int maximum){return value!=null&&value.length()<=maximum&&!value.matches("(?s).*[\\x00-\\x1f\\x7f].*");}
    private static boolean positive(String value){return value.matches("[1-9][0-9]{0,17}");}
    private static boolean forumId(String value){return value.matches("-?[1-9][0-9]{0,11}");}
}
