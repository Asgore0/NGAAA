import cn.ngapure.app.NgaApi;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Synthetic timestamps only. The same NgaApi instance is deliberately shared by parallel callers. */
public final class ForumTopicTimeTest {
    private static final Object MISSING = new Object();
    private static final class Sample {
        final Object lastpost, postdate;
        final String expected;
        Sample(Object value, String expected) { this(value, MISSING, expected); }
        Sample(Object lastpost, Object postdate, String expected) {
            this.lastpost = lastpost; this.postdate = postdate; this.expected = expected;
        }
    }
    private static void check(boolean pass, String message) { if (!pass) throw new AssertionError(message); }
    private static long epoch(String value) { return Instant.parse(value).getEpochSecond(); }
    private static List<Sample> samples() {
        return Arrays.asList(
                new Sample(MISSING, ""), new Sample(JSONObject.NULL, ""),
                new Sample(MISSING, 1L, "01-01 08:00"), new Sample(JSONObject.NULL, 1L, ""),
                new Sample(60L, epoch("2024-02-29T16:01:00Z"), "01-01 08:01"),
                new Sample(1L, "01-01 08:00"), new Sample("0001", "01-01 08:00"),
                new Sample("+1", "01-01 08:00"),
                new Sample(epoch("2000-02-29T15:59:59Z"), "02-29 23:59"),
                new Sample(epoch("2000-02-29T16:00:00Z"), "03-01 00:00"),
                new Sample(epoch("2023-12-31T16:00:00Z"), "01-01 00:00"),
                new Sample(String.valueOf(epoch("2024-02-29T16:01:00Z")), "03-01 00:01"),
                new Sample(epoch("1991-07-01T00:00:00Z"), "07-01 09:00"),
                new Sample(2147483648L, "01-19 11:14"),
                new Sample(253402300799L, "01-01 07:59"),
                new Sample(0L, ""), new Sample(-1L, ""), new Sample("-0", ""),
                new Sample(253402300800L, ""), new Sample(Long.MAX_VALUE, ""),
                new Sample(Long.MIN_VALUE, ""),
                new Sample("9223372036854775808", "9223372036854775808"),
                new Sample("-9223372036854775809", "-9223372036854775809"),
                new Sample("", ""), new Sample("   ", ""), new Sample(" 1 ", "1"),
                new Sample("刚刚", "刚刚"), new Sample(" <b>昨天</b> &amp; 今天 ", "昨天 & 今天"),
                new Sample("2024-02-29 08:30", "2024-02-29 08:30"),
                new Sample("1.5", "1.5"), new Sample(1.0d, "1.0"),
                new Sample(true, "true"), new Sample("1e3", "1e3"));
    }
    private static JSONObject fixture(List<Sample> samples, int rotation, boolean keyedRows) throws Exception {
        JSONArray array = new JSONArray();
        JSONObject object = new JSONObject();
        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get((i + rotation) % samples.size());
            JSONObject row = new JSONObject().put("tid", String.valueOf(i + 1)).put("subject", "合成时间样例");
            if (sample.lastpost != MISSING) row.put("lastpost", sample.lastpost);
            if (sample.postdate != MISSING) row.put("postdate", sample.postdate);
            array.put(row); object.put(String.valueOf(i), row);
        }
        return new JSONObject().put("data", new JSONObject().put("__T", keyedRows ? object : array));
    }
    private static Object invoke(NgaApi api, Method method, Object... args) throws Exception {
        try { return method.invoke(api, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            if (failure.getCause() instanceof Error) throw (Error) failure.getCause();
            throw failure;
        }
    }
    @SuppressWarnings("unchecked")
    private static List<NgaApi.Topic> parse(NgaApi api, Method method, JSONObject fixture) throws Exception {
        return (List<NgaApi.Topic>) invoke(api, method, fixture, "7");
    }
    private static void checkBatch(List<NgaApi.Topic> topics, List<Sample> samples, int rotation) {
        check(topics.size() == samples.size(), "batch retains every topic");
        for (int i = 0; i < topics.size(); i++) {
            Sample sample = samples.get((i + rotation) % samples.size());
            check(topics.get(i).time.equals(sample.expected), "parallel batch time/order at row " + i);
        }
    }
    public static int verify(NgaApi api) throws Exception {
        Method parse = NgaApi.class.getDeclaredMethod("parseTopics", JSONObject.class, String.class);
        Method single = NgaApi.class.getDeclaredMethod("time", Object.class);
        parse.setAccessible(true); single.setAccessible(true);
        List<Sample> samples = samples();
        int assertions = 0;
        TimeZone original = TimeZone.getDefault();
        try {
            for (String hostZone : new String[]{"UTC", "Pacific/Honolulu", "America/New_York"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(hostZone));
                for (boolean keyed : new boolean[]{false, true}) {
                    List<NgaApi.Topic> topics = parse(api, parse, fixture(samples, 0, keyed));
                    check(topics.size() == samples.size(), "all mixed rows retained in " + hostZone); assertions++;
                    for (int i = 0; i < samples.size(); i++) {
                        check(topics.get(i).time.equals(samples.get(i).expected), "fixed Shanghai time/fallback row " + i + " in " + hostZone); assertions++;
                    }
                }
            }
            for (Sample sample : samples) {
                Object value = sample.lastpost == MISSING ? sample.postdate : sample.lastpost;
                if (value == MISSING) value = null;
                check(invoke(api, single, value).equals(sample.expected), "standalone thread/feed time retains behavior"); assertions++;
            }
            List<Sample> textOnly = Arrays.asList(new Sample("昨天", "昨天"), new Sample(JSONObject.NULL, ""), new Sample(0, ""));
            checkBatch(parse(api, parse, fixture(textOnly, 0, false)), textOnly, 0); assertions += textOnly.size() + 1;

            final int workers = 6, batches = 24;
            CountDownLatch ready = new CountDownLatch(workers), start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            List<Future<Integer>> results = new ArrayList<>();
            try {
                for (int w = 0; w < workers; w++) {
                    final int worker = w;
                    results.add(pool.submit(() -> {
                        ready.countDown();
                        check(start.await(10, TimeUnit.SECONDS), "parallel parser start signalled");
                        int checks = 1;
                        for (int batch = 0; batch < batches; batch++) {
                            int rotation = (worker * 7 + batch) % samples.size();
                            checkBatch(parse(api, parse, fixture(samples, rotation, (batch & 1) == 0)), samples, rotation);
                            checks += samples.size() + 1;
                        }
                        return checks;
                    }));
                }
                check(ready.await(10, TimeUnit.SECONDS), "all parser workers reach concurrent start"); assertions++;
                start.countDown();
                for (Future<Integer> result : results) assertions += result.get(30, TimeUnit.SECONDS);
            } finally {
                start.countDown(); pool.shutdownNow();
                check(pool.awaitTermination(10, TimeUnit.SECONDS), "parallel workers terminate"); assertions++;
            }
        } finally { TimeZone.setDefault(original); }
        System.out.println("ForumTopicTimeTest PASS " + assertions + " assertions; fixed timezone, mixed boundaries and 144 parallel batches.");
        return assertions;
    }
}
