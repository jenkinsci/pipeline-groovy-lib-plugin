/*
 * The MIT License
 *
 * Copyright 2017 CloudBees, Inc.
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

import groovy.grape.Grape;
import groovy.lang.MetaClass;
import hudson.PluginManager;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.GitSampleRepoRule;
import jenkins.plugins.git.junit.jupiter.WithGitSampleRepo;
import org.codehaus.groovy.reflection.ClassInfo;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.support.storage.BulkFlowNodeStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.MemoryAssert;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertFalse;

@WithJenkins
@WithGitSampleRepo
class LibraryMemoryTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();
    private JenkinsRule r;
    private GitSampleRepoRule sampleRepo;
    @SuppressWarnings("unused")
    private final LogRecorder logging = new LogRecorder().record(CpsFlowExecution.class, Level.FINER);

    private static final List<WeakReference<ClassLoader>> LOADERS = new ArrayList<>();

    @BeforeEach
    void beforeEach(JenkinsRule rule, GitSampleRepoRule repo) {
        r = rule;
        sampleRepo = repo;
        LOADERS.clear();
    }

    @SuppressWarnings("unused")
    public static void register(Object o) {
        System.err.println("registering " + o);
        for (ClassLoader loader = o.getClass().getClassLoader(); !(loader instanceof PluginManager.UberClassLoader); loader = loader.getParent()) {
            System.err.println("…from " + loader);
            LOADERS.add(new WeakReference<>(loader));
        }
    }

    @Issue("JENKINS-50223")
    @Test
    void loaderReleased() throws Exception {
        sampleRepo.init();
        sampleRepo.write("src/p/C.groovy", "package p; class C {}");
        sampleRepo.write("vars/leak.groovy", "def call() {def c = node {new p.C()}; [this, c].each {" + LibraryMemoryTest.class.getName() + ".register(it)}}");
        sampleRepo.git("add", "src", "vars");
        sampleRepo.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Collections.singletonList(new LibraryConfiguration("leak", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('leak@master') _; " + LibraryMemoryTest.class.getName() + ".register(this); leak(); new p.C()", false));
        r.assertBuildStatusSuccess(p.scheduleBuild2(0));
        assertFalse(LOADERS.isEmpty());
        { // cf. CpsFlowExecutionMemoryTest
            MetaClass metaClass = ClassInfo.getClassInfo(LibraryMemoryTest.class).getMetaClass();
            Method clearInvocationCaches = metaClass.getClass().getDeclaredMethod("clearInvocationCaches");
            clearInvocationCaches.setAccessible(true);
            clearInvocationCaches.invoke(metaClass);
        }
        for (WeakReference<ClassLoader> loaderRef : LOADERS) {
            MemoryAssert.assertGC(loaderRef, false);
        }
    }

    @Test
    void loaderReleasedWithGrab() throws Exception {
        {
            // http-builder loads xerces stuff that XStream has special handling for, and if BulkFlowNodeStorage
            // is first initialized by a Pipeline that uses @Grab, the grabbed classes will be available when
            // BulkFlowNodeStorage.XSTREAM2 is instantiated, causing them to be strongly retained.
            // Specifically com.thoughtworks.xstream.converters.extended.DurationConverter and org.apache.xerces.jaxp.datatype.DatatypeFactoryImpl cause problems.
            // TODO: Should we add an @Initializer to BulkFlowNodeStorage.XSTREAM to avoid this?
            // TODO: Why doesn't MemoryAssert.assertGC notice this reference path?
            BulkFlowNodeStorage.XSTREAM.getClass();
        }
        WorkflowJob p = r.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(
                "@Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.7.1')\n" +
                "import groovyx.net.http.RESTClient\n" +
                LibraryMemoryTest.class.getName() + ".register(this)\n", false));
        r.buildAndAssertSuccess(p);
        assertFalse(LOADERS.isEmpty());
        {
            // cf. CpsFlowExecutionMemoryTest
            MetaClass metaClass = ClassInfo.getClassInfo(LibraryMemoryTest.class).getMetaClass();
            Method clearInvocationCaches = metaClass.getClass().getDeclaredMethod("clearInvocationCaches");
            clearInvocationCaches.setAccessible(true);
            clearInvocationCaches.invoke(metaClass);

            // TODO: GrapeIvy's $callSiteArray softly references the class that most recently used @Grab. I have not
            // tried to track down exactly why. Should this be handled in CpsFlowExecution.cleanUpHeap? IDK if we could
            // do anything besides deleting the cached call sites.
            // Note that we use Grape.getInstance() here to get the GrapeIvy instance set up by GrapeHack.
            var callSiteArrayF = Grape.getInstance().getClass().getDeclaredField("$callSiteArray");
            callSiteArrayF.setAccessible(true);
            ((SoftReference<?>) callSiteArrayF.get(null)).clear();
        }
        for (var loaderRef : LOADERS) {
            MemoryAssert.assertGC(loaderRef, false);
        }
    }
}
