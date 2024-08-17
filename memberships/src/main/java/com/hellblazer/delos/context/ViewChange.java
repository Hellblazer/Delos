package com.hellblazer.delos.context;

import com.hellblazer.delos.cryptography.Digest;

import java.util.Collection;

public record ViewChange(Context context, Digest diadem, Collection<Digest> joining, Collection<Digest> leaving) {
}
