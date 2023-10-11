/*
 * The MIT License
 *
 * Copyright 2021 CloudBees, Inc.
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

import hudson.ExtensionList;
import hudson.FilePath;
import java.io.File;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.GitSampleRepoRule;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.WithoutJenkins;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.io.FileMatchers.anExistingDirectory;
import static org.hamcrest.io.FileMatchers.anExistingFile;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LibraryCachingConfigurationTest {
    @Rule
    public JenkinsRule r = new JenkinsRule();
    @Rule
    public GitSampleRepoRule sampleRepo = new GitSampleRepoRule();

    private LibraryCachingConfiguration nullVersionConfig;
    private LibraryCachingConfiguration oneVersionConfig;
    private LibraryCachingConfiguration multiVersionConfig;
    private LibraryCachingConfiguration substringVersionConfig;

    private static int REFRESH_TIME_MINUTES = 23;
    private static int NO_REFRESH_TIME_MINUTES = 0;

    private static String NULL_EXCLUDED_VERSION = null;
    private static String NULL_INCLUDED_VERSION = null;

    private static String ONE_EXCLUDED_VERSION = "branch-1";

    private static String ONE_INCLUDED_VERSION = "branch-1i";


    private static String MULTIPLE_EXCLUDED_VERSIONS_1 = "main";

    private static String MULTIPLE_INCLUDED_VERSIONS_1 = "master";

    private static String MULTIPLE_EXCLUDED_VERSIONS_2 = "branch-2";

    private static String MULTIPLE_INCLUDED_VERSIONS_2 = "branch-2i";

    private static String MULTIPLE_EXCLUDED_VERSIONS_3 = "branch-3";
    private static String MULTIPLE_INCLUDED_VERSIONS_3 = "branch-3i";


    private static String SUBSTRING_EXCLUDED_VERSIONS_1 = "feature/test-substring-exclude";

    private static String SUBSTRING_INCLUDED_VERSIONS_1 = "feature_include/test-substring";

    private static String SUBSTRING_EXCLUDED_VERSIONS_2 = "test-other-substring-exclude";
    private static String SUBSTRING_INCLUDED_VERSIONS_2 = "test-other-substring-include";


    private static String MULTIPLE_EXCLUDED_VERSIONS =
        MULTIPLE_EXCLUDED_VERSIONS_1 + " " +
        MULTIPLE_EXCLUDED_VERSIONS_2 + " " +
        MULTIPLE_EXCLUDED_VERSIONS_3;

    private static String MULTIPLE_INCLUDED_VERSIONS =
            MULTIPLE_INCLUDED_VERSIONS_1 + " " +
            MULTIPLE_INCLUDED_VERSIONS_2 + " " +
            MULTIPLE_INCLUDED_VERSIONS_3;

    private static String SUBSTRING_EXCLUDED_VERSIONS =
        "feature/ other-substring";

    private static String SUBSTRING_INCLUDED_VERSIONS =
            "feature_include/ other-substring";

    private static String NEVER_EXCLUDED_VERSION = "never-excluded-version";

    @Before
    public void createCachingConfiguration() {
        nullVersionConfig = new LibraryCachingConfiguration(REFRESH_TIME_MINUTES, NULL_EXCLUDED_VERSION, NULL_INCLUDED_VERSION);
        oneVersionConfig = new LibraryCachingConfiguration(NO_REFRESH_TIME_MINUTES, ONE_EXCLUDED_VERSION, ONE_INCLUDED_VERSION);
        multiVersionConfig = new LibraryCachingConfiguration(REFRESH_TIME_MINUTES, MULTIPLE_EXCLUDED_VERSIONS, MULTIPLE_INCLUDED_VERSIONS);
        substringVersionConfig = new LibraryCachingConfiguration(REFRESH_TIME_MINUTES, SUBSTRING_EXCLUDED_VERSIONS, SUBSTRING_INCLUDED_VERSIONS);
    }

    @Issue("JENKINS-66045") // NPE getting excluded versions
    @Test
    @WithoutJenkins
    public void npeGetExcludedVersions() {
        assertFalse(nullVersionConfig.isExcluded(NEVER_EXCLUDED_VERSION));
    }

    @Test
    @WithoutJenkins
    public void getRefreshTimeMinutes() {
        assertThat(nullVersionConfig.getRefreshTimeMinutes(), is(REFRESH_TIME_MINUTES));
        assertThat(oneVersionConfig.getRefreshTimeMinutes(), is(NO_REFRESH_TIME_MINUTES));
    }

    @Test
    @WithoutJenkins
    public void getRefreshTimeMilliseconds() {
        assertThat(nullVersionConfig.getRefreshTimeMilliseconds(), is(60 * 1000L * REFRESH_TIME_MINUTES));
        assertThat(oneVersionConfig.getRefreshTimeMilliseconds(), is(60 * 1000L * NO_REFRESH_TIME_MINUTES));
    }

    @Test
    @WithoutJenkins
    public void isRefreshEnabled() {
        assertTrue(nullVersionConfig.isRefreshEnabled());
        assertFalse(oneVersionConfig.isRefreshEnabled());
    }

    @Test
    @WithoutJenkins
    public void getExcludedVersionsStr() {
        assertThat(nullVersionConfig.getExcludedVersionsStr(), is(NULL_EXCLUDED_VERSION));
        assertThat(oneVersionConfig.getExcludedVersionsStr(), is(ONE_EXCLUDED_VERSION));
        assertThat(multiVersionConfig.getExcludedVersionsStr(), is(MULTIPLE_EXCLUDED_VERSIONS));
        assertThat(substringVersionConfig.getExcludedVersionsStr(), is(SUBSTRING_EXCLUDED_VERSIONS));
    }

    @Test
    @WithoutJenkins
    public void getIncludedVersionsStr() {
        assertThat(nullVersionConfig.getIncludedVersionsStr(), is(NULL_INCLUDED_VERSION));
        assertThat(oneVersionConfig.getIncludedVersionsStr(), is(ONE_INCLUDED_VERSION));
        assertThat(multiVersionConfig.getIncludedVersionsStr(), is(MULTIPLE_INCLUDED_VERSIONS));
        assertThat(substringVersionConfig.getIncludedVersionsStr(), is(SUBSTRING_INCLUDED_VERSIONS));
    }

    @Test
    @WithoutJenkins
    public void isExcluded() {
        assertFalse(nullVersionConfig.isExcluded(NULL_EXCLUDED_VERSION));
        assertFalse(nullVersionConfig.isExcluded(""));

        assertTrue(oneVersionConfig.isExcluded(ONE_EXCLUDED_VERSION));

        assertTrue(multiVersionConfig.isExcluded(MULTIPLE_EXCLUDED_VERSIONS_1));
        assertTrue(multiVersionConfig.isExcluded(MULTIPLE_EXCLUDED_VERSIONS_2));
        assertTrue(multiVersionConfig.isExcluded(MULTIPLE_EXCLUDED_VERSIONS_3));

        assertTrue(substringVersionConfig.isExcluded(SUBSTRING_EXCLUDED_VERSIONS_1));
        assertTrue(substringVersionConfig.isExcluded(SUBSTRING_EXCLUDED_VERSIONS_2));

        assertFalse(nullVersionConfig.isExcluded(NEVER_EXCLUDED_VERSION));
        assertFalse(oneVersionConfig.isExcluded(NEVER_EXCLUDED_VERSION));
        assertFalse(multiVersionConfig.isExcluded(NEVER_EXCLUDED_VERSION));

        assertFalse(nullVersionConfig.isExcluded(""));
        assertFalse(oneVersionConfig.isExcluded(""));
        assertFalse(multiVersionConfig.isExcluded(""));
        assertFalse(substringVersionConfig.isExcluded(""));

        assertFalse(nullVersionConfig.isExcluded(null));
        assertFalse(oneVersionConfig.isExcluded(null));
        assertFalse(multiVersionConfig.isExcluded(null));
        assertFalse(substringVersionConfig.isExcluded(null));
    }

    @Issue("JENKINS-69135") //"Versions to include" feature for caching
    @Test
    @WithoutJenkins
    public void isIncluded() {
        assertFalse(nullVersionConfig.isIncluded(NULL_INCLUDED_VERSION));
        assertFalse(nullVersionConfig.isIncluded(""));

        assertTrue(oneVersionConfig.isIncluded(ONE_INCLUDED_VERSION));

        assertTrue(multiVersionConfig.isIncluded(MULTIPLE_INCLUDED_VERSIONS_1));
        assertTrue(multiVersionConfig.isIncluded(MULTIPLE_INCLUDED_VERSIONS_2));
        assertTrue(multiVersionConfig.isIncluded(MULTIPLE_INCLUDED_VERSIONS_3));

        assertTrue(substringVersionConfig.isIncluded(SUBSTRING_INCLUDED_VERSIONS_1));
        assertTrue(substringVersionConfig.isIncluded(SUBSTRING_INCLUDED_VERSIONS_2));

    }

    @Test
    public void clearCache() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/foo.groovy", "def call() { echo 'foo' }");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        LibraryConfiguration config = new LibraryConfiguration("library",
                new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)));
        config.setDefaultVersion("master");
        config.setImplicit(true);
        config.setCachingConfiguration(new LibraryCachingConfiguration(30, null));
        GlobalLibraries.get().getLibraries().add(config);
        // Run build and check that cache gets created.
        WorkflowJob p = r.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition("foo()", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        LibrariesAction action = b.getAction(LibrariesAction.class);
        LibraryRecord record = action.getLibraries().get(0);
        FilePath cache = LibraryCachingConfiguration.getGlobalLibrariesCacheDir().child(record.getDirectoryName());
        assertThat(new File(cache.getRemote()), anExistingDirectory());
        assertThat(new File(cache.withSuffix("-name.txt").getRemote()), anExistingFile());
        // Clear the cache. TODO: Would be more realistic to set up security and use WebClient.
        ExtensionList.lookupSingleton(LibraryCachingConfiguration.DescriptorImpl.class).doClearCache("library", false);
        assertThat(new File(cache.getRemote()), not(anExistingDirectory()));
        assertThat(new File(cache.withSuffix("-name.txt").getRemote()), not(anExistingFile()));
    }

    //Test similar substrings in "Versions to include" & "Versions to exclude"
    //Exclusion takes precedence
    @Issue("JENKINS-69135") //"Versions to include" feature for caching
    @Test
    public void clearCacheConflict() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/foo.groovy", "def call() { echo 'foo' }");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        LibraryConfiguration config = new LibraryConfiguration("library",
                new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)));
        config.setDefaultVersion("master");
        config.setImplicit(true);
        // Same version specified in both include and exclude version
        //Exclude takes precedence
        config.setCachingConfiguration(new LibraryCachingConfiguration(30, "master", "master"));
        GlobalLibraries.get().getLibraries().add(config);
        // Run build and check that cache gets created.
        WorkflowJob p = r.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition("foo()", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        LibrariesAction action = b.getAction(LibrariesAction.class);
        LibraryRecord record = action.getLibraries().get(0);
        FilePath cache = LibraryCachingConfiguration.getGlobalLibrariesCacheDir().child(record.getDirectoryName());
        // Cache should not get created since the version is included in "Versions to exclude"
        assertThat(new File(cache.getRemote()), not(anExistingDirectory()));
        assertThat(new File(cache.withSuffix("-name.txt").getRemote()), not(anExistingFile()));
    }

    @Issue("JENKINS-69135") //"Versions to include" feature for caching
    @Test
    public void clearCacheIncludedVersion() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/foo.groovy", "def call() { echo 'foo' }");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        sampleRepo.git("branch", "test/include");
        LibraryConfiguration config = new LibraryConfiguration("library",
                new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)));
        config.setDefaultVersion("master");
        config.setAllowVersionOverride(true);
        config.setImplicit(false);
        config.setCachingConfiguration(new LibraryCachingConfiguration(30, "", "test/include"));
        GlobalLibraries.get().getLibraries().add(config);
        // Run build and check that cache gets created.
        WorkflowJob p = r.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition("library identifier: 'library', changelog:false\n\nfoo()", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        WorkflowJob p2 = r.createProject(WorkflowJob.class);
        p2.setDefinition(new CpsFlowDefinition("library identifier: 'library@test/include', changelog:false\n\nfoo()", true));
        WorkflowRun b2 = r.buildAndAssertSuccess(p2);
        LibrariesAction action = b.getAction(LibrariesAction.class);
        LibraryRecord record = action.getLibraries().get(0);
        LibrariesAction action2 = b2.getAction(LibrariesAction.class);
        LibraryRecord record2 = action2.getLibraries().get(0);
        FilePath cache = LibraryCachingConfiguration.getGlobalLibrariesCacheDir().child(record.getDirectoryName());
        FilePath cache2 = LibraryCachingConfiguration.getGlobalLibrariesCacheDir().child(record2.getDirectoryName());
        assertThat(new File(cache.getRemote()), not(anExistingDirectory()));
        assertThat(new File(cache.withSuffix("-name.txt").getRemote()), not(anExistingFile()));
        assertThat(new File(cache2.getRemote()), anExistingDirectory());
        assertThat(new File(cache2.withSuffix("-name.txt").getRemote()), anExistingFile());
        // Clears cache for the entire library, until the "Delete specific cache version" feature in merged
        // Clear the cache. TODO: Would be more realistic to set up security and use WebClient.
        ExtensionList.lookupSingleton(LibraryCachingConfiguration.DescriptorImpl.class).doClearCache("library", false);
        assertThat(new File(cache2.getRemote()), not(anExistingDirectory()));
        assertThat(new File(cache2.withSuffix("-name.txt").getRemote()), not(anExistingFile()));
    }

}
