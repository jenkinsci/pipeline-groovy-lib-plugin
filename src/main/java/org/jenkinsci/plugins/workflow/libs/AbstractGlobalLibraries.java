/*
 * The MIT License
 *
 * Copyright 2024 CloudBees, Inc.
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

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.ItemGroup;
import hudson.model.Job;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Common code between {@link GlobalLibraries} and {@link GlobalUntrustedLibraries}.
 */
public abstract class AbstractGlobalLibraries extends GlobalConfiguration {
    private List<LibraryConfiguration> libraries = new ArrayList<>();

    protected AbstractGlobalLibraries() {
        load();
    }

    public abstract String getDescription();

    public List<LibraryConfiguration> getLibraries() {
        return libraries;
    }

    public void setLibraries(List<LibraryConfiguration> libraries) {
        this.libraries = libraries;
        save();
    }

    @Override public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
        if (Jenkins.get().hasPermission(getRequiredGlobalConfigPagePermission())) {
            setLibraries(Collections.emptyList()); // allow last library to be deleted
            return super.configure(req, json);
        } else {
            return true;
        }
    }

    abstract static class AbstractForJob extends LibraryResolver {
        protected abstract AbstractGlobalLibraries getConfiguration();

        @NonNull @Override public final Collection<LibraryConfiguration> forJob(@NonNull Job<?,?> job, @NonNull Map<String,String> libraryVersions) {
            return getLibraries();
        }

        @NonNull @Override public final Collection<LibraryConfiguration> fromConfiguration(@NonNull StaplerRequest request) {
            AbstractGlobalLibraries abstractGlobalLibraries = getConfiguration();
            if (Jenkins.get().hasPermission(abstractGlobalLibraries.getRequiredGlobalConfigPagePermission())) {
                return getLibraries();
            }
            return Collections.emptySet();
        }

        @NonNull @Override public final Collection<LibraryConfiguration> suggestedConfigurations(@NonNull ItemGroup<?> group) {
            return getLibraries();
        }

        private List<LibraryConfiguration> getLibraries() {
            return getConfiguration()
                    .getLibraries()
                    .stream()
                    .map(this::mayWrapLibrary)
                    .collect(Collectors.toList());
        }

        protected abstract LibraryConfiguration mayWrapLibrary(LibraryConfiguration library);
    }
}
