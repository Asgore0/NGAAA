package cn.ngapure.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Immutable unread observations. Persistence contains counts and one-way event digests only. */
public final class UnreadNotificationPolicy {
    private static final int LIMIT=200;
    private UnreadNotificationPolicy() { }
    public static final class Snapshot {
        public final int reminders,conversations;
        public final Set<String> reminderEvents,conversationEvents;
        public Snapshot(int reminderCount,int conversationCount,Collection<String> reminderTokens,Collection<String> conversationTokens) {
            if(reminderCount<0||conversationCount<0)throw new IllegalArgumentException("未读数量无效");
            reminders=bounded(reminderCount);conversations=bounded(conversationCount);
            reminderEvents=events(reminders>0?reminderTokens:Collections.emptySet());
            conversationEvents=events(conversations>0?conversationTokens:Collections.emptySet());
        }
        @Override public String toString(){return "UnreadNotificationPolicy.Snapshot(counts and opaque digests)";}
    }
    private static Set<String> events(Collection<String> input) {
        if(input==null)throw new IllegalArgumentException("未读事件缺失");
        TreeSet<String> values=new TreeSet<>();
        for(String token:input){if(token==null||!token.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("未读事件格式无效");values.add(token);if(values.size()>LIMIT)throw new IllegalArgumentException("未读事件过多");}
        return Collections.unmodifiableSet(values);
    }
    public static boolean hasNew(int previousReminders,int previousConversations,int reminders,int conversations) {
        if(previousReminders<0||previousConversations<0||reminders<0||conversations<0)throw new IllegalArgumentException("未读数量无效");
        return reminders>previousReminders||conversations>previousConversations;
    }
    public static boolean hasNew(Snapshot previous,Snapshot current) {
        if(current==null)throw new IllegalArgumentException("未读快照缺失");
        if(previous==null)return current.reminders>0||current.conversations>0;
        return hasNew(previous.reminders,previous.conversations,current.reminders,current.conversations)
            ||!previous.reminderEvents.containsAll(current.reminderEvents)||!previous.conversationEvents.containsAll(current.conversationEvents);
    }
    /** No subject, body, display name, cookie or plaintext identifier is retained. */
    public static String reminderEvent(int type,String category,String uid,String tid,String pid,long timestamp) {
        return digest("reminder",Integer.toString(type),category,uid,tid,pid,Long.toString(Math.max(0,timestamp)));
    }
    public static String conversationEvent(String mid,int posts,long timestamp) {
        return digest("conversation",mid,Integer.toString(bounded(posts)),Long.toString(Math.max(0,timestamp)));
    }
    public static String scope(String host,String account){return digest("scope",host,account);}
    private static String digest(String... parts) {
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            for(String value:parts){String item=value==null?"":value;if(item.length()>65536)throw new IllegalArgumentException("事件标识过长");byte[] bytes=item.getBytes(StandardCharsets.UTF_8);hash.update((byte)(bytes.length>>>24));hash.update((byte)(bytes.length>>>16));hash.update((byte)(bytes.length>>>8));hash.update((byte)bytes.length);hash.update(bytes);}
            StringBuilder out=new StringBuilder(64);for(byte value:hash.digest()){out.append(Character.forDigit((value>>>4)&15,16)).append(Character.forDigit(value&15,16));}return out.toString();
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static String encode(Snapshot snapshot) {
        if(snapshot==null)throw new IllegalArgumentException("未读快照缺失");
        StringBuilder value=new StringBuilder("1\n").append(snapshot.reminders).append('\n').append(snapshot.conversations).append('\n');
        for(String token:snapshot.reminderEvents)value.append('r').append(token).append('\n');for(String token:snapshot.conversationEvents)value.append('m').append(token).append('\n');return value.toString();
    }
    /** Corrupt or oversized disk state is discarded; no text is interpreted as identifiers or code. */
    public static Snapshot decode(String raw) {
        if(raw==null||raw.length()>28000)return null;
        String[] lines=raw.split("\n",-1);if(lines.length<4||!lines[0].equals("1")||!lines[lines.length-1].isEmpty())return null;
        try {
            if(!lines[1].matches("0|[1-9][0-9]{0,6}")||!lines[2].matches("0|[1-9][0-9]{0,6}"))return null;
            int reminders=Integer.parseInt(lines[1]),conversations=Integer.parseInt(lines[2]);if(reminders>1000000||conversations>1000000)return null;
            Set<String> r=new TreeSet<>(),m=new TreeSet<>();
            for(int i=3;i<lines.length-1;i++){String item=lines[i];if(!item.matches("[rm][0-9a-f]{64}"))return null;boolean added=(item.charAt(0)=='r'?r:m).add(item.substring(1));if(!added||r.size()>LIMIT||m.size()>LIMIT)return null;}
            if(reminders==0&&!r.isEmpty()||conversations==0&&!m.isEmpty())return null;
            return new Snapshot(reminders,conversations,r,m);
        }catch(IllegalArgumentException invalid){return null;}
    }
    /** Final callbacks are accepted only while the same session/settings generation still owns the read. */
    public static boolean allows(boolean enabled,boolean permission,boolean canceled,boolean sameSession,long expectedGeneration,long generation) {
        return enabled&&permission&&!canceled&&sameSession&&expectedGeneration==generation;
    }
    public static int bounded(long value) { return (int)Math.max(0,Math.min(1000000,value)); }
}
