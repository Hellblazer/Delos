package com.hellblazer.delos.skiplist;

import com.hellblazer.delos.cryptography.DigestAlgorithm;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * @author hal.hildebrand
 **/
public class AuthenticatedSkipList {
    private static final byte[] NIL = new byte[0];

    private final DigestAlgorithm algorithm;
    private       Node            root;
    private       long            timestamp = 0;

    public AuthenticatedSkipList(DigestAlgorithm algorithm) {
        this.algorithm = algorithm;
        init();
        hash(root);
    }

    public AuthenticatedSkipList(DigestAlgorithm algorithm, final List<Long> source, Random rng) {
        this.algorithm = algorithm;
        init();
        build(source, rng);
    }

    public Confirmation confirmation() {
        return new Confirmation(timestamp, root.hash);
    }

    public void delete(long elem) {
        if (!find(elem)) {
            return;
        }
        var cur = root;
        var backtrack = new ArrayDeque<Node>();
        while (true) {
            backtrack.push(cur);
            while (cur.right.data < elem) {
                cur = cur.right;
                backtrack.push(cur);
            }
            if (cur.down == null) {
                cur.right = cur.right.right;
                rehash(cur);
                break;
            }
            cur = cur.down;
        }
        while (!backtrack.isEmpty()) {
            var nextNode = backtrack.pop();
            if (nextNode.right.data == elem) {
                nextNode.right = nextNode.right.right;
            }
            rehash(nextNode);
        }
        timestamp++;
    }

    public boolean find(long key) {
        var cur = root;
        while (true) {
            while (cur.right.data < key) {
                cur = cur.right;
            }
            if (cur.down == null) {
                return cur.right.data == key;
            }
            cur = cur.down;
        }
    }

    public byte[] hash() {
        return root.hash;
    }

    public void insert(long elem, Random rng) {
        if (find(elem)) {
            return;
        }
        var lastLayer = root;
        var backtrack = new ArrayList<Node>();
        insert(lastLayer, elem, backtrack, rng);
        if (notEmpty(lastLayer)) {
            var newLayer = infinity();
            lastLayer.plateau = false;
            lastLayer.right.right.plateau = false;
            newLayer.down = lastLayer;
            newLayer.right.down = lastLayer.right.right;
            backtrack(backtrack);
            rehash(newLayer);
            root = newLayer;
        } else {
            backtrack(backtrack);
        }
        timestamp++;
    }

    public Proof proofOf(long elem) {
        var pList = new ArrayList<Node>();
        var cur = root;
        pList.add(cur);
        while (true) {
            while (cur.right.data <= elem) {
                cur = cur.right;
                pList.add(cur);
            }
            if (cur.down == null) {
                break;
            }
            cur = cur.down;
            pList.add(cur);
        }
        Collections.reverse(pList);
        var qList = new ArrayList<byte[]>();

        var cur_w = pList.getFirst().right;
        var isPresent = cur.data == elem;
        if (cur_w.plateau) {
            qList.add(cur_w.hash);
        } else {
            if (cur_w.right == null) {
                qList.add(NIL);
            } else {
                qList.add(hash(cur_w.data));
            }
        }
        qList.add(hash(cur.data));
        for (int i = 1; i < pList.size(); i++) {
            var cur_v = pList.get(i);
            cur_w = cur_v.right;
            if (cur_w.plateau) {
                if (cur_w != pList.get(i - 1)) {
                    qList.add(cur_w.hash);
                } else {
                    if (cur_v.down == null) {
                        qList.add(hash(cur_v.data));
                    } else {
                        qList.add(cur_v.down.hash);
                    }
                }
            }
        }
        return new Proof(timestamp, isPresent, elem, qList, algorithm);
    }

    private void backtrack(List<Node> backtrack) {
        for (var i = backtrack.size() - 1; i >= 0; i--) {
            var rec = backtrack.get(i);
            if (rec.right != null) {
                rehash(rec.right);
            }
            rehash(rec);
        }
    }

    private void build(final List<Long> source, Random rng) {
        buildBottom(new ArrayList<>(source));
        var lastLayer = root;
        while (notEmpty(lastLayer)) {
            boolean changed = false;
            var nextLayer = infinity();
            lastLayer.plateau = false;
            nextLayer.down = lastLayer;  // Link left infinity
            var lastInLayer = nextLayer;
            var cur = lastLayer.right;
            while (cur.right != null) {
                if (rng.nextBoolean()) {  // Keep alive
                    lastInLayer.right = new Node(cur.data, lastInLayer.right, cur);
                    lastInLayer = lastInLayer.right;
                    cur.plateau = false;
                } else {
                    cur.plateau = true;
                    changed = true;
                }
                cur = cur.right;
            }
            lastInLayer.right.down = cur;  // Link right infinity
            cur.plateau = false;
            if (changed) {
                root = nextLayer;
            }
            lastLayer = root;
        }
        hash(lastLayer);
    }

    private void buildBottom(List<Long> source) {
        Collections.sort(source);
        var cur = root;
        for (var key : source) {
            cur.right = new Node(key, cur.right, null);
            cur = cur.right;
        }
    }

    private byte[] hash(Node v) {
        var nxt = v.right;
        var dwn = v.down;
        if (v.right == null) {
            return NIL;
        }
        if (dwn == null) {
            if (nxt.plateau) {
                v.hash = algorithm.commutative(hash(v.data), hash(nxt));
            } else {
                var newBytes = (nxt.right == null) ? NIL : hash(nxt.data);
                v.hash = algorithm.commutative(hash(v.data), newBytes);
            }
            return v.hash;
        }
        if (nxt.plateau) {
            v.hash = algorithm.commutative(hash(dwn), hash(nxt));
        } else {
            v.hash = hash(dwn);
        }
        return v.hash;
    }

    private byte[] hash(long data) {
        var buff = ByteBuffer.allocate(8);
        buff.putLong(data);
        return buff.array();
    }

    private Node infinity() {
        var rightSentinel = new Node(Long.MAX_VALUE);
        return new Node(Long.MIN_VALUE, rightSentinel, null);
    }

    private void init() {
        root = infinity();
    }

    private Node insert(Node cur, long key, List<Node> backtrack, Random rng) {
        backtrack.add(cur);
        while (cur.right.data < key) {
            cur = cur.right;
            backtrack.add(cur);
        }
        if (cur.down == null) {
            cur.right = new Node(key, cur.right, null);
        } else {
            var res = insert(cur.down, key, backtrack, rng);
            if (res != null) {
                cur.right = new Node(key, cur.right, res);
                res.plateau = false;
            } else {
                return null;
            }
        }
        return rng.nextBoolean() ? cur.right : null;
    }

    private boolean notEmpty(final Node beginning) {
        return beginning.right.data != Long.MAX_VALUE;
    }

    private void rehash(Node v) {
        if (v.right == null) {
            v.hash = NIL;
            return;
        }
        if (v.down == null) {
            if (v.right.plateau) {
                v.hash = (algorithm.commutative(hash(v.data), v.right.hash));
            } else {
                var hashed = (v.right.right == null) ? NIL : hash(v.right.data);
                v.hash = algorithm.commutative(hash(v.data), hashed);
            }
        } else {
            if (!v.right.plateau) {
                v.hash = v.down.hash;
            } else {
                v.hash = algorithm.commutative(v.down.hash, v.right.hash);
            }
        }
    }

    public enum Result {
        STALE, CORRECT, INCORRECT
    }

    public record Confirmation(long timestamp, byte[] hash) {

        public Result validate(Proof proof) {
            if (proof.timestamp < timestamp) {
                return Result.STALE;
            }
            var cur = proof.algorithm.commutative(proof.sequence.get(0), proof.sequence.get(1));
            for (var i = 2; i < proof.sequence.size(); i++) {
                cur = proof.algorithm.commutative(cur, proof.sequence.get(i));
            }
            return Arrays.equals(cur, hash) ? Result.CORRECT : Result.INCORRECT;
        }
    }

    public record Proof(long timestamp, boolean present, long element, List<byte[]> sequence,
                        DigestAlgorithm algorithm) {
    }

    private static class Node {
        private final long    data;
        private       Node    right;
        private       Node    down;
        private       boolean plateau = true;
        private       byte[]  hash    = NIL;

        private Node(final long data, final Node right, final Node down) {
            this.data = data;
            this.right = right;
            this.down = down;
        }

        private Node(final long data) {
            this.data = data;
        }
    }
}
