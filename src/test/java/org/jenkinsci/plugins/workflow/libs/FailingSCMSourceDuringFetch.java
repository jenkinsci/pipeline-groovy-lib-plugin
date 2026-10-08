package org.jenkinsci.plugins.workflow.libs;

import hudson.AbortException;
import hudson.model.TaskListener;
import jenkins.scm.api.*;

import edu.umd.cs.findbugs.annotations.NonNull;

import java.io.IOException;

public class FailingSCMSourceDuringFetch extends FailingSCMSource {

    @Override
    protected SCMRevision retrieve(@NonNull final String thingName, @NonNull final TaskListener listener) throws IOException, InterruptedException {
        throw new AbortException("Failing 'fetch' on purpose!");
    }
}
