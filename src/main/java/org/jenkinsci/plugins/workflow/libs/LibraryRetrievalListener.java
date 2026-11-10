/*
 * The MIT License
 *
 * Copyright 2026 Noam Goren
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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Run;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Observes the retrieval of each library of a build, for tracing, metrics or logging.
 * <p>
 * Both {@code @Library} and the {@code library} step retrieve through the same code, so a listener sees both.
 * {@link #onStart} and {@link #onEnd} of one retrieval run on the same thread, which lets an implementation open
 * and close a scope. The time spent in a cache lock and in copying from the cache is inside the interval.
 * Nothing is sent when a build resumes, because nothing is retrieved then.
 * <p>
 * Listeners must be quick and must not throw: an exception from a listener is logged and ignored, and never changes
 * the result of the retrieval. {@link #onEnd} is called only for a listener whose {@link #onStart} returned normally.
 * <p>
 * No credential material is passed to a listener. The arguments are the library record (name, version, trusted,
 * changelog, source, caching configuration), the retriever, the run, the cache outcome and the error. An
 * implementation that reads the retriever should take care not to record the SCM URL, which may carry user information.
 */
@Restricted(Beta.class)
public abstract class LibraryRetrievalListener implements ExtensionPoint {

    /** How a retrieval used the library cache. */
    @Restricted(Beta.class)
    public enum CacheOutcome {
        /** The library has no caching configuration. It was fetched. */
        DISABLED,
        /** The library has a caching configuration, but caching does not apply to this version. It was fetched. */
        EXCLUDED,
        /** A valid cache entry existed. The library was copied from the cache. */
        HIT,
        /** There was no cache entry. The library was fetched and cached. */
        MISS,
        /** The cache entry was older than the refresh time. The library was fetched and cached again. */
        EXPIRED,
        /** The cache entry existed but was empty. The library was fetched and cached again. */
        EMPTY_RECACHED
    }

    /**
     * Called just before a library is retrieved.
     * @param record the library, as resolved for this build
     * @param retriever how the library is fetched
     * @param run the build
     */
    public void onStart(@NonNull LibraryRecord record, @NonNull LibraryRetriever retriever, @NonNull Run<?, ?> run) {}

    /**
     * Called after a library was retrieved, or failed to be retrieved.
     * @param record the library, as resolved for this build
     * @param retriever how the library is fetched
     * @param run the build
     * @param outcome how the cache was used; null if the retrieval failed before the cache was consulted
     * @param error the failure, or null if the retrieval succeeded
     */
    public void onEnd(@NonNull LibraryRecord record, @NonNull LibraryRetriever retriever, @NonNull Run<?, ?> run,
                      @CheckForNull CacheOutcome outcome, @CheckForNull Throwable error) {}

    /** All registered listeners. */
    public static @NonNull ExtensionList<LibraryRetrievalListener> all() {
        return ExtensionList.lookup(LibraryRetrievalListener.class);
    }
}
