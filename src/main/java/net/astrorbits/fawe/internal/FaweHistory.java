package net.astrorbits.fawe.internal;

/**
 * EditSession 构造时是否记录可撤销的变更。
 *
 * <p>由 {@code FaweConfig} 在启动时写入。打开时变更写入磁盘历史文件。
 * 关闭后新建的会话不记录，因此不会进入撤销栈。已经在栈里的编辑仍可撤销。
 */
public final class FaweHistory {
    private static volatile boolean record = true;

    private FaweHistory() {
    }

    /**
     * @return 为 true 时 EditSession 包上磁盘 ChangeSet
     */
    public static boolean record() {
        return record;
    }

    /**
     * @param value 为 false 时后续新建的会话不再记录历史
     */
    public static void setRecord(boolean value) {
        record = value;
    }
}
