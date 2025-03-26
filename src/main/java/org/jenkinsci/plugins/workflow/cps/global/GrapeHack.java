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

package org.jenkinsci.plugins.workflow.cps.global;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import groovy.grape.Grape;
import groovy.grape.GrapeEngine;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.util.MaskingClassLoader;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.ivy.core.cache.DefaultRepositoryCacheManager;
import org.apache.ivy.core.cache.RepositoryCacheManager;
import org.apache.ivy.core.settings.IvySettings;
import org.apache.ivy.plugins.lock.LockStrategy;
import org.apache.ivy.plugins.lock.NoLockStrategy;

public class GrapeHack {

    private static final Logger LOGGER = Logger.getLogger(GrapeHack.class.getName());

    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "for script console")
    public static boolean DISABLE_NIO_FILE_LOCK =
            Boolean.getBoolean(GrapeHack.class.getName() + ".DISABLE_NIO_FILE_LOCK");

    @Initializer(after=InitMilestone.PLUGINS_PREPARED, fatal=false)
    public static void hack() throws Exception {
        try {
            GrapeEngine engine = Grape.getInstance();
            LOGGER.log(Level.FINE, "{0} was already set, not touching it", engine);
        } catch (RuntimeException | LinkageError x) {
            LOGGER.log(Level.FINE, "by default we could not load Grape", x);
        }
        String grapeIvyName = "groovy.grape.GrapeIvy";
        String ivyGrabRecordName = "groovy.grape.IvyGrabRecord"; // another top-level class
        URL groovyJar = Grape.class.getProtectionDomain().getCodeSource().getLocation();
        LOGGER.log(Level.FINE, "using {0}", groovyJar);
        ClassLoader l = new URLClassLoader(new URL[] {groovyJar}, new MaskingClassLoader(GrapeHack.class.getClassLoader(), grapeIvyName, ivyGrabRecordName));
        Class<?> c = Class.forName(grapeIvyName, false, l);
        Field instance = Grape.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, c.newInstance());
        GrapeEngine engine = Grape.getInstance();
        LOGGER.log(Level.FINE, "successfully loaded {0}", engine);
        l = engine.getClass().getClassLoader();
        LOGGER.log(Level.FINE, "was also able to load {0}", l.loadClass(ivyGrabRecordName));
        LOGGER.log(Level.FINE, "linked to {0}", l.loadClass("org.apache.ivy.core.module.id.ModuleRevisionId").getProtectionDomain().getCodeSource().getLocation());
        if (!DISABLE_NIO_FILE_LOCK) {
            try {
                /*
                 * We must use reflection instead of simply casting to GrapeIvy and invoking directly due to the use of
                 * MaskingClassLoader a few lines above.
                 */
                IvySettings settings = (IvySettings) c.getMethod("getSettings").invoke(instance.get(null));
                RepositoryCacheManager repositoryCacheManager = settings.getDefaultRepositoryCacheManager();
                if (repositoryCacheManager instanceof DefaultRepositoryCacheManager) {
                    DefaultRepositoryCacheManager defaultRepositoryCacheManager =
                            (DefaultRepositoryCacheManager) repositoryCacheManager;
                    LockStrategy lockStrategy = defaultRepositoryCacheManager.getLockStrategy();
                    LOGGER.log(Level.FINE, "default lock strategy {0}", lockStrategy);
                    if (lockStrategy == null || lockStrategy instanceof NoLockStrategy) {
                        lockStrategy = settings.getLockStrategy("artifact-lock-nio");
                        if (lockStrategy != null) {
                            defaultRepositoryCacheManager.setLockStrategy(lockStrategy.getName());
                            defaultRepositoryCacheManager.setLockStrategy(lockStrategy);
                        }
                    }
                    LOGGER.log(Level.FINE, "using lock strategy {0}", defaultRepositoryCacheManager.getLockStrategy());
                }
            } catch (RuntimeException | LinkageError x) {
                LOGGER.log(Level.FINE, "failed to enable NIO file lock", x);
            }
        }
    }

    private GrapeHack() {}

}
