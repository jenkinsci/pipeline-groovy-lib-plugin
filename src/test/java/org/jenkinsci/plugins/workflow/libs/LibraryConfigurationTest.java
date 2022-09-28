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

import java.util.Collections;

import hudson.AbortException;
import hudson.plugins.git.GitSCM;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.Assert;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

public class LibraryConfigurationTest {

    @Rule public JenkinsRule r = new JenkinsRule();

    @Issue("JENKINS-38550")
    @Test public void visibleRetrievers() throws Exception {
        assertThat(r.jenkins.getDescriptorByType(LibraryConfiguration.DescriptorImpl.class).getRetrieverDescriptors(),
            Matchers.<LibraryRetrieverDescriptor>containsInAnyOrder(r.jenkins.getDescriptorByType(SCMSourceRetriever.DescriptorImpl.class), r.jenkins.getDescriptorByType(SCMRetriever.DescriptorImpl.class)));
    }

    @Issue("JENKINS-59527")
    @Test public void validDefaultVersionAndName() {
        String libraryName = "valid-name";
        String defaultVersion = "master";

        LibraryConfiguration cfg = new LibraryConfiguration(libraryName, new SCMRetriever(new GitSCM("https://phony.jenkins.io/bar.git")));
        cfg.setDefaultVersion(defaultVersion);

        assertEquals("valid-name", cfg.getName());
        assertEquals("master", cfg.getDefaultVersion());
    }

    @Issue("JENKINS-59527")
    @Test public void spacesDefaultVersionAndName() {
        String libraryName = "     valid-name   ";
        String defaultVersion = "   master    ";

        LibraryConfiguration cfg = new LibraryConfiguration(libraryName, new SCMRetriever(new GitSCM("https://phony.jenkins.io/bar.git")));
        cfg.setDefaultVersion(defaultVersion);

        assertEquals("valid-name", cfg.getName());
        assertEquals("master", cfg.getDefaultVersion());
    }

    @Issue("JENKINS-59527")
    @Test public void emptyStringDefaultVersionAndName() {
        String libraryName = "";
        String defaultVersion = "";

        LibraryConfiguration cfg = new LibraryConfiguration(libraryName, new SCMRetriever(new GitSCM("https://phony.jenkins.io/bar.git")));
        cfg.setDefaultVersion(defaultVersion);

        assertNull(cfg.getName());
        assertNull(cfg.getDefaultVersion());
    }

    @Issue("JENKINS-59527")
    @Test public void nullDefaultVersionAndName() {
        String libraryName = null;
        String defaultVersion = null;

        LibraryConfiguration cfg = new LibraryConfiguration(libraryName, new SCMRetriever(new GitSCM("https://phony.jenkins.io/bar.git")));
        cfg.setDefaultVersion(defaultVersion);

        assertNull(cfg.getName());
        assertNull(cfg.getDefaultVersion());
    }

    @Issue("JENKINS-69731")
    @Test public void nullPresentDefaultedVersion() {
        String libraryName = "valid-name";
        String defaultVersion = "master";

        LibraryConfiguration cfg = new LibraryConfiguration(libraryName, new SCMRetriever(new GitSCM("https://phony.jenkins.io/bar.git")));
        cfg.setDefaultVersion(defaultVersion);

        assertEquals("master", cfg.getDefaultVersion());
        try {
            assertEquals("master", cfg.defaultedVersion(null));
        } catch(AbortException ae) {
            Assert.fail("LibraryConfiguration.defaultedVersion() threw an AbortException when it was not expected: " + ae.getMessage());
        }
    }

    @Issue("JENKINS-69731")
    @Test public void nullAbsentDefaultedVersion() {
        String libraryName = "valid-name";

        LibraryConfiguration cfg = new LibraryConfiguration(libraryName, new SCMRetriever(new GitSCM("https://phony.jenkins.io/bar.git")));

        assertEquals(null, cfg.getDefaultVersion());
        assertThrows(AbortException.class, () -> cfg.defaultedVersion(null));
    }

    @Issue("JENKINS-69731")
    @Test public void forbiddenOverrideDefaultedVersion() {
        String libraryName = "valid-name";

        LibraryConfiguration cfg = new LibraryConfiguration(libraryName, new SCMRetriever(new GitSCM("https://phony.jenkins.io/bar.git")));
        cfg.setAllowVersionOverride(false);

        assertEquals(false, cfg.isAllowVersionOverride());
        assertThrows(AbortException.class, () -> cfg.defaultedVersion("branchname"));
    }

    @Issue("JENKINS-69731")
    @Test public void allowedOverrideDefaultedVersion() {
        String libraryName = "valid-name";

        LibraryConfiguration cfg = new LibraryConfiguration(libraryName, new SCMRetriever(new GitSCM("https://phony.jenkins.io/bar.git")));
        cfg.setAllowVersionOverride(true);

        assertEquals(true, cfg.isAllowVersionOverride());
        try {
            assertEquals("branchname", cfg.defaultedVersion("branchname"));
        } catch(AbortException ae) {
            Assert.fail("LibraryConfiguration.defaultedVersion() threw an AbortException when it was not expected: " + ae.getMessage());
        }
    }

    @Issue("JENKINS-69731")
    @Test public void notAllowedOverrideDefaultedVersionWhenBRANCH_NAME() {
        String libraryName = "valid-name";

        LibraryConfiguration cfg = new LibraryConfiguration(libraryName, new SCMRetriever(new GitSCM("https://phony.jenkins.io/bar.git")));
        cfg.setAllowVersionOverride(true);
        cfg.setAllowBRANCH_NAME(false);

        assertEquals(true, cfg.isAllowVersionOverride());
        assertEquals(false, cfg.isAllowBRANCH_NAME());
        assertThrows(AbortException.class, () -> cfg.defaultedVersion("${BRANCH_NAME}"));
        /* This SHOULD NOT return a version string that literally remains '${BRANCH_NAME}'! */
    }

    @Issue("JENKINS-69731")
    @Test public void allowedBRANCH_NAMEnoRunPresentDefaultedVersion() {
        String libraryName = "valid-name";
        String defaultVersion = "master";

        LibraryConfiguration cfg = new LibraryConfiguration(libraryName, new SCMRetriever(new GitSCM("https://phony.jenkins.io/bar.git")));
        cfg.setDefaultVersion(defaultVersion);
        cfg.setAllowBRANCH_NAME(true);

        assertEquals(true, cfg.isAllowBRANCH_NAME());
        try {
            assertEquals("master", cfg.defaultedVersion("${BRANCH_NAME}", null, null));
        } catch(AbortException ae) {
            Assert.fail("LibraryConfiguration.defaultedVersion() threw an AbortException when it was not expected: " + ae.getMessage());
        }
    }

    @Issue("JENKINS-69731")
    @Test public void allowedBRANCH_NAMEnoRunAbsentDefaultedVersion() {
        String libraryName = "valid-name";

        LibraryConfiguration cfg = new LibraryConfiguration(libraryName, new SCMRetriever(new GitSCM("https://phony.jenkins.io/bar.git")));
        cfg.setAllowBRANCH_NAME(true);

        assertEquals(true, cfg.isAllowBRANCH_NAME());
        assertThrows(AbortException.class, () -> cfg.defaultedVersion("${BRANCH_NAME}", null, null));
    }

    /* Note: further tests for JENKINS-69731 behaviors with allowBRANCH_NAME
     * would rely on having a Run with or without a BRANCH_NAME envvar, and
     * a TaskListener, and a (mock?) LibraryRetriever that would confirm or
     * deny existence of a requested "version" (e.g. Git branch) of the lib.
     * For examples, see e.g. SCMSourceRetrieverTest codebase.
     */

}
