package com.hellblazer.delos.skipList;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.skiplist.AuthenticatedSkipList;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author hal.hildebrand
 **/
@TestMethodOrder(MethodOrderer.MethodName.class)
public class AuthenticatedSkipListTest {

    @Test
    public void test01_empty() {
        var list = new AuthenticatedSkipList(DigestAlgorithm.DEFAULT);
        assertFalse(list.find(666));
    }

    @Test
    public void test02_single() {
        var list = new AuthenticatedSkipList(DigestAlgorithm.DEFAULT, List.of(5L), new Random(0x666));
        assertTrue(list.find(5));
        assertFalse(list.find(666));
    }

    @Test
    public void test03_multiple() {
        var list = new AuthenticatedSkipList(DigestAlgorithm.DEFAULT, List.of(16L, 5L, 2L, 8L), new Random(0x666));
        assertFalse(list.find(0L));
        assertFalse(list.find(7));
        assertFalse(list.find(1000));
        assertTrue(list.find(2));
        assertTrue(list.find(8));
        assertTrue(list.find(16));
    }

    @Test
    public void test04_randomizedSearch() {
        var rng = new Random(0x666);
        var elements = new HashSet<Long>();
        for (var i = 1L; i <= 10000L; i++) {
            if (rng.nextBoolean()) {
                elements.add(i);
            }
        }
        var list = new AuthenticatedSkipList(DigestAlgorithm.DEFAULT, new ArrayList<>(elements), new Random(0x666));
        for (var i = 1L; i <= 10000L; i++) {
            if (elements.contains(i)) {
                assertTrue(list.find(i), "should contain: " + i);
            } else {
                assertFalse(list.find(i), "should not contain: " + i);
            }
        }
    }

    @Test
    public void test05_basicInsertion() {
        var rng = new Random(0x666);
        var list = new AuthenticatedSkipList(DigestAlgorithm.DEFAULT);
        list.insert(2L, rng);
        list.insert(3L, rng);
        list.insert(5L, rng);
        assertTrue(list.find(2));
        assertTrue(list.find(3));
        assertTrue(list.find(5));
        assertFalse(list.find(4));
    }

    @Test
    public void test06_randomizedInsertion() {
        var rng = new Random(0x666);
        Set<Integer> elements = new HashSet<>();
        var list = new AuthenticatedSkipList(DigestAlgorithm.DEFAULT, List.of(1L), new Random(0x666));
        elements.add(1);
        for (int i = 2; i <= 10000; i++) {
            if (rng.nextBoolean()) {
                elements.add(i);
                list.insert(i, rng);
            }
        }
        for (int i = 1; i <= 10000; i++) {
            if (elements.contains(i)) {
                assertTrue(list.find(i));
            } else {
                assertFalse(list.find(i));
            }
        }

    }

    @Test
    public void test07_basicDeletion() {
        var rng = new Random(0x666);
        var list = new AuthenticatedSkipList(DigestAlgorithm.DEFAULT);
        list.insert(5, rng);
        list.insert(2, rng);
        list.insert(3, rng);
        assertTrue(list.find(3));

        list.delete(3);
        assertFalse(list.find(3));
    }

    @Test
    public void test08_allOperations() {
        var rng = new Random(0x666);
        var list = new AuthenticatedSkipList(DigestAlgorithm.DEFAULT);
        Set<Integer> elements = new HashSet<>();
        int queries = 0;
        for (int i = 0; i < 20000; i++) {
            int arg = rng.nextInt(Integer.MAX_VALUE);
            int op = rng.nextInt(3);
            switch (op) {
            case 0:
                assertEquals(list.find(arg), elements.contains(arg));
                queries++;
                break;
            case 1:
                list.insert(arg, rng);
                elements.add(arg);
                break;
            case 2:
                if (elements.isEmpty()) {
                    continue;
                }
                list.delete(elements.iterator().next());
                elements.remove(elements.iterator().next());
                break;
            }
        }
        System.out.println("Total find() queries done: " + queries);
    }

    @Test
    public void test09_commutativeSha() {
        var a1 = new byte[] { 1, 2, 3, 4 };
        var a2 = new byte[] { 5, 6, 7 };
        var a3 = new byte[] { 8, 9 };
        var p1 = DigestAlgorithm.DEFAULT.commutative(a1, a2);
        var p2 = DigestAlgorithm.DEFAULT.commutative(a2, a1);
        var p3 = DigestAlgorithm.DEFAULT.commutative(p2, a3);
        var p4 = DigestAlgorithm.DEFAULT.commutative(p1, a3);
        assertArrayEquals(p1, p2);
        assertArrayEquals(p3, p4);
    }

    @Test
    public void test10_commutativeShaEmpty() {
        var a1 = new byte[] {};
        var a2 = new byte[] { 5, 6, 7 };
        var p1 = DigestAlgorithm.DEFAULT.commutative(a1, a2);
        var p2 = DigestAlgorithm.DEFAULT.commutative(a2, a1);
        assertArrayEquals(p1, p2);
    }

    @Test
    public void test11_validation() {
        var rng = new Random(0x666);
        var list = new AuthenticatedSkipList(DigestAlgorithm.DEFAULT);
        list.insert(5, rng);
        list.insert(2, rng);
        list.insert(3, rng);
        var conf = list.confirmation();
        var pr = list.proofOf(5);
        assertEquals(AuthenticatedSkipList.Result.CORRECT, conf.validate(pr));
    }

    @Test
    public void test12_hardValidation() {
        var rng = new Random(0x666);
        var list = new AuthenticatedSkipList(DigestAlgorithm.DEFAULT);
        var elements = new HashSet<Long>();
        for (int i = 1; i <= 10000; i++) {
            var element = rng.nextLong();
            elements.add(element);
            list.insert(element, rng);
        }
        var conf = list.confirmation();
        for (var i : elements) {
            var pr = list.proofOf(i);
            assertEquals(AuthenticatedSkipList.Result.CORRECT, conf.validate(pr));
        }
    }

    @Test
    public void test13_outdatedPostInsertValidation() {
        var rng = new Random(0x666);
        var list = new AuthenticatedSkipList(DigestAlgorithm.DEFAULT);
        list.insert(5, rng);
        list.insert(2, rng);
        list.insert(3, rng);
        var pr = list.proofOf(5);
        var conf = list.confirmation();
        assertEquals(AuthenticatedSkipList.Result.CORRECT, conf.validate(pr));
        list.insert(4, rng);
        conf = list.confirmation();
        var pr2 = list.proofOf(4);
        assertEquals(AuthenticatedSkipList.Result.CORRECT, conf.validate(pr2));
        assertEquals(AuthenticatedSkipList.Result.STALE, conf.validate(pr));
    }

    @Test
    public void test14_outdatedPostDeleteValidation() {
        var rng = new Random(0x666);
        var list = new AuthenticatedSkipList(DigestAlgorithm.DEFAULT);
        list.insert(666, rng);
        list.insert(19, rng);
        list.insert(28, rng);
        var pr = list.proofOf(28);
        var conf = list.confirmation();
        assertEquals(AuthenticatedSkipList.Result.CORRECT, conf.validate(pr));
        list.delete(666);
        conf = list.confirmation();
        var pr2 = list.proofOf(19);
        assertEquals(AuthenticatedSkipList.Result.CORRECT, conf.validate(pr2));
        assertEquals(AuthenticatedSkipList.Result.STALE, conf.validate(pr));
    }

    @Test
    public void test15_emptyNonExist() {
        var list = new AuthenticatedSkipList(DigestAlgorithm.DEFAULT);
        var pr = list.proofOf(1);
        var conf = list.confirmation();
        assertFalse(pr.present());
        assertEquals(AuthenticatedSkipList.Result.CORRECT, conf.validate(pr));
    }

    @Test
    public void test16_simpleNonExist() {
        var rng = new Random(0x666);
        var list = new AuthenticatedSkipList(DigestAlgorithm.DEFAULT);
        list.insert(5, rng);
        list.insert(2, rng);
        list.insert(3, rng);
        list.insert(7, rng);
        var conf = list.confirmation();
        assertEquals(AuthenticatedSkipList.Result.CORRECT, conf.validate(list.proofOf(-1349)));
        assertEquals(AuthenticatedSkipList.Result.CORRECT, conf.validate(list.proofOf(4)));
        assertEquals(AuthenticatedSkipList.Result.CORRECT, conf.validate(list.proofOf(2)));
        assertEquals(AuthenticatedSkipList.Result.CORRECT, conf.validate(list.proofOf(6)));
        assertEquals(AuthenticatedSkipList.Result.CORRECT, conf.validate(list.proofOf(0)));
    }

    @Test
    public void test17_postRandomOperationsValidation() {
        var rng = new Random(0x666);
        var list = new AuthenticatedSkipList(DigestAlgorithm.DEFAULT);
        Set<Long> elements = new HashSet<>();
        AuthenticatedSkipList.Proof pr;
        var conf = list.confirmation();
        for (int i = 0; i < 10000; i++) {
            int op = rng.nextInt(3);
            switch (op) {
            case 0:
                if (elements.isEmpty()) {
                    continue;
                }
                long arg = getRandomElement(elements, rng);
                pr = list.proofOf(arg);
                assertEquals(AuthenticatedSkipList.Result.CORRECT, conf.validate(pr));
                break;
            case 1:
                long newElem = rng.nextLong(Long.MAX_VALUE);
                list.insert(newElem, rng);
                elements.add(newElem);
                pr = list.proofOf(newElem);
                conf = list.confirmation();
                assertEquals(AuthenticatedSkipList.Result.CORRECT, conf.validate(pr));
                break;
            case 2:
                if (elements.isEmpty()) {
                    continue;
                }
                long removedElem = getRandomElement(elements, rng);
                list.delete(removedElem);
                elements.remove(removedElem);
                pr = list.proofOf(removedElem);
                conf = list.confirmation();
                assertEquals(AuthenticatedSkipList.Result.CORRECT, conf.validate(pr));
                break;
            }
        }
    }

    @Test
    public void test18_fakeProof() {
        var rng = new Random(0x666);
        var list = new AuthenticatedSkipList(DigestAlgorithm.DEFAULT);
        list.insert(5, rng);
        list.insert(2, rng);
        list.insert(3, rng);
        var pr = list.proofOf(5);
        list = new AuthenticatedSkipList(DigestAlgorithm.DEFAULT);
        list.insert(5, rng);
        list.insert(1337, rng);
        list.insert(1349, rng);
        var conf = list.confirmation();
        assertEquals(AuthenticatedSkipList.Result.INCORRECT, conf.validate(pr));
    }

    private long getRandomElement(final Set<Long> s, Random rng) {
        return s.stream().skip(rng.nextInt(s.size())).findFirst().get();
    }
}
