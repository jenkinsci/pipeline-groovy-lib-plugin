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
import hudson.Util;
import java.util.Map;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.Set;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Step to load a resource from a library.
 */
public class ResourceStep extends Step {

    private final String resource;
    private String encoding;

    @DataBoundConstructor public ResourceStep(String resource) {
        this.resource = resource;
    }

    public String getResource() {
        return resource;
    }

    public @CheckForNull String getEncoding() {
        return encoding;
    }

    /**
     * Set the encoding to be used when loading the resource. If the specified value is null or
     * whitespace-only, then the platform default encoding will be used. Binary resources can be
     * loaded as a Base64-encoded string by specifying {@code Base64} as the encoding.
     */
    @DataBoundSetter public void setEncoding(String encoding) {
        this.encoding = Util.fixEmptyAndTrim(encoding);
    }

    @Override public StepExecution start(StepContext context) throws Exception {
        return new Execution(this, context);
    }

    @Extension public static class DescriptorImpl extends StepDescriptor {

        @Override public String getDisplayName() {
            return "Load a resource file from a library";
        }

        @Override public String getFunctionName() {
            return "libraryResource";
        }

        @Override public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(FlowExecution.class);
        }

    }

    public static class Execution extends SynchronousStepExecution<String> {

        private static final long serialVersionUID = 1L;

        private transient final ResourceStep step;

        public Execution(ResourceStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        @Override protected String run() throws Exception {
            String resource = step.resource;
            Map<String,String> contents = LibraryAdder.findResources((CpsFlowExecution) getContext().get(FlowExecution.class), resource, step.encoding);
            if (contents.isEmpty()) {
                throw new AbortException(Messages.ResourceStep_no_such_library_resource_could_be_found_(resource));
            } else if (contents.size() == 1) {
                return contents.values().iterator().next();
            } else {
                throw new AbortException(Messages.ResourceStep_library_resource_ambiguous_among_librari(resource, contents.keySet()));
            }
        }

    }

}
