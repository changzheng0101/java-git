package com.weixiao.config;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 配置文件中的一行逻辑记录。
 */
@Data
@AllArgsConstructor
public final class ConfigLine {

    private String text;
    private String section;
    private ConfigVariable variable;
}
