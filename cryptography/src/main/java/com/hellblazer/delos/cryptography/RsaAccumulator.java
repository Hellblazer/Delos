package com.hellblazer.delos.cryptography;

import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * @author hal.hildebrand
 **/
public class RsaAccumulator {
    private static final int CERTAINTY        = 5;
    private static final int KEY_SIZE         = 3072;
    private static final int PRIME_SIZE       = KEY_SIZE / 2;
    private static final int ACCUMULATED_SIZE = 128;

    private final Map<BigInteger, BigInteger> data = new HashMap<>();
    private final BigInteger                  N;
    private final BigInteger                  A0;
    private       BigInteger                  A;

    public RsaAccumulator(SecureRandom entropy) {
        var pair = generateTwo(PRIME_SIZE, entropy);
        var p = pair.a();
        var q = pair.b();
        N = p.multiply(q);
        A0 = next(BigInteger.ZERO, entropy, N);
        A = A0;
    }

    public static BigInteger next(SecureRandom entropy) {
        return next(BigInteger.valueOf(2).pow(256), entropy);
    }

    public static tuple generateTwo(int bitLength, Random entropy) {
        BigInteger first = generate(bitLength, entropy);
        while (true) {
            BigInteger second = generate(bitLength, entropy);
            if (first.compareTo(second) != 0) {
                return new tuple(first, second);
            }
        }
    }

    public static BigInteger generate(int bitLength, Random entropy) {
        return BigInteger.probablePrime(bitLength, entropy);
    }

    public static tuple primeHash(BigInteger x, int bitLength) {
        return primeHash(x, bitLength, BigInteger.ZERO);
    }

    private static boolean verify(BigInteger A, BigInteger x, BigInteger proof, BigInteger n) {
        return proof.modPow(x, n).compareTo(A) == 0;
    }

    public static boolean verify(BigInteger A, BigInteger x, BigInteger nonce, BigInteger proof, BigInteger n) {
        return verify(A, primeHash(x, ACCUMULATED_SIZE, nonce).a(), proof, n);
    }

    public static tuple primeHash(BigInteger x, int bitLength, BigInteger initial) {
        var nonce = initial;
        while (true) {
            var num = hash(x.add(nonce), bitLength);
            if (num.isProbablePrime(CERTAINTY)) {
                return new tuple(num, nonce);
            }
            nonce = nonce.add(BigInteger.ONE);
        }
    }

    public static BigInteger hash(BigInteger x, int bitLength) {
        var hex = new StringBuilder();
        var numOfBlocks = (int) Math.ceil(bitLength / 256.00);

        for (var i = 0; i < numOfBlocks; i++) {
            hex.append(BaseEncoding.base16()
                                   .lowerCase()
                                   .encode(Hashing.sha256()
                                                  .hashBytes(
                                                  (x.add(new BigInteger(Integer.toString(i)))).toString(10).getBytes())
                                                  .asBytes()));

        }

        if (bitLength % 256 > 0) {
            hex = new StringBuilder(hex.substring((bitLength % 256) / 4));
        }
        return new BigInteger(hex.toString(), 16);
    }

    public static BigInteger next(BigInteger from, SecureRandom entropy, BigInteger until) {
        if (from.compareTo(until) >= 0) {
            throw new IllegalArgumentException("until must be greater than from");
        }
        BigInteger randomNumber;
        int bitLength;
        if (from.bitLength() == until.bitLength()) {
            bitLength = from.bitLength();
        } else {
            var fromBitLength = from.bitLength();
            var untilBitLength = until.bitLength();
            bitLength = entropy.nextInt(fromBitLength, untilBitLength);
        }

        do {
            randomNumber = new BigInteger(bitLength, entropy);
        } while (randomNumber.compareTo(from) < 0 || randomNumber.compareTo(until) >= 0);

        return randomNumber;
    }

    public static BigInteger next(BigInteger until, SecureRandom entropy) {
        return next(BigInteger.ZERO, entropy, until);
    }

    public BigInteger add(BigInteger x) {
        if (!data.containsKey(x)) {
            var bigIntegerPair = primeHash(x, ACCUMULATED_SIZE);
            var hashPrime = bigIntegerPair.a();
            var nonce = bigIntegerPair.b();
            A = A.modPow(hashPrime, N);
            data.put(x, nonce);
        }
        return A;
    }

    public BigInteger delete(BigInteger x) {
        if (data.containsKey(x)) {
            data.remove(x);
            final var product = product(x);
            this.A = A0.modPow(product, N);
        }
        return A;
    }

    public BigInteger n() {
        return N;
    }

    public BigInteger nonce(BigInteger x) {
        return data.get(x);
    }

    public BigInteger prove(BigInteger x) {
        if (!data.containsKey(x)) {
            return null;
        } else {
            var product = product(x);
            return A0.modPow(product, N);
        }
    }

    public int size() {
        return data.size();
    }

    private BigInteger product(BigInteger x) {
        var product = BigInteger.ONE;
        for (var k : data.keySet()) {
            if (k.compareTo(x) != 0) {
                var nonce = data.get(k);
                product = product.multiply(primeHash(k, ACCUMULATED_SIZE, nonce).a());
            }
        }
        return product;
    }

    private record tuple(BigInteger a, BigInteger b) {
    }
}
