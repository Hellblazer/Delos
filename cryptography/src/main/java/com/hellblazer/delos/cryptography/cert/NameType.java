/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.cryptography.cert;

import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;

public enum NameType {
    DIRECTORY_NAME(GeneralName.directoryName), DNS_NAME(GeneralName.dNSName), EDI_PARTY_NAME(GeneralName.ediPartyName),
    IP_ADDRESS(GeneralName.iPAddress), OTHER_NAME(GeneralName.otherName), REGISTERED_ID(GeneralName.registeredID),
    RFC_822_NAME(GeneralName.rfc822Name),
    /**
     * URI : Uniform Resource Identifier
     */
    URI(GeneralName.uniformResourceIdentifier), X400_NAME(GeneralName.x400Address);

    private final int id;

    private NameType(final int id) {
        this.id = id;
    }

    public GeneralName generalName(final String name) {
        return new GeneralName(id, name);
    }

    public GeneralNames generalNames(final String name) {
        return new GeneralNames(generalName(name));
    }
}
