package br.com.almoxarifado;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.*;
import java.util.Base64;

public final class Security {
    private static final SecureRandom RANDOM = new SecureRandom();
    private Security() {}
    public static String token() { byte[] b = new byte[32]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    public static String hash(String password) {
        if (password == null || password.length() < 8 || password.length() > 128)
            throw new Domain.BusinessException(400, "A senha deve ter de 8 a 128 caracteres.");
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        return "210000:" + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(derive(password, salt, 210000));
    }
    public static boolean verify(String password, String hash) {
        if (password == null || password.length() > 128) return false;
        String[] parts = hash.split(":");
        return MessageDigest.isEqual(Base64.getDecoder().decode(parts[2]), derive(password, Base64.getDecoder().decode(parts[1]), Integer.parseInt(parts[0])));
    }
    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
        finally { spec.clearPassword(); }
    }
}
