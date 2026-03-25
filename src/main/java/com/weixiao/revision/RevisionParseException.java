package com.weixiao.revision;

/**
 * Revision 解析失败时抛出。
 */
public final class RevisionParseException extends RuntimeException {

    private final int position;

    public RevisionParseException(String message) {
        this(message, -1);
    }

    public RevisionParseException(String message, int position) {
        super(message);
        this.position = position;
    }

    @SuppressWarnings("unused")
    public int getPosition() {
        return position;
    }
}
