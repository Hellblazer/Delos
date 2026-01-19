/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.cryptography.*;
import com.hellblazer.delos.cryptography.proto.Sig;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.BasicIdentifier;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.proto.WitnessReceipt;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.hellblazer.delos.stereotomy.event.proto.KERL_;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Helper for creating test witness receipts and providing test utilities
 * for Byzantine fault injection, recovery, and performance testing.
 */
public class WitnessReceiptTestHelper {

    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;
    private final SecureRandom entropy = new SecureRandom();
    private final ConcurrentHashMap<String, Object> keyStateCache = new ConcurrentHashMap<>();

    /**
     * Create a witness receipt with specified signature count.
     *
     * @param eventCoordinates Event being receipted
     * @param signatureCount Number of signatures to include
     * @return Receipt with specified signature count
     */
    public static WitnessReceipt createReceipt(EventCoordinates eventCoordinates, int signatureCount) {
        var builder = WitnessReceipt.newBuilder()
            .setEventCoordinates(eventCoordinates.toEventCoords())
            .setEventDigest(ALGORITHM.digest("content".getBytes()).toDigeste())
            .setEpoch(0)
            .setViewRef(ALGORITHM.digest("view".getBytes()).toDigeste());

        // Add signatures - Sig uses code/sequenceNumber fields
        IntStream.range(0, signatureCount).forEach(i -> {
            var sig = com.hellblazer.delos.cryptography.proto.Sig.newBuilder()
                .setCode(i)
                .setSequenceNumber((long) i)
                .build();
            builder.addSignatures(sig);
        });

        // Set timestamp
        var now = Instant.now();
        builder.setTimestamp(Timestamp.newBuilder()
            .setSeconds(now.getEpochSecond())
            .setNanos(now.getNano())
            .build());

        return builder.build();
    }

    /**
     * Create a receipt with invalid signature count.
     *
     * @param eventCoordinates Event being receipted
     * @param signatureCount Number of signatures (less than threshold)
     * @return Receipt with insufficient signatures
     */
    public static WitnessReceipt createInvalidReceipt(EventCoordinates eventCoordinates, int signatureCount) {
        return createReceipt(eventCoordinates, signatureCount);
    }

    // Byzantine Testing Support

    public TestSigner createTestSigner(String identifier) {
        var keyPair = SignatureAlgorithm.ED_25519.generateKeyPair(entropy);
        return new TestSigner(keyPair, identifier);
    }

    public KERL_ createTestKERL() {
        return createTestKERL("test-event-" + entropy.nextInt());
    }

    public KERL_ createTestKERL(String identifier) {
        // KERL_ only contains events, not identifier or sequence number
        return KERL_.newBuilder().build();
    }

    public byte[] tamperSignature(byte[] signature) {
        var tampered = Arrays.copyOf(signature, signature.length);
        tampered[tampered.length / 2] ^= 0xFF; // Flip bits in middle
        return tampered;
    }

    public boolean validateSignature(KERL_ event, byte[] signature, PublicKey publicKey) {
        try {
            var sig = Sig.newBuilder()
                .setCode(SignatureAlgorithm.ED_25519.signatureCode())
                .setSequenceNumber(0)
                .addSignatures(ByteString.copyFrom(signature))
                .build();
            return SignatureAlgorithm.ED_25519.verify(publicKey,
                JohnHancock.of(sig),
                event.toByteString());
        } catch (Exception e) {
            return false;
        }
    }

    public boolean validateSignatureGeneric(KERL_ event, byte[] signature) {
        // Generic validation without specific public key
        return signature != null && signature.length > 0;
    }

    public List<String> createTestIdentifiers(int count) {
        var identifiers = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            identifiers.add("member-" + i);
        }
        return identifiers;
    }

    // Recovery Testing Support

    public void writeCheckpoint(Path path, CheckpointData context, long blockHeight) throws IOException {
        try (var oos = new ObjectOutputStream(new FileOutputStream(path.toFile()))) {
            oos.writeLong(context.viewNumber());
            oos.writeInt(context.committeeSize());
            oos.writeObject(context.committeeIdentifiers());
            oos.writeLong(blockHeight);
        }
    }

    public CheckpointData readCheckpoint(Path path) throws IOException, ClassNotFoundException {
        try (var ois = new ObjectInputStream(new FileInputStream(path.toFile()))) {
            var viewNumber = ois.readLong();
            var committeeSize = ois.readInt();
            @SuppressWarnings("unchecked")
            var identifiers = (List<String>) ois.readObject();
            var blockHeight = ois.readLong();
            return new CheckpointData(viewNumber, committeeSize, identifiers, blockHeight);
        }
    }

    public void writePartialCheckpoint(Path path, long viewNumber, long blockHeight) throws IOException {
        try (var oos = new ObjectOutputStream(new FileOutputStream(path.toFile()))) {
            oos.writeLong(viewNumber);
            oos.writeLong(blockHeight);
            // Intentionally incomplete - missing committee data
        }
    }

    public boolean validateCheckpoint(Path path) {
        try {
            readCheckpoint(path);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void writeBlockHeight(Path logPath, long height) throws IOException {
        Files.writeString(logPath, height + "\n");
    }

    public void appendBlock(Path logPath, long blockNumber) throws IOException {
        Files.writeString(logPath, blockNumber + "\n",
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND);
    }

    public long scanForHighestBlock(Path logPath) throws IOException {
        return Files.lines(logPath)
            .filter(line -> !line.isEmpty())
            .mapToLong(Long::parseLong)
            .max()
            .orElse(0L);
    }

    public List<Long> detectGaps(Path logPath) throws IOException {
        var blocks = Files.lines(logPath)
            .filter(line -> !line.isEmpty())
            .map(Long::parseLong)
            .sorted()
            .toList();

        var gaps = new ArrayList<Long>();
        for (int i = 0; i < blocks.size() - 1; i++) {
            var current = blocks.get(i);
            var next = blocks.get(i + 1);
            for (long gap = current + 1; gap < next; gap++) {
                gaps.add(gap);
            }
        }
        return gaps;
    }

    public List<Long> requestMissingBlocks(List<Long> gaps) {
        // Simulate requesting missing blocks from peers
        return new ArrayList<>(gaps);
    }

    public void replayLog(Path logPath, Consumer<Long> blockHandler) throws IOException {
        Files.lines(logPath)
            .filter(line -> !line.isEmpty())
            .map(Long::parseLong)
            .forEach(blockHandler);
    }

    public long replayLogFrom(Path logPath, long startHeight) throws IOException {
        return Files.lines(logPath)
            .filter(line -> !line.isEmpty())
            .map(Long::parseLong)
            .filter(block -> block > startHeight)
            .max(Long::compare)
            .orElse(startHeight);
    }

    public void writeCheckpointWithCollections(Path path, long viewNumber, long blockHeight,
                                               List<InFlightCollection> collections) throws IOException {
        try (var oos = new ObjectOutputStream(new FileOutputStream(path.toFile()))) {
            oos.writeLong(viewNumber);
            oos.writeLong(blockHeight);
            oos.writeObject(collections);
        }
    }

    @SuppressWarnings("unchecked")
    public CheckpointWithCollections readCheckpointWithCollections(Path path)
            throws IOException, ClassNotFoundException {
        try (var ois = new ObjectInputStream(new FileInputStream(path.toFile()))) {
            var viewNumber = ois.readLong();
            var blockHeight = ois.readLong();
            var collections = (List<InFlightCollection>) ois.readObject();
            return new CheckpointWithCollections(viewNumber, blockHeight, collections);
        }
    }

    public InFlightCollection createInFlightCollection(String id, int current, int threshold) {
        return new InFlightCollection(id, current, threshold);
    }

    public void corruptBlock(Path logPath, long blockNumber) throws IOException {
        var lines = Files.readAllLines(logPath);
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            if (line.isEmpty()) continue;
            // Handle both "blockNumber" and "blockNumber:checksum" formats
            var parts = line.split(":");
            if (parts.length > 0) {
                var lineBlockNumber = Long.parseLong(parts[0]);
                if (lineBlockNumber == blockNumber) {
                    // Corrupt by changing the checksum to an invalid value
                    lines.set(i, blockNumber + ":99999");
                    break;
                }
            }
        }
        Files.write(logPath, lines);
    }

    public List<Long> requestBlocksFrom(long start, long end) {
        var blocks = new ArrayList<Long>();
        for (long i = start; i <= end; i++) {
            blocks.add(i);
        }
        return blocks;
    }

    public long calculateBlockChecksum(long blockNumber) {
        return blockNumber * 31; // Simple checksum
    }

    public void appendBlockWithChecksum(Path logPath, long blockNumber, long checksum) throws IOException {
        Files.writeString(logPath, blockNumber + ":" + checksum + "\n",
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND);
    }

    public List<Long> validateLogIntegrity(Path logPath) throws IOException {
        var errors = new ArrayList<Long>();
        var lines = Files.readAllLines(logPath);
        for (var line : lines) {
            if (line.isEmpty()) continue;
            var parts = line.split(":");
            if (parts.length == 2) {
                var block = Long.parseLong(parts[0]);
                var checksum = Long.parseLong(parts[1]);
                var expected = calculateBlockChecksum(block);
                if (checksum != expected) {
                    errors.add(block);
                }
            }
        }
        return errors;
    }

    public void tamperBlock(Path logPath, long blockNumber) throws IOException {
        corruptBlock(logPath, blockNumber);
    }

    // Performance Testing Support

    public void performViewChange(long viewNumber, int committeeSize) {
        // Simulate view change coordination
        try {
            Thread.sleep(entropy.nextInt(10)); // Simulate work
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void executeDrainPeriod(long targetMs) {
        var start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < targetMs) {
            Thread.onSpinWait();
        }
    }

    public List<byte[]> collectReceipts(KERL_ event, List<TestSigner> signers, int threshold) {
        var signatures = new ArrayList<byte[]>();
        for (int i = 0; i < Math.min(threshold, signers.size()); i++) {
            try {
                var hancock = signers.get(i).sign(event.toByteString());
                // getBytes() returns byte[][], take first signature
                var bytes = hancock.getBytes();
                if (bytes != null && bytes.length > 0 && bytes[0] != null) {
                    signatures.add(bytes[0]);
                }
            } catch (Exception e) {
                // Skip failed signatures
            }
        }
        return signatures;
    }

    public void cacheKeyState(String key, Object state) {
        keyStateCache.put(key, state);
    }

    public Object createTestKeyState() {
        return new Object(); // Placeholder key state
    }

    public Object lookupKeyState(String key) {
        return keyStateCache.get(key);
    }

    public void clearKeyCache() {
        keyStateCache.clear();
    }

    public Object lookupKeyStateWithRetry(String key, int maxRetries) {
        for (int i = 0; i < maxRetries; i++) {
            var state = keyStateCache.get(key);
            if (state != null) {
                return state;
            }
            try {
                Thread.sleep(10); // Simulate retry delay
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    // Data classes for checkpoint recovery

    public record CheckpointData(long viewNumber, int committeeSize,
                                  List<String> committeeIdentifiers, long blockHeight) {
    }

    public record CheckpointWithCollections(long viewNumber, long blockHeight,
                                            List<InFlightCollection> inFlightCollections) {}

    public record InFlightCollection(String id, int currentSignatures, int threshold)
    implements Serializable {}

    /**
     * Test wrapper for Signer that provides public key access for testing.
     */
    public static class TestSigner {
        private final KeyPair keyPair;
        private final Identifier identifier;
        private final Signer signer;

        public TestSigner(KeyPair keyPair, String identifierStr) {
            this.keyPair = keyPair;
            // Create a basic identifier from the public key
            this.identifier = new BasicIdentifier(keyPair.getPublic());
            this.signer = new Signer.SignerImpl(keyPair.getPrivate(), org.joou.ULong.MIN);
        }

        public JohnHancock sign(ByteString... data) {
            return signer.sign(data);
        }

        public JohnHancock sign(byte[]... data) {
            return signer.sign(data);
        }

        public PublicKey getPublicKey() {
            return keyPair.getPublic();
        }

        public Identifier getIdentifier() {
            return identifier;
        }
    }
}
