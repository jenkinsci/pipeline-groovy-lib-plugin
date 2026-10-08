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

import hudson.plugins.git.GitSCM;
import org.hamcrest.Matchers;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LibraryConfigurationTest {

    private JenkinsRule r;

    @BeforeEach
    void beforeEach(JenkinsRule rule) {
        r = rule;
    }

    @Issue("JENKINS-38550")
    @Test
    void visibleRetrievers() {
        assertThat(r.jenkins.getDescriptorByType(LibraryConfiguration.DescriptorImpl.class).getRetrieverDescriptors(),
            Matchers.containsInAnyOrder(r.jenkins.getDescriptorByType(SCMSourceRetriever.DescriptorImpl.class), r.jenkins.getDescriptorByType(SCMRetriever.DescriptorImpl.class)));
    }

    @Issue("JENKINS-59527")
    @Test
    void validDefaultVersionAndName() {
        String libraryName = "valid-name";
        String defaultVersion = "master";

        LibraryConfiguration cfg = new LibraryConfiguration(libraryName, new SCMRetriever(new GitSCM("https://phony.jenkins.io/bar.git")));
        cfg.setDefaultVersion(defaultVersion);

        assertEquals("valid-name", cfg.getName());
        assertEquals("master", cfg.getDefaultVersion());
    }

    @Issue("JENKINS-59527")
    @Test
    void spacesDefaultVersionAndName() {
        String libraryName = "     valid-name   ";
        String defaultVersion = "   master    ";

        LibraryConfiguration cfg = new LibraryConfiguration(libraryName, new SCMRetriever(new GitSCM("https://phony.jenkins.io/bar.git")));
        cfg.setDefaultVersion(defaultVersion);

        assertEquals("valid-name", cfg.getName());
        assertEquals("master", cfg.getDefaultVersion());
    }

    @Issue("JENKINS-59527")
    @Test
    void emptyStringDefaultVersionAndName() {
        String libraryName = "";
        String defaultVersion = "";

        LibraryConfiguration cfg = new LibraryConfiguration(libraryName, new SCMRetriever(new GitSCM("https://phony.jenkins.io/bar.git")));
        cfg.setDefaultVersion(defaultVersion);

        assertNull(cfg.getName());
        assertNull(cfg.getDefaultVersion());
    }

    @Issue("JENKINS-59527")
    @Test
    void nullDefaultVersionAndName() {
        String libraryName = null;
        String defaultVersion = null;

        LibraryConfiguration cfg = new LibraryConfiguration(libraryName, new SCMRetriever(new GitSCM("https://phony.jenkins.io/bar.git")));
        cfg.setDefaultVersion(defaultVersion);

        assertNull(cfg.getName());
        assertNull(cfg.getDefaultVersion());
    }
}
