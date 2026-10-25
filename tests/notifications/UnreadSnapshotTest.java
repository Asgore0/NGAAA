package cn.ngapure.app;

/** Exercises the actual production API-model-to-event adapter; never constructs Context or uses a transport. */
public final class UnreadSnapshotTest {
    private static int checks;
    private UnreadSnapshotTest() { }
    public static void main(String[] args) {
        NativeCommunity.ReminderPage reminders=new NativeCommunity.ReminderPage();reminders.unread=1;
        NativeCommunity.Reminder reply=new NativeCommunity.Reminder();reply.tid="1001";reply.pid="2001";reply.timestamp=3001;reply.title="synthetic title must never be persisted";reminders.items.add(reply);
        NativeCommunity.ConversationPage messages=new NativeCommunity.ConversationPage();
        for(int i=0;i<20;i++){NativeCommunity.Conversation item=new NativeCommunity.Conversation();item.mid=Integer.toString(4001+i);item.posts=1;item.timestamp=5001+i;item.unread=true;item.subject="synthetic private subject";messages.items.add(item);}
        UnreadNotificationPolicy.Snapshot first=NativeNotifications.snapshot(reminders,messages);
        check(first.reminders==1&&first.conversations==20,"aggregate reminders and first-page unread conversations");
        check(first.reminderEvents.size()==1,"legacy raw3 reminder without per-row flag tracked while aggregate unread positive");
        check(!UnreadNotificationPolicy.encode(first).contains(reply.title)&&!UnreadNotificationPolicy.encode(first).contains("synthetic private"),"titles and message bodies never stored");
        messages.items.get(0).posts++;
        check(UnreadNotificationPolicy.hasNew(first,NativeNotifications.snapshot(reminders,messages)),"actual adapter detects post count update with same count");
        messages.items.get(0).posts--;messages.items.get(0).timestamp++;
        check(UnreadNotificationPolicy.hasNew(first,NativeNotifications.snapshot(reminders,messages)),"actual adapter detects timestamp update with same count");
        messages.items.get(0).timestamp--;messages.items.get(19).mid="9999";
        check(UnreadNotificationPolicy.hasNew(first,NativeNotifications.snapshot(reminders,messages)),"actual adapter detects replacement on full page");
        messages.items.get(19).mid="4020";messages.items.get(0).unread=false;
        check(!UnreadNotificationPolicy.hasNew(first,NativeNotifications.snapshot(reminders,messages)),"reading conversation does not notify");
        reminders.unread=0;check(NativeNotifications.snapshot(reminders,messages).reminderEvents.isEmpty(),"read reminders not retained");
        reminders.unread=1;reply.unread=true;NativeCommunity.Reminder read=new NativeCommunity.Reminder();read.tid="1002";read.pid="2002";read.timestamp=3002;reminders.items.add(read);
        check(NativeNotifications.snapshot(reminders,messages).reminderEvents.size()==1,"explicit unread flags exclude read rows");
        System.out.println("PASS unread production snapshot adapter: "+checks+" checks");
    }
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
