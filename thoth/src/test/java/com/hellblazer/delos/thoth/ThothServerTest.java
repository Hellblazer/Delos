package com.hellblazer.delos.thoth;

import com.google.protobuf.Empty;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.ControlledIdentifier;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.Stereotomy;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.event.InceptionEvent;
import com.hellblazer.delos.stereotomy.event.RotationEvent;
import com.hellblazer.delos.stereotomy.event.Seal;
import com.hellblazer.delos.stereotomy.event.protobuf.ProtobufEventFactory;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.identifier.spec.InteractionSpecification;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.thoth.grpc.ThothServer;
import com.hellblazer.delos.thoth.proto.Thoth_Grpc;
import io.grpc.Channel;
import io.grpc.ServerBuilder;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @author hal.hildebrand
 **/
public class ThothServerTest {
    private SecureRandom secureRandom;

    @BeforeEach
    public void before() throws Exception {
        secureRandom = SecureRandom.getInstance("SHA1PRNG");
        secureRandom.setSeed(new byte[] { 0 });
    }

    @Test
    public void smokin() throws Exception {
        var ks = new MemKeyStore();
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        Stereotomy stereotomy = new StereotomyImpl(ks, kerl, secureRandom);
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());

        var localId = UUID.randomUUID().toString();
        ServerBuilder<?> serverBuilder = InProcessServerBuilder.forName(localId)
                                                               .addService(new ThothServer(new Thoth(stereotomy)));
        var server = serverBuilder.build();
        server.start();
        var channel = InProcessChannelBuilder.forName(localId).usePlaintext().build();
        try {
            var thoth = new ThothClient(channel);
            ControlledIdentifier<SelfAddressingIdentifier> controller = stereotomy.newIdentifier();

            // delegated inception
            var incp = thoth.inception(controller.getIdentifier());
            assertNotNull(incp);

            var seal = Seal.EventSeal.construct(incp.getIdentifier(), incp.hash(stereotomy.digestAlgorithm()),
                                                incp.getSequenceNumber().longValue());

            var builder = InteractionSpecification.newBuilder().addAllSeals(Collections.singletonList(seal));

            // Commit
            EventCoordinates coords = controller.seal(builder);
            thoth.commit(coords);
            assertNotNull(thoth.identifier());

            // Delegated rotation
            var rot = thoth.rotate();

            assertNotNull(rot);

            seal = Seal.EventSeal.construct(rot.getIdentifier(), rot.hash(stereotomy.digestAlgorithm()),
                                            rot.getSequenceNumber().longValue());

            builder = InteractionSpecification.newBuilder().addAllSeals(Collections.singletonList(seal));

            // Commit
            coords = controller.seal(builder);
            thoth.commit(coords);
        } finally {
            channel.shutdown();
            server.shutdown();
            server.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    private static class ThothClient {
        private Thoth_Grpc.Thoth_BlockingStub client;

        private ThothClient(Channel channel) {
            this.client = Thoth_Grpc.newBlockingStub(channel);
        }

        public void commit(EventCoordinates coordinates) {
            client.commit(coordinates.toEventCoords());
        }

        public SelfAddressingIdentifier identifier() {
            return (SelfAddressingIdentifier) Identifier.from(client.identifier(Empty.getDefaultInstance()));
        }

        public InceptionEvent inception(SelfAddressingIdentifier identifier) {
            return ProtobufEventFactory.toKeyEvent(client.inception(identifier.toIdent()));
        }

        public RotationEvent rotate() {
            return ProtobufEventFactory.toKeyEvent(client.rotate(Empty.getDefaultInstance()));
        }
    }
}
