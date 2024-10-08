package com.hellblazer.delos.leyden;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.leyden.proto.Binding;
import com.hellblazer.delos.leyden.proto.Bound;
import com.hellblazer.delos.leyden.proto.Key;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Utils;
import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.mvstore.MVStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author hal.hildebrand
 **/
public class LeydenJarTest {

    private static final double                            PBYZ    = 0.1;
    protected final      TreeMap<SigningMember, LeydenJar> dhts    = new TreeMap<>();
    protected final      Map<SigningMember, Router>        routers = new HashMap<>();
    private              String                            prefix;
    private              LeydenJar.OpValidator             validator;
    private              DynamicContext<Member>            context;

    @AfterEach
    public void after() {
        routers.values().forEach(r -> r.close(Duration.ofSeconds(0)));
        routers.clear();
        dhts.values().forEach(t -> t.stop());
        dhts.clear();
    }

    @BeforeEach
    public void before() throws Exception {
        validator = new LeydenJar.OpValidator() {
            @Override
            public boolean validateBind(Bound bound) {
                return true;
            }

            @Override
            public boolean validateGet(byte[] key) {
                return true;
            }

            @Override
            public boolean validateUnbind(byte[] key) {
                return true;
            }
        };
        prefix = UUID.randomUUID().toString();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var cardinality = 5;
        var identities = IntStream.range(0, cardinality)
                                  .mapToObj(i -> stereotomy.newIdentifier())
                                  .collect(Collectors.toMap(controlled -> new ControlledIdentifierMember(controlled),
                                                            controlled -> controlled));
        var b = DynamicContext.newBuilder();
        b.setpByz(PBYZ).setCardinality(cardinality);
        context = b.build();
        identities.keySet().forEach(m -> context.activate(m));
        identities.keySet().forEach(member -> instantiate(member, context));

        System.out.println();
        System.out.println();
        System.out.printf("Cardinality: %s, Prob Byz: %s, Rings: %s Majority: %s%n", cardinality, PBYZ,
                          context.getRingCount(), context.majority());
        System.out.println();
    }

    @Test
    public void smokin() {
        routers.values().forEach(r -> r.start());
        dhts.values().forEach(lj -> lj.start(Duration.ofMillis(10)));

        var source = dhts.firstEntry().getValue();
        var sink = dhts.lastEntry().getValue();

        var key = ByteString.copyFrom("hello".getBytes());
        var value = ByteString.copyFrom("world".getBytes());
        var binding = Binding.newBuilder().setBound(Bound.newBuilder().setKey(key).setValue(value).build()).build();
        source.bind(binding);

        for (var e : dhts.entrySet()) {
            var success = Utils.waitForCondition(10_000, () -> {
                Bound bound;
                try {
                    bound = e.getValue().get(Key.newBuilder().setKey(key).build());
                } catch (NoSuchElementException nse) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ex) {
                    }
                    return false;
                }
                return bound != null;
            });
            assertTrue(success, "Failed for " + e.getKey().getId());
        }
    }

    protected void instantiate(SigningMember member, Context<Member> context) {
        final var url = String.format("jdbc:h2:mem:%s-%s;DB_CLOSE_ON_EXIT=FALSE", member.getId(), prefix);
        JdbcConnectionPool connectionPool = JdbcConnectionPool.create(url, "", "");
        connectionPool.setMaxConnections(10);
        var exec = Executors.newVirtualThreadPerTaskExecutor();
        var router = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        routers.put(member, router);
        dhts.put(member,
                 new LeydenJar(validator, Duration.ofSeconds(5), member, context, Duration.ofMillis(10), router, 0.0125,
                               DigestAlgorithm.DEFAULT, new MVStore.Builder().open(), null, null));
    }
}
