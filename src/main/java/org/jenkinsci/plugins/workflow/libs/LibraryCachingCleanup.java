package org.jenkinsci.plugins.workflow.libs;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import jenkins.model.Jenkins;

import jenkins.util.SystemProperties;

@Extension public class LibraryCachingCleanup extends AsyncPeriodicWork {
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "non-final for script console access")
    public static int EXPIRE_AFTER_READ_DAYS =
            SystemProperties.getInteger(LibraryCachingCleanup.class.getName() + ".EXPIRE_AFTER_READ_DAYS", 7);

    public LibraryCachingCleanup() {
        super("LibraryCachingCleanup");
    }

    @Override public long getRecurrencePeriod() {
        return TimeUnit.HOURS.toMillis(12);
    }

    @Override protected void execute(TaskListener listener) throws IOException, InterruptedException {
        FilePath globalCacheDir = LibraryCachingConfiguration.getGlobalLibrariesCacheDir();
        for (FilePath libJar : globalCacheDir.list()) {
            removeIfExpiredCacheJar(libJar);
        }
        // Old cache directory; format has changed, so just delete it:
        Jenkins.get().getRootPath().child("global-libraries-cache").deleteRecursive();
    }

    /**
     * Delete the specified cache JAR if it is outdated.
     */
    private void removeIfExpiredCacheJar(FilePath libJar) throws IOException, InterruptedException {
        final FilePath lastReadFile = libJar.sibling(libJar.getBaseName() + "." + LibraryCachingConfiguration.LAST_READ_FILE);
        if (lastReadFile.exists()) {
            ReentrantReadWriteLock retrieveLock = LibraryAdder.getReadWriteLockFor(libJar.getBaseName());
            retrieveLock.writeLock().lockInterruptibly();
            try {
                if (System.currentTimeMillis() - lastReadFile.lastModified() > TimeUnit.DAYS.toMillis(EXPIRE_AFTER_READ_DAYS)) {
                    libJar.delete();
                }
            } finally {
                retrieveLock.writeLock().unlock();
            }
        }
    }
}
