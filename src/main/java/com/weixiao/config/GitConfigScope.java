package com.weixiao.config;

/**
 * 与 Git {@code --local} / {@code --global} / {@code --system} 对应的配置作用域。
 */
public enum GitConfigScope {

    /** 仓库 {@code .git/config} */
    LOCAL,

    /** 用户主目录下 {@code ~/.gitconfig} */
    GLOBAL,

    /** {@code /etc/gitconfig} */
    SYSTEM
}
