/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for QuorumResponseTracker.
 * <p>
 * Verifies response counting, member provenance tracking, and majority
 * determination for quorum operations.
 * </p>
 *
 * @author hal.hildebrand
 */
public class QuorumResponseTrackerTest {

    /**
     * Test basic response tracking.
     */
    @Test
    void testBasicResponseTracking() {
        var tracker = new QuorumResponseTracker<String>();
        var member1 = createMember("member-1");
        var member2 = createMember("member-2");

        tracker.add("response-A", member1);
        tracker.add("response-B", member2);

        assertThat(tracker.size()).isEqualTo(2);
        assertThat(tracker.isEmpty()).isFalse();
        assertThat(tracker.count("response-A")).isEqualTo(1);
        assertThat(tracker.count("response-B")).isEqualTo(1);
    }

    /**
     * Test majority response detection.
     */
    @Test
    void testMajorityDetection() {
        var tracker = new QuorumResponseTracker<String>();
        var member1 = createMember("member-1");
        var member2 = createMember("member-2");
        var member3 = createMember("member-3");
        var member4 = createMember("member-4");

        // 3 members return "correct", 1 returns "byzantine"
        tracker.add("correct", member1);
        tracker.add("correct", member2);
        tracker.add("correct", member3);
        tracker.add("byzantine", member4);

        var maxEntry = tracker.maxEntry();
        assertThat(maxEntry).isNotNull();
        assertThat(maxEntry.getElement()).isEqualTo("correct");
        assertThat(maxEntry.getCount()).isEqualTo(3);
        assertThat(tracker.maxCount()).isEqualTo(3);
    }

    /**
     * Test member provenance tracking.
     */
    @Test
    void testMemberProvenance() {
        var tracker = new QuorumResponseTracker<String>();
        var member1 = createMember("member-1");
        var member2 = createMember("member-2");
        var member3 = createMember("member-3");

        tracker.add("response-A", member1);
        tracker.add("response-A", member2);
        tracker.add("response-B", member3);

        // Verify provenance for majority response
        var providersOfA = tracker.providersOf("response-A");
        assertThat(providersOfA).hasSize(2);
        assertThat(providersOfA).contains(member1, member2);

        // Verify provenance for minority response
        var providersOfB = tracker.providersOf("response-B");
        assertThat(providersOfB).hasSize(1);
        assertThat(providersOfB).contains(member3);
    }

    /**
     * Test null member handling (defensive).
     */
    @Test
    void testNullMemberHandling() {
        var tracker = new QuorumResponseTracker<String>();

        tracker.add("response-A", null);
        tracker.add("response-A", null);

        assertThat(tracker.count("response-A")).isEqualTo(2);
        assertThat(tracker.providersOf("response-A")).isEmpty(); // No provenance for null members
    }

    /**
     * Test minority member identification.
     * <p>
     * Scenario: 3 members return "correct", 1 returns "byzantine".
     * Verify we can identify the Byzantine member.
     * </p>
     */
    @Test
    void testMinorityMemberIdentification() {
        var tracker = new QuorumResponseTracker<String>();
        var honest1 = createMember("honest-1");
        var honest2 = createMember("honest-2");
        var honest3 = createMember("honest-3");
        var byzantine = createMember("byzantine");

        tracker.add("correct", honest1);
        tracker.add("correct", honest2);
        tracker.add("correct", honest3);
        tracker.add("forged", byzantine);

        // Identify minority members
        var allResponses = tracker.allResponsesWithProviders();
        assertThat(allResponses).hasSize(2);

        var majorityValue = tracker.maxEntry().getElement();
        var minorityMembers = allResponses.entrySet()
                                          .stream()
                                          .filter(e -> !e.getKey().equals(majorityValue))
                                          .flatMap(e -> e.getValue().stream())
                                          .toList();

        assertThat(minorityMembers).hasSize(1);
        assertThat(minorityMembers).contains(byzantine);
    }

    /**
     * Test divergent responses with multiple minorities.
     * <p>
     * Scenario: 2 members return "A", 1 returns "B", 1 returns "C".
     * Verify all responses and provenance tracked correctly.
     * </p>
     */
    @Test
    void testMultipleMinorities() {
        var tracker = new QuorumResponseTracker<String>();
        var member1 = createMember("member-1");
        var member2 = createMember("member-2");
        var member3 = createMember("member-3");
        var member4 = createMember("member-4");

        tracker.add("A", member1);
        tracker.add("A", member2);
        tracker.add("B", member3);
        tracker.add("C", member4);

        assertThat(tracker.maxCount()).isEqualTo(2);
        assertThat(tracker.maxEntry().getElement()).isEqualTo("A");

        var allResponses = tracker.allResponsesWithProviders();
        assertThat(allResponses).hasSize(3);
        assertThat(allResponses.get("A")).hasSize(2);
        assertThat(allResponses.get("B")).hasSize(1);
        assertThat(allResponses.get("C")).hasSize(1);
    }

    /**
     * Test empty tracker.
     */
    @Test
    void testEmptyTracker() {
        var tracker = new QuorumResponseTracker<String>();

        assertThat(tracker.isEmpty()).isTrue();
        assertThat(tracker.size()).isEqualTo(0);
        assertThat(tracker.maxEntry()).isNull();
        assertThat(tracker.maxCount()).isEqualTo(0);
        assertThat(tracker.providersOf("nonexistent")).isEmpty();
    }

    /**
     * Test response value equality.
     * <p>
     * Verifies that response tracking uses value equality, not reference equality.
     * </p>
     */
    @Test
    void testValueEquality() {
        var tracker = new QuorumResponseTracker<String>();
        var member1 = createMember("member-1");
        var member2 = createMember("member-2");

        tracker.add(new String("response"), member1); // NOSONAR - intentional new String
        tracker.add(new String("response"), member2); // NOSONAR - intentional new String

        assertThat(tracker.count("response")).isEqualTo(2);
        assertThat(tracker.providersOf("response")).hasSize(2);
    }

    private Member createMember(String name) {
        var digest = DigestAlgorithm.DEFAULT.digest(name.getBytes());
        return new Member() {
            @Override
            public com.hellblazer.delos.cryptography.Digest getId() {
                return digest;
            }

            @Override
            public int compareTo(Member o) {
                return digest.compareTo(o.getId());
            }

            @Override
            public boolean verify(com.hellblazer.delos.cryptography.JohnHancock signature,
                                  java.io.InputStream message) {
                return true; // Mock implementation
            }

            @Override
            public boolean verify(com.hellblazer.delos.cryptography.SigningThreshold threshold,
                                  com.hellblazer.delos.cryptography.JohnHancock signature,
                                  java.io.InputStream message) {
                return true; // Mock implementation
            }
        };
    }
}
