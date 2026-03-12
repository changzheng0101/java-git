package com.weixiao.revision;

import com.weixiao.obj.Commit;
import com.weixiao.obj.GitObject;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.Refs;
import com.weixiao.repo.Repository;
import com.weixiao.repo.SysRef;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Git revision 语法 AST：@ = HEAD；<refname> = 分支/远程/oid；<rev>^ = 父提交；<rev>~<n> = 第 n 代祖先。
 */
public interface Revision {

    Kind getKind();

    /**
     * 解析并返回对应的完整 40 位 commit id，语义与 Git 一致：
     * - Ref：支持分支名（refs/heads/name 或 name）、HEAD、以及 40 位或前缀 oid（唯一匹配）。
     * - Parent (^)、Ancestor (~n)：在解析出的 commit 基础上向上追溯父提交。
     * - 非法引用名按照 Git 规则校验，抛 RevisionParseException。
     */
    String getCommitId(Repository repo) throws IOException;

    enum Kind {REF, PARENT, ANCESTOR}

    static Revision parse(String s) {
        if (s == null) {
            throw new RevisionParseException("revision string is null");
        }
        s = s.trim();
        if (s.isEmpty()) {
            throw new RevisionParseException("empty revision");
        }

        if (s.endsWith("^")) {
            return new Parent(parse(s.substring(0, s.length() - 1)));
        }

        java.util.regex.Matcher m = Pattern.compile("^(.+)~(\\d*)$").matcher(s);
        if (m.matches()) {
            String n = m.group(2);
            return new Ancestor(parse(m.group(1)), n.isEmpty() ? 1 : Integer.parseInt(n));
        }
        return new Ref("@".equals(s) ? "HEAD" : s);
    }

    final class Ref implements Revision {

        private final String name;

        public Ref(String name) {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("ref name must not be empty");
            }
            this.name = name;
        }

        public String name() {
            return name;
        }

        @Override
        public Kind getKind() {
            return Kind.REF;
        }

        @Override
        public String getCommitId(Repository repo) throws IOException {
            Refs refs = repo.getRefs();
            ObjectDatabase db = repo.getDatabase();
            String target = "HEAD".equals(name)
                    ? refs.readHead()
                    : refs.readRef(new SysRef(Refs.REFS_HEADS + name));

            if (target != null) {
                return target;
            }

            List<String> candidates = db.prefixMatch(name);

            if (candidates.size() == 1) {
                return candidates.get(0);
            }
            if (candidates.size() > 1) {
                throw ambiguousRevisionException(name, candidates, db);
            }

            throw new RevisionParseException("revision not found: " + name);
        }


        private static RevisionParseException ambiguousRevisionException(String name, List<String> candidates,
                                                                         ObjectDatabase db) throws IOException {
            List<String> lines = new ArrayList<>();
            for (String oid : candidates) {
                GitObject obj = db.load(oid);
                String shortOid = ObjectDatabase.shortOid(oid);
                String line = " " + shortOid + " " + obj.getType();
                if ("commit".equals(obj.getType())) {
                    Commit commit = (Commit) obj;
                    String shortDate = formatAuthorShortDate(commit.getAuthor());
                    String titleLine = Commit.firstLine(commit.getMessage());
                    line += " " + shortDate + " - " + titleLine;
                }
                lines.add(line);
            }
            String message = "short SHA1 " + name + " is ambiguous\nThe candidates are:\n" + String.join("\n", lines);
            return new RevisionParseException(message);
        }

        private static String formatAuthorShortDate(String author) {
            if (author == null) {
                return "";
            }
            String[] parts = author.split("\\s+");
            for (String p : parts) {
                if (p.length() == 10 && p.matches("\\d+")) {
                    try {
                        long ts = Long.parseLong(p);
                        return Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ISO_LOCAL_DATE);
                    } catch (NumberFormatException ignored) {
                        // ignore
                    }
                }
            }
            return "";
        }

    }

    final class Parent implements Revision {

        private final Revision rev;

        public Parent(Revision rev) {
            if (rev == null) {
                throw new IllegalArgumentException("rev must not be null");
            }
            this.rev = rev;
        }

        public Revision rev() {
            return rev;
        }

        @Override
        public Kind getKind() {
            return Kind.PARENT;
        }

        @Override
        public String getCommitId(Repository repo) throws IOException {
            String base = rev.getCommitId(repo);
            String parent = parseParentOid(repo.getDatabase(), base);
            if (parent == null) {
                throw new RevisionParseException("no parent for commit: " + base);
            }
            return parent;
        }
    }

    final class Ancestor implements Revision {

        private final Revision rev;
        private final int n;

        public Ancestor(Revision rev, int n) {
            if (rev == null) {
                throw new IllegalArgumentException("rev must not be null");
            }
            if (n < 1) {
                throw new IllegalArgumentException("n must be >= 1");
            }
            this.rev = rev;
            this.n = n;
        }

        public Revision rev() {
            return rev;
        }

        public int n() {
            return n;
        }

        @Override
        public Kind getKind() {
            return Kind.ANCESTOR;
        }

        @Override
        public String getCommitId(Repository repo) throws IOException {
            String cur = rev.getCommitId(repo);
            for (int i = 0; i < n; i++) {
                String parent = parseParentOid(repo.getDatabase(), cur);
                if (parent == null) {
                    throw new RevisionParseException("no ancestor at depth " + (i + 1) + " for " + cur);
                }
                cur = parent;
            }
            return cur;
        }
    }

    static String parseParentOid(ObjectDatabase db, String oid) throws IOException {
        GitObject raw = db.load(oid);
        if (!"commit".equals(raw.getType())) {
            throw new RevisionParseException("not a commit: " + oid);
        }
        return ((Commit) raw).getParentOid();
    }
}
