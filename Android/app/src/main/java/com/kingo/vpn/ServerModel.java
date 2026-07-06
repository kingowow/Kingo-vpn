package com.kingo.vpn;

import java.io.Serializable;

public class ServerModel implements Serializable {
    private String name;
    private String config;
    private String protocol;
    private long ping = -1;
    private boolean isFavorite;
    private String group; // "servers" or "custom"

    public ServerModel(String name, String config, String protocol, String group) {
        this.name = name;
        this.config = config;
        this.protocol = protocol;
        this.ping = -1;
        this.isFavorite = false;
        this.group = group;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getConfig() {
        return config;
    }

    public void setConfig(String config) {
        this.config = config;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public long getPing() {
        return ping;
    }

    public void setPing(long ping) {
        this.ping = ping;
    }

    public boolean isFavorite() {
        return isFavorite;
    }

    public void setFavorite(boolean favorite) {
        isFavorite = favorite;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    /**
     * Parse a URI or JSON config and extract a display name + protocol.
     */
    public static ServerModel fromConfig(String config, String group) {
        String name = "Custom Server";
        String protocol = "Unknown";

        if (config.startsWith("vless://")) {
            protocol = "Vless";
            name = extractUriRemark(config);
        } else if (config.startsWith("vmess://")) {
            protocol = "VMess";
            name = extractUriRemark(config);
        } else if (config.startsWith("trojan://")) {
            protocol = "Trojan";
            name = extractUriRemark(config);
        } else if (config.startsWith("{")) {
            try {
                org.json.JSONObject json = new org.json.JSONObject(config);
                org.json.JSONArray outbounds = json.optJSONArray("outbounds");
                if (outbounds != null && outbounds.length() > 0) {
                    protocol = outbounds.getJSONObject(0).optString("protocol", "Unknown");
                    protocol = protocol.substring(0, 1).toUpperCase() + protocol.substring(1);
                }
            } catch (Exception ignore) {}
        }

        if (name.equals("Custom Server") || name.isEmpty()) {
            name = protocol + " Server";
        }

        return new ServerModel(name, config, protocol, group);
    }

    /**
     * Extract the remark/name after # in a URI.
     */
    private static String extractUriRemark(String config) {
        try {
            if (config.contains("#")) {
                String remark = config.substring(config.lastIndexOf("#") + 1);
                String decoded = java.net.URLDecoder.decode(remark, "UTF-8");
                if (!decoded.isEmpty()) {
                    return decoded;
                }
            }
        } catch (Exception ignore) {}
        return "";
    }
}
