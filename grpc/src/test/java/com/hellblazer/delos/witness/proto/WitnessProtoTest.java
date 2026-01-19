/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness.proto;

import com.google.protobuf.Timestamp;
import com.hellblazer.delos.cryptography.proto.Digeste;
import com.hellblazer.delos.cryptography.proto.Sig;
import com.hellblazer.delos.stereotomy.event.proto.EventCoords;
import com.hellblazer.delos.stereotomy.event.proto.Ident;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Test-first validation of witness.proto definitions.
 * Verifies descriptor compatibility, field ordering, and serialization round-trips.
 */
class WitnessProtoTest {

    @Test
    void shouldCreateWitnessReceipt() {
        var eventCoords = EventCoords.newBuilder()
                                      .setIdentifier(Ident.newBuilder().setNONE(true).build())
                                      .setSequenceNumber(1)
                                      .setIlk("icp")
                                      .setDigest(Digeste.newBuilder().setType(1).build())
                                      .build();

        var eventDigest = Digeste.newBuilder().setType(1).build();

        var sig = Sig.newBuilder()
                     .setCode(1)
                     .setSequenceNumber(1)
                     .build();

        var viewRef = Digeste.newBuilder().setType(1).build();

        var timestamp = Timestamp.newBuilder()
                                 .setSeconds(System.currentTimeMillis() / 1000)
                                 .build();

        var receipt = WitnessReceipt.newBuilder()
                                    .setEventCoordinates(eventCoords)
                                    .setEventDigest(eventDigest)
                                    .addSignatures(sig)
                                    .setEpoch(1)
                                    .setViewRef(viewRef)
                                    .setTimestamp(timestamp)
                                    .build();

        assertThat(receipt.getEventCoordinates()).isEqualTo(eventCoords);
        assertThat(receipt.getEventDigest()).isEqualTo(eventDigest);
        assertThat(receipt.getSignaturesCount()).isEqualTo(1);
        assertThat(receipt.getEpoch()).isEqualTo(1);
        assertThat(receipt.getViewRef()).isEqualTo(viewRef);
        assertThat(receipt.getTimestamp()).isEqualTo(timestamp);
    }

    @Test
    void shouldSerializeAndDeserializeWitnessReceipt() throws Exception {
        var eventCoords = EventCoords.newBuilder()
                                      .setIdentifier(Ident.newBuilder().setNONE(true).build())
                                      .setSequenceNumber(5)
                                      .setIlk("rot")
                                      .setDigest(Digeste.newBuilder().setType(2).build())
                                      .build();

        var receipt = WitnessReceipt.newBuilder()
                                    .setEventCoordinates(eventCoords)
                                    .setEventDigest(Digeste.newBuilder().setType(2).build())
                                    .setEpoch(42)
                                    .setViewRef(Digeste.newBuilder().setType(3).build())
                                    .setTimestamp(Timestamp.newBuilder().setSeconds(1234567890).build())
                                    .build();

        // Serialize
        var bytes = receipt.toByteArray();
        assertThat(bytes).isNotEmpty();

        // Deserialize
        var deserialized = WitnessReceipt.parseFrom(bytes);

        // Verify identical
        assertThat(deserialized).isEqualTo(receipt);
        assertThat(deserialized.getEventCoordinates().getSequenceNumber()).isEqualTo(5);
        assertThat(deserialized.getEpoch()).isEqualTo(42);
    }

    @Test
    void shouldCreateReceiptRequest() {
        var eventCoords = EventCoords.newBuilder()
                                      .setIdentifier(Ident.newBuilder().setNONE(true).build())
                                      .setSequenceNumber(10)
                                      .setIlk("ixn")
                                      .build();

        var request = ReceiptRequest.newBuilder()
                                    .setEventCoordinates(eventCoords)
                                    .setTimeoutMs(5000)
                                    .addWitnessFilter(Ident.newBuilder().setNONE(true).build())
                                    .build();

        assertThat(request.getEventCoordinates()).isEqualTo(eventCoords);
        assertThat(request.getTimeoutMs()).isEqualTo(5000);
        assertThat(request.getWitnessFilterCount()).isEqualTo(1);
    }

    @Test
    void shouldCreateReceiptResponse() {
        var receipt = WitnessReceipt.newBuilder()
                                    .setEventCoordinates(EventCoords.newBuilder()
                                                                    .setIdentifier(Ident.newBuilder().setNONE(true).build())
                                                                    .setSequenceNumber(1)
                                                                    .build())
                                    .setEpoch(1)
                                    .build();

        var response = ReceiptResponse.newBuilder()
                                      .setReceipt(receipt)
                                      .setStatus(ValidationStatus.THRESHOLD_MET)
                                      .setSignatureCount(5)
                                      .setRequiredThreshold(5)
                                      .build();

        assertThat(response.getReceipt()).isEqualTo(receipt);
        assertThat(response.getStatus()).isEqualTo(ValidationStatus.THRESHOLD_MET);
        assertThat(response.getSignatureCount()).isEqualTo(5);
        assertThat(response.getRequiredThreshold()).isEqualTo(5);
    }

    @Test
    void shouldHaveValidationStatusEnum() {
        assertThat(ValidationStatus.UNKNOWN.getNumber()).isEqualTo(0);
        assertThat(ValidationStatus.PENDING.getNumber()).isEqualTo(1);
        assertThat(ValidationStatus.THRESHOLD_MET.getNumber()).isEqualTo(2);
        assertThat(ValidationStatus.INVALID.getNumber()).isEqualTo(3);
        assertThat(ValidationStatus.STALE.getNumber()).isEqualTo(4);
        assertThat(ValidationStatus.TIMEOUT.getNumber()).isEqualTo(5);
    }

    @Test
    void shouldCreateEventSigningRequest() {
        var eventCoords = EventCoords.newBuilder()
                                      .setIdentifier(Ident.newBuilder().setNONE(true).build())
                                      .setSequenceNumber(1)
                                      .build();

        var request = EventSigningRequest.newBuilder()
                                         .setEventCoordinates(eventCoords)
                                         .setEventDigest(Digeste.newBuilder().setType(1).build())
                                         .setSigningThreshold(5)
                                         .setCommitteeSize(7)
                                         .setTimeoutMs(10000)
                                         .build();

        assertThat(request.getEventCoordinates()).isEqualTo(eventCoords);
        assertThat(request.getSigningThreshold()).isEqualTo(5);
        assertThat(request.getCommitteeSize()).isEqualTo(7);
        assertThat(request.getTimeoutMs()).isEqualTo(10000);
    }

    @Test
    void shouldCreateReceiptFuture() {
        var eventCoords = EventCoords.newBuilder()
                                      .setIdentifier(Ident.newBuilder().setNONE(true).build())
                                      .setSequenceNumber(1)
                                      .build();

        var future = ReceiptFuture.newBuilder()
                                  .setCollectionId("collection-123")
                                  .setStatus(ValidationStatus.PENDING)
                                  .setEventCoordinates(eventCoords)
                                  .build();

        assertThat(future.getCollectionId()).isEqualTo("collection-123");
        assertThat(future.getStatus()).isEqualTo(ValidationStatus.PENDING);
        assertThat(future.getEventCoordinates()).isEqualTo(eventCoords);
    }

    @Test
    void shouldCreateReceiptFilter() {
        var filter = ReceiptFilter.newBuilder()
                                  .addControllers(Ident.newBuilder().setNONE(true).build())
                                  .setMinSequence(1)
                                  .setMaxSequence(100)
                                  .addIlks("icp")
                                  .addIlks("rot")
                                  .setEpoch(5)
                                  .build();

        assertThat(filter.getControllersCount()).isEqualTo(1);
        assertThat(filter.getMinSequence()).isEqualTo(1);
        assertThat(filter.getMaxSequence()).isEqualTo(100);
        assertThat(filter.getIlksCount()).isEqualTo(2);
        assertThat(filter.getEpoch()).isEqualTo(5);
    }

    @Test
    void shouldSerializeAndDeserializeReceiptRequest() throws Exception {
        var request = ReceiptRequest.newBuilder()
                                    .setEventCoordinates(EventCoords.newBuilder()
                                                                    .setIdentifier(Ident.newBuilder().setNONE(true).build())
                                                                    .setSequenceNumber(99)
                                                                    .setIlk("dip")
                                                                    .build())
                                    .setTimeoutMs(3000)
                                    .build();

        var bytes = request.toByteArray();
        var deserialized = ReceiptRequest.parseFrom(bytes);

        assertThat(deserialized).isEqualTo(request);
        assertThat(deserialized.getEventCoordinates().getSequenceNumber()).isEqualTo(99);
    }

    @Test
    void shouldSerializeAndDeserializeEventSigningRequest() throws Exception {
        var request = EventSigningRequest.newBuilder()
                                         .setEventCoordinates(EventCoords.newBuilder()
                                                                         .setIdentifier(Ident.newBuilder().setNONE(true).build())
                                                                         .setSequenceNumber(1)
                                                                         .build())
                                         .setEventDigest(Digeste.newBuilder().setType(1).build())
                                         .setSigningThreshold(7)
                                         .setCommitteeSize(10)
                                         .setTimeoutMs(15000)
                                         .build();

        var bytes = request.toByteArray();
        var deserialized = EventSigningRequest.parseFrom(bytes);

        assertThat(deserialized).isEqualTo(request);
        assertThat(deserialized.getSigningThreshold()).isEqualTo(7);
        assertThat(deserialized.getCommitteeSize()).isEqualTo(10);
    }

    @Test
    void shouldHaveCorrectFieldNumbersForWitnessReceipt() {
        var descriptor = WitnessReceipt.getDescriptor();

        assertThat(descriptor.findFieldByName("eventCoordinates").getNumber()).isEqualTo(1);
        assertThat(descriptor.findFieldByName("eventDigest").getNumber()).isEqualTo(2);
        assertThat(descriptor.findFieldByName("signatures").getNumber()).isEqualTo(3);
        assertThat(descriptor.findFieldByName("epoch").getNumber()).isEqualTo(4);
        assertThat(descriptor.findFieldByName("viewRef").getNumber()).isEqualTo(5);
        assertThat(descriptor.findFieldByName("timestamp").getNumber()).isEqualTo(6);
    }

    @Test
    void shouldSupportMultipleSignaturesInReceipt() {
        var sig1 = Sig.newBuilder().setCode(1).setSequenceNumber(1).build();
        var sig2 = Sig.newBuilder().setCode(2).setSequenceNumber(2).build();
        var sig3 = Sig.newBuilder().setCode(3).setSequenceNumber(3).build();

        var receipt = WitnessReceipt.newBuilder()
                                    .setEventCoordinates(EventCoords.newBuilder()
                                                                    .setIdentifier(Ident.newBuilder().setNONE(true).build())
                                                                    .setSequenceNumber(1)
                                                                    .build())
                                    .setEventDigest(Digeste.newBuilder().setType(1).build())
                                    .addSignatures(sig1)
                                    .addSignatures(sig2)
                                    .addSignatures(sig3)
                                    .setEpoch(1)
                                    .setViewRef(Digeste.newBuilder().setType(1).build())
                                    .build();

        assertThat(receipt.getSignaturesCount()).isEqualTo(3);
        assertThat(receipt.getSignaturesList()).containsExactly(sig1, sig2, sig3);
    }
}
