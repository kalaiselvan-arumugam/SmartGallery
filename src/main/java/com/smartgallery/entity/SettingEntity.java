package com.smartgallery.entity;

import jakarta.persistence.*;

/**
 * Key-value store for application settings (encrypted HF token, preferences,
 * etc.).
 */
@Entity
@Table(name = "settings")
public class SettingEntity {

    @Id
    @Column(name = "setting_key", length = 128)
    private String key;

    @Lob
    @Column(name = "setting_value", columnDefinition = "CLOB")
    private String value;

    public SettingEntity() {
    }

    public SettingEntity(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
