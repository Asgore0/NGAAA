package cn.ngapure.app;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Offline public-response contract tests; no network or account credentials. */
public final class RatingsFixtureTest {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    private static byte[] fixture(String filename) throws Exception {
        return Files.readAllBytes(Paths.get("tests/ratings/fixtures/" + filename));
    }
    private static void rejects(byte[] response, String category, String label) throws Exception {
        try {
            NgaRatings.parse(response, category, 1);
            throw new AssertionError(label);
        } catch (IOException expected) {
            check(expected.getMessage() != null && !expected.getMessage().isEmpty(), label);
        }
    }
    public static void main(String[] args) throws Exception {
        NgaRatings.Page first = NgaRatings.parse(fixture("traditional-page1.json"), "", 1);
        check(first.items.size() == 2, "traditional selected response rows");
        check(first.categories.size() == 4, "official category contract");
        check(first.categories.get(2).id.equals("4") && first.categories.get(2).name.equals("手机游戏") &&
                first.categories.get(2).fid.equals("571"), "mobile category identity");
        check(first.items.get(0).tid.equals("45799474") && first.items.get(0).name.equals("超英派遣中心"), "topic identity");
        check(first.items.get(0).score.equals("7.6") && first.items.get(0).scoreCount == 13, "score and count");
        check(first.items.get(0).tags.contains("Steam") && first.items.get(0).tags.contains("冒险"), "platform and genre tags");
        check(first.items.get(0).image.startsWith("https://img.nga.cn/"), "official image URL");
        NgaRatings.Page second = NgaRatings.parse(fixture("traditional-page2.json"), "2", 2);
        check(second.page == 2 && second.hasNext && second.items.size() == 1 &&
                !second.items.get(0).tid.equals(first.items.get(0).tid), "second-page data and continuation");
        NgaRatings.Page mobile = NgaRatings.parse(fixture("mobile-page1.json"), "4", 1);
        check(mobile.items.size() == 1 && mobile.items.get(0).tags.contains("iOS") &&
                mobile.items.get(0).tags.contains("Android"), "mobile result and platform tags");
        NgaRatings.Page film = NgaRatings.parse(fixture("film-page1.json"), "8", 1);
        check(film.items.size() == 1 && film.items.get(0).score.equals("7.4") &&
                film.items.get(0).tags.contains("电影"), "film accepts missing genre and release date");
        rejects(fixture("traditional-page1.json"), "1", "special recommendation category cannot be mislabelled");
        rejects(fixture("server-error.json"), "2", "readable server failure");
        rejects("{\"code\":0,\"result\":[]}".getBytes(StandardCharsets.UTF_8), "2", "invalid response schema");
        rejects("<html>error</html>".getBytes(StandardCharsets.UTF_8), "2", "HTML is not ratings JSON");
        Method limit = NgaRatings.class.getDeclaredMethod("readLimited", InputStream.class);
        limit.setAccessible(true);
        try {
            limit.invoke(null, new ByteArrayInputStream(new byte[8 * 1024 * 1024 + 1]));
            throw new AssertionError("8 MiB response limit");
        } catch (InvocationTargetException error) {
            check(error.getCause() instanceof IOException, "8 MiB response limit");
        }
        if (checks != 15) throw new AssertionError("Expected 15 checks, ran " + checks);
        System.out.println("PASS NgaRatings: " + checks + " offline public-response checks");
    }
}
