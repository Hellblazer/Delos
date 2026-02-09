/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.consensus;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.ethereal.DataSource;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Factory for creating ConsensusOracle instances with the required callbacks and data sources.
 * This allows CHOAM Producer to create consensus engines without knowing the concrete implementation.
 * <p>
 * The factory pattern encapsulates the complex wiring of consensus engines (transaction data sources,
 * result callbacks, epoch transition callbacks, and verifier setup) while maintaining clean
 * abstraction boundaries.
 * <p>
 * Implementations should:
 * - Create consensus engine instances (e.g., Ethereal) with appropriate configuration
 * - Wire the provided callbacks and data source
 * - Set up gossip integration as needed
 * - Return a ConsensusOracle that manages the engine's lifecycle
 * <p>
 * Thread Safety: Factory instances may be called from multiple threads to create different
 * ConsensusOracle instances. Implementations should be stateless or thread-safe.
 *
 * @author hal.hildebrand
 */
@FunctionalInterface
public interface ConsensusOracleFactory {

    /**
     * Create a ConsensusOracle instance with the specified configuration.
     * <p>
     * The callbacks and data source are wired into the consensus engine during construction:
     * - dataSource: Provides transactions for consensus (engine pulls data)
     * - serialCallback: Called when consensus produces a block (preblock, isLast)
     * - newEpochCallback: Called on epoch boundaries with new epoch number
     * - verifiers: Array of verifiers for block validation
     *
     * @param dataSource       the transaction data source for consensus to pull from
     * @param serialCallback   callback for consensus results (preblock, isLast flag)
     * @param newEpochCallback callback for epoch transitions (epoch number)
     * @param label            diagnostic label for this consensus instance
     * @param verifiers        array of verifiers for BFT block validation
     * @return a ConsensusOracle managing the consensus engine lifecycle
     * @throws IllegalArgumentException if parameters are invalid (null callbacks, empty verifiers, etc.)
     */
    ConsensusOracle create(DataSource dataSource, BiConsumer<List<ByteString>, Boolean> serialCallback,
                           Consumer<Integer> newEpochCallback, String label, Verifier[] verifiers);
}
