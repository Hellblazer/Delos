package com.hellblazer.delos.stereotomy.specification;

import static com.hellblazer.delos.cryptography.SigningThreshold.group;
import static com.hellblazer.delos.cryptography.SigningThreshold.unweighted;
import static com.hellblazer.delos.cryptography.SigningThreshold.weighted;
import static com.hellblazer.delos.stereotomy.identifier.spec.KeyConfigurationDigester.signingThresholdRepresentation;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

import com.hellblazer.delos.utils.Hex;

public class KeyConfigurationDigesterTest {

    @Test
    public void test__signingThresholdRepresentation__unweighted() {
        assertArrayEquals("1".getBytes(UTF_8), signingThresholdRepresentation(unweighted(1)));

        assertArrayEquals(Hex.hexNoPad(16).getBytes(UTF_8), signingThresholdRepresentation(unweighted(16)));

    }

    @Test
    public void test__signingThresholdRepresentation__weighted() {
        assertArrayEquals("1".getBytes(UTF_8), signingThresholdRepresentation(weighted("1")));

        assertArrayEquals("1,2,3".getBytes(UTF_8), signingThresholdRepresentation(weighted("1", "2", "3")));

        assertArrayEquals("1,2,3&4,5,6".getBytes(UTF_8),
                          signingThresholdRepresentation(weighted(group("1", "2", "3"), group("4", "5", "6"))));

        assertArrayEquals("1/2,1/3,1/4".getBytes(UTF_8), signingThresholdRepresentation(weighted("1/2", "1/3", "1/4")));

        assertArrayEquals("1,1/2,1/3&1,1/4,1/5,1/6".getBytes(UTF_8),
                          signingThresholdRepresentation(weighted(group("1", "1/2", "1/3"),
                                                                  group("1", "1/4", "1/5", "1/6"))));

    }

}
