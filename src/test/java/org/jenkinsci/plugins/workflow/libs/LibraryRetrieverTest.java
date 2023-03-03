/*
 * The MIT License
 *
 * Copyright 2023 CloudBees, Inc.
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

import hudson.FilePath;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LibraryRetrieverTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void justVars() throws Exception {
        assertDir2Jar(Set.of("vars/xxx.groovy", "vars/yyy.groovy"), Set.of("xxx.groovy", "yyy.groovy"));
    }

    @Test public void justSrc() throws Exception {
        assertDir2Jar(Set.of("src/p1/xxx.groovy", "src/p2/yyy.groovy"), Set.of("p1/xxx.groovy", "p2/yyy.groovy"));
    }

    @Test public void theWorks() throws Exception {
        assertDir2Jar(Set.of("src/p1/xxx.groovy", "vars/yyy.groovy", "resources/a.txt", "resources/b/c.txt"), Set.of("p1/xxx.groovy", "yyy.groovy", "resources/a.txt", "resources/b/c.txt"));
    }

    // TODO assert that other files are not copied

    private void assertDir2Jar(Set<String> inputs, Set<String> outputs) throws Exception {
        FilePath work = new FilePath(tmp.newFolder());
        FilePath dir = work.child("dir");
        for (String input : inputs) {
            dir.child(input).write("xxx", null);
        }
        FilePath jar = work.child("x.jar");
        LibraryRetriever.dir2Jar("mylib", dir, jar);
        Set<String> actualOutputs = new TreeSet<>();
        try (JarFile jf = new JarFile(jar.getRemote())) {
            assertThat(jf.getManifest().getMainAttributes().getValue(LibraryRetriever.ATTR_LIBRARY_NAME), is("mylib"));
            jf.stream().forEach(e -> {
                String name = e.getName();
                if (!name.endsWith("/") && !name.startsWith("META-INF/")) {
                    actualOutputs.add(name);
                }
            });
        }
        assertThat(actualOutputs, is(outputs));
        assertThat(work.list(), containsInAnyOrder(dir, jar));
    }

}
