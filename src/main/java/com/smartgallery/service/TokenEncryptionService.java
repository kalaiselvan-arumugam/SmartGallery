package com.smartgallery.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for the Hugging Face token.
 * Key is derived from the OS username + hostname so the token is machine-bound.
 * The encrypted token cannot be read if moved to another machine.
 */
@Service
public class TokenEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(TokenEncryptionService.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 96-bit IV for GCM
    private static final int GCM_TAG_LENGTH = 128; // 128-bit authentication tag

    private final SecretKey secretKey;

    public TokenEncryptionService() {
        this.secretKey = deriveKey();
    }

    /**
     * Derives a 256-bit AES key from machine-specific information.
     * This binds the encrypted token to the current user and machine.
     */
    private SecretKey deriveKey() {
        try {
            String username = System.getProperty("user.name", "default_user");
            String hostname = "unknown_host";
            try {
                hostname = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                // Use default if hostname lookup fails
            }
            String keyMaterial = username + ":" + hostname + ":SmartGallery:v1";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(keyMaterial.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            log.error("Failed to derive encryption key, using fallback", e);
            // Fallback: derive from constant (less secure but won't crash)
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] keyBytes = digest.digest("SmartGallery:FallbackKey:v1".getBytes(StandardCharsets.UTF_8));
                return new SecretKeySpec(keyBytes, "AES");
            } catch (Exception ex) {
                throw new RuntimeException("Cannot initialize encryption", ex);
            }
        }
    }

    /**
     * Encrypts plaintext using AES-256-GCM.
     * Output format: Base64(IV + ciphertext + authTag)
     *
     * @param plaintext the HF token or other sensitive string
     * @return Base64-encoded encrypted string suitable for storage
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Cannot encrypt null or blank value");
        }
        try {
            SecureRandom random = new SecureRandom();
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypts a Base64-encoded AES-GCM ciphertext.
     *
     * @param encryptedBase64 the stored encrypted token
     * @return the original plaintext token
     */
    public String decrypt(String encryptedBase64) {
        if (encryptedBase64 == null || encryptedBase64.isBlank()) {
            throw new IllegalArgumentException("Cannot decrypt null or blank value");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedBase64);

            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed — token may have been saved on a different machine", e);
        }
    }
}
