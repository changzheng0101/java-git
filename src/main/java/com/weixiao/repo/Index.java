package com.weixiao.repo;

import com.weixiao.utils.HexUtils;
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
import java.security.NoSuchAlgorithmException;
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
public final class Index {

    private static final Logger log = LoggerFactory.getLogger(Index.class);

    private static final String INDEX_FILE = "index";
    private static final byte[] SIGNATURE = new byte[]{'D', 'I', 'R', 'C'};
    private static final int VERSION = 2;
    private static final int ENTRY_FIXED_SIZE = 62; // 不含 name 与 padding
    private static final int OID_SIZE = 20;
    private static final int CHECKSUM_SIZE = 20;

    private final Path gitDir;
    private final List<Entry> entries = new ArrayList<>();

    public Index(Path gitDir) {
        this.gitDir = gitDir.toAbsolutePath().normalize();
    }

    /** index 一条 entry 的 stat 属性（ctime/mtime/dev/ino/uid/gid），与 Git index 格式一致。 */
    @Value
    public static class IndexStat {
        int ctimeSec;
        int ctimeNsec;
        int mtimeSec;
        int mtimeNsec;
        int dev;
        int ino;
        int uid;
        int gid;
    }

    /** 暂存区一条记录：相对路径、mode、blob oid、文件大小、stat 属性。 */
    @Value
    public static class Entry {
        /** 相对仓库根的路径，使用 / 分隔（如 "a/b.txt"）。 */
        String path;
        String mode;
        String oid;
        /** 文件大小（字节），与 Git index 中 file size 一致。 */
        int size;
        /** stat 属性（ctime/mtime/dev/ino/uid/gid），可为 null（加载旧格式时用 0 填充）。 */
        IndexStat stat;
    }

    /**
     * 从 .git/index 加载暂存区；文件不存在或非 Git 格式则视为空暂存区。
     */
    public void load() throws IOException {
        entries.clear();
        Path indexPath = gitDir.resolve(INDEX_FILE);
        if (!Files.exists(indexPath)) {
            log.debug("index file not found, using empty index");
            return;
        }
        byte[] raw = Files.readAllBytes(indexPath);
        if (raw.length < 12 + CHECKSUM_SIZE) {
            log.warn("index file too short, using empty index");
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
            log.warn("index checksum mismatch, using empty index");
            return;
        }

        ByteBuffer buf = ByteBuffer.wrap(content).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < 4; i++) {
            if (buf.get() != SIGNATURE[i]) {
                log.warn("index signature invalid, using empty index");
                return;
            }
        }
        int version = buf.getInt();
        if (version != 2 && version != 3 && version != 4) {
            log.warn("index version {} not supported, using empty index", version);
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
            entries.add(new Entry(path, String.format("%o", mode), oid, size, stat));
            int pad = (8 - ((ENTRY_FIXED_SIZE + nameBytes.length + 1) % 8)) % 8;
            for (int p = 0; p < pad && buf.hasRemaining(); p++) buf.get();
        }
        log.debug("loaded index entries={}", entries.size());
    }

    /**
     * 将当前暂存区写入 .git/index：Header、按 path 排序的 entry、无扩展、SHA-1 校验和。
     */
    public void save() throws IOException {
        entries.sort(Comparator.comparing(Entry::getPath));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SIGNATURE);
        writeInt(out, VERSION);
        writeInt(out, entries.size());

        for (Entry e : entries) {
            IndexStat s = e.getStat() != null ? e.getStat() : new IndexStat(0, 0, 0, 0, 0, 0, 0, 0);
            writeInt(out, s.getCtimeSec());
            writeInt(out, s.getCtimeNsec());
            writeInt(out, s.getMtimeSec());
            writeInt(out, s.getMtimeNsec());
            writeInt(out, s.getDev());
            writeInt(out, s.getIno());
            int mode = Integer.parseInt(e.getMode(), 8);
            writeInt(out, mode);
            writeInt(out, s.getUid());
            writeInt(out, s.getGid());
            writeInt(out, e.getSize());
            out.write(HexUtils.hexToBytes(e.getOid()));
            byte[] nameBytes = e.getPath().getBytes(StandardCharsets.UTF_8);
            int nameLen = Math.min(nameBytes.length, 0xFFF);
            int flags = nameLen;
            writeShort(out, (short) flags);
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

    private void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >> 24) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 8) & 0xff);
        out.write(v & 0xff);
    }

    private void writeShort(ByteArrayOutputStream out, short v) {
        out.write((v >> 8) & 0xff);
        out.write(v & 0xff);
    }

    private static byte[] sha1(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 添加或覆盖一条记录（同一 path 只保留一条）。stat 为文件真实属性（ctime/mtime/dev/ino/uid/gid）。
     * 同时移除与“文件/目录”冲突的已有条目，避免出现同一名字既是文件又是目录：
     * - 若新路径为 a/b（目录下的文件），则移除已有条目 a（原为文件，现为目录）。
     * - 若新路径为 a（文件），则移除已有条目 a/xxx（原为目录下的文件，现 a 为文件）。
     */
    public void add(String path, String mode, String oid, int size, IndexStat stat) {
        String normalized = path.replace('\\', '/');
        // 同路径覆盖
        entries.removeIf(e -> e.getPath().equals(normalized));
        // 新路径是「目录/文件」时：移除原以该路径为名的文件（现为目录）
        entries.removeIf(e -> normalized.startsWith(e.getPath() + "/"));
        // 新路径是单层文件时：移除原在该路径「目录」下的所有条目（现该路径为文件）
        entries.removeIf(e -> e.getPath().startsWith(normalized + "/"));
        entries.add(new Entry(normalized, mode, oid, size, stat));
        log.debug("add entry path={} mode={} oid={} size={} stat={}", normalized, mode, oid, size, stat);
    }

    /** 返回当前所有暂存条目（只读）。 */
    public List<Entry> getEntries() {
        return new ArrayList<>(entries);
    }

    /** 暂存区是否为空。 */
    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
