/*
 * Copyright (c) 2024, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;

import java.util.concurrent.Callable;

/**
 * Adapter that implements ViewContext by delegating to View's existing methods.
 * This bridges the View class to the ViewContext interface used by extracted components.
 *
 * @author hal.hildebrand
 */
class ViewContextAdapter implements ViewContext {
    private final View view;

    ViewContextAdapter(View view) {
        this.view = view;
    }

    @Override
    public boolean enterOperation() {
        return view.enterOperation();
    }

    @Override
    public void exitOperation() {
        view.exitOperation();
    }

    @Override
    public boolean isStarted() {
        return view.started.get();
    }

    @Override
    public void stable(Runnable action) {
        view.stable(action);
    }

    @Override
    public <T> T stable(Callable<T> callable) {
        return view.stable(callable);
    }

    @Override
    public void viewChange(Runnable action) {
        view.viewChange(action);
    }

    @Override
    public Digest currentView() {
        return view.currentView();
    }

    @Override
    public DynamicContext<Participant> getContext() {
        return view.context;
    }

    @Override
    public NodeMember getNode() {
        return view.node;
    }

    @Override
    public DigestAlgorithm getDigestAlgorithm() {
        return view.digestAlgo;
    }

    @Override
    public Parameters getParams() {
        return view.params;
    }

    @Override
    public FireflyMetrics getMetrics() {
        return view.metrics;
    }

    @Override
    public boolean validate(SelfAddressingIdentifier identifier) {
        return view.validate(identifier);
    }

    @Override
    public boolean validateBootstrapNote(NoteWrapper note) {
        return view.validateBootstrapNote(note);
    }
}
