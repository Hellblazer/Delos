package com.hellblazer.delos.cryptography;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author hal.hildebrand
 **/
public class RsaAccumulatorTest {
    @Test
    public void testAccumulator() {
        var entropy = new SecureRandom();
        entropy.setSeed(0x666);
        for (int i = 0; i < 100; i++) {
            var mem = RsaAccumulator.next(entropy);
            var accu1 = new RsaAccumulator(entropy);
            var a1 = accu1.add(mem);
            var witness1 = accu1.prove(mem);
            var nonce1 = accu1.nonce(mem);
            var n1 = accu1.n();
            try {
                assertTrue(RsaAccumulator.verify(a1, mem, nonce1, witness1, n1));
            } catch (AssertionError e) {
                System.err.print(
                "False negative:" + "mem:   " + mem.toString() + ", " + "accu1: " + accu1.toString() + ", " + "a1:    "
                + a1.toString() + ", " + "witness1:  " + witness1.toString() + ", " + "nonce1:    " + nonce1.toString()
                + ", " + "n1:    " + n1.toString() + ", ");
            }

            final var nonce2 = nonce1.add(BigInteger.ONE);
            try {
                assertFalse(RsaAccumulator.verify(a1, mem, nonce2, witness1, n1));
            } catch (AssertionError e) {
                System.out.println(
                "False positive:" + "mem:   " + mem.toString() + ", " + "accu1: " + accu1 + ", " + "a1:    "
                + a1.toString() + ", " + "witness1:  " + witness1.toString() + ", " + "nonce2:    " + nonce2.toString()
                + ", " + "n1:    " + n1.toString() + ", ");
            }
        }
    }
}
