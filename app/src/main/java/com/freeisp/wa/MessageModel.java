package com.freeisp.wa;

import com.google.gson.annotations.SerializedName;

public class MessageModel {
    @SerializedName("id")
    private int id;

    @SerializedName("phone")
    private String phone;

    @SerializedName("message")
    private String message;

    @SerializedName("whatsapp_type")
    private String whatsappType;

    @SerializedName("priority")
    private int priority;

    @SerializedName("retry_count")
    private int retryCount;

    @SerializedName("created_at")
    private String createdAt;

    // Getters
    public int getId() { return id; }
    public String getPhone() { return phone; }
    public String getMessage() { return message; }
    public String getWhatsappType() { return whatsappType; }
    public int getPriority() { return priority; }
    public int getRetryCount() { return retryCount; }
    public String getCreatedAt() { return createdAt; }

    /**
     * Returns the correct WhatsApp package name based on type
     */
    public String getTargetPackage() {
        if ("whatsapp_business".equals(whatsappType)) {
            return AppConstants.WHATSAPP_BUSINESS_PACKAGE;
        }
        return AppConstants.WHATSAPP_PACKAGE;
    }

    @Override
    public String toString() {
        return "Message{id=" + id + ", phone=" + phone + ", type=" + whatsappType + "}";
    }
}
