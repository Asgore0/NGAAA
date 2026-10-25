package cn.ngapure.app;

/** Native implementation of NGA's legacy UTF-16 RC4 BBCode envelope. Passwords are never persisted. */
public final class NativeEncryptedContent {
    private static final String NIBBLES = "~!#|^()=`];,?:{}";
    private NativeEncryptedContent() { }
    public static String decrypt(String password, String encoded) {
        if (password == null || password.isEmpty() || password.length() > 256) throw new IllegalArgumentException("请输入 1 至 256 个字符的密码");
        if (encoded == null || encoded.length() > 262144 || !encoded.matches("[\\*@_+./~!#\\^()=`\\];,?:{}|A-Za-z0-9%\\-]*")) throw new IllegalArgumentException("加密内容格式无效或过长");
        StringBuilder escaped = new StringBuilder(encoded.length());
        for (int at = 0; at < encoded.length();) {
            if (at + 4 <= encoded.length() && nibble(encoded.charAt(at)) >= 0 && nibble(encoded.charAt(at + 1)) >= 0
                    && nibble(encoded.charAt(at + 2)) >= 0 && nibble(encoded.charAt(at + 3)) >= 0) {
                int value = 0; for (int n = 0; n < 4; n++) value = value * 16 + nibble(encoded.charAt(at + n));
                escaped.append((char) value); at += 4;
            } else if (encoded.charAt(at) == '%' && at + 2 < encoded.length()) {
                int count = encoded.charAt(at + 1) == 'u' ? 4 : 2, start = at + (count == 4 ? 2 : 1);
                if (start + count <= encoded.length()) {
                    int value = 0; boolean valid = true;
                    for (int n = 0; n < count; n++) { int digit = Character.digit(encoded.charAt(start + n), 16); if (digit < 0) { valid = false; break; } value = value * 16 + digit; }
                    if (valid) { escaped.append((char) value); at = start + count; continue; }
                }
                escaped.append(encoded.charAt(at++));
            } else escaped.append(encoded.charAt(at++));
        }
        return rc4(password, escaped.toString());
    }
    private static int nibble(char ch) { return NIBBLES.indexOf(ch); }
    private static String rc4(String key, String data) {
        int[] state = new int[256]; for (int i = 0; i < 256; i++) state[i] = i;
        for (int i = 0, j = 0; i < 256; i++) { j = (j + state[i] + key.charAt(i % key.length())) & 255; int value = state[i]; state[i] = state[j]; state[j] = value; }
        StringBuilder result = new StringBuilder(data.length());
        for (int at = 0, i = 0, j = 0; at < data.length(); at++) { i = (i + 1) & 255; j = (j + state[i]) & 255; int value = state[i]; state[i] = state[j]; state[j] = value; result.append((char) (data.charAt(at) ^ state[(state[i] + state[j]) & 255])); }
        return result.toString();
    }
}
