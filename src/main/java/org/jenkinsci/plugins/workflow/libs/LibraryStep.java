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

import com.google.common.collect.Lists;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyObject;
import groovy.lang.GroovyObjectSupport;
import groovy.lang.GroovyRuntimeException;
import hudson.AbortException;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.scm.SCM;
import hudson.security.AccessControlled;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import groovy.lang.MissingPropertyException;
import javax.inject.Inject;
import jenkins.model.Jenkins;
import jenkins.scm.impl.SingleSCMSource;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.AbstractWhitelist;
import org.jenkinsci.plugins.workflow.cps.CpsCompilationErrorsException;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.cps.CpsThread;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.groovy.sandbox.GroovyInterceptor;
import org.kohsuke.groovy.sandbox.impl.Checker;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 * Dynamically injects a library into the running build.
 */
public class LibraryStep extends AbstractStepImpl {

    private static final Logger LOGGER = Logger.getLogger(LibraryStep.class.getName());

    private final String identifier;
    private Boolean changelog = true;
    private LibraryRetriever retriever;

    @DataBoundConstructor public LibraryStep(String identifier) {
        this.identifier = identifier;
    }

    public String getIdentifier() {
        return identifier;
    }

    public LibraryRetriever getRetriever() {
        return retriever;
    }

    public Boolean getChangelog() {
        return changelog;
    }
    @DataBoundSetter public void setRetriever(LibraryRetriever retriever) {
        this.retriever = retriever;
    }

    // default to including changes of the library in job recent changes
    @DataBoundSetter public void setChangelog(Boolean changelog) {
        this.changelog = changelog;
    }

    @Extension public static class DescriptorImpl extends AbstractStepDescriptorImpl {

        public DescriptorImpl() {
            super(Execution.class);
        }

        @Override public String getFunctionName() {
            return "library";
        }

        @Override public String getDisplayName() {
            return "Load a library on the fly";
        }

        @Restricted(DoNotUse.class) // Jelly
        public Collection<LibraryRetrieverDescriptor> getRetrieverDescriptors() {
            return Jenkins.get().getDescriptorByType(LibraryConfiguration.DescriptorImpl.class).getRetrieverDescriptors();
        }

        public AutoCompletionCandidates doAutoCompleteIdentifier(@AncestorInPath ItemGroup<?> group, @QueryParameter String value) {
            Set<String> names = new TreeSet<>();
            if (group instanceof AccessControlled && ((AccessControlled) group).hasPermission(Item.EXTENDED_READ)) {
                for (LibraryResolver resolver : ExtensionList.lookup(LibraryResolver.class)) {
                    for (LibraryConfiguration cfg : resolver.suggestedConfigurations(group)) {
                        names.add(cfg.getName());
                    }
                }
            }
            AutoCompletionCandidates candidates = new AutoCompletionCandidates();
            for (String name : names) {
                if (name.startsWith(value)) {
                    candidates.add(name);
                }
            }
            return candidates;
        }

    }

    public static class Execution extends AbstractSynchronousNonBlockingStepExecution<LoadedClasses> {

        private static final long serialVersionUID = 1L;

        @Inject private transient LibraryStep step;
        @StepContextParameter private transient Run<?,?> run;
        @StepContextParameter private transient TaskListener listener;

        @Override protected LoadedClasses run() throws Exception {
            String[] parsed = LibraryAdder.parse(step.identifier);
            String name = parsed[0], version = parsed[1];
            boolean trusted = false;
            Boolean changelog = step.getChangelog();
            LibraryCachingConfiguration cachingConfiguration = null;
            // Note that cachingConfiguration is only ever non-null if source is overwritten below, so the default value of source will never be used when caching is enabled.
            String source = LibraryStep.class.getName() + " " + run.getExternalizableId();
            LibraryRetriever retriever = step.getRetriever();
            if (retriever == null) {
                for (LibraryResolver resolver : ExtensionList.lookup(LibraryResolver.class)) {
                    for (LibraryConfiguration cfg : resolver.forJob(run.getParent(), Collections.singletonMap(name, version))) {
                        if (cfg.getName().equals(name)) {
                            retriever = cfg.getRetriever();
                            trusted = resolver.isTrusted();
                            version = cfg.defaultedVersion(version);
                            changelog = cfg.defaultedChangelogs(changelog);
                            cachingConfiguration = cfg.getCachingConfiguration();
                            source = resolver.getClass().getName();
                            if (cfg instanceof LibraryResolver.ResolvedLibraryConfiguration) {
                                source = ((LibraryResolver.ResolvedLibraryConfiguration) cfg).getSource();
                            }
                            break;
                        }
                    }
                }
                if (retriever == null) {
                    throw new AbortException("No library named " + name + " found");
                }
            } else if (version == null) {
                throw new AbortException("Must specify a version for library " + name);
            }
            // When a user specifies a non-null retriever, they may be using SCMVar in its configuration,
            // so we need to run MultibranchScmRevisionVerifier to prevent unsafe behavior.
            // SCMVar would typically be used with SCMRetriever, but it is also possible to use it with SCMSourceRetriever and SingleSCMSource.
            // There may be false-positive rejections if a Multibranch Pipeline for the repo of a Pipeline library
            // uses the library step with a non-null retriever to check out a static version of the library.
            // Fixing this would require us being able to detect usage of SCMVar precisely, which is not currently possible.
            else if (retriever instanceof SCMRetriever) {
                verifyRevision(((SCMRetriever) retriever).getScm(), name);
            } else if (retriever instanceof SCMSourceRetriever && ((SCMSourceRetriever) retriever).getScm() instanceof SingleSCMSource) {
                verifyRevision(((SingleSCMSource) ((SCMSourceRetriever) retriever).getScm()).getScm(), name);
            }

            LibraryRecord record = new LibraryRecord(name, version, trusted, changelog, cachingConfiguration, source);
            LibrariesAction action = run.getAction(LibrariesAction.class);
            if (action == null) {
                action = new LibrariesAction(Lists.newArrayList(record));
                run.addAction(action);
            } else {
                List<LibraryRecord> libraries = action.getLibraries();
                for (LibraryRecord existing : libraries) {
                    if (existing.name.equals(name)) {
                        listener.getLogger().println("Only using first definition of library " + name);
                        return new LoadedClasses(name, existing.getDirectoryName(), trusted, changelog, run);
                    }
                }
                List<LibraryRecord> newLibraries = new ArrayList<>(libraries);
                newLibraries.add(record);
                run.replaceAction(new LibrariesAction(newLibraries));
            }
            listener.getLogger().println("Loading library " + record.name + "@" + record.version);
            CpsFlowExecution exec = (CpsFlowExecution) getContext().get(FlowExecution.class);
            GroovyClassLoader loader = (trusted ? exec.getTrustedShell() : exec.getShell()).getClassLoader();
            loader.addURL(LibraryAdder.retrieve(record, retriever, listener, run, (CpsFlowExecution) getContext().get(FlowExecution.class)));
            run.save(); // persist changes to LibrariesAction.libraries*.variables
            return new LoadedClasses(name, record.getDirectoryName(), trusted, changelog, run);
        }

        private void verifyRevision(SCM scm, String name) throws IOException, InterruptedException {
            for (LibraryStepRetrieverVerifier revisionVerifier : LibraryStepRetrieverVerifier.all()) {
                revisionVerifier.verify(this.run, listener, scm, name);
            }
        }

    }

    public static final class LoadedClasses extends GroovyObjectSupport implements Serializable {

        private final @NonNull String library;
        private final boolean trusted;
        private final Boolean changelog;
        /** package prefix, like {@code } or {@code some.pkg.} */
        private final @NonNull String prefix;
        /** {@link Class#getName} minus package prefix */
        private final @CheckForNull String clazz;
        /** {@code jar:file:/…/libs/$hash.jar!/} */
        private final @NonNull String srcUrl;

        LoadedClasses(String library, String libraryDirectoryName, boolean trusted, Boolean changelog, Run<?,?> run) {
            this(library, trusted, changelog, "", null, /* cf. LibraryAdder.retrieve */ "jar:" + new File(run.getRootDir(), "libs/" + libraryDirectoryName + ".jar").toURI() + "!/");
        }

        LoadedClasses(String library, boolean trusted, Boolean changelog, String prefix, String clazz, String srcUrl) {
            this.library = library;
            this.trusted = trusted;
            this.changelog = changelog;
            this.prefix = prefix;
            this.clazz = clazz;
            this.srcUrl = srcUrl;
        }

        @Override public Object getProperty(String property) {
            if (clazz != null) {
                // Field access?
                try {
                    if (isSandboxed()) {
                        return Checker.checkedGetAttribute(loadClass(prefix + clazz), false, false, property);
                    }
                    return loadClass(prefix + clazz).getField(property).get(null);
                } catch (MissingPropertyException | NoSuchFieldException x) {
                    // guessed wrong
                } catch (SecurityException x) {
                    throw x;
                } catch (Throwable x) {
                    throw new GroovyRuntimeException(x);
                }
            }
            if (property.matches("^[A-Z].*")) {
                // looks like a class name component
                String fullClazz = clazz != null ? clazz + '$' + property : property;
                loadClass(prefix + fullClazz);
                // OK, class really exists, stash it and await methods
                return new LoadedClasses(library, trusted, changelog, prefix, fullClazz, srcUrl);
            } else if (clazz != null) {
                throw new MissingPropertyException(property, loadClass(prefix + clazz));
            } else {
                // Still selecting package components.
                return new LoadedClasses(library, trusted, changelog, prefix + property + '.', null, srcUrl);
            }
        }

        @Override public Object invokeMethod(String name, Object _args) {
            Class<?> c = loadClass(prefix + clazz);
            Object[] args = _args instanceof Object[] ? (Object[]) _args : new Object[] {_args}; // TODO why does Groovy not just pass an Object[] to begin with?!
            if (isSandboxed()) {
                try {
                    if (name.equals("new")) {
                        return Checker.checkedConstructor(c, args);
                    } else {
                        return Checker.checkedStaticCall(c, name, args);
                    }
                } catch (SecurityException x) {
                    throw x;
                } catch (Throwable x) {
                    throw new GroovyRuntimeException(x);
                }
            }
            if (name.equals("new")) {
                return InvokerHelper.invokeConstructorOf(c, args);
            } else {
                return InvokerHelper.invokeStaticMethod(c, name, args);
            }
        }

        /**
         * Check whether the current thread has at least one active {@link GroovyInterceptor}.
         * <p>
         * Typically, {@code GroovyClassLoaderWhitelist} will allow access to everything defined in a class in a
         * library, but there are some synthetic constructors, fields, and methods which should not be accessible.
         * <p>
         * As a result, when getting properties or invoking methods using this class, we need to apply sandbox
         * protection if the Pipeline code performing the operation is sandbox-transformed. Unfortunately, it is
         * difficult to detect that case specifically, so we instead intercept all calls if the Pipeline itself is
         * sandboxed. This results in a false positive {@code RejectedAccessException} being thrown if a trusted
         * library uses the {@code library} step and tries to access static fields or methods that are not permitted to
         * be used in the sandbox.
         */
        private static boolean isSandboxed() {
            return !GroovyInterceptor.getApplicableInterceptors().isEmpty();
        }

        // TODO putProperty for static field set

        private Class<?> loadClass(String name) {
            CpsFlowExecution exec = CpsThread.current().getExecution();
            GroovyClassLoader loader = (trusted ? exec.getTrustedShell() : exec.getShell()).getClassLoader();
            try {
                Class<?> c = loader.loadClass(name);
                ClassLoader definingLoader = c.getClassLoader();
                if (definingLoader instanceof GroovyClassLoader.InnerLoader) {
                    definingLoader = definingLoader.getParent();
                }
                if (definingLoader != loader) {
                    throw new IllegalAccessException("cannot access " + c + " via library handle: " + definingLoader + " is not " + loader);
                }
                // Note that this goes through GroovyCodeSource.<init>(File, String), which unlike (say) URLClassLoader set the “location” to the actual file, *not* the root.
                CodeSource codeSource = c.getProtectionDomain().getCodeSource();
                if (codeSource == null) {
                    throw new IllegalAccessException(name + " had no defined code source");
                }
                String loc = codeSource.getLocation().toString();
                if (!loc.startsWith(srcUrl)) {
                    throw new IllegalAccessException(name + " was defined in " + loc + " which was not inside " + srcUrl);
                }
                if (!Modifier.isPublic(c.getModifiers())) { // unlikely since Groovy makes classes implicitly public
                    throw new IllegalAccessException(c + " is not public");
                }
                return c;
            } catch (MultipleCompilationErrorsException x) {
                throw new CpsCompilationErrorsException(x);
            } catch (ClassNotFoundException | IllegalAccessException x) {
                throw new GroovyRuntimeException(x);
            }
        }


    }

    @Extension public static class LoadedClassesWhitelist extends AbstractWhitelist { // TODO JENKINS-24982 @Whitelisted does not suffice
        @Override public boolean permitsMethod(Method method, Object receiver, Object[] args) {
            String name = method.getName();
            return receiver instanceof LoadedClasses && method.getDeclaringClass() == GroovyObject.class && (name.equals("getProperty") || name.equals("invokeMethod"));
        }
    }

}
