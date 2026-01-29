/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
/*
 * Portions copyright (c) 2025, Hal Hildebrand.
 * Modifications made under GNU Affero General Public License.
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.impl.TekuBLSProvider;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.committee.CommitteeBLSKeyStore;
import com.hellblazer.delos.witness.committee.CommitteeKeyCache;
import com.hellblazer.delos.witness.committee.InMemoryCommitteeBLSKeyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedSet;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Witness subset context with dynamic committee selection using ring iterator pattern.
 * <p>
 * Provides deterministic committee selection for witness network operations:
 * - Committee selection via ring iterator: committee = context.bftSubset(H(eventCoordinates))
 * - Per-event variation prevents predictable targeting attacks
 * - Epoch synchronization with Fireflies view changes
 * - Drain period for graceful view transitions
 * </p>
 * <p>
 * Thread-safe: Committee selection and view changes use read-write locks.
 * </p>
 * <p>
 * Phase 1C-1-D-D: Integrated CommitteeKeyCache for pre-parsing BLS keys during view changes.
 * The cache is populated during the 500ms drain period, eliminating per-receipt parsing overhead.
 * </p>
 */
public class WitnessContext {

    private static final Logger log = LoggerFactory.getLogger(WitnessContext.class);

    private final Context<?> firefliesContext;
    private final WitnessParameters parameters;
    private final DigestAlgorithm digestAlgorithm;
    private final ReadWriteLock lock;
    private final CommitteeBLSKeyStore committeeBLSKeyStore;
    private final CommitteeKeyCache committeeKeyCache;

    // Phase 1A-3-B: KERL integration for committee member verification
    private volatile WitnessKerlIntegration kerlIntegration;
    private volatile boolean kerlVerificationEnabled = false;

    private volatile long currentEpoch;
    private volatile Set<Identifier> currentMembers;

    /**
     * Create witness context wrapping Fireflies context.
     *
     * @param firefliesContext Parent Fireflies context (provides ring iterator)
     * @param parameters       Witness configuration (k, threshold, epoch, drain period)
     */
    public WitnessContext(Context<?> firefliesContext, WitnessParameters parameters) {
        this(firefliesContext, parameters, DigestAlgorithm.DEFAULT);
    }

    /**
     * Create witness context with specific digest algorithm.
     * <p>
     * Backward-compatible constructor - delegates to 4-parameter constructor
     * with default InMemoryCommitteeBLSKeyStore.
     *
     * @param firefliesContext Parent Fireflies context
     * @param parameters       Witness configuration
     * @param digestAlgorithm  Algorithm for event hashing
     */
    public WitnessContext(Context<?> firefliesContext, WitnessParameters parameters,
                          DigestAlgorithm digestAlgorithm) {
        this(firefliesContext, parameters, digestAlgorithm, new InMemoryCommitteeBLSKeyStore());
    }

    /**
     * Create witness context with specific digest algorithm and BLS key store.
     * <p>
     * Primary constructor for Phase 1B-3. Allows injection of custom CommitteeBLSKeyStore
     * implementation (e.g., CHOAM-backed persistence in Phase 1C).
     * <p>
     * Phase 1C-1-D-D: Initializes CommitteeKeyCache with default BLS provider for
     * pre-computing parsed keys during view changes.
     *
     * @param firefliesContext     Parent Fireflies context
     * @param parameters           Witness configuration
     * @param digestAlgorithm      Algorithm for event hashing
     * @param committeeBLSKeyStore Storage for committee member BLS public keys
     * @throws NullPointerException if any parameter is null
     */
    public WitnessContext(Context<?> firefliesContext, WitnessParameters parameters,
                          DigestAlgorithm digestAlgorithm, CommitteeBLSKeyStore committeeBLSKeyStore) {
        this.firefliesContext = Objects.requireNonNull(firefliesContext, "firefliesContext cannot be null");
        this.parameters = Objects.requireNonNull(parameters, "parameters cannot be null");
        this.digestAlgorithm = Objects.requireNonNull(digestAlgorithm, "digestAlgorithm cannot be null");
        this.committeeBLSKeyStore = Objects.requireNonNull(committeeBLSKeyStore, "committeeBLSKeyStore cannot be null");
        this.lock = new ReentrantReadWriteLock();
        this.currentEpoch = parameters.epoch();

        // Initialize CommitteeKeyCache with default BLS provider
        this.committeeKeyCache = new CommitteeKeyCache(TekuBLSProvider.getInstance());

        // Initialize current members from Fireflies context
        this.currentMembers = firefliesContext.allMembers()
            .map(Member::getId)
            .map(this::toIdentifier)
            .collect(Collectors.toSet());
    }

    /**
     * Select witness committee for specific event using ring iterator.
     * <p>
     * CRITICAL: Deterministic - same event always yields same committee.
     * Algorithm:
     * 1. Hash event coordinates deterministically
     * 2. Use hash as point on rings: context.bftSubset(hash)
     * 3. Return k unique successors from rings
     * 4. (Phase 1A-3-B) Filter by KERL KeyState verification if enabled
     * </p>
     *
     * @param eventCoordinates The event being witnessed
     * @return k-member committee selected via context.bftSubset(hash)
     */
    public Set<Identifier> selectCommittee(EventCoordinates eventCoordinates) {
        lock.readLock().lock();
        try {
            // Compute deterministic hash from event coordinates
            var eventHash = hashEventCoordinates(eventCoordinates);

            // Select committee via proven ring iterator pattern
            var bftSubset = firefliesContext.bftSubset(eventHash);

            // Convert Member to Identifier
            var candidates = bftSubset.stream()
                .map(Member::getId)
                .map(this::toIdentifier)
                .limit(parameters.k() * 2)  // Get extra candidates for KERL filtering
                .collect(Collectors.toSet());

            // Phase 1A-3-B: Filter by KERL verification if enabled
            if (kerlVerificationEnabled && kerlIntegration != null) {
                try {
                    return kerlIntegration.filterValidMembers(candidates, parameters.k());
                } catch (WitnessKerlIntegration.InsufficientCommitteeException e) {
                    log.warn("KERL filtering failed, falling back to unfiltered committee: {}", e.getMessage());
                    // Fall back to unfiltered committee to maintain availability
                }
            }

            // Return k members without KERL filtering
            return candidates.stream()
                .limit(parameters.k())
                .collect(Collectors.toSet());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Validate that witness is selected for event's committee.
     * Used by receipt generation to determine if witness should sign.
     *
     * @param witness          Witness identifier to check
     * @param eventCoordinates Event coordinates
     * @return true if witness is in committee for this event
     */
    public boolean isCommitteeMember(Identifier witness, EventCoordinates eventCoordinates) {
        var committee = selectCommittee(eventCoordinates);
        return committee.contains(witness);
    }

    /**
     * Get current fault tolerance parameter.
     * f = (k-1)/3, computed from parameters.
     *
     * @return Maximum Byzantine failures tolerated
     */
    public int getFaultTolerance() {
        return parameters.toleranceLevel();
    }

    /**
     * Get current threshold (M) for receipt validity.
     * M > (2*k)/3 per KERI requirement.
     *
     * @return Signature threshold
     */
    public int getThreshold() {
        return parameters.threshold();
    }

    /**
     * Get current epoch (synchronized with Fireflies view changes).
     *
     * @return Current epoch number
     */
    public long getEpoch() {
        return currentEpoch;
    }

    /**
     * Handle Fireflies view change: update epoch and drain period.
     * Called when Fireflies detects view membership change.
     * <p>
     * Phase 1C-1-D-D: Pre-computes BLS keys for new committee members during the drain period.
     * This eliminates per-receipt parsing overhead by caching parsed keys before verification resumes.
     *
     * @param newMembers Updated Fireflies view members
     * @param newEpoch   New epoch number
     * @return drain period duration (500ms) for completing in-flight collections
     */
    public Duration handleViewChange(Set<Identifier> newMembers, long newEpoch) {
        lock.writeLock().lock();
        try {
            var startTime = System.nanoTime();

            // Update members and epoch
            currentMembers = Set.copyOf(newMembers);
            currentEpoch = newEpoch;

            // Extract BLS public keys for new members from key store
            var committeeKeys = extractCommitteeKeys(newMembers);

            // Pre-compute parsed keys during drain period
            log.info("View change epoch={} members={}, pre-parsing {} committee keys",
                     newEpoch, newMembers.size(), committeeKeys.size());

            committeeKeyCache.clearAndPrecompute(committeeKeys);

            var elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            log.info("Committee keys pre-computed in {}ms (cache size: {})",
                     elapsedMs, committeeKeyCache.getSize());

            return parameters.drainPeriod();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Extract raw BLS public keys for committee members from key store.
     * <p>
     * Filters to only members with registered keys. Used for cache pre-computation.
     *
     * @param members Committee members to extract keys for
     * @return Map of member IDs to raw public key bytes (48 bytes each)
     */
    private Map<Identifier, byte[]> extractCommitteeKeys(Set<Identifier> members) {
        var keys = new HashMap<Identifier, byte[]>();
        for (var memberId : members) {
            var publicKeyOpt = committeeBLSKeyStore.getPublicKey(memberId);
            publicKeyOpt.ifPresent(publicKey -> {
                keys.put(memberId, publicKey.g1Compressed());
            });
        }
        return keys;
    }

    /**
     * Validate all committee members are current Fireflies members.
     * Used during receipt validation.
     *
     * @param committee Committee to validate
     * @return true if all members are current
     */
    public boolean validateCommittee(Set<Identifier> committee) {
        lock.readLock().lock();
        try {
            return currentMembers.containsAll(committee);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Hash event coordinates deterministically for committee selection.
     * <p>
     * Combines:
     * - Event identifier
     * - Sequence number
     * - Event digest
     * - Event ilk (inception/rotation/interaction)
     * </p>
     *
     * @param eventCoordinates Event to hash
     * @return Deterministic hash for ring iterator
     */
    private Digest hashEventCoordinates(EventCoordinates eventCoordinates) {
        // Extract components
        var identifierDigest = eventCoordinates.getIdentifier().getDigest(digestAlgorithm);
        var identifierBytes = identifierDigest.getBytes();
        var sequenceNumber = eventCoordinates.getSequenceNumber().longValue();
        var digestBytes = eventCoordinates.getDigest().getBytes();
        var ilkBytes = eventCoordinates.getIlk().getBytes();

        // Combine into deterministic hash
        var buffer = ByteBuffer.allocate(
            identifierBytes.length + 8 + digestBytes.length + ilkBytes.length
        );
        buffer.put(identifierBytes);
        buffer.putLong(sequenceNumber);
        buffer.put(digestBytes);
        buffer.put(ilkBytes);

        return digestAlgorithm.digest(buffer.array());
    }

    /**
     * Refresh committee cache after view change.
     * Updates internal state to reflect new Fireflies membership.
     * Called during view change propagation.
     *
     * @return Updated member count
     */
    public int refreshCommittee() {
        lock.writeLock().lock();
        try {
            currentMembers = firefliesContext.allMembers()
                .map(Member::getId)
                .map(this::toIdentifier)
                .collect(Collectors.toSet());
            return currentMembers.size();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get current committee members (immutable view).
     *
     * @return Current members
     */
    public Set<Identifier> getCurrentMembers() {
        lock.readLock().lock();
        try {
            return Set.copyOf(currentMembers);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Update committee membership for view change.
     * Thread-safe update with ReadWriteLock.
     *
     * @param newMembers New committee members
     */
    public void updateCommitteeMembers(Set<Identifier> newMembers) {
        lock.writeLock().lock();
        try {
            currentMembers = Set.copyOf(newMembers);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Convert Digest to Identifier.
     * Helper for compatibility between membership and stereotomy types.
     *
     * @param digest Digest to convert
     * @return Identifier wrapping digest
     */
    public Identifier toIdentifier(Digest digest) {
        return new SelfAddressingIdentifier(digest);
    }

    /**
     * Get the CommitteeBLSKeyStore for managing committee member BLS public keys.
     * <p>
     * Provides access to the BLS key storage for committee key registration,
     * retrieval, and removal. Used by KeyRegistrationService and receipt validators.
     * <p>
     * <b>Phase 1B-3 Implementation</b>: Returns the CommitteeBLSKeyStore instance
     * provided during construction (defaults to InMemoryCommitteeBLSKeyStore).
     * <p>
     * The returned store allows:
     * <ul>
     *   <li>Key registration via {@link CommitteeBLSKeyStore#registerKey}</li>
     *   <li>Key retrieval via {@link CommitteeBLSKeyStore#getPublicKey}</li>
     *   <li>Batch retrieval via {@link CommitteeBLSKeyStore#getPublicKeys}</li>
     *   <li>Key removal via {@link CommitteeBLSKeyStore#removeKey}</li>
     * </ul>
     *
     * @return The committee BLS key store instance
     */
    public CommitteeBLSKeyStore getCommitteeBLSKeys() {
        return committeeBLSKeyStore;
    }

    /**
     * Get the CommitteeKeyCache for accessing pre-parsed BLS keys.
     * <p>
     * Phase 1C-1-D-D: Provides access to the cache of pre-parsed BLS keys that are
     * populated during view changes. This cache eliminates per-receipt parsing overhead
     * during signature verification.
     * <p>
     * The cache is automatically managed:
     * <ul>
     *   <li>Cleared and repopulated during view changes via {@link #handleViewChange}</li>
     *   <li>Contains only current committee members with registered keys</li>
     *   <li>Thread-safe for concurrent access during verification</li>
     *   <li>Provides metrics (hits, misses, evictions) for observability</li>
     * </ul>
     * <p>
     * Typical usage pattern:
     * <pre>
     * var cache = witnessContext.getCommitteeKeyCache();
     * var parsedKey = cache.get(memberId);  // Cache hit during verification
     * if (parsedKey != null) {
     *     // Use cached key for fast verification
     * }
     * </pre>
     *
     * @return The committee key cache instance
     */
    public CommitteeKeyCache getCommitteeKeyCache() {
        return committeeKeyCache;
    }

    /**
     * Enable KERL verification for committee member selection.
     * <p>
     * Phase 1A-3-B: When enabled, committee members are filtered by KERL KeyState
     * verification. Only members with valid, non-revoked keys are included in committees.
     *
     * @param kerlIntegration The KERL integration instance
     */
    public void enableKerlVerification(WitnessKerlIntegration kerlIntegration) {
        this.kerlIntegration = kerlIntegration;
        this.kerlVerificationEnabled = true;
        log.info("KERL verification enabled for committee selection");
    }

    /**
     * Disable KERL verification for committee member selection.
     * <p>
     * When disabled, all Fireflies members are eligible for committee membership
     * without KeyState verification (backward-compatible behavior).
     */
    public void disableKerlVerification() {
        this.kerlVerificationEnabled = false;
        log.info("KERL verification disabled for committee selection");
    }

    /**
     * Check if KERL verification is enabled.
     *
     * @return true if KERL verification is active
     */
    public boolean isKerlVerificationEnabled() {
        return kerlVerificationEnabled && kerlIntegration != null;
    }

    /**
     * Get the KERL integration instance.
     * <p>
     * Returns null if KERL verification is not configured.
     *
     * @return KERL integration or null
     */
    public WitnessKerlIntegration getKerlIntegration() {
        return kerlIntegration;
    }
}
