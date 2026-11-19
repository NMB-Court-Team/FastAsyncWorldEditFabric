package net.astrorbits.fawe.internal;

/**
 * EditSession 构造时是否包上内存 ChangeSet。
 *
 * <p>由 {@code FaweConfig} 在启动时写入。关闭后新建的会话不记录可撤销的方块变更。
 */
public final class FaweHistory {
    private static volatile boolean record = true;

    private FaweHistory() {
    }

    /**
     * @return 为 true 时 EditSession 包上 ChangeSet
     */
    public static boolean record() {
        return record;
    }

    /**
     * @param value 为 false 时后续新建的会话不再包 ChangeSet
     */
    public static void setRecord(boolean value) {
        record = value;
    }
}
