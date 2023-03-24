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
import hudson.FilePath;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.DescriptorVisibilityFilter;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import hudson.util.StreamTaskListener;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceDescriptor;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.structs.describable.CustomDescribableModel;
import org.jenkinsci.plugins.structs.describable.UninstantiatedDescribable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Uses {@link SCMSource#fetch(String, TaskListener)} to retrieve a specific revision.
 */
public class SCMSourceRetriever extends SCMBasedRetriever {

    private final SCMSource scm;

    @DataBoundConstructor public SCMSourceRetriever(SCMSource scm) {
        this.scm = scm;
    }

    /**
     * A source of SCM checkouts.
     */
    public SCMSource getScm() {
        return scm;
    }

    @Override public void retrieve(String name, String version, boolean changelog, FilePath target, Run<?, ?> run, TaskListener listener) throws Exception {
        SCMRevision revision = retrySCMOperation(listener, () -> scm.fetch(version, listener, run.getParent()));
        if (revision == null) {
            throw new AbortException("No version " + version + " found for library " + name);
        }
        doRetrieve(name, changelog, scm.build(revision.getHead(), revision), target, run, listener);
    }

    @Override public void retrieve(String name, String version, FilePath target, Run<?, ?> run, TaskListener listener) throws Exception {
        retrieve(name, version, true, target, run, listener);
    }

    @Override public FormValidation validateVersion(String name, String version, Item context) {
        StringWriter w = new StringWriter();
        try {
            StreamTaskListener listener = new StreamTaskListener(w);
            SCMRevision revision = scm.fetch(version, listener, context);
            if (revision != null) {
                // TODO validate repository structure using SCMFileSystem when implemented (JENKINS-33273)
                return FormValidation.ok("Currently maps to revision: " + revision);
            } else {
                listener.getLogger().flush();
                return FormValidation.warning("Revision seems invalid:\n" + w);
            }
        } catch (Exception x) {
            return FormValidation.warning(x, "Cannot validate default version.");
        }
    }

    @Symbol("modernSCM")
    @Extension public static class DescriptorImpl extends SCMBasedRetrieverDescriptor implements CustomDescribableModel {

        @Override public String getDisplayName() {
            return "Modern SCM";
        }

        /**
         * Returns only implementations overriding {@link SCMSource#retrieve(String, TaskListener)} or {@link SCMSource#retrieve(String, TaskListener, Item)}.
         */
        @Restricted(NoExternalUse.class) // Jelly, Hider
        public Collection<SCMSourceDescriptor> getSCMDescriptors() {
            List<SCMSourceDescriptor> descriptors = new ArrayList<>();
            for (SCMSourceDescriptor d : ExtensionList.lookup(SCMSourceDescriptor.class)) {
                if (Util.isOverridden(SCMSource.class, d.clazz, "retrieve", String.class, TaskListener.class) || Util.isOverridden(SCMSource.class, d.clazz, "retrieve", String.class, TaskListener.class, Item.class)) {
                    descriptors.add(d);
                }
            }
            return descriptors;
        }

        @Override public UninstantiatedDescribable customUninstantiate(UninstantiatedDescribable ud) {
            Object scm = ud.getArguments().get("scm");
            if (scm instanceof UninstantiatedDescribable) {
                UninstantiatedDescribable scmUd = (UninstantiatedDescribable) scm;
                Map<String, Object> scmArguments = new HashMap<>(scmUd.getArguments());
                scmArguments.remove("id");
                Map<String, Object> retrieverArguments = new HashMap<>(ud.getArguments());
                retrieverArguments.put("scm", scmUd.withArguments(scmArguments));
                return ud.withArguments(retrieverArguments);
            }
            return ud;
        }

    }

    @Restricted(DoNotUse.class)
    @Extension public static class Hider extends DescriptorVisibilityFilter {

        @SuppressWarnings("rawtypes")
        @Override public boolean filter(Object context, Descriptor descriptor) {
            if (descriptor instanceof DescriptorImpl) {
                return !((DescriptorImpl) descriptor).getSCMDescriptors().isEmpty();
            } else {
                return true;
            }
        }

    }

}
