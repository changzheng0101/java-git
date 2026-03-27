package com.weixiao.repo;

import com.weixiao.obj.TreeEntry;
import com.weixiao.utils.BinaryIOUtils;
import com.weixiao.utils.CryptoUtils;
import com.weixiao.utils.HexUtils;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import lombok.Value;

/**
 * 暂存区（index）：记录已 add 的文件，供 commit 使用。
 * 格式与 Git 的 .git/index 一致：Header（DIRC + version + entry count）、按 path 排序的 entry 列表、
 * 可选扩展、末尾 20 字节 SHA-1 校验和（对前面全部内容）。
 * Entry 格式（version 2）：ctime/mtime/dev/ino/mode/uid/gid/size/oid(20)/flags/name(NUL)/padding(8 字节对齐)。
 */
@Data
@NoArgsConstructor
public final class Index {

    private static final Logger log = LoggerFactory.getLogger(Index.class);

    private static final String INDEX_FILE = "index";
    private static final byte[] SIGNATURE = new byte[]{'D', 'I', 'R', 'C'};
    private static final int VERSION = 2;
    private static final int ENTRY_FIXED_SIZE = 62; // 不含 name 与 padding
    private static final int OID_SIZE = 20;
    private static final int CHECKSUM_SIZE = 20;

    private Path gitDir;
    private final List<Entry> entries = new ArrayList<>();

    public Index(Path gitDir) {
        this.gitDir = gitDir.toAbsolutePath().normalize();
    }


    /**
     * 路径是否被 index 跟踪。
     * <p>
     * 对文件路径：存在同路径 entry（任意 stage）即为 true。
     * 对目录路径：存在该目录下任意已跟踪路径（path/xxx）即为 true。
     */
    public boolean tracked(String path) {
        return entries.stream()
                .anyMatch(entry -> entry.getPath().startsWith(path));
    }


    /**
     * index 一条 entry 的 stat 属性（ctime/mtime/dev/ino/uid/gid），与 Git index 格式一致。
     */
    @Value
    public static class IndexStat {
        public static final IndexStat EMPTY = new IndexStat(0, 0, 0, 0, 0, 0, 0, 0);

        int ctimeSec;
        int ctimeNsec;
        int mtimeSec;
        int mtimeNsec;
        int dev;
        int ino;
        int uid;
        int gid;
    }

    /**
     * 暂存区一条记录：相对路径、mode、blob oid、文件大小、stat 属性。
     */
    @Value
    public static class Entry {
        /**
         * 相对仓库根的路径，使用 / 分隔（如 "a/b.txt"）。
         */
        String path;
        String mode;
        String oid;
        /**
         * 冲突阶段（0..3）：0=normal，1=base，2=ours，3=theirs。
         */
        int stage;
        /**
         * 文件大小（字节），与 Git index 中 file size 一致。
         */
        int size;
        /**
         * stat 属性（ctime/mtime/dev/ino/uid/gid），不能为空。
         */
        IndexStat stat;
    }

    /**
     * 从 .git/index 加载暂存区；文件不存在或非 Git 格式则视为空暂存区。
     */
    public void load() throws IOException {
        entries.clear();
        Path indexPath = gitDir.resolve(INDEX_FILE);
        if (!Files.exists(indexPath)) {
            log.debug("index file not found, using emptyTree index");
            return;
        }
        byte[] raw = Files.readAllBytes(indexPath);
        if (raw.length < 12 + CHECKSUM_SIZE) {
            log.warn("index file too short, using emptyTree index");
            return;
        }
        // 校验和：最后 20 字节为前面内容的 SHA-1
        int contentEnd = raw.length - CHECKSUM_SIZE;
        byte[] content = new byte[contentEnd];
        System.arraycopy(raw, 0, content, 0, contentEnd);
        byte[] expectedChecksum = new byte[CHECKSUM_SIZE];
        System.arraycopy(raw, contentEnd, expectedChecksum, 0, CHECKSUM_SIZE);
        byte[] actualChecksum = sha1(content);
        if (!MessageDigest.isEqual(expectedChecksum, actualChecksum)) {
            log.warn("index checksum mismatch, using emptyTree index");
            return;
        }

        ByteBuffer buf = ByteBuffer.wrap(content).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < 4; i++) {
            if (buf.get() != SIGNATURE[i]) {
                log.warn("index signature invalid, using emptyTree index");
                return;
            }
        }
        int version = buf.getInt();
        if (version != 2 && version != 3 && version != 4) {
            log.warn("index version {} not supported, using emptyTree index", version);
            return;
        }
        int numEntries = buf.getInt();
        log.debug("index version={} numEntries={}", version, numEntries);

        for (int i = 0; i < numEntries; i++) {
            if (buf.remaining() < ENTRY_FIXED_SIZE) {
                log.warn("index truncated at entry {}", i);
                break;
            }
            int ctimeSec = buf.getInt();
            int ctimeNsec = buf.getInt();
            int mtimeSec = buf.getInt();
            int mtimeNsec = buf.getInt();
            int dev = buf.getInt();
            int ino = buf.getInt();
            int mode = buf.getInt();
            int uid = buf.getInt();
            int gid = buf.getInt();
            int size = buf.getInt();
            byte[] oidBytes = new byte[OID_SIZE];
            buf.get(oidBytes);
            String oid = HexUtils.bytesToHex(oidBytes);
            int flags = buf.getShort() & 0xFFFF;
            int stage = (flags >> 12) & 0x3;
            int nameLen = flags & 0x0FFF;
            byte[] nameBytes;
            if (nameLen == 0x0FFF) {
                ByteArrayOutputStream nameOut = new ByteArrayOutputStream();
                while (buf.hasRemaining()) {
                    byte b = buf.get();
                    if (b == 0) break;
                    nameOut.write(b);
                }
                nameBytes = nameOut.toByteArray();
            } else {
                nameBytes = new byte[nameLen];
                if (nameLen > 0) buf.get(nameBytes);
                if (buf.hasRemaining()) buf.get(); // NUL
            }
            String path = new String(nameBytes, StandardCharsets.UTF_8);
            IndexStat stat = new IndexStat(ctimeSec, ctimeNsec, mtimeSec, mtimeNsec, dev, ino, uid, gid);
            entries.add(new Entry(path, String.format("%o", mode), oid, stage, size, stat));
            int pad = (8 - ((ENTRY_FIXED_SIZE + nameBytes.length + 1) % 8)) % 8;
            for (int p = 0; p < pad && buf.hasRemaining(); p++) buf.get();
        }
        log.debug("loaded index entries={}", entries.size());
    }

    /**
     * 将当前暂存区写入 .git/index：Header、按 path 排序的 entry、无扩展、SHA-1 校验和。
     */
    public void save() throws IOException {
        entries.sort(Comparator.comparing(Entry::getPath).thenComparingInt(Entry::getStage));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SIGNATURE);
        BinaryIOUtils.writeInt(out, VERSION);
        BinaryIOUtils.writeInt(out, entries.size());

        for (Entry e : entries) {
            IndexStat s = e.getStat();
            BinaryIOUtils.writeInt(out, s.getCtimeSec());
            BinaryIOUtils.writeInt(out, s.getCtimeNsec());
            BinaryIOUtils.writeInt(out, s.getMtimeSec());
            BinaryIOUtils.writeInt(out, s.getMtimeNsec());
            BinaryIOUtils.writeInt(out, s.getDev());
            BinaryIOUtils.writeInt(out, s.getIno());
            int mode = Integer.parseInt(e.getMode(), 8);
            BinaryIOUtils.writeInt(out, mode);
            BinaryIOUtils.writeInt(out, s.getUid());
            BinaryIOUtils.writeInt(out, s.getGid());
            BinaryIOUtils.writeInt(out, e.getSize());
            out.write(HexUtils.hexToBytes(e.getOid()));
            byte[] nameBytes = e.getPath().getBytes(StandardCharsets.UTF_8);
            int nameLen = Math.min(nameBytes.length, 0xFFF);
            // flags（16 bits）：
            // - bit 15: assume-valid
            // - bit 14: extended（v2 固定为 0）
            // - bit 13-12: stage（0..3）
            // - bit 11-0: name length（最大 0xFFF，超长使用 0xFFF + NUL 结尾路径）
            int flags = ((e.getStage() & 0x3) << 12) | nameLen;
            BinaryIOUtils.writeShort(out, (short) flags);
            out.write(nameBytes);
            out.write(0);       // NUL
            int pad = (8 - ((ENTRY_FIXED_SIZE + nameBytes.length + 1) % 8)) % 8;
            for (int i = 0; i < pad; i++) out.write(0);
        }

        byte[] content = out.toByteArray();
        byte[] checksum = sha1(content);
        out.write(checksum);

        Path indexPath = gitDir.resolve(INDEX_FILE);
        Files.write(indexPath, out.toByteArray());
        log.debug("saved index entries={} checksum={}", entries.size(), HexUtils.bytesToHex(checksum));
    }

    private static byte[] sha1(byte[] input) {
        return CryptoUtils.sha1(input);
    }

    /**
     * 添加或覆盖一条记录（同一 path+stage 只保留一条）。
     * stat 为文件真实属性（ctime/mtime/dev/ino/uid/gid）。
     * 同时移除与“文件/目录”冲突的已有条目，避免出现同一名字既是文件又是目录：
     * - 若新路径为 a/b（目录下的文件），则移除已有条目 a（原为文件，现为目录）。
     * - 若新路径为 a（文件），则移除已有条目 a/xxx（原为目录下的文件，现 a 为文件）。
     */
    @SuppressWarnings("ConstantValue")
    public void add(Entry entry) {
        if (entry.getStage() != 0) {
            throw new IllegalArgumentException("add only supports stage-0 entry");
        }
        String path = entry.getPath();
        // 同路径同 stage 覆盖
        entries.removeIf(e -> e.getPath().equals(path) && e.getStage() == entry.getStage());
        // 同路径冲突 stage 清理：当写入 stage-0 时移除 stage-1/2/3
        entries.removeIf(e -> e.getPath().equals(path) && e.getStage() != 0);
        // 新路径是「目录/文件」时：移除原以该路径为名的文件（现为目录）
        entries.removeIf(e -> path.startsWith(e.getPath() + "/"));
        // 新路径是单层文件时：移除原在该路径「目录」下的所有条目（现该路径为文件）
        entries.removeIf(e -> e.getPath().startsWith(path + "/"));

        entries.add(entry);
        log.debug(
                "add entry path={} mode={} oid={} stage={} size={} stat={}",
                entry.getPath(),
                entry.getMode(),
                entry.getOid(),
                entry.getStage(),
                entry.getSize(),
                entry.getStat()
        );
    }

    /**
     * 写入三方冲突集合（base/ours/theirs）到同一路径：
     * - 先移除该路径已有的 stage-0 及旧冲突条目；
     * - 再把非空条目分别写为 stage 1/2/3；
     * - 若某一侧不存在（entry 为 null），则跳过该 stage。
     */
    public void addConflictSet(String path, TreeEntry base, TreeEntry ours, TreeEntry theirs) {
        entries.removeIf(e -> e.getPath().equals(path));
        addConflictEntry(path, base, 1);
        addConflictEntry(path, ours, 2);
        addConflictEntry(path, theirs, 3);
        log.debug("add conflict set path={} base={} ours={} theirs={}", path, base, ours, theirs);
    }

    private void addConflictEntry(String path, TreeEntry entry, int stage) {
        if (entry == null) {
            return;
        }
        entries.add(new Entry(path, entry.getMode(), entry.getOid(), stage, 0, IndexStat.EMPTY));
    }

    /**
     * 从暂存区移除指定路径的条目（用于如 rm --cached 的场景）。
     */
    public void remove(String path) {
        String normalized = path.replace('\\', '/');
        entries.removeIf(e -> e.getPath().equals(normalized));
        log.debug("remove entry path={}", normalized);
    }

    /**
     * 返回当前所有暂存条目（只读）。
     */
    public List<Entry> getEntries() {
        return new ArrayList<>(entries);
    }

    /**
     * 按路径查找一条暂存条目，不存在返回 null。
     */
    public Entry getEntryForPath(String path) {
        return getEntryForPath(path, 0);
    }

    /**
     * 按路径和 stage 查找一条暂存条目，不存在返回 null。
     */
    public Entry getEntryForPath(String path, int stage) {
        String normalized = path.replace('\\', '/');
        for (Entry e : entries) {
            if (e.getPath().equals(normalized) && e.getStage() == stage) return e;
        }
        return null;
    }

    /**
     * 暂存区是否处于冲突态：任一条目的 stage 非 0 即为冲突态。
     */
    public boolean isConflicted() {
        for (Entry entry : entries) {
            if (entry.getStage() != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 暂存区是否为空。
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
