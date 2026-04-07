package com.weixiao.config;

import com.google.common.primitives.Ints;

public record ConfigVariable(String key, Object value) {

    public static ConfigVariable fromRaw(String key, String rawValue) {
        key = key.trim();
        rawValue = rawValue.trim();

        Object value = switch (rawValue) {
            case "yes", "on", "true" -> true;
            case "no", "off", "false" -> false;
            default -> {
                if (Ints.tryParse(rawValue) != null) {
                    yield Integer.parseInt(rawValue);
                }

                yield rawValue;
            }
        };

        return new ConfigVariable(key, value);
    }
}
