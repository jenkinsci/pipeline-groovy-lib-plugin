/*
 * The MIT License
 *
 * Copyright 2024 CloudBees, Inc.
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

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.security.Permission;
import jenkins.model.Jenkins;

/**
 * Manages untrusted libraries available to any job in the system.
 */
@Extension public class GlobalUntrustedLibraries extends AbstractGlobalLibraries {

    public GlobalUntrustedLibraries() {
        super();
    }

    @Override
    public String getDescription() {
        return Messages.GlobalUntrustedLibraries_Description();
    }

    @NonNull
    @Override
    public String getDisplayName() {
        return Messages.GlobalUntrustedLibraries_DisplayName();
    }

    public static @NonNull GlobalUntrustedLibraries get() {
        return ExtensionList.lookupSingleton(GlobalUntrustedLibraries.class);
    }

    @NonNull
    @Override
    public Permission getRequiredGlobalConfigPagePermission() {
        return Jenkins.MANAGE;
    }

    @Extension(ordinal=0) public static class ForJob extends AbstractForJob {
        protected GlobalUntrustedLibraries getConfiguration() {
            return get();
        }

        @Override
        public boolean isTrusted() {
            return false;
        }
    }
}
