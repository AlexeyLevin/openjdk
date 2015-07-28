/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.internal.module;

import java.io.File;
import java.lang.module.Configuration;
import java.lang.module.Layer;
import java.lang.module.Layer.ClassLoaderFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Module;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jdk.internal.misc.BootLoader;
import jdk.internal.misc.Modules;
import sun.misc.PerfCounter;
import sun.misc.SharedSecrets;

/**
 * Initializes/boots the module system.
 *
 * The {@link #boot() boot} method is called early in the startup to initialize
 * the module system. In summary, the boot method creates a Configuration by
 * resolving a set of module names specified via the launcher (or equivalent)
 * -m and -addmods options. The modules are located on a module path that is
 * constructed from the upgrade, system and application module paths. The
 * Configuration is reified by creating the boot Layer with each module in the
 * the configuration defined to one of the built-in class loaders. The mapping
 * of modules to class loaders is statically mapped in a helper class.
 */

public final class ModuleBootstrap {
    private ModuleBootstrap() { }

    private static final String JAVA_BASE = "java.base";

    /**
     * Initialize the module system.
     *
     * @see java.lang.System#initPhase2
     */
    public static void boot() {
        long t0 = System.nanoTime();

        // -upgrademodulepath option specified to launcher
        ModuleFinder upgradeModulePath =
            createModulePathFinder("java.upgrade.module.path");

        // system module path, aka the installed modules
        ModuleFinder systemModulePath = ModuleFinder.ofInstalled();

        // -modulepath option specified to the launcher
        ModuleFinder appModulePath = createModulePathFinder("java.module.path");

        // The module finder: [-upgrademodulepath] system-module-path [-modulepath]
        ModuleFinder finder = systemModulePath;
        if (upgradeModulePath != null)
            finder = ModuleFinder.concat(upgradeModulePath, finder);
        if (appModulePath != null)
            finder = ModuleFinder.concat(finder, appModulePath);

        // Once the finder is created then we find the base module and define
        // it to the boot loader. We do this here so that resources in the
        // base module can be located for error messages that may happen
        // from here on.
        Optional<ModuleReference> obase = finder.find(JAVA_BASE);
        if (!obase.isPresent())
            throw new InternalError(JAVA_BASE + " not found");
        BootLoader.register(obase.get());

        // launcher -m option to specify the initial module
        String mainModule = null;
        String propValue = System.getProperty("java.module.main");
        if (propValue != null) {
            int i = propValue.indexOf('/');
            String s = (i == -1) ? propValue : propValue.substring(0, i);
            mainModule = s;
        }

        // additional module(s) specified by -addmods
        Set<String> additionalMods = null;
        propValue = System.getProperty("jdk.launcher.addmods");
        if (propValue != null) {
            additionalMods = new HashSet<>();
            for (String mod: propValue.split(",")) {
                additionalMods.add(mod);
            }
        }

        // -limitmods
        boolean limitmods = false;
        propValue = System.getProperty("jdk.launcher.limitmods");
        if (propValue != null) {
            Set<String> mods = new HashSet<>();
            for (String mod: propValue.split(",")) {
                mods.add(mod);
            }
            if (mainModule != null)
                mods.add(mainModule);
            finder = limitFinder(finder, mods);
            limitmods = true;
        }

        // The root modules to resolve
        Set<String> roots = new HashSet<>();
        if (mainModule != null) {

            // main/initial module
            roots.add(mainModule);

        } else {

            // If there is no initial module specified then assume that the
            // initial module is the unnamed module of the application class
            // loader. By convention, and for compatibility, this is
            // implemented by putting the names of all modules on the system
            // module path into the set of modules to resolve. If the
            // -limitmods option is specified then it may be a subset of the
            // system module path.
            Set<ModuleReference> mrefs = systemModulePath.findAll();
            if (limitmods) {
                ModuleFinder f = finder;
                mrefs = mrefs.stream()
                    .filter(m -> f.find(m.descriptor().name()).isPresent())
                    .collect(Collectors.toSet());
            }
            // map to module names
            for (ModuleReference mref : mrefs) {
                roots.add(mref.descriptor().name());
            }
        }

        // If -addmods is specified then these module names must be resolved
        if (additionalMods != null)
            roots.addAll(additionalMods);

        long t1 = System.nanoTime();

        // run the resolver to create the configuration
        Configuration cf = (Configuration.resolve(finder,
                                                  Layer.empty(),
                                                  ModuleFinder.empty(),
                                                  roots)
                            .bind());

        // time to create configuration
        PerfCounters.configTime.addElapsedTimeFrom(t1);

        // mapping of modules to class loaders
        ClassLoaderFinder clf = ModuleLoaderMap.classLoaderFinder(cf);

        // check that all modules to be mapped to the boot loader will be
        // loaded from the system module path
        if (finder != systemModulePath) {
            for (ModuleReference mref : cf.references()) {
                String name = mref.descriptor().name();
                ClassLoader cl = clf.loaderForModule(name);
                if (cl == null) {

                    if (upgradeModulePath != null
                            && upgradeModulePath.find(name).isPresent())
                        fail(name + ": cannot be loaded from upgrade module path");

                    if (!systemModulePath.find(name).isPresent())
                        fail(name + ": cannot be loaded from application module path");
                }
            }
        }

        long t2 = System.nanoTime();

        // define modules to VM/runtime
        Layer bootLayer = Layer.create(cf, clf);

        // if -XaddExports is specified then process the value to export
        // additional API packages
        propValue= System.getProperty("jdk.launcher.addexports");
        if (propValue != null)
            addMoreExports(bootLayer, propValue);

        // time to reify modules
        PerfCounters.bootLayerTime.addElapsedTimeFrom(t2);

        // set the boot Layer
        SharedSecrets.getJavaLangModuleAccess().setBootLayer(bootLayer);

        // total time to initialize
        PerfCounters.bootstrapTime.addElapsedTimeFrom(t0);
    }

    /**
     * Returns a ModuleFinder that locates modules via the given
     * ModuleFinder but limits what can be found to the given
     * modules and their transitive dependences.
     */
    private static ModuleFinder limitFinder(ModuleFinder finder,
                                            Set<String> mods)
    {
        Configuration cf = Configuration.resolve(finder,
                                                 Layer.empty(),
                                                 ModuleFinder.empty(),
                                                 mods);

        // module name -> reference
        Map<String, ModuleReference> map = new HashMap<>();
        cf.descriptors().forEach(md -> {
            String name = md.name();
            map.put(name, finder.find(name).get());
        });

        Set<ModuleReference> mrefs = new HashSet<>(map.values());

        return new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(String name) {
                return Optional.ofNullable(map.get(name));
            }
            @Override
            public Set<ModuleReference> findAll() {
                return mrefs;
            }
        };
    }

    /**
     * Creates a finder from the module path that is the value of the given
     * system property.
     */
    private static ModuleFinder createModulePathFinder(String prop) {
        String s = System.getProperty(prop);
        if (s == null) {
            return null;
        } else {
            String[] dirs = s.split(File.pathSeparator);
            Path[] paths = new Path[dirs.length];
            int i = 0;
            for (String dir: dirs) {
                paths[i++] = Paths.get(dir);
            }
            return ModuleFinder.of(paths);
        }
    }

    /**
     * The value of -XaddExports is a sequence of $MODULE/$PACKAGE=$TARGET
     * where $TARGET is a module name or the token "ALL-UNNAMED".
     *
     * If the sequence contains $MODULE/$PACKAGE (no =$TARGET) then an
     * unqualified export is created. This is temporary and will be removed
     * once jtreg and tests have been updated to export to ALL-UNNAMED.
     */
    private static void addMoreExports(Layer bootLayer, String moreExports) {
        for (String expr: moreExports.split(",")) {
            if (expr.length() > 0) {

                String[] s = expr.split("=");
                if (s.length == 1)
                    fail("Missing target module: " + expr);
                if (s.length != 2)
                    fail("Unable to parse: " + expr);

                // $MODULE/$PACKAGE
                String[] moduleAndPackage = s[0].split("/");
                if (moduleAndPackage.length != 2)
                    fail("Unable to parse: " + expr);

                String mn = moduleAndPackage[0];
                String pn = moduleAndPackage[1];

                // source module
                Module source;
                Optional<Module> om = bootLayer.findModule(mn);
                if (!om.isPresent())
                    fail("Unknown source module: " + mn);
                source = om.get();

                // $TARGET
                boolean allUnnamed = false;
                String tn = s[1];
                Module target = null;
                if ("ALL-UNNAMED".equals(tn)) {
                    allUnnamed = true;
                } else {
                    om = bootLayer.findModule(tn);
                    if (om.isPresent()) {
                        target = om.get();
                    } else {
                        fail("Unknown target module: " + tn);
                    }
                }

                if (allUnnamed) {
                    Modules.addExportsToAllUnnamed(source, pn);
                } else {
                    Modules.addExports(source, pn, target);
                }
            }
        }
    }


    /**
     * Throws a RuntimeException with the given message
     */
    static void fail(String m) {
        throw new RuntimeException(m);
    }

    static class PerfCounters {
        static PerfCounter bootstrapTime
            = PerfCounter.newPerfCounter("jdk.module.bootstrap.totalTime");
        static PerfCounter configTime
            = PerfCounter.newPerfCounter("jdk.module.bootstrap.configTime");
        static PerfCounter bootLayerTime
            = PerfCounter.newPerfCounter("jdk.module.bootstrap.createLayerTime");
    }
}
