package cn.ngapure.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/** Real pure directory/metadata presentation policy; never creates an Android view. */
public final class CommunityPresentationTest {
    private static int checks;
    private static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static NativeCommunity.Folder folder(String id,String name){NativeCommunity.Folder result=new NativeCommunity.Folder();result.id=id;result.name=name;return result;}
    private static int selected(List<NativeCommunityView.FolderChoice> values){int count=0;for(NativeCommunityView.FolderChoice item:values)if(item.selected)count++;return count;}
    public static void main(String[] args)throws Exception {
        List<NativeCommunityView.FolderChoice> empty=NativeCommunityView.folderChoices("1",Collections.emptyList());
        ok(empty.size()==1&&empty.get(0).selected&&empty.get(0).title.equals("默认收藏夹"),"empty directory still exposes the selected default folder");
        List<NativeCommunity.Folder> rows=new ArrayList<>(Arrays.asList(folder("1","server duplicate default"),folder("71001"," 游戏 "),folder("71002","工作"),folder("71001","duplicate"),folder("0","invalid"),null));
        List<NativeCommunityView.FolderChoice> choices=NativeCommunityView.folderChoices("71001",rows);
        ok(choices.size()==3&&selected(choices)==1,"directory duplicates cannot duplicate the selected state");
        ok(choices.get(0).id.equals("1")&&!choices.get(0).selected,"default always remains the first reachable choice");
        ok(choices.get(1).id.equals("71001")&&choices.get(1).selected&&choices.get(1).title.equals("游戏"),"custom folder selection carries the real server name");
        ok(choices.get(2).id.equals("71002")&&!choices.get(2).selected,"other folders remain distinct choices");
        ok(rows.size()==6&&rows.get(1).name.equals(" 游戏 "),"presentation never mutates the protocol directory");
        choices=NativeCommunityView.folderChoices("71003",Collections.singletonList(folder("71002","另一页目录")));
        ok(choices.size()==3&&choices.get(1).id.equals("71003")&&choices.get(1).selected&&choices.get(1).title.equals("当前收藏夹"),"selection outside the current directory page remains visible");
        ok(selected(choices)==1,"paged directory still has exactly one selected choice");
        choices=NativeCommunityView.folderChoices("71003",Collections.emptyList());
        ok(choices.size()==2&&choices.get(1).selected,"directory failure can retain the current folder without inventing an empty selection");
        choices=NativeCommunityView.folderChoices("script",Collections.singletonList(folder("71002","")));
        ok(choices.get(0).selected&&choices.get(1).title.equals("未命名收藏夹"),"invalid route falls back to default and unnamed folder has a useful label");
        choices=NativeCommunityView.folderChoices("71002",Collections.singletonList(folder("71002",null)));
        ok(choices.get(1).selected&&choices.get(1).title.equals("未命名收藏夹"),"missing server label does not lose selection");
        ok(NativeCommunityView.favoritePid("92001").equals("92001"),"reply bookmark passes its validated post locator");
        ok(NativeCommunityView.favoritePid("0").equals("0")&&NativeCommunityView.favoritePid("").isEmpty(),"main-topic locator keeps official zero or absent semantics");
        for(String pid:new String[]{"-1","1&tid=2","1.0","not-a-post","9999999999999999999"})ok(NativeCommunityView.favoritePid(pid).isEmpty(),"invalid post locator never becomes a navigation target");
        final int[] called={0};NativeCommunityView.Listener legacy=new NativeCommunityView.Listener(){public void navigate(String kind,String id,String title,int page){}public void topic(String tid,String title){called[0]++;}public void user(String uid){}public void login(){}};
        legacy.topic("91001","92001","合成主题");ok(called[0]==1,"new post-aware callback remains compatible with old listeners");
        final String[] opened={"",""};final int[] replyCalls={0},headCalls={0};
        NativeCommunityView.Listener profileListener=new NativeCommunityView.Listener(){
            public void navigate(String kind,String id,String title,int page){}
            public void topic(String tid,String title){headCalls[0]++;}
            @Override public void topic(String tid,String pid,String title){replyCalls[0]++;opened[0]=tid;opened[1]=pid;}
            public void user(String uid){}public void login(){}
        };
        NativeCommunity.Topic reply=new NativeCommunity.Topic();reply.tid="91001";reply.pid="92003";reply.title="合成回复所在主题";
        ok(NativeCommunityView.openTopic(profileListener,reply,false,true)&&replyCalls[0]==1&&headCalls[0]==0&&opened[0].equals("91001")&&opened[1].equals("92003"),"profile reply opens exact nonzero post instead of its topic head");
        for(String missing:new String[]{"","0","-1","1&tid=2","9999999999999999999"}){
            reply.pid=missing;ok(!NativeCommunityView.openTopic(profileListener,reply,false,true)&&replyCalls[0]==1&&headCalls[0]==0,"missing or invalid reply locator never falls back to opening the topic head");
        }
        reply.pid="0";ok(NativeCommunityView.openTopic(profileListener,reply,true,false)&&replyCalls[0]==2&&opened[1].equals("0"),"topic bookmarks retain their explicit official zero locator");
        ok(NativeCommunityView.openTopic(profileListener,reply,false,false)&&headCalls[0]==1,"authored topics remain ordinary topic navigation");
        ok(NativeCommunityView.profileRoute(true,false).equals("self")&&NativeCommunityView.profileRoute(false,false).equals("user"),"topics preserve existing own and public profile route identities");
        ok(NativeCommunityView.profileRoute(true,true).equals("selfreplies")&&NativeCommunityView.profileRoute(false,true).equals("userreplies"),"reply pagination has distinct own and public profile identities");
        for(String kind:new String[]{"self","user","selfreplies","userreplies"})ok(NativeCommunityView.profileKind(kind),"profile sections share native profile rendering");
        ok(NativeCommunityView.profileReplies("selfreplies")&&NativeCommunityView.profileReplies("userreplies")&&!NativeCommunityView.profileReplies("user")&&!NativeCommunityView.profileKind("serverfav"),"only reply sections request reply records");
        ok(NativeCommunityView.voteSummary(null).equals("点赞 — · 点踩 —"),"both missing totals remain visibly unknown");
        ok(NativeCommunityView.voteSummary(new VoteMetrics()).equals("点赞 — · 点踩 —"),"default metrics do not invent zero likes or dislikes");
        VoteMetrics score=VoteMetrics.topicFrom(new JSONObject().put("recommend",3));
        ok(!score.likesKnown&&!score.dislikesKnown&&!score.scoreKnown,"topic scalar has no proven like/dislike or net-score meaning");
        ok(NativeCommunityView.voteSummary(score).equals("点赞 — · 点踩 —"),"topic scalar must not be shown as net score");
        VoteMetrics recommended=VoteMetrics.topicFrom(new JSONObject().put("recommend","10"));
        ok(!recommended.likesKnown&&!recommended.scoreKnown&&NativeCommunityView.voteSummary(recommended).equals("点赞 — · 点踩 —"),"string recommendation level also remains unknown in topic UI");
        VoteMetrics explicit=VoteMetrics.topicFrom(new JSONObject().put("recommend","2,7,5"));
        ok(explicit.likesKnown&&explicit.dislikesKnown&&explicit.likes==7&&explicit.dislikes==5,"only explicit topic tuple exposes positive and negative counts");
        ok(NativeCommunityView.voteSummary(explicit).equals("点赞 7 · 点踩 5"),"explicit up/down tuple presents both independent counts");
        VoteMetrics zero=VoteMetrics.topicFrom(new JSONObject().put("recommend","0,0,0"));
        ok(NativeCommunityView.voteSummary(zero).equals("点赞 0 · 点踩 0"),"confirmed zero remains different from unavailable totals");
        VoteMetrics malformed=VoteMetrics.topicFrom(new JSONObject().put("recommend","2,not-a-count,5"));
        ok(NativeCommunityView.voteSummary(malformed).equals("点赞 — · 点踩 —"),"malformed counts never masquerade as independent totals");
        VoteMetrics negative=VoteMetrics.topicFrom(new JSONObject().put("recommend","0,2,5"));
        ok(NativeCommunityView.voteSummary(negative).equals("点赞 2 · 点踩 5"),"more negative than positive votes still shows both totals");
        VoteMetrics scalarPost=VoteMetrics.from(new JSONObject().put("recommend",-3));
        ok(scalarPost.scoreKnown&&NativeCommunityView.voteSummary(scalarPost).equals("点赞 — · 点踩 —"),"post net scalar never leaks through shared presentation fallback");
        System.out.println("CommunityPresentation: "+checks+" directory/metadata assertions PASS; no Android UI, device or network");
    }
}
