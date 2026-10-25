package cn.ngapure.verify;

import android.app.Activity;
import android.content.SharedPreferences;
import cn.ngapure.app.NgaStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** In-memory rollback support; values, account IDs and cookies are never emitted. */
final class FavoriteProbeState {
    private final SharedPreferences preferences;
    private final Map<String,Object> before;
    private final Map<String,String> icons;
    private final Map<String,String> iconsBefore;
    FavoriteProbeState(Activity activity, SharedPreferences preferences) throws Exception {
        this.preferences = preferences; before = copy(preferences.getAll());
        @SuppressWarnings("unchecked") Map<String,String> current = (Map<String,String>) field(activity, "forumIcons");
        icons = current; iconsBefore = new HashMap<>(current);
    }
    boolean unrelatedUnchanged() { return withoutFavorites(before).equals(withoutFavorites(copy(preferences.getAll()))); }
    boolean restore() throws Exception {
        Map<String,Object> current = copy(preferences.getAll()); Set<String> keys = new HashSet<>(before.keySet()); keys.addAll(current.keySet());
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : keys) if (favoriteKey(key)) {
            if (before.containsKey(key)) putPreference(editor, key, before.get(key)); else editor.remove(key);
        }
        boolean committed = editor.commit(); icons.clear(); icons.putAll(iconsBefore);
        return committed && before.equals(copy(preferences.getAll())) && icons.equals(iconsBefore);
    }
    void clear() { before.clear(); iconsBefore.clear(); }
    static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    static Object invoke(Object target, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, arguments);
    }
    static String scope(NgaStore store) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest((store.host() + "\0" + store.accountId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder value = new StringBuilder(); for (byte item : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255)); return value.toString();
    }
    private static boolean favoriteKey(String key) { return key.startsWith("favorite_"); }
    private static Map<String,Object> withoutFavorites(Map<String,Object> values) {
        Map<String,Object> result = new HashMap<>(); for (Map.Entry<String,Object> row : values.entrySet()) if (!favoriteKey(row.getKey())) result.put(row.getKey(), row.getValue()); return result;
    }
    private static Map<String,Object> copy(Map<String,?> values) {
        Map<String,Object> result = new HashMap<>(); for (Map.Entry<String,?> row : values.entrySet()) { Object value = row.getValue(); if (value instanceof Set<?>) value = new HashSet<>((Set<?>) value); result.put(row.getKey(), value); } return result;
    }
    private static void putPreference(SharedPreferences.Editor editor, String key, Object value) throws Exception {
        if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof Set<?>) {
            Set<String> result = new HashSet<>(); for (Object item : (Set<?>) value) { if (!(item instanceof String)) throw new IllegalStateException("PREFERENCE_TYPE_UNAVAILABLE"); result.add((String) item); } editor.putStringSet(key, result);
        } else throw new IllegalStateException("PREFERENCE_TYPE_UNAVAILABLE");
    }
}
