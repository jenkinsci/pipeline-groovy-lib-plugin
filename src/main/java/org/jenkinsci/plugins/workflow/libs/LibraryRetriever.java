/*
 * The MIT License
 *
 * Copyright 2016 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.libs;

import hudson.AbortException;
import hudson.ExtensionPoint;
import hudson.FilePath;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.util.io.ArchiverFactory;
import java.io.IOException;
import java.io.OutputStream;

/**
 * A way in which a library can be physically obtained for use in a build.
 */
public abstract class LibraryRetriever extends AbstractDescribableImpl<LibraryRetriever> implements ExtensionPoint {

    /**
     * Obtains library sources.
     * @param name the {@link LibraryConfiguration#getName}
     * @param version the version of the library, such as from {@link LibraryConfiguration#getDefaultVersion} or an override
     * @param changelog whether to include changesets in the library in jobs using it from {@link LibraryConfiguration#getIncludeInChangesets}
     * @param target a JAR file in which to stash sources; should contain {@code **}{@code /*.groovy} (sources at top level will be considered vars), and optionally also {@code resources/}
     * @param run a build which will use the library
     * @param listener a way to report progress
     * @throws Exception if there is any problem (use {@link AbortException} for user errors)
     */
    public void retrieveJar(@NonNull String name, @NonNull String version, boolean changelog, @NonNull FilePath target, @NonNull Run<?,?> run, @NonNull TaskListener listener) throws Exception {
        if (Util.isOverridden(LibraryRetriever.class, getClass(), "retrieve", String.class, String.class, boolean.class, FilePath.class, Run.class, TaskListener.class)) {
            FilePath tmp = target.withSuffix(".checkout");
            try {
                retrieve(name, version, changelog, tmp, run, listener);
                dir2Jar(tmp, target);
            } finally {
                tmp.deleteRecursive();
            }
        } else {
            throw new AbstractMethodError("Implement retrieveJar");
        }
    }

    /**
     * Translates a historical directory with {@code src/} and/or {@code vars/} and/or {@code resources/} subdirectories
     * into a JAR file with Groovy in classpath orientation and {@code resources/} as a ZIP folder.
     */
    static void dir2Jar(@NonNull FilePath dir, @NonNull FilePath jar) throws IOException, InterruptedException {
        // TODO do this more efficiently by packing JAR directly
        FilePath tmp2 = jar.withSuffix(".tmp2");
        tmp2.mkdirs();
        try {
            FilePath src = dir.child("src");
            if (src.isDirectory()) {
                src.moveAllChildrenTo(tmp2);
            }
            FilePath vars = dir.child("vars");
            if (vars.isDirectory()) {
                vars.moveAllChildrenTo(tmp2);
            }
            FilePath resources = dir.child("resources");
            if (resources.isDirectory()) {
                resources.renameTo(tmp2.child("resources"));
            }
            try (OutputStream os = jar.write()) {
                tmp2.archive(ArchiverFactory.ZIP, os, "**");
            }
        } finally {
            tmp2.deleteRecursive();
        }
    }

    /**
     * Obtains library sources.
     * @param name the {@link LibraryConfiguration#getName}
     * @param version the version of the library, such as from {@link LibraryConfiguration#getDefaultVersion} or an override
     * @param changelog whether to include changesets in the library in jobs using it from {@link LibraryConfiguration#getIncludeInChangesets}
     * @param target a directory in which to check out sources; should create {@code src/**}{@code /*.groovy} and/or {@code vars/*.groovy}, and optionally also {@code resources/}
     * @param run a build which will use the library
     * @param listener a way to report progress
     * @throws Exception if there is any problem (use {@link AbortException} for user errors)
     */
    @Deprecated
    public void retrieve(@NonNull String name, @NonNull String version, boolean changelog, @NonNull FilePath target, @NonNull Run<?,?> run, @NonNull TaskListener listener) throws Exception {
        if (Util.isOverridden(LibraryRetriever.class, getClass(), "retrieve", String.class, String.class, FilePath.class, Run.class, TaskListener.class)) {
            retrieve(name, version, target, run, listener);
        } else {
            throw new AbstractMethodError("Implement retrieveJar");
        }
    }

    /**
     * Obtains library sources.
     * @param name the {@link LibraryConfiguration#getName}
     * @param version the version of the library, such as from {@link LibraryConfiguration#getDefaultVersion} or an override
     * @param target a directory in which to check out sources; should create {@code src/**}{@code /*.groovy} and/or {@code vars/*.groovy}, and optionally also {@code resources/}
     * @param run a build which will use the library
     * @param listener a way to report progress
     * @throws Exception if there is any problem (use {@link AbortException} for user errors)
     */
    @Deprecated
    public void retrieve(@NonNull String name, @NonNull String version, @NonNull FilePath target, @NonNull Run<?,?> run, @NonNull TaskListener listener) throws Exception {
        throw new AbstractMethodError("Implement retrieveJar");
    }

    @Deprecated
    public FormValidation validateVersion(@NonNull String name, @NonNull String version) {
        if (Util.isOverridden(LibraryRetriever.class, getClass(), "validateVersion", String.class, String.class, Item.class)) {
            return validateVersion(name, version, null);
        }
        return FormValidation.ok();
    }

    /**
     * Offer to validate a proposed {@code version} for {@link #retrieve}.
     * @param name the proposed library name
     * @param version a proposed version
     * @param context optional context in which this runs
     * @return by default, OK
     */
    public FormValidation validateVersion(@NonNull String name, @NonNull String version, @CheckForNull Item context) {
        return validateVersion(name, version);
    }

    @Override public LibraryRetrieverDescriptor getDescriptor() {
        return (LibraryRetrieverDescriptor) super.getDescriptor();
    }

}
