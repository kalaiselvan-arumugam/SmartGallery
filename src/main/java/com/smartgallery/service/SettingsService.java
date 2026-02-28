package com.smartgallery.service;

import com.smartgallery.entity.SettingEntity;
import com.smartgallery.repository.SettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Manages application settings, particularly the encrypted Hugging Face API
 * token.
 * The token is NEVER logged or exposed in plain text through this service.
 */
@Service
@Transactional
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    private static final String KEY_HF_TOKEN = "hf_token_encrypted";
    private static final String KEY_HF_REPO = "hf_repo";
    private static final String KEY_ALLOW_DOWNLOAD = "allow_auto_download";

    private final SettingRepository settingRepository;
    private final TokenEncryptionService encryptionService;

    public SettingsService(SettingRepository settingRepository,
            TokenEncryptionService encryptionService) {
        this.settingRepository = settingRepository;
        this.encryptionService = encryptionService;
    }

    /**
     * Saves the Hugging Face token, encrypting it before storage.
     * The plain-text token is not retained after this method returns.
     */
    public void saveHfToken(String plainToken) {
        if (plainToken == null || plainToken.isBlank()) {
            throw new IllegalArgumentException("Token cannot be empty");
        }
        if (!plainToken.startsWith("hf_") && plainToken.length() < 8) {
            throw new IllegalArgumentException("Token does not look like a valid Hugging Face token");
        }
        String encrypted = encryptionService.encrypt(plainToken);
        saveOrUpdate(KEY_HF_TOKEN, encrypted);
        log.info("Hugging Face token saved (encrypted)");
    }

    /**
     * Retrieves the decrypted Hugging Face token, if stored.
     *
     * @return Optional containing the plain-text token, or empty if not set
     */
    @Transactional(readOnly = true)
    public Optional<String> getHfToken() {
        return settingRepository.findByKey(KEY_HF_TOKEN)
                .map(entity -> {
                    try {
                        return encryptionService.decrypt(entity.getValue());
                    } catch (Exception e) {
                        log.error(
                                "Failed to decrypt HF token — it may have been created on a different machine. Please re-enter it.");
                        return null;
                    }
                });
    }

    /**
     * Removes the stored Hugging Face token.
     */
    public void clearHfToken() {
        settingRepository.deleteByKey(KEY_HF_TOKEN);
        log.info("Hugging Face token cleared");
    }

    /**
     * Returns true if a Hugging Face token is currently stored.
     */
    @Transactional(readOnly = true)
    public boolean hasHfToken() {
        return settingRepository.existsByKey(KEY_HF_TOKEN);
    }

    /**
     * Saves a raw string setting value.
     */
    public void saveSetting(String key, String value) {
        saveOrUpdate(key, value);
    }

    /**
     * Retrieves a string setting by key.
     */
    @Transactional(readOnly = true)
    public Optional<String> getSetting(String key) {
        return settingRepository.findByKey(key).map(SettingEntity::getValue);
    }

    /**
     * Saves or updates an entry in the settings table.
     */
    private void saveOrUpdate(String key, String value) {
        SettingEntity entity = settingRepository.findByKey(key)
                .orElse(new SettingEntity(key, value));
        entity.setValue(value);
        settingRepository.save(entity);
    }
}
