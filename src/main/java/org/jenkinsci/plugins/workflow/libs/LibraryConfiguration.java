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
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.DescriptorVisibilityFilter;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.interceptor.RequirePOST;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;

/**
 * User configuration for one library.
 */
public class LibraryConfiguration extends AbstractDescribableImpl<LibraryConfiguration> {

    private final String name;
    private final LibraryRetriever retriever;
    private String defaultVersion;
    private boolean implicit;
    private boolean allowVersionOverride = true;
    private boolean allowBRANCH_NAME = false;
    private boolean includeInChangesets = true;
    private LibraryCachingConfiguration cachingConfiguration = null;

    @DataBoundConstructor public LibraryConfiguration(String name, LibraryRetriever retriever) {
        this.name = Util.fixEmptyAndTrim(name);
        this.retriever = retriever;
    }

    /**
     * Library name.
     * Should match {@link Library#value}, up to the first occurrence of {@code @}, if any.
     */
    public String getName() {
        return name;
    }

    public LibraryRetriever getRetriever() {
        return retriever;
    }

    /**
     * The default version to use with {@link LibraryRetriever#retrieve} if none other is specified.
     */
    public String getDefaultVersion() {
        return defaultVersion;
    }

    @DataBoundSetter public void setDefaultVersion(String defaultVersion) {
        this.defaultVersion = Util.fixEmptyAndTrim(defaultVersion);
    }

    /**
     * Whether the library should be made accessible to qualifying jobs
     * without any explicit {@link Library} declaration.
     */
    public boolean isImplicit() {
        return implicit;
    }

    @DataBoundSetter public void setImplicit(boolean implicit) {
        this.implicit = implicit;
    }

    /**
     * Whether jobs should be permitted to override {@link #getDefaultVersion}.
     */
    public boolean isAllowVersionOverride() {
        return allowVersionOverride;
    }

    @DataBoundSetter public void setAllowVersionOverride(boolean allowVersionOverride) {
        this.allowVersionOverride = allowVersionOverride;
    }

    /**
     * Whether jobs should be permitted to specify literally @Library('libname@${BRANCH_NAME}')
     * in pipeline files (from SCM) to try using the same branch name of library as of the job
     * definition. If such branch name does not exist, fall back to retrieve() defaultVersion.
     */

    public boolean isAllowBRANCH_NAME() {
        return allowBRANCH_NAME;
    }

    @DataBoundSetter public void setAllowBRANCH_NAME(boolean allowBRANCH_NAME) {
        this.allowBRANCH_NAME = allowBRANCH_NAME;
    }

    /**
     * Whether to include library changes in reported changes in a job {@link #getIncludeInChangesets}.
     */
    public boolean getIncludeInChangesets() {
        return includeInChangesets;
    }

    @DataBoundSetter public void setIncludeInChangesets(boolean includeInChangesets) {
        this.includeInChangesets = includeInChangesets;
    }

    public LibraryCachingConfiguration getCachingConfiguration() {
        return cachingConfiguration;
    }

    @DataBoundSetter public void setCachingConfiguration(LibraryCachingConfiguration cachingConfiguration) {
        this.cachingConfiguration = cachingConfiguration;
    }

    @NonNull boolean defaultedChangelogs(@CheckForNull Boolean changelog) throws AbortException {
      if (changelog == null) {
        return includeInChangesets;
      } else {
        return changelog;
      }
    }

    @NonNull String defaultedVersion(@CheckForNull String version) throws AbortException {
        return defaultedVersion(version, null, null);
    }

    @NonNull String defaultedVersion(@CheckForNull String version, Run<?, ?> run, TaskListener listener) throws AbortException {
        if (version == null) {
            if (defaultVersion == null) {
                throw new AbortException("No version specified for library " + name);
            } else {
                return defaultVersion;
            }
        } else if (allowVersionOverride && !"${BRANCH_NAME}".equals(version)) {
            return version;
        } else if (allowBRANCH_NAME && "${BRANCH_NAME}".equals(version)) {
            String runVersion = null;
            Item runParent = null;
            if (run != null && listener != null) {
                try {
                    runParent = run.getParent();
                    runVersion = run.getEnvironment(listener).get("BRANCH_NAME", null);
                } catch (Exception x) {
                    // no-op, keep null
                }
            }

            if (runParent == null || runVersion == null || "".equals(runVersion)) {
                // Current build does not know a BRANCH_NAME envvar,
                // or it's an empty string, or this request has null
                // args for run/listener needed for validateVersion()
                // below, or some other problem occurred.
                // Fall back if we can:
                if (defaultVersion == null) {
                    throw new AbortException("No version specified for library " + name);
                } else {
                    return defaultVersion;
                }
            }

            // Check if runVersion is resolvable by LibraryRetriever
            // implementation (SCM, HTTP, etc.); fall back if not:
            if (retriever != null) {
                FormValidation fv = retriever.validateVersion(name, runVersion, runParent);

                if (fv != null && fv.kind == FormValidation.Kind.OK) {
                    return runVersion;
                }
            }

            // No retriever, or its validateVersion() did not confirm
            // usability of BRANCH_NAME string value as the version...
            if (defaultVersion == null) {
                throw new AbortException("BRANCH_NAME version " + runVersion +
                    " was not found, and no default version specified, for library " + name);
            } else {
                return defaultVersion;
            }
        } else {
            throw new AbortException("Version override not permitted for library " + name);
        }
    }

    @Extension public static class DescriptorImpl extends Descriptor<LibraryConfiguration> {

        // TODO JENKINS-20020 ought to be unnecessary
        @Restricted(NoExternalUse.class) // Jelly
        public Collection<LibraryRetrieverDescriptor> getRetrieverDescriptors() {
            StaplerRequest req = Stapler.getCurrentRequest();
            Item it = req != null ? req.findAncestorObject(Item.class) : null;
            return DescriptorVisibilityFilter.apply(it != null ? it : Jenkins.get(), ExtensionList.lookup(LibraryRetrieverDescriptor.class));
        }

        public FormValidation doCheckName(@QueryParameter String name) {
            if (name.isEmpty()) {
                return FormValidation.error("You must enter a name.");
            }
            // Currently no character restrictions.
            return FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doCheckDefaultVersion(@AncestorInPath Item context, @QueryParameter String defaultVersion, @QueryParameter boolean implicit, @QueryParameter boolean allowVersionOverride, @QueryParameter boolean allowBRANCH_NAME, @QueryParameter String name) {
            if (defaultVersion.isEmpty()) {
                if (implicit) {
                    return FormValidation.error("If you load a library implicitly, you must specify a default version.");
                }
                if (allowBRANCH_NAME) {
                    return FormValidation.error("If you allow use of literal '@${BRANCH_NAME}' for overriding a default version, you must define that version as fallback.");
                }
                if (!allowVersionOverride) {
                    return FormValidation.error("If you deny overriding a default version, you must define that version.");
                }
                return FormValidation.ok();
            } else {
                if ("${BRANCH_NAME}".equals(defaultVersion)) {
                    if (!allowBRANCH_NAME) {
                        return FormValidation.error("Use of literal '@${BRANCH_NAME}' not allowed in this configuration.");
                    }

                    // The context is not a particular Run (might be a Job)
                    // so we can't detect which BRANCH_NAME is relevant:
                    String msg = "Cannot validate default version: " +
                            "literal '@${BRANCH_NAME}' is reserved " +
                            "for pipeline files from SCM";
                    if (implicit) {
                        // Someone might want to bind feature branches of
                        // job definitions and implicit libs by default?..
                        return FormValidation.warning(msg);
                    } else {
                        return FormValidation.error(msg);
                    }
                }

                for (LibraryResolver resolver : ExtensionList.lookup(LibraryResolver.class)) {
                    for (LibraryConfiguration config : resolver.fromConfiguration(Stapler.getCurrentRequest())) {
                        if (config.getName().equals(name)) {
                            return config.getRetriever().validateVersion(name, defaultVersion, context);
                        }
                    }
                }
                return FormValidation.ok("Cannot validate default version until after saving and reconfiguring.");
            }
        }

        /* TODO currently impossible; autoCompleteField does not support passing neighboring fields:
        public AutoCompletionCandidates doAutoCompleteDefaultVersion(@AncestorInPath Item context, @QueryParameter String defaultVersion, @QueryParameter String name) {
            AutoCompletionCandidates candidates = new AutoCompletionCandidates();
            for (LibraryResolver resolver : ExtensionList.lookup(LibraryResolver.class)) {
                for LibraryConfiguration config : resolver.fromConfiguration(Stapler.getCurrentRequest()) {
                    // TODO define LibraryRetriever.completeVersions
                    if (config.getName().equals(name) && config.getRetriever() instanceof SCMSourceRetriever) {
                        try {
                            for (String revision : ((SCMSourceRetriever) config.getRetriever()).getScm().fetchRevisions(null, context)) {
                                if (revision.startsWith(defaultVersion)) {
                                    candidates.add(revision);
                                }
                            }
                        } catch (IOException | InterruptedException x) {
                            LOGGER.log(Level.FINE, null, x);
                        }
                    }
                }
            }
            return candidates;
        }
        */

    }

}
