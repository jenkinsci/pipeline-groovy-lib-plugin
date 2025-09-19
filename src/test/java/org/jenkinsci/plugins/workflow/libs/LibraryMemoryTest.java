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

import groovy.lang.MetaClass;
import hudson.PluginManager;
import java.io.ObjectStreamClass;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.GitSampleRepoRule;
import org.codehaus.groovy.reflection.ClassInfo;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.support.storage.BulkFlowNodeStorage;
import org.junit.Before;
import static org.junit.Assert.assertFalse;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.MemoryAssert;

public class LibraryMemoryTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsRule r = new JenkinsRule();
    @Rule public GitSampleRepoRule sampleRepo = new GitSampleRepoRule();
    @Rule public LoggerRule logging = new LoggerRule().record(CpsFlowExecution.class, Level.FINER);

    // Using a set to avoid duplicate elements, which just makes `assertGC` slower.
    private static final List<WeakReference<ClassLoader>> LOADERS = new ArrayList<>();
    public static void register(Object o) {
        System.err.println("registering " + o);
        for (ClassLoader loader = o.getClass().getClassLoader(); !(loader instanceof PluginManager.UberClassLoader); loader = loader.getParent()) {
            System.err.println("…from " + loader);
            LOADERS.add(new WeakReference<>(loader));
        }
    }

    @Before
    public void cleanUp() {
        LOADERS.clear();
    }

    @Issue("JENKINS-50223")
    @Test public void loaderReleased() throws Exception {
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
        {
            // cf. CpsFlowExecutionMemoryTest
            MetaClass metaClass = ClassInfo.getClassInfo(LibraryMemoryTest.class).getMetaClass();
            Method clearInvocationCaches = metaClass.getClass().getDeclaredMethod("clearInvocationCaches");
            clearInvocationCaches.setAccessible(true);
            clearInvocationCaches.invoke(metaClass);

            // Force ObjectStreamClass$Caches.localDesc -> ClassCache.processQueue, otherwise ClassCache.type sometimes references the script.
            // TODO: Does this need to go into CpsFlowExecution.cleanUpHeap?
            // Also, this is inconsistent (or maybe useless), sometimes it still shows up in "Apparent soft references..."
            ObjectStreamClass.lookup(String.class);
        }
        for (WeakReference<ClassLoader> loaderRef : LOADERS) {
            MemoryAssert.assertGC(loaderRef, false);
        }
    }

    @Test public void loaderReleasedWithGrab() throws Exception {
        {
            // http-builder loads xerces stuff that XStream has special handling for, and apparently if BulkFlowNodeStorage
            // is first loaded by a Pipeline that uses @Grab, the grabbed stuff will be available when BulkFlowNodeStorage.XSTREAM2
            // is instantiated and then strongly retained, so we need to avoid that.
            // Specifically com.thoughtworks.xstream.converters.extended.DurationConverter and org.apache.xerces.jaxp.datatype.DatatypeFactoryImpl
            // TODO: Do we need an @Initializer for BulkFlowNodeStorage.XSTREAM to avoid this?
            BulkFlowNodeStorage.XSTREAM.getClass();
        }
        sampleRepo.init();
        sampleRepo.write("vars/util.groovy",
                "@Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.7.1')\n" +
                "import groovyx.net.http.RESTClient\n" +
                "def call(body) {\n" +
                "  return\n" +
                "}\n" +
                "");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Collections.singletonList(new LibraryConfiguration("leak3", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('leak3@master') _; " + LibraryMemoryTest.class.getName() + ".register(this); util()", false));
        r.buildAndAssertSuccess(p);
        assertFalse(LOADERS.isEmpty());
        {
            // cf. CpsFlowExecutionMemoryTest
            MetaClass metaClass = ClassInfo.getClassInfo(LibraryMemoryTest.class).getMetaClass();
            Method clearInvocationCaches = metaClass.getClass().getDeclaredMethod("clearInvocationCaches");
            clearInvocationCaches.setAccessible(true);
            clearInvocationCaches.invoke(metaClass);

            // Force ObjectStreamClass$Caches.localDesc -> ClassCache.processQueue, otherwise ClassCache.type references util.
            // TODO: Does this need to go into CpsFlowExecution.cleanUpHeap?
            // Also, this is inconsistent (or maybe useless), sometimes it still shows up in "Apparent soft references..."
            ObjectStreamClass.lookup(String.class);

            // TODO: GrapeIvy's $callSiteArray softly references `util`, why?
            // It also makes assertGC slow, but it often still passes, even with `allowSoft`, why?
            /*
            Apparent soft references to org.jenkinsci.plugins.workflow.cps.CpsGroovyShell$CleanGroovyClassLoader@1ed31c97: {org.jenkinsci.plugins.workflow.cps.CpsGroovyShell$CleanGroovyClassLoader@1ed31c97=private static java.lang.ref.SoftReference groovy.grape.GrapeIvy.$callSiteArray->
            java.lang.ref.SoftReference@707812c-referent->
            org.codehaus.groovy.runtime.callsite.CallSiteArray@4c0bfefb-array->
            [Lorg.codehaus.groovy.runtime.callsite.CallSite;@251238-[44]->
            org.codehaus.groovy.runtime.callsite.ClassMetaClassGetPropertySite@19a8bc43-aClass->
            class util@33186969-<changed>->
            org.jenkinsci.plugins.workflow.cps.CpsGroovyShell$CleanGroovyClassLoader@1ed31c97};
            */
            // Also, the following makes no difference:
            var grapeIvyC = Class.forName("groovy.grape.GrapeIvy");
            var callSiteArrayF = grapeIvyC.getDeclaredField("$callSiteArray");
            callSiteArrayF.setAccessible(true);
            var softRef = ((SoftReference<?>) callSiteArrayF.get(null));
            softRef.clear();
        }
        // TODO: Trying to debug why assertGC(_, false) is slow but still passes
        var pid = ProcessHandle.current().pid();
        new ProcessBuilder().command("jmap", "-dump:format=b,file=heap-" + pid + ".bin", String.valueOf(pid)).start().waitFor(10, TimeUnit.SECONDS);
        for (var loaderRef : LOADERS) {
            MemoryAssert.assertGC(loaderRef, false);
        }
    }

}
