package cn.ngapure.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, process-only data for returning to an existing forum screen. Never stores credentials or views. */
final class ForumPageSnapshots<K> {
    static final int CAPACITY = 4, MAX_TOPICS = 128, MAX_SUBFORUMS = 64, MAX_CHARS = 200000;
    static final long TTL_MS = 120000;
    private final LinkedHashMap<Identity<K>, Snapshot> entries = new LinkedHashMap<>(8, .75f, true);
    private int retainedChars;

    private static final class Identity<T> {
        final T owner;
        Identity(T owner) { this.owner = owner; }
        @Override public int hashCode() { return System.identityHashCode(owner); }
        @Override public boolean equals(Object other) { return other instanceof Identity && ((Identity<?>) other).owner == owner; }
    }
    private static final class Snapshot {
        final String scope, route, parent;
        final int page, chars;
        final long loadedAt;
        final NgaApi.ForumPage data;
        Snapshot(String scope, String route, String parent, int page, long loadedAt, NgaApi.ForumPage data, int chars) {
            this.scope = scope; this.route = route; this.parent = parent; this.page = page;
            this.loadedAt = loadedAt; this.data = data; this.chars = chars;
        }
        boolean expired(long now) { return now < loadedAt || now - loadedAt >= TTL_MS; }
    }
    private static final class TooLarge extends RuntimeException { private static final long serialVersionUID = 1L; }
    private static final class Budget {
        int chars;
        String text(String value) {
            String safe = value == null ? "" : value;
            if (safe.length() > MAX_CHARS - chars) throw new TooLarge();
            chars += safe.length(); return safe;
        }
        List<String> strings(List<String> values) {
            if (values == null || values.size() > 64) throw new TooLarge();
            List<String> copy = new ArrayList<>(values.size());
            for (String value : values) copy.add(text(value));
            return copy;
        }
    }

    boolean put(K owner, String scope, String route, String parent, int page, NgaApi.ForumPage data, long now) {
        remove(owner); prune(now);
        if (owner == null || scope == null || scope.isEmpty() || route == null || parent == null || page < 1
                || now < 0 || data == null || !route.equals(data.key) || page != data.page) return false;
        try {
            Budget budget = new Budget();
            budget.text(scope); budget.text(route); budget.text(parent);
            NgaApi.ForumPage copy = copy(data, budget);
            entries.put(new Identity<>(owner), new Snapshot(scope, route, parent, page, now, copy, budget.chars));
            retainedChars += budget.chars;
            Iterator<Snapshot> iterator = entries.values().iterator();
            while ((entries.size() > CAPACITY || retainedChars > MAX_CHARS) && iterator.hasNext()) {
                retainedChars -= iterator.next().chars; iterator.remove();
            }
            return entries.containsKey(new Identity<>(owner));
        } catch (TooLarge invalid) { return false; }
    }
    NgaApi.ForumPage get(K owner, String scope, String route, String parent, int page, long now) {
        prune(now); Snapshot snapshot = entries.get(new Identity<>(owner));
        if (snapshot == null) return null;
        if (!snapshot.scope.equals(scope) || !snapshot.route.equals(route) || !snapshot.parent.equals(parent) || snapshot.page != page) {
            remove(owner); return null;
        }
        // Reads do not renew loadedAt. Rendering and its callbacks never mutate stored data.
        return copy(snapshot.data, new Budget());
    }
    void remove(K owner) {
        Snapshot removed = entries.remove(new Identity<>(owner)); if (removed != null) retainedChars -= removed.chars;
    }
    void retain(Collection<K> owners, long now) {
        prune(now);
        Iterator<Map.Entry<Identity<K>, Snapshot>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Identity<K>, Snapshot> entry = iterator.next(); boolean found = false;
            if (owners != null) for (K owner : owners) if (entry.getKey().owner == owner) { found = true; break; }
            if (!found) { retainedChars -= entry.getValue().chars; iterator.remove(); }
        }
    }
    private void prune(long now) {
        Iterator<Snapshot> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Snapshot snapshot = iterator.next();
            if (snapshot.expired(now)) { retainedChars -= snapshot.chars; iterator.remove(); }
        }
    }
    void clear() { entries.clear(); retainedChars = 0; }
    int size() { return entries.size(); }
    int chars() { return retainedChars; }

    private static NgaApi.ForumPage copy(NgaApi.ForumPage source, Budget budget) {
        if (source.topics == null || source.subForums == null || source.topics.size() > MAX_TOPICS
                || source.subForums.size() > MAX_SUBFORUMS) throw new TooLarge();
        NgaApi.ForumPage result = new NgaApi.ForumPage();
        result.key = budget.text(source.key); result.fid = budget.text(source.fid);
        result.stid = budget.text(source.stid); result.name = budget.text(source.name); result.page = source.page;
        for (NgaApi.Topic topic : source.topics) {
            if (topic == null) throw new TooLarge();
            NgaApi.Topic next = new NgaApi.Topic();
            next.tid = budget.text(topic.tid); next.title = budget.text(topic.title); next.author = budget.text(topic.author);
            next.authorId = budget.text(topic.authorId); next.time = budget.text(topic.time); next.forum = budget.text(topic.forum);
            next.fid = budget.text(topic.fid); next.image = budget.text(topic.image); next.avatar = budget.text(topic.avatar);
            next.replies = topic.replies; next.votes = topic.votes == null ? new VoteMetrics() : topic.votes.copy();
            result.topics.add(next);
        }
        for (NgaApi.Forum forum : source.subForums) {
            if (forum == null) throw new TooLarge();
            NgaApi.Forum next = new NgaApi.Forum();
            next.key = budget.text(forum.key); next.fid = budget.text(forum.fid); next.stid = budget.text(forum.stid);
            next.parentFid = budget.text(forum.parentFid); next.name = budget.text(forum.name); next.category = budget.text(forum.category);
            next.description = budget.text(forum.description); next.icon = budget.text(forum.icon);
            next.group = budget.text(forum.group); next.searchAliases = budget.text(forum.searchAliases);
            next.aliases = budget.strings(forum.aliases); next.categoryNames = budget.strings(forum.categoryNames);
            next.groupNames = budget.strings(forum.groupNames); result.subForums.add(next);
        }
        return result;
    }
}
