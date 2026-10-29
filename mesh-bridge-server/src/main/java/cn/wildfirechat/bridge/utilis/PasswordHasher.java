package cn.wildfirechat.bridge.utilis;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 管理员密码哈希。新密码使用 PBKDF2-HMAC-SHA256，旧 MD5 哈希在登录成功后自动升级。
 */
public final class PasswordHasher {
    private static final String PREFIX = "pbkdf2$";
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static String hash(String password) {
        if (password == null) {
            throw new IllegalArgumentException("password is null");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, ITERATIONS);
        return PREFIX + ITERATIONS + "$"
            + Base64.getEncoder().withoutPadding().encodeToString(salt) + "$"
            + Base64.getEncoder().withoutPadding().encodeToString(hash);
    }

    public static boolean isModern(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    public static boolean verify(String password, String salt, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        if (stored.startsWith(PREFIX)) {
            String[] parts = stored.split("\\$");
            if (parts.length != 4) {
                return false;
            }
            try {
                int iterations = Integer.parseInt(parts[1]);
                byte[] saltBytes = Base64.getDecoder().decode(parts[2]);
                byte[] expected = Base64.getDecoder().decode(parts[3]);
                byte[] actual = pbkdf2(password, saltBytes, iterations);
                return MessageDigest.isEqual(expected, actual);
            } catch (Exception e) {
                return false;
            }
        }

        // 兼容旧数据：Base64(MD5(password + salt))
        try {
            String legacySalt = salt == null ? "" : salt;
            byte[] digest = MessageDigest.getInstance("MD5")
                .digest((password + legacySalt).getBytes(StandardCharsets.UTF_8));
            String actual = Base64.getEncoder().encodeToString(digest);
            return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }
}
