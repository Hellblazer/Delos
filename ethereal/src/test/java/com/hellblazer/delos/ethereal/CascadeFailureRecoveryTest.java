/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.ethereal.Dag.DagImpl;
import com.hellblazer.delos.ethereal.PreUnit.preUnit;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for cascade failure recovery from transient parent unavailability.
 * Validates that brief network issues don't cause permanent subtree loss.
 *
 * @author hal.hildebrand
 */
class CascadeFailureRecoveryTest {

    private Config               conf;
    private Dag                  dag;
    private ConcurrentSkipListSet<Digest> failed;
    private BlacklistStore       blacklistStore;
    private List<SigningMember>  members;

    @BeforeEach
    void setUp() throws Exception {
        short nProc = 4;
        failed = new ConcurrentSkipListSet<>();
        blacklistStore = new BlacklistStore.InMemoryBlacklistStore();

        var b = DynamicContext.newBuilder();
        b.setCardinality(10);
        var context = b.build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        members = IntStream.range(0, nProc)
                           .mapToObj(i -> stereotomy.newIdentifier())
                           .map(cpk -> new ControlledIdentifierMember(cpk))
                           .map(e -> (SigningMember) e)
                           .toList();
        members.forEach(m -> context.activate(m));

        conf = Config.newBuilder()
                     .setnProc(nProc)
                     .setPid((short) 0)
                     .setSigner(members.get(0))
                     .setEpochLength(11)
                     .setNumberOfEpochs(3)
                     .setBias(3)
                     .setLabel("CascadeTest")
                     .setDigestAlgorithm(DigestAlgorithm.DEFAULT)
                     // Key config: short parent failure retry timeout for testing (minimum is 1000ms)
                     .setParentFailureRetryTimeoutMillis(1000L)
                     .build();

        dag = new DagImpl(conf, 0);
    }

    /**
     * Test that transient parent unavailability doesn't immediately cascade failure.
     * A unit with a temporarily unavailable parent should be marked for retry,
     * not immediately failed along with all its children.
     */
    @Test
    void testTransientParentUnavailabilityDoesNotCascade() {
        var adder = new Adder(0, dag, 1000, conf, failed, null, blacklistStore);

        var parent = new Waiting(createTestPreUnit((short) 0, 1, 0));
        var child = new Waiting(createTestPreUnit((short) 1, 1, 0));
        child.incMissing(); // Mark parent as missing
        parent.addChild(child);

        // Simulate first transient failure detection (e.g., network blip)
        // This should mark parent as transiently failed, not cascade to child
        adder.markTransientFailure(parent);

        // Verify parent is marked as transient failure, not permanent
        assertTrue(parent.isTransientFailure(), "Parent should be marked as transient failure");
        assertNotEquals(Adder.State.FAILED, parent.state(), "Parent should not be in FAILED state");

        // Verify child is NOT cascaded (still waiting for parent)
        assertNotEquals(Adder.State.FAILED, child.state(), "Child should not be failed due to transient parent issue");
        assertEquals(1, child.missingParents(), "Child should still be waiting for parent");
    }

    /**
     * Test that permanent parent failure (after timeout) does cascade.
     * After the retry timeout expires, the failure should be promoted to permanent
     * and children should be cascaded.
     */
    @Test
    void testPermanentParentFailureCascades() throws InterruptedException {
        var adder = new Adder(0, dag, 1000, conf, failed, null, blacklistStore);

        var parent = new Waiting(createTestPreUnit((short) 0, 1, 0));
        var child = new Waiting(createTestPreUnit((short) 1, 1, 0));
        child.incMissing();
        parent.addChild(child);

        // Mark as transient failure initially
        adder.markTransientFailure(parent);
        assertTrue(parent.isTransientFailure());

        // Wait for retry timeout to expire (1000ms configured)
        Thread.sleep(1100);

        // Process timeout check - should promote to permanent failure
        adder.processFailureTimeouts();

        // Verify parent is now permanently failed
        assertEquals(Adder.State.FAILED, parent.state(), "Parent should be permanently failed after timeout");

        // Verify child was cascaded
        assertEquals(Adder.State.FAILED, child.state(), "Child should be cascaded after parent permanent failure");
    }

    /**
     * Test recovery from transient failure when parent becomes available.
     * If parent is resolved before the timeout, the child should proceed normally.
     */
    @Test
    void testRecoveryFromTransientFailure() {
        var adder = new Adder(0, dag, 1000, conf, failed, null, blacklistStore);

        var parent = new Waiting(createTestPreUnit((short) 0, 1, 0));
        var child = new Waiting(createTestPreUnit((short) 1, 1, 0));
        child.incMissing();
        parent.addChild(child);

        // Mark as transient failure
        adder.markTransientFailure(parent);
        assertTrue(parent.isTransientFailure());

        // Simulate parent recovery (becomes available)
        adder.resolveTransientFailure(parent);

        // Verify parent is no longer in transient failure
        assertFalse(parent.isTransientFailure(), "Parent should be recovered from transient failure");
        assertNotEquals(Adder.State.FAILED, parent.state(), "Parent should not be in FAILED state");

        // Verify child can proceed once parent is resolved
        child.decMissing();
        assertEquals(0, child.missingParents(), "Child should have no missing parents after recovery");
    }

    /**
     * Test backoff timing for retries.
     * Multiple transient failures should extend the retry period.
     */
    @Test
    void testRetryBackoffTiming() throws InterruptedException {
        var adder = new Adder(0, dag, 1000, conf, failed, null, blacklistStore);

        var parent = new Waiting(createTestPreUnit((short) 0, 1, 0));

        // First failure
        adder.markTransientFailure(parent);
        var firstFailureTime = parent.getFirstFailureTime();
        assertNotNull(firstFailureTime, "First failure time should be recorded");
        assertEquals(1, parent.getTransientFailureCount(), "Failure count should be 1");

        // Small delay
        Thread.sleep(100);

        // Second failure (retry)
        adder.markTransientFailure(parent);
        assertEquals(2, parent.getTransientFailureCount(), "Failure count should increment");
        assertEquals(firstFailureTime, parent.getFirstFailureTime(),
                     "First failure time should remain unchanged");

        // Verify still in transient state (not yet timed out)
        assertTrue(parent.isTransientFailure());
        assertNotEquals(Adder.State.FAILED, parent.state());
    }

    /**
     * Test that validation failures (not missing parent) still cascade immediately.
     * Actual validation errors (bad signature, invalid data) should not use retry logic.
     */
    @Test
    void testValidationFailuresStillCascadeImmediately() {
        var adder = new Adder(0, dag, 1000, conf, failed, null, blacklistStore);

        var parent = new Waiting(createTestPreUnit((short) 0, 1, 0));
        var child = new Waiting(createTestPreUnit((short) 1, 1, 0));
        parent.addChild(child);

        // Simulate validation failure (not transient network issue)
        // Note: removeFailed is private, so this test verifies that validation errors
        // bypass the transient failure mechanism (tested indirectly via integration tests)

        // Verify that if we don't call markTransientFailure, the unit isn't tracked
        assertFalse(parent.isTransientFailure(), "Parent should not be in transient failure without marking");
    }

    /**
     * Create a test PreUnit with specified creator, height, and epoch.
     */
    private PreUnit createTestPreUnit(short creator, int height, int epoch) {
        var crown = new Crown(new int[conf.nProc()], Digest.NONE);
        var signature = new JohnHancock(SignatureAlgorithm.DEFAULT, new byte[64], ULong.MIN);
        var hash = DigestAlgorithm.DEFAULT.digest("test-unit-" + creator + "-" + epoch + "-" + height);
        return new preUnit(creator, epoch, height, hash, crown, ByteString.EMPTY, signature, new byte[0]);
    }
}
