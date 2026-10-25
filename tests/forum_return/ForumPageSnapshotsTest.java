package cn.ngapure.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/** Real production models and cache, with deterministic time; no Android runtime or network. */
public final class ForumPageSnapshotsTest {
    private static int assertions;
    private static final class Owner {
        @Override public boolean equals(Object other) { return other instanceof Owner; }
        @Override public int hashCode() { return 1; }
    }
    private static void check(String label, boolean value) {
        assertions++; if (!value) throw new AssertionError(label);
    }
    private static String repeat(int count) { char[] value = new char[count]; Arrays.fill(value, 'x'); return new String(value); }
    private static NgaApi.ForumPage page() {
        NgaApi.ForumPage value = new NgaApi.ForumPage();
        value.key = "414"; value.fid = "414"; value.name = "Synthetic forum"; value.page = 2;
        NgaApi.Topic topic = new NgaApi.Topic(); topic.tid = "1234"; topic.title = "Original title";
        topic.author = "Original author"; topic.authorId = "123"; topic.fid = "414"; topic.forum = value.name;
        topic.image = "image"; topic.avatar = "avatar"; topic.time = "time"; topic.replies = 5;
        topic.votes.likesKnown = true; topic.votes.dislikesKnown = true; topic.votes.scoreKnown = true;
        topic.votes.likes = 4; topic.votes.dislikes = 1; topic.votes.score = 3; value.topics.add(topic);
        NgaApi.Forum forum = new NgaApi.Forum(); forum.key = "stid:55"; forum.stid = "55"; forum.parentFid = "414";
        forum.name = "Child"; forum.category = "Collection"; forum.description = "Description"; forum.icon = "icon";
        forum.group = "Group"; forum.searchAliases = "Aliases";
        forum.aliases = new ArrayList<>(Arrays.asList("Alias")); forum.categoryNames = new ArrayList<>(Arrays.asList("Category"));
        forum.groupNames = new ArrayList<>(Arrays.asList("GroupName")); value.subForums.add(forum);
        return value;
    }
    private static boolean put(ForumPageSnapshots<Owner> cache, Owner owner, NgaApi.ForumPage value, long now) {
        return cache.put(owner, "scope-a", "414", "7", 2, value, now);
    }
    private static NgaApi.ForumPage get(ForumPageSnapshots<Owner> cache, Owner owner, long now) {
        return cache.get(owner, "scope-a", "414", "7", 2, now);
    }
    private static void identityAndMatching() {
        ForumPageSnapshots<Owner> cache = new ForumPageSnapshots<>(); Owner a = new Owner(), b = new Owner();
        check("equal owners are intentionally different identities", a.equals(b) && a != b);
        check("put valid", put(cache, a, page(), 1000));
        check("equal-valued other screen misses", get(cache, b, 1001) == null);
        check("same owner hits", get(cache, a, 1001) != null);
        String[][] variants = {{"scope-b", "414", "7", "2"}, {"scope-a", "415", "7", "2"},
                {"scope-a", "414", "8", "2"}, {"scope-a", "414", "7", "3"}};
        for (String[] variant : variants) {
            put(cache, a, page(), 1000);
            check("scope route parent and page are independent exact guards", cache.get(a, variant[0], variant[1], variant[2], Integer.parseInt(variant[3]), 1001) == null);
            check("mismatch retires old snapshot", get(cache, a, 1001) == null && cache.size() == 0 && cache.chars() == 0);
        }
        put(cache, a, page(), 1000); put(cache, b, page(), 1001);
        cache.retain(Collections.singletonList(b), 1002);
        check("retain uses identity not equals", get(cache, a, 1002) == null && get(cache, b, 1002) != null);
        cache.retain(Collections.emptyList(), 1003);
        check("popped and removed screens release payload", cache.size() == 0 && cache.chars() == 0);
    }
    private static void copyIsolation() {
        ForumPageSnapshots<Owner> cache = new ForumPageSnapshots<>(); Owner owner = new Owner(); NgaApi.ForumPage input = page();
        put(cache, owner, input, 1000); int budget = cache.chars();
        input.name = "changed"; input.topics.get(0).title = "changed"; input.topics.get(0).votes.likes = 999;
        input.subForums.get(0).aliases.set(0, "changed"); input.subForums.get(0).categoryNames.set(0, "changed");
        input.subForums.get(0).groupNames.set(0, "changed"); input.subForums.get(0).name = "changed";
        input.topics.add(new NgaApi.Topic()); input.subForums.add(new NgaApi.Forum());
        NgaApi.ForumPage first = get(cache, owner, 1001);
        check("input page mutation isolated", "Synthetic forum".equals(first.name) && first.topics.size() == 1 && first.subForums.size() == 1);
        check("topic and vote are deep copies", "Original title".equals(first.topics.get(0).title) && first.topics.get(0).votes.likes == 4);
        check("forum and all metadata lists are deep copies", "Child".equals(first.subForums.get(0).name)
                && first.subForums.get(0).aliases.equals(Collections.singletonList("Alias"))
                && first.subForums.get(0).categoryNames.equals(Collections.singletonList("Category"))
                && first.subForums.get(0).groupNames.equals(Collections.singletonList("GroupName")));
        check("all topic fields and known vote flags survive", "Original author".equals(first.topics.get(0).author)
                && "123".equals(first.topics.get(0).authorId) && "414".equals(first.topics.get(0).fid)
                && "image".equals(first.topics.get(0).image) && "avatar".equals(first.topics.get(0).avatar)
                && "time".equals(first.topics.get(0).time) && first.topics.get(0).replies == 5
                && first.topics.get(0).votes.likesKnown && first.topics.get(0).votes.dislikesKnown
                && first.topics.get(0).votes.scoreKnown && first.topics.get(0).votes.dislikes == 1 && first.topics.get(0).votes.score == 3);
        first.key = "415"; first.topics.get(0).votes.dislikes = 111; first.topics.get(0).title = "render mutation";
        first.subForums.get(0).aliases.clear(); first.topics.clear(); first.subForums.clear();
        NgaApi.ForumPage second = get(cache, owner, 1002);
        check("returned values never alias stored page", second != first && second.topics.size() == 1 && second.subForums.size() == 1);
        check("render callbacks cannot mutate later returns", "Original title".equals(second.topics.get(0).title)
                && second.topics.get(0).votes.dislikes == 1 && second.subForums.get(0).aliases.size() == 1);
        check("copy mutation never changes retained budget", cache.chars() == budget);
    }
    private static void expiryAndCapacity() {
        ForumPageSnapshots<Owner> cache = new ForumPageSnapshots<>(); Owner owner = new Owner();
        put(cache, owner, page(), 1000);
        check("just before ttl is available", get(cache, owner, 120999) != null);
        check("hit never extends original ttl", get(cache, owner, 121000) == null && cache.chars() == 0);
        put(cache, owner, page(), 1000);
        check("clock rollback cannot preserve snapshot", get(cache, owner, 999) == null);
        Owner[] owners = {new Owner(), new Owner(), new Owner(), new Owner(), new Owner()};
        for (int i = 0; i < 4; i++) check("within four page capacity", put(cache, owners[i], page(), 1000 + i));
        check("four pages retained", cache.size() == 4);
        get(cache, owners[0], 1005); put(cache, owners[4], page(), 1006);
        check("fifth page evicts least recently used owner", cache.size() == 4 && get(cache, owners[1], 1007) == null);
        check("recently returned owner survives capacity eviction", get(cache, owners[0], 1007) != null);
        cache.retain(Arrays.asList(owners), 121006);
        check("retain also removes expired pages", cache.size() == 0 && cache.chars() == 0);
        put(cache, owner, page(), 200000); cache.clear();
        check("clear releases every data page and budget", cache.size() == 0 && cache.chars() == 0 && get(cache, owner, 200001) == null);
    }
    private static void budgetsAndMalformedData() {
        ForumPageSnapshots<Owner> cache = new ForumPageSnapshots<>(); Owner owner = new Owner();
        NgaApi.ForumPage value = page(); value.topics.clear();
        for (int i = 0; i < 128; i++) value.topics.add(new NgaApi.Topic());
        check("128 topics accepted intact", put(cache, owner, value, 1) && get(cache, owner, 2).topics.size() == 128);
        value.topics.add(new NgaApi.Topic());
        check("129 topics reject whole snapshot", !put(cache, owner, value, 3) && cache.size() == 0);
        value = page(); value.subForums.clear();
        for (int i = 0; i < 64; i++) value.subForums.add(new NgaApi.Forum());
        check("64 child forums accepted intact", put(cache, owner, value, 1) && get(cache, owner, 2).subForums.size() == 64);
        value.subForums.add(new NgaApi.Forum()); check("65 child forums reject whole snapshot", !put(cache, owner, value, 3));
        value = page(); value.name = repeat(200001);
        check("oversized text is never retained", !put(cache, owner, value, 1) && cache.chars() == 0);
        value = page(); value.name = repeat(110000); Owner second = new Owner();
        check("single bounded large page accepted", put(cache, owner, value, 1));
        check("aggregate budget keeps newest page", put(cache, second, value, 2) && cache.size() == 1
                && cache.chars() <= ForumPageSnapshots.MAX_CHARS && get(cache, owner, 3) == null && get(cache, second, 3) != null);
        value = page(); value.subForums.get(0).aliases = Collections.nCopies(65, "");
        check("zero length strings cannot evade collection bound", !put(cache, owner, value, 1));
        value = page(); value.topics.add(null); check("null topic rejected", !put(cache, owner, value, 1));
        value = page(); value.subForums.add(null); check("null child rejected", !put(cache, owner, value, 1));
        value = page(); value.key = "415"; check("wrong response route rejected", !put(cache, owner, value, 1));
        value = page(); value.page = 3; check("wrong response page rejected", !put(cache, owner, value, 1));
        check("null payload rejected", !put(cache, owner, null, 1));
        check("null owner rejected", !put(cache, null, page(), 1));
        check("negative monotonic time rejected", !put(cache, owner, page(), -1));
        check("empty scope rejected", !cache.put(owner, "", "414", "7", 2, page(), 1));
        check("null parent rejected", !cache.put(owner, "scope-a", "414", null, 2, page(), 1));
        cache.clear();
        boolean replacementsBounded = true;
        for (int i = 0; i < 300; i++) replacementsBounded &= put(cache, owner, page(), i) && cache.size() == 1 && cache.chars() < 1000;
        check("300 repeated replacements retain only one bounded payload", replacementsBounded);
    }
    public static void main(String[] args) {
        identityAndMatching(); copyIsolation(); expiryAndCapacity(); budgetsAndMalformedData();
        System.out.println("ForumPageSnapshots: " + assertions + " assertions passed");
    }
}
