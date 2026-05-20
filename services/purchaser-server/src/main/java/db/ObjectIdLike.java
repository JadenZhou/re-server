package db;

import java.security.SecureRandom;

public final class ObjectIdLike {

    private static final SecureRandom RND = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ObjectIdLike() {}

    public static String next() {
        byte[] bytes = new byte[12];
        int seconds = (int) (System.currentTimeMillis() / 1000L);
        bytes[0] = (byte) (seconds >>> 24);
        bytes[1] = (byte) (seconds >>> 16);
        bytes[2] = (byte) (seconds >>> 8);
        bytes[3] = (byte) seconds;
        byte[] tail = new byte[8];
        RND.nextBytes(tail);
        System.arraycopy(tail, 0, bytes, 4, 8);
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2]     = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    public static boolean isValid(String s) {
        if (s == null || s.length() != 24) return false;
        for (int i = 0; i < 24; i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }
}
