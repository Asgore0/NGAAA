package cn.ngapure.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure production policy tests: no Context, accounts, sockets, notifications or private data. */
public final class UnreadNotificationPolicyTest {
    private static int checks;
    private UnreadNotificationPolicyTest() { }
    public static void main(String[] args) {
        transitions(); sameCountEvents(); persistence(); guards(); privacyAndBounds();
        System.out.println("PASS unread notification policy: " + checks + " checks");
    }
    private static void transitions() {
        UnreadNotificationPolicy.Snapshot zero=snapshot(0,0),one=snapshot(1,0),two=snapshot(2,0),privateOne=snapshot(0,1);
        check(!UnreadNotificationPolicy.hasNew(null,zero),"first empty observation stays quiet");
        check(UnreadNotificationPolicy.hasNew(null,one),"first opt-in observation can surface existing unread count");
        check(UnreadNotificationPolicy.hasNew(zero,one),"new reminder count notifies");
        check(UnreadNotificationPolicy.hasNew(one,two),"increased count notifies");
        check(!UnreadNotificationPolicy.hasNew(two,one),"decreased count stays quiet");
        check(!UnreadNotificationPolicy.hasNew(one,one),"unchanged count and event remains quiet");
        check(UnreadNotificationPolicy.hasNew(zero,privateOne),"new unread private conversation notifies");
        check(!UnreadNotificationPolicy.hasNew(privateOne,zero),"reading messages never creates an alert");
        check(UnreadNotificationPolicy.hasNew(8,9,8,10),"compatibility count transition");
        try{UnreadNotificationPolicy.hasNew(-1,0,0,0);throw new AssertionError("negative count accepted");}catch(IllegalArgumentException expected){checks++;}
    }
    private static void sameCountEvents() {
        List<String> first=new ArrayList<>();for(int i=0;i<20;i++)first.add(UnreadNotificationPolicy.conversationEvent(Integer.toString(i+1),2,100));
        UnreadNotificationPolicy.Snapshot full=new UnreadNotificationPolicy.Snapshot(0,20,Collections.emptySet(),first);
        List<String> reordered=new ArrayList<>(first);Collections.reverse(reordered);
        check(!UnreadNotificationPolicy.hasNew(full,new UnreadNotificationPolicy.Snapshot(0,20,Collections.emptySet(),reordered)),"reordering full unread page stays quiet");
        List<String> replaced=new ArrayList<>(first);replaced.remove(19);replaced.add(UnreadNotificationPolicy.conversationEvent("99",1,101));
        check(UnreadNotificationPolicy.hasNew(full,new UnreadNotificationPolicy.Snapshot(0,20,Collections.emptySet(),replaced)),"20-item full page replacement detected despite equal count");
        List<String> morePosts=new ArrayList<>(first);morePosts.set(0,UnreadNotificationPolicy.conversationEvent("1",3,100));
        check(UnreadNotificationPolicy.hasNew(full,new UnreadNotificationPolicy.Snapshot(0,20,Collections.emptySet(),morePosts)),"same conversation receives new post with unchanged timestamp");
        List<String> laterTime=new ArrayList<>(first);laterTime.set(0,UnreadNotificationPolicy.conversationEvent("1",2,101));
        check(UnreadNotificationPolicy.hasNew(full,new UnreadNotificationPolicy.Snapshot(0,20,Collections.emptySet(),laterTime)),"same conversation timestamp advances with unchanged post count");
        check(!UnreadNotificationPolicy.hasNew(full,new UnreadNotificationPolicy.Snapshot(0,19,Collections.emptySet(),first.subList(1,20))),"removing a read conversation stays quiet");
        check(UnreadNotificationPolicy.hasNew(full,new UnreadNotificationPolicy.Snapshot(0,19,Collections.emptySet(),replaced.subList(1,20))),"new event found even when total unread count decreases");
        String a=UnreadNotificationPolicy.reminderEvent(1,"reply","7","8","9",100),b=UnreadNotificationPolicy.reminderEvent(1,"reply","7","8","10",100);
        check(UnreadNotificationPolicy.hasNew(new UnreadNotificationPolicy.Snapshot(1,0,Arrays.asList(a),Collections.emptySet()),new UnreadNotificationPolicy.Snapshot(1,0,Arrays.asList(b),Collections.emptySet())),"replacement reply at equal count detected");
        check(new UnreadNotificationPolicy.Snapshot(0,0,Arrays.asList(a),first).reminderEvents.isEmpty(),"zero unread count never retains stale reminder events");
        check(new UnreadNotificationPolicy.Snapshot(0,0,Arrays.asList(a),first).conversationEvents.isEmpty(),"zero unread count never retains stale message events");
    }
    private static void persistence() {
        String r=UnreadNotificationPolicy.reminderEvent(1,"reply","1","2","3",44),m=UnreadNotificationPolicy.conversationEvent("8",5,66);
        UnreadNotificationPolicy.Snapshot input=new UnreadNotificationPolicy.Snapshot(2,3,Arrays.asList(r),Arrays.asList(m));String encoded=UnreadNotificationPolicy.encode(input);
        UnreadNotificationPolicy.Snapshot restored=UnreadNotificationPolicy.decode(encoded);
        check(restored!=null&&restored.reminders==2&&restored.conversations==3,"disk restart retains counts");
        check(restored.reminderEvents.equals(input.reminderEvents)&&restored.conversationEvents.equals(input.conversationEvents),"disk restart retains exact opaque event baseline");
        check(!UnreadNotificationPolicy.hasNew(restored,input),"cold restart of same unread state never repeats alert");
        check(UnreadNotificationPolicy.encode(restored).equals(encoded),"serialization stable");
        for(String raw:new String[]{null,"","1","2\n0\n0\n","1\n-1\n0\n","1\n0\n1000001\n","1\n00\n0\n","1\n0\n0\nmessage body\n","1\n0\n0\nm"+m+"\n","1\n1\n0\nr"+r+"\nr"+r+"\n",encoded+"tail"})check(UnreadNotificationPolicy.decode(raw)==null,"corrupt disk snapshot discarded");
    }
    private static void guards() {
        check(UnreadNotificationPolicy.allows(true,true,false,true,7,7),"current enabled permitted session allowed");
        check(!UnreadNotificationPolicy.allows(false,true,false,true,7,7),"disabled while read in-flight suppresses late delivery");
        check(!UnreadNotificationPolicy.allows(true,false,false,true,7,7),"permission revoked while read in-flight suppresses delivery");
        check(!UnreadNotificationPolicy.allows(true,true,true,true,7,7),"onStopJob cancellation suppresses delivery");
        check(!UnreadNotificationPolicy.allows(true,true,false,false,7,7),"cookie/account/host changed suppresses delivery");
        check(!UnreadNotificationPolicy.allows(true,true,false,true,7,8),"disable-enable session generation prevents stale delivery");
        check(!UnreadNotificationPolicy.allows(true,true,false,true,8,7),"superseded generation never accepted");
    }
    private static void privacyAndBounds() {
        String a=UnreadNotificationPolicy.scope("bbs.nga.cn","12345"),b=UnreadNotificationPolicy.scope("ngabbs.com","12345"),c=UnreadNotificationPolicy.scope("bbs.nga.cn","54321");
        check(a.matches("[0-9a-f]{64}")&&!a.equals(b)&&!a.equals(c),"host/account snapshots isolated without raw identifiers");
        String token=UnreadNotificationPolicy.conversationEvent("12345",6,700);check(token.matches("[0-9a-f]{64}"),"event stores opaque digest only");
        check(!UnreadNotificationPolicy.conversationEvent("12",34,5).equals(UnreadNotificationPolicy.conversationEvent("123",4,5)),"length prefix prevents ambiguous field concatenation");
        check(UnreadNotificationPolicy.bounded(Long.MAX_VALUE)==1000000&&UnreadNotificationPolicy.bounded(-1)==0,"untrusted counts bounded");
        List<String> source=new ArrayList<>(Arrays.asList(token));UnreadNotificationPolicy.Snapshot safe=new UnreadNotificationPolicy.Snapshot(0,1,Collections.emptySet(),source);source.clear();check(safe.conversationEvents.size()==1,"snapshot defensively copies source");
        try{safe.conversationEvents.clear();throw new AssertionError("mutable snapshot");}catch(UnsupportedOperationException expected){checks++;}
        try{new UnreadNotificationPolicy.Snapshot(0,1,Collections.emptySet(),Arrays.asList("message body"));throw new AssertionError("plaintext accepted as token");}catch(IllegalArgumentException expected){checks++;}
        List<String> tooMany=new ArrayList<>();for(int i=0;i<201;i++)tooMany.add(UnreadNotificationPolicy.conversationEvent(Integer.toString(i),1,1));
        try{new UnreadNotificationPolicy.Snapshot(0,201,Collections.emptySet(),tooMany);throw new AssertionError("unbounded state");}catch(IllegalArgumentException expected){checks++;}
        check(!safe.toString().contains(token),"diagnostics omit event fingerprints");
    }
    private static UnreadNotificationPolicy.Snapshot snapshot(int r,int m){return new UnreadNotificationPolicy.Snapshot(r,m,Collections.emptySet(),Collections.emptySet());}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
