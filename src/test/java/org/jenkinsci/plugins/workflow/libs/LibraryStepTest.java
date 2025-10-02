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

import com.cloudbees.hudson.plugins.folder.Folder;
import com.google.common.collect.ImmutableMap;
import hudson.model.JDK;
import hudson.model.Result;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.GitSampleRepoRule;
import jenkins.plugins.git.traits.BranchDiscoveryTrait;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.Snippetizer;
import org.jenkinsci.plugins.workflow.cps.SnippetizerTester;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepConfigTester;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@Issue("JENKINS-39450")
@WithJenkins
class LibraryStepTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();
    private JenkinsRule r;
    private final GitSampleRepoRule sampleRepo1 = new GitSampleRepoRule();
    private final GitSampleRepoRule sampleRepo2 = new GitSampleRepoRule();

    @BeforeEach
    void beforeEach(JenkinsRule rule) throws Throwable {
        r = rule;
        sampleRepo1.before();
        sampleRepo2.before();
    }

    @AfterEach
    void afterEach() {
        sampleRepo1.after();
        sampleRepo2.after();
    }

    @Test
    void configRoundtrip() throws Exception {
        StepConfigTester stepTester = new StepConfigTester(r);
        SnippetizerTester snippetizerTester = new SnippetizerTester(r);
        LibraryStep s = new LibraryStep("foo");
        r.assertEqualDataBoundBeans(s, stepTester.configRoundTrip(s));
        snippetizerTester.assertRoundTrip(s, "library 'foo'");
        s = new LibraryStep("foo@master");
        GitSCMSource scmSource = new GitSCMSource("https://nowhere.net/");
        scmSource.setTraits(Collections.singletonList(new BranchDiscoveryTrait()));
        scmSource.setCredentialsId(""); // TODO the setter ought to use fixEmpty
        s.setRetriever(new SCMSourceRetriever(scmSource));
        s.setChangelog(true);
        r.assertEqualDataBoundBeans(s, stepTester.configRoundTrip(s));
        // TODO uninstantiate works but SnippetizerTester.assertRoundTrip fails due to differing SCMSource.id values
        assertEquals("library identifier: 'foo@master', retriever: modernSCM([$class: 'GitSCMSource', credentialsId: '', remote: 'https://nowhere.net/', traits: [gitBranchDiscovery()]])", Snippetizer.object2Groovy(s));
        s.setRetriever(new SCMRetriever(new GitSCM(Collections.singletonList(new UserRemoteConfig("https://nowhere.net/", null, null, null)),
            Collections.singletonList(new BranchSpec("${library.foo.version}")),
            null, null, Collections.emptyList())));
        s.setChangelog(false);
        r.assertEqualDataBoundBeans(s, stepTester.configRoundTrip(s));
        snippetizerTester.assertRoundTrip(s, "library changelog: false, identifier: 'foo@master', retriever: legacySCM(scmGit(branches: [[name: '${library.foo.version}']], extensions: [], userRemoteConfigs: [[url: 'https://nowhere.net/']]))");
    }

    @Test
    void vars() throws Exception {
        sampleRepo1.init();
        sampleRepo1.write("vars/x.groovy", "def call() {echo 'ran library'}");
        sampleRepo1.git("add", "vars");
        sampleRepo1.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Collections.singletonList(new LibraryConfiguration("stuff", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo1.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("library 'stuff@master'; x()", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("ran library", b);
        LibrariesAction action = b.getAction(LibrariesAction.class);
        assertNotNull(action);
        String directoryName = LibraryRecord.directoryNameFor("stuff", "master", String.valueOf(true), GlobalLibraries.ForJob.class.getName());
        assertEquals("[LibraryRecord{name=stuff, version=master, variables=[x], trusted=true, changelog=true, cachingConfiguration=null, directoryName=" + directoryName + "}]", action.getLibraries().toString());
        p.setDefinition(new CpsFlowDefinition("library identifier: 'otherstuff@master', retriever: modernSCM([$class: 'GitSCMSource', remote: $/" + sampleRepo1 + "/$, credentialsId: '']), changelog: false; x()", true));
        b = r.buildAndAssertSuccess(p);
        r.assertLogContains("ran library", b);
        action = b.getAction(LibrariesAction.class);
        assertNotNull(action);
        directoryName = LibraryRecord.directoryNameFor("otherstuff", "master", String.valueOf(false), LibraryStep.class.getName() + " " + b.getExternalizableId());
        assertEquals("[LibraryRecord{name=otherstuff, version=master, variables=[x], trusted=false, changelog=false, cachingConfiguration=null, directoryName=" + directoryName + "}]", action.getLibraries().toString());
    }

    @Test
    void classes() throws Exception {
        sampleRepo1.init();
        sampleRepo1.write("src/some/pkg/Lib.groovy", "package some.pkg; class Lib {static class Inner {static String stuff() {Constants.CONST}}}");
        sampleRepo1.write("src/some/pkg/Constants.groovy", "package some.pkg; class Constants {static String CONST = 'constant'}");
        sampleRepo1.write("src/some/pkg/App.groovy", "package some.pkg; class App implements Serializable {def run() {Lib.Inner.stuff()}}");
        sampleRepo1.git("add", "src");
        sampleRepo1.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Collections.singletonList(new LibraryConfiguration("stuff", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo1.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("def lib = library 'stuff@master'; echo(/using ${lib.some.pkg.Lib.Inner.stuff()} vs. ${lib.some.pkg.App.new().run()}/)", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("using constant vs. constant", b);
    }

    @Test
    void missingProperty() throws Exception {
        sampleRepo1.init();
        sampleRepo1.write("src/some/pkg/MyClass.groovy", "package some.pkg; class MyClass { }");
        sampleRepo1.git("add", "src");
        sampleRepo1.git("commit", "--message=init");
        Folder f = r.jenkins.createProject(Folder.class, "f");
        f.getProperties().add(new FolderLibraries(Collections.singletonList(new LibraryConfiguration("stuff", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo1.toString(), "", "*", "", true))))));
        WorkflowJob p = f.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                        def lib = library 'stuff@master'
                        lib.some.pkg.MyClass.no_field_with_this_name
                        """, true));
        WorkflowRun b = r.buildAndAssertStatus(Result.FAILURE, p);
        r.assertLogContains("MissingPropertyException: No such property: no_field_with_this_name for class: some.pkg.MyClass", b);
    }

    @Test
    void reflectionInLoadedClassesIsIntercepted() throws Exception {
        sampleRepo1.init();
        sampleRepo1.write("src/some/pkg/MyThread.groovy", "package some.pkg; class MyThread extends Thread { }");
        sampleRepo1.git("add", "src");
        sampleRepo1.git("commit", "--message=init");
        Folder f = r.jenkins.createProject(Folder.class, "f");
        f.getProperties().add(new FolderLibraries(Collections.singletonList(new LibraryConfiguration("stuff", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo1.toString(), "", "*", "", true))))));
        WorkflowJob p = f.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                        def lib = library 'stuff@master'
                        catchError() { lib.some.pkg.MyThread.new(null) }
                        catchError() { lib.some.pkg.MyThread.__$stMC }
                        catchError() { lib.some.pkg.MyThread.$getCallSiteArray() }
                        """, true));
        WorkflowRun b = r.buildAndAssertStatus(Result.FAILURE, p);
        r.assertLogContains("Rejecting illegal call to synthetic constructor", b);
        r.assertLogContains("staticField some.pkg.MyThread __$stMC", b);
        r.assertLogContains("staticMethod some.pkg.MyThread $getCallSiteArray", b);
    }

    @Issue("SECURITY-2824")
    @Test
    void constructorInvocationInLoadedClassesIsIntercepted() throws Exception {
        sampleRepo1.init();
        sampleRepo1.write("src/pkg/Superclass.groovy",
                """
                        package pkg;
                        class Superclass { Superclass(String x) { } }
                        """);
        sampleRepo1.write("src/pkg/Subclass.groovy",
                """
                        package pkg;
                        class Subclass extends Superclass {
                          def wrapper
                          Subclass() { super('secret.key'); def $cw = $cw; wrapper = $cw }
                        }
                        """);
        sampleRepo1.write("src/pkg/MyFile.groovy",
                """
                        package pkg;
                        class MyFile extends File {
                          MyFile(String path) {
                            super(path)
                          }
                        }
                        """);
        sampleRepo1.git("add", "src");
        sampleRepo1.git("commit", "--message=init");
        Folder f = r.jenkins.createProject(Folder.class, "f");
        f.getProperties().add(new FolderLibraries(Collections.singletonList(new LibraryConfiguration("stuff", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo1.toString(), "", "*", "", true))))));
        WorkflowJob p = f.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                        def lib = library 'stuff@master'
                        def wrapper = lib.pkg.Subclass.new().wrapper
                        def file = lib.pkg.MyFile.new(wrapper, 'unused')
                        echo(/${[file, file.class]}/)""", true));
        WorkflowRun b = r.buildAndAssertStatus(Result.FAILURE, p);
        r.assertLogContains("Rejecting illegal call to synthetic constructor: private pkg.MyFile", b);
    }

    @Disabled("Trusted libraries should never get a RejectedAccessException, but this case should be uncommon and is difficult to handle more precisely")
    @Test
    void falsePositiveRejectedAccessExceptionInTrustedLibrary() throws Exception {
        sampleRepo1.init();
        sampleRepo1.git("branch", "myBranch");
        sampleRepo1.write("vars/doStuff.groovy",
                """
                        def call() {
                          def lib = library('stuff2@myBranch')
                          lib.some.pkg.MyClass.$getCallSiteArray()
                        }
                        """);
        sampleRepo1.git("add", ".");
        sampleRepo1.git("commit", "--message=init");
        sampleRepo1.git("checkout", "myBranch");
        sampleRepo1.write("src/some/pkg/MyClass.groovy", "package some.pkg; class MyClass { }");
        sampleRepo1.git("add", ".");
        sampleRepo1.git("commit", "--message=myBranch");
        GlobalLibraries.get().setLibraries(Arrays.asList(
                new LibraryConfiguration("stuff", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo1.toString(), "", "*", "", true))),
                new LibraryConfiguration("stuff2", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo1.toString(), "", "*", "", true)))));
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                        @Library('stuff@master')
                        import doStuff
                        doStuff()
                        """, true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
    }

    @Test
    void classesFromWrongPlace() throws Exception {
        sampleRepo1.init();
        sampleRepo1.write("src/some/pkg/Lib.groovy", "package some.pkg; class Lib {static void m() {}}");
        sampleRepo1.git("add", "src");
        sampleRepo1.git("commit", "--message=init");
        sampleRepo2.init();
        sampleRepo2.write("src/other/pkg/Lib.groovy", "package other.pkg; class Lib {static void m() {}}");
        sampleRepo2.git("add", "src");
        sampleRepo2.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Arrays.asList(
            new LibraryConfiguration("stuff", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo1.toString(), "", "*", "", true))),
            new LibraryConfiguration("stuph", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo2.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("library('stuff@master').some.pkg.Lib.m()", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        p.setDefinition(new CpsFlowDefinition("library('stuph@master').other.pkg.Lib.m()", true));
        b = r.buildAndAssertSuccess(p);
        p.setDefinition(new CpsFlowDefinition("library('stuph@master'); library('stuff@master').other.pkg.Lib.m()", true));
        b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
        r.assertLogContains(IllegalAccessException.class.getName(), b);
        p.setDefinition(new CpsFlowDefinition("library('stuff@master'); library('stuph@master').some.pkg.Lib.m()", true));
        b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
        r.assertLogContains(IllegalAccessException.class.getName(), b);
        p.setDefinition(new CpsFlowDefinition("library('stuff@master').java.beans.Introspector.flushCaches()", true));
        b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
        r.assertLogContains(IllegalAccessException.class.getName(), b);
    }

    @Test
    void callSites() throws Exception {
        sampleRepo1.init();
        sampleRepo1.write("src/p/C.groovy", "package p; class C {int x; C(int x) {this.x = x}; public static final String CONST = 'constant'; static String append(String x, String y) {x + y}}");
        sampleRepo1.git("add", "src");
        sampleRepo1.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Collections.singletonList(new LibraryConfiguration("x", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo1.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("def klazz = library('x@master').p.C; echo(/CONST=${klazz.CONST} x=${klazz.new(33).x} append=${klazz.append('non', null)}/)", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("CONST=constant x=33 append=nonnull", b);
    }

    @Test
    void untrustedAndReplay() throws Exception {
        sampleRepo1.init();
        sampleRepo1.write("src/p/C.groovy", "package p; class C {static String message() {'used library'}}");
        sampleRepo1.write("vars/x.groovy", "def call() {'ran library'}");
        sampleRepo1.git("add", "src", "vars");
        sampleRepo1.git("commit", "--message=init");
        Folder d = r.jenkins.createProject(Folder.class, "d");
        d.getProperties().add(new FolderLibraries(Collections.singletonList(new LibraryConfiguration("stuff", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo1.toString(), "", "*", "", true))))));
        WorkflowJob p = d.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("echo(/${library('stuff@master').p.C.message()} and ${x()}/)", true));
        WorkflowRun b1 = r.buildAndAssertSuccess(p);
        r.assertLogContains("used library and ran library", b1);
        ReplayAction ra = b1.getAction(ReplayAction.class);
        WorkflowRun b2 = r.assertBuildStatusSuccess((WorkflowRun) ra.run(ra.getOriginalScript(), ImmutableMap.of("p.C", "package p; class C {static String message() {'reused library'}}", "x", "def call() {'reran library'}")).get());
        r.assertLogContains("reused library and reran library", b2);
    }

    @Test
    void nonexistentLibrary() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("library 'nonexistent'", true));
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
        r.assertLogContains("No library named nonexistent found", b);
    }

    @Test
    void duplicatedLibrary() throws Exception {
        sampleRepo1.init();
        sampleRepo1.write("vars/x.groovy", "def call() {echo 'ran library'}");
        sampleRepo1.git("add", "vars");
        sampleRepo1.git("commit", "--message=init");
        LibraryConfiguration cfg = new LibraryConfiguration("stuff", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo1.toString(), "", "*", "", true)));
        cfg.setDefaultVersion("master");
        GlobalLibraries.get().setLibraries(Collections.singletonList(cfg));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('stuff') _; library 'stuff'; library 'stuff'; x()", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("ran library", b);
        r.assertLogContains("Only using first definition of library stuff", b);
        List<LibrariesAction> actions = b.getActions(LibrariesAction.class);
        assertEquals(1, actions.size());
        List<LibraryRecord> libraries = actions.get(0).getLibraries();
        assertEquals(1, libraries.size());
    }

    @Test
    void usingInterpolation() throws Exception {
        sampleRepo1.init();
        sampleRepo1.write("src/pkg/Lib.groovy", "package pkg; public class Lib {public static final String CONST = 'initial'}");
        sampleRepo1.git("add", "src");
        sampleRepo1.git("commit", "--message=init");
        sampleRepo1.git("tag", "initial");
        sampleRepo1.write("src/pkg/Lib.groovy", "package pkg; public class Lib {public static final String CONST = 'modified'}");
        sampleRepo1.git("commit", "--all", "--message=modified");
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        String retriever = "legacySCM([$class: 'GitSCM', branches: [[name: '${library.stuff.version}']], userRemoteConfigs: [[url: '" + sampleRepo1.fileUrl() + "']]])";
        p.setDefinition(new CpsFlowDefinition("echo(/using ${library(identifier: 'stuff@master', retriever: " + retriever + ").pkg.Lib.CONST}/)", true));
        r.assertLogContains("using modified", r.buildAndAssertSuccess(p));
        p.setDefinition(new CpsFlowDefinition("echo(/using ${library(identifier: 'stuff@initial', retriever: " + retriever + ").pkg.Lib.CONST}/)", true));
        r.assertLogContains("using initial", r.buildAndAssertSuccess(p));
    }

    @Test
    void usingToolsFromLibrary() throws Exception {
        JDK.DescriptorImpl jdkDescriptor = r.jenkins.getDescriptorByType(JDK.DescriptorImpl.class);
        jdkDescriptor.setInstallations(new JDK("lib-java", "/lib/java"), new JDK("pipeline-java", "/pipeline/java"));

        sampleRepo1.init();
        sampleRepo1.write("vars/x.groovy", "def call() { def path = tool 'lib-java'; echo path }");
        sampleRepo1.git("add", "vars");
        sampleRepo1.git("commit", "--message=init");
        LibraryConfiguration cfg = new LibraryConfiguration("stuff", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo1.toString(), "", "*", "", true)));
        cfg.setDefaultVersion("master");
        cfg.setImplicit(true);

        Folder f = r.jenkins.createProject(Folder.class, "f");
        f.addProperty(new FolderLibraries(Collections.singletonList(cfg)));

        WorkflowJob p = f.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node () { def path = tool 'pipeline-java'; echo path; x() }", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("/lib/java", b);
        r.assertLogContains("/pipeline/java", b);
    }
}
