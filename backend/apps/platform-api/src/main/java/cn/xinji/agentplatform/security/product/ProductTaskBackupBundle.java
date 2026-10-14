package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Authenticated DB-and-file bytes; quiescence and current authority are separate gates. */
public final class ProductTaskBackupBundle {
    public record SourceEpoch(long epoch, String status, String historySha) { }
    public record Snapshot(String batchId, String issuer, String primarySchema, String keyId,
            long grantGeneration, String grantHistorySha, Map<String, SourceEpoch> sourceWatermarks,
            Instant createdAt, Instant expiresAt, String retentionRef, long retentionVersion) {
        public Snapshot {
            if (sourceWatermarks != null) sourceWatermarks = Map.copyOf(sourceWatermarks);
        }
    }
    public record Entry(String name, long length, String sha256) { }
    public record Manifest(Snapshot snapshot, List<Entry> entries) {
        public Manifest {
            if (entries != null) entries = List.copyOf(entries);
        }
    }

    private static final byte[] MAGIC = "CONBKP01".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int NONCE_BYTES = 12;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ProductTaskBackupBundle(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public Manifest seal(Path export, Path evidenceRoot, List<String> references, Path target,
            byte[] key, Snapshot snapshot) throws Exception {
        validate(key, snapshot);
        if (references == null || references.isEmpty() || target == null || Files.exists(target))
            throw new IllegalArgumentException("New backup target and exact file references required");
        Path root = evidenceRoot.toRealPath();
        Path database = export.toRealPath();
        if (!Files.isRegularFile(database) || !Files.isDirectory(root)
                || target.toAbsolutePath().getParent() == null)
            throw new IllegalArgumentException("Controlled DB export and file root required");
        if (target.toAbsolutePath().getParent().toRealPath().startsWith(root))
            throw new IllegalArgumentException("Backup target overlaps controlled evidence");
        List<Path> sources = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        sources.add(database);
        entries.add(entry("db/primary.dmp", database));
        Set<String> distinct = new HashSet<>();
        for (String reference : references) {
            if (!reference(reference) || !distinct.add(reference))
                throw new IllegalArgumentException("Invalid or duplicate evidence reference");
            Path file = root.resolve(reference).toRealPath();
            if (!file.startsWith(root) || !Files.isRegularFile(file))
                throw new IllegalArgumentException("Evidence escapes the controlled root");
            sources.add(file);
            entries.add(entry("files/" + reference, file));
        }
        Manifest manifest = new Manifest(snapshot, List.copyOf(entries));
        Path output = target.toAbsolutePath();
        Path temporary = Files.createTempFile(output.getParent(), ".con-backup-", ".part");
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce);
            byte[] header = ByteBuffer.allocate(MAGIC.length + NONCE_BYTES)
                    .put(MAGIC).put(nonce).array();
            cipher.updateAAD(header);
            try (OutputStream raw = Files.newOutputStream(temporary)) {
                raw.write(header);
                try (ZipOutputStream zip = new ZipOutputStream(new CipherOutputStream(raw, cipher))) {
                    zip.putNextEntry(new ZipEntry("manifest.json"));
                    zip.write(mapper.writeValueAsBytes(manifest));
                    zip.closeEntry();
                    for (int i = 0; i < entries.size(); i++) {
                        validate(key, snapshot);
                        zip.putNextEntry(new ZipEntry(entries.get(i).name()));
                        if (!copyMatches(sources.get(i), zip, entries.get(i)))
                            throw new IllegalStateException("Backup source changed during sealing");
                        zip.closeEntry();
                    }
                }
            }
            validate(key, snapshot);
            if (Files.exists(output)) throw new IllegalStateException("Backup target appeared");
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
            validate(key, snapshot); // Late expiry refuses success; the encrypted scene remains.
            return manifest;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Manifest open(Path bundle, Path isolatedTarget, byte[] key, long maxBytes)
            throws Exception {
        if (key == null || key.length != 32 || maxBytes <= 0 || isolatedTarget == null
                || Files.exists(isolatedTarget))
            throw new IllegalArgumentException("Empty isolated target, key and size limit required");
        Path parent = isolatedTarget.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent))
            throw new IllegalArgumentException("Isolated target parent required");
        Path plaintext = Files.createTempFile(parent, ".con-restore-", ".zip");
        Path staged = Files.createTempDirectory(parent, ".con-restore-");
        try {
            try (InputStream raw = Files.newInputStream(bundle)) {
                byte[] header = raw.readNBytes(MAGIC.length + NONCE_BYTES);
                if (header.length != MAGIC.length + NONCE_BYTES
                        || !Arrays.equals(MAGIC, Arrays.copyOf(header, MAGIC.length)))
                    throw new IllegalArgumentException("Invalid backup format");
                byte[] nonce = Arrays.copyOfRange(header, MAGIC.length, header.length);
                Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, nonce);
                cipher.updateAAD(header);
                try (InputStream decrypted = new CipherInputStream(raw, cipher);
                     OutputStream output = Files.newOutputStream(plaintext)) {
                    transferBounded(decrypted, output, maxBytes);
                }
            }
            Manifest manifest;
            try (ZipFile zip = new ZipFile(plaintext.toFile())) {
                ZipEntry metadata = zip.getEntry("manifest.json");
                if (metadata == null || metadata.getSize() < 1 || metadata.getSize() > 1_000_000)
                    throw new IllegalStateException("Backup manifest missing or excessive");
                try (InputStream input = zip.getInputStream(metadata)) {
                    manifest = mapper.readValue(input, Manifest.class);
                }
                validate(key, manifest.snapshot());
                if (manifest.entries() == null || manifest.entries().isEmpty()
                        || !"db/primary.dmp".equals(manifest.entries().getFirst().name())
                        || zip.size() != manifest.entries().size() + 1)
                    throw new IllegalStateException("Incomplete backup manifest");
                long remaining = maxBytes;
                Set<String> names = new HashSet<>();
                for (Entry entry : manifest.entries()) {
                    validate(key, manifest.snapshot());
                    if (!names.add(entry.name()) || !bundleEntry(entry.name()) || entry.length() < 0
                            || entry.length() > remaining || !sha256(entry.sha256()))
                        throw new IllegalStateException("Invalid backup entry");
                    remaining -= entry.length();
                    ZipEntry archived = zip.getEntry(entry.name());
                    if (archived == null || archived.isDirectory() || archived.getSize() != entry.length())
                        throw new IllegalStateException("Backup entry missing or changed");
                    Path destination = staged.resolve(entry.name()).normalize();
                    if (!destination.startsWith(staged)) throw new IllegalStateException("Backup path escapes target");
                    Files.createDirectories(destination.getParent());
                    try (InputStream input = zip.getInputStream(archived);
                         OutputStream output = Files.newOutputStream(destination)) {
                        if (!copyMatches(input, output, entry))
                            throw new IllegalStateException("Backup entry digest changed");
                    }
                }
            }
            validate(key, manifest.snapshot());
            if (Files.exists(isolatedTarget)) throw new IllegalStateException("Restore target appeared");
            Files.move(staged, isolatedTarget, StandardCopyOption.ATOMIC_MOVE);
            validate(key, manifest.snapshot()); // Isolated bytes are not a restore/activation success.
            return manifest;
        } finally {
            Files.deleteIfExists(plaintext);
            if (Files.exists(staged)) {
                try (var paths = Files.walk(staged)) {
                    for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                        Files.deleteIfExists(path);
                }
            }
        }
    }

    private void validate(byte[] key, Snapshot snapshot) {
        if (key == null || key.length != 32 || snapshot == null
                || !reference(snapshot.batchId()) || !issuer(snapshot.issuer())
                || !reference(snapshot.primarySchema()) || !reference(snapshot.keyId())
                || snapshot.grantGeneration() < 0
                || !sha256(snapshot.grantHistorySha()) || snapshot.sourceWatermarks() == null
                || snapshot.sourceWatermarks().entrySet().stream().anyMatch(e ->
                    !reference(e.getKey()) || e.getValue() == null
                    || e.getValue().epoch() < 0
                    || e.getValue().status() == null
                    || !Set.of("READY", "WITHDRAWN").contains(e.getValue().status())
                    || !sha256(e.getValue().historySha()))
                || snapshot.createdAt() == null || snapshot.expiresAt() == null
                || snapshot.createdAt().isAfter(clock.instant())
                || !snapshot.expiresAt().isAfter(clock.instant())
                || !reference(snapshot.retentionRef()) || snapshot.retentionVersion() < 1)
            throw new IllegalArgumentException("Invalid backup authority or retention snapshot");
    }

    private static boolean reference(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,499}")
                && !value.contains("..") && !value.startsWith("/");
    }
    private static boolean issuer(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
    private static boolean bundleEntry(String value) {
        return "db/primary.dmp".equals(value)
                || (value.startsWith("files/") && reference(value.substring(6)));
    }
    private static boolean sha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }
    private static Entry entry(String name, Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1; ) {
                digest.update(buffer, 0, read);
                size += read;
            }
            return new Entry(name, size, HexFormat.of().formatHex(digest.digest()));
        }
    }
    private static boolean copyMatches(Path path, OutputStream output, Entry expected) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            return copyMatches(input, output, expected);
        }
    }
    private static boolean copyMatches(InputStream input, OutputStream output, Entry expected)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        byte[] buffer = new byte[8192];
        for (int read; (read = input.read(buffer)) != -1; ) {
            size += read;
            if (size > expected.length()) throw new IllegalStateException("Backup entry grew");
            digest.update(buffer, 0, read);
            output.write(buffer, 0, read);
        }
        return size == expected.length()
                && HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expected.sha256());
    }
    private static void transferBounded(InputStream input, OutputStream output, long limit)
            throws Exception {
        long size = 0;
        byte[] buffer = new byte[8192];
        for (int read; (read = input.read(buffer)) != -1; ) {
            size += read;
            if (size > limit) throw new IllegalStateException("Backup exceeds restore limit");
            output.write(buffer, 0, read);
        }
    }
    private static Cipher cipher(int mode, byte[] key, byte[] nonce) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher;
    }
}
