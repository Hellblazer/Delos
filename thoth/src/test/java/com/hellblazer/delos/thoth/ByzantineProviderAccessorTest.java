/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.membership.byzantine.IntelligenceConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Byzantine provider accessor method in KerlDHT.
 * <p>
 * Verifies that the accessor returns a properly configured provider
 * compatible with ByzantineIntelligenceCoordinator registration.
 * </p>
 *
 * @author hal.hildebrand
 */
public class ByzantineProviderAccessorTest extends AbstractDhtTest {

    /**
     * Test that accessor returns properly configured provider.
     * <p>
     * Verifies:
     * - Accessor returns non-null provider
     * - Provider layer name is LAYER_THOTH
     * - Provider is ready for coordinator registration
     * </p>
     */
    @Test
    void testAccessorReturnsConfiguredProvider() {
        // Setup: Create DHT instance (from AbstractDhtTest)
        // Note: Full DHT cluster setup is expensive, so this test uses
        // the test infrastructure to verify accessor method works correctly
        assertThat(dhts).isNotEmpty();
        var dht = dhts.values().iterator().next();

        // Verify accessor returns provider
        var provider = dht.getByzantineStateProvider();
        assertThat(provider).isNotNull();

        // Verify layer name matches THOTH constant
        assertThat(provider.getLayerName()).isEqualTo(IntelligenceConfig.LAYER_THOTH);

        // Verify provider is properly initialized (no anomalies initially)
        var states = provider.getMemberAnomalyStates();
        assertThat(states).isEmpty(); // Fresh provider has no tracked members
    }

    /**
     * Test that provider can be registered with coordinator.
     * <p>
     * This is a structural test verifying the accessor exposes a type
     * compatible with coordinator.registerProvider().
     * </p>
     */
    @Test
    void testProviderIsCompatibleWithCoordinator() {
        assertThat(dhts).isNotEmpty();
        var dht = dhts.values().iterator().next();

        var provider = dht.getByzantineStateProvider();

        // Verify provider implements ByzantineStateProvider interface
        assertThat(provider).isInstanceOf(com.hellblazer.delos.membership.byzantine.ByzantineStateProvider.class);

        // Verify tracked member count is accessible (coordinator polls this)
        assertThat(provider.getTrackedMemberCount()).isEqualTo(0); // Initially no tracked members
    }

    /**
     * Test that multiple DHT instances have independent providers.
     * <p>
     * Verifies that each DHT instance has its own provider instance,
     * ensuring proper isolation in multi-tenant scenarios.
     * </p>
     */
    @Test
    void testProviderInstancesAreIndependent() {
        assertThat(dhts).hasSizeGreaterThanOrEqualTo(2);

        var iter = dhts.values().iterator();
        var provider1 = iter.next().getByzantineStateProvider();
        var provider2 = iter.next().getByzantineStateProvider();

        // Verify different instances
        assertThat(provider1).isNotSameAs(provider2);

        // Verify independent state tracking
        provider1.recordValidationFailure(com.hellblazer.delos.stereotomy.identifier.Identifier.NONE, "test");
        assertThat(provider1.getTrackedMemberCount()).isEqualTo(1);
        assertThat(provider2.getTrackedMemberCount()).isEqualTo(0);
    }

    @Override
    protected int getCardinality() {
        return 4; // Minimum for multi-instance tests
    }
}
