/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.bootstrap;

import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

import cpw.mods.jarhandling.SecureJar;
import net.minecraftforge.bootstrap.api.BootstrapClasspathModifier;
import net.minecraftforge.bootstrap.api.BootstrapEntryPoint;
import net.minecraftforge.securemodules.SecureModuleClassLoader;
import net.minecraftforge.securemodules.SecureModuleFinder;
import net.minecraftforge.unsafe.UnsafeHacks;

public class Bootstrap {
    static final boolean DEBUG = Boolean.getBoolean("bsl.debug");
    static void log(String message) {
        System.out.println(message);
    }

    protected void start(String... args) throws Exception {
        var raw = findAllClassPathEntries();
        var classpath = new ArrayList<Path[]>(raw.size());
        var processed = new ArrayList<Path[]>(raw.size());
        for (var path : raw) {
            classpath.add(new Path[] { path });
            processed.add(new Path[] { path });
        }

        var cl = Thread.currentThread().getContextClassLoader();
        var modified = false;
        for (var itr = ServiceLoader.load(BootstrapClasspathModifier.class, cl).iterator(); itr.hasNext(); ) {
            var service = itr.next();
            if (DEBUG)
                log("Calling Service: " + service.name());
            modified |= service.process(processed);
        }

        if (!modified) {
            bootstrapMain(args, classpath);
            return;
        }

        if (modified) {
            if (DEBUG)
                log("Services modified the classpath, building new classloader:");

            var urls = new ArrayList<URL>();
            for (var paths : processed) {
                if (paths == null)
                    continue;

                if (paths.length == 1) {
                    var url = paths[0].toUri().toURL();
                    urls.add(url);
                    if (DEBUG)
                        log("    " + url);
                } else {
                    var ordered = new Path[paths.length];
                    // SecureJar is last win instead of first win like the class path
                    for (int x = 0; x < paths.length; x++)
                        ordered[x] = paths[paths.length - x - 1];
                    var jar = SecureJar.from(ordered);
                    var url = jar.getRootPath().toUri().toURL();
                    urls.add(url);
                    if (DEBUG) {
                        log("    " + url);
                        for (var path : paths)
                            log("        " + path.toUri().toURL());
                    }
                }
            }

            var platform = ClassLoader.getPlatformClassLoader(); // Use Platform so any modules that arn't explicitly asked for can be found/hot loaded.
            var newCL = new URLClassLoader("CLEANED-BOOTSTRAP", urls.toArray(URL[]::new), platform);

            try {
                Thread.currentThread().setContextClassLoader(newCL);
                // Find ourselves in the new class loader with joined paths
                var self = Class.forName(this.getClass().getName(), false, newCL);
                var inst = self.getDeclaredConstructor().newInstance();

                // And now invoke main as if we had done all the command line arguments to specify modules!
                var main = findMethod(self, "bootstrapMain", String[].class, List.class);
                if (main == null)
                    throw new IllegalStateException("Could not find \"bootstrapMain(String[], List<Path[]>))\" on " + self.getName());
                UnsafeHacks.setAccessible(main);
                main.invoke(inst, (Object)args, processed);
            } finally {
                Thread.currentThread().setContextClassLoader(cl);
            }
        }
    }

    protected void bootstrapMain(String[] args, List<Path[]> classpath) {
        try {
            // Default parent class loader
            var cl = Thread.currentThread().getContextClassLoader();
            // This should be the AppClassloader but doesn't quite work right, can't remember why off hand but I had it commented out for a reason
            // cl == BaseBootstrap.class.getClassLoader();
            var boot = selectBootModules(classpath);

            // First we need to get ourselves onto a module layer, so that we can be the parent of the actual runtime layer
            var finder = SecureModuleFinder.of(boot.toArray(SecureJar[]::new));
            var targets = boot.stream().map(SecureJar::name).toList();
            var cfg = ModuleLayer.boot().configuration().resolve(finder, ModuleFinder.ofSystem(), targets);
            var layer = ModuleLayer.boot().defineModulesWithOneLoader(cfg, cl);

            // Find ourselves in the new fancy module environment.
            var bootstrap = layer.findModule("net.minecraftforge.bootstrap").get();
            var moduleCl = bootstrap.getClassLoader();
            var self = Class.forName(this.getClass().getName(), false, moduleCl);
            var inst = self.getDeclaredConstructor().newInstance();

            // And now invoke main as if we had done all the command line arguments to specify modules!
            var moduleMain = findMethod(self, "moduleMain", String[].class, List.class);
            if (moduleMain == null)
                throw new IllegalStateException("Could not find \"moduleMain(String[], List<Path[]>))\" on " + self.getName());
            UnsafeHacks.setAccessible(moduleMain);
            moduleMain.invoke(inst, (Object)args, classpath);
        } catch (Exception e) {
            sneak(e);
        }
    }

    protected List<SecureJar> selectBootModules(List<Path[]> classpath) {
        var ret = new ArrayList<SecureJar>();
        var bootLibraries = Set.of(
            "cpw.mods.securejarhandler",
            "net.minecraftforge.unsafe",
            "net.minecraftforge.bootstrap",
            "net.minecraftforge.bootstrap.api",
            "org.objectweb.asm",
            "org.objectweb.asm.tree"
        );

        for (var paths : classpath) {
            var jar = secureJar(paths);
            if (bootLibraries.contains(jar.moduleDataProvider().name()))
                ret.add(jar);
        }

        return ret;
    }

    protected void moduleMain(String[] args, List<Path[]> classpath) throws Exception {
        var bootlayer = getClass().getModule().getLayer();
        var secure = selectRuntimeModules(classpath);

        // Now lets build a layer that has all the non-Bootstrap/SecureModule libraries on it.
        var finder = SecureModuleFinder.of(secure.toArray(SecureJar[]::new));
        var targets = secure.stream().map(SecureJar::name).toList();
        var cfg = bootlayer.configuration().resolveAndBind(finder, ModuleFinder.ofSystem(), targets);
        var parent = List.of(ModuleLayer.boot(), bootlayer);

        // Use the current classloader as the parent, if set, so that we don't get things from the bootstrap loader.
        var oldcl = Thread.currentThread().getContextClassLoader();
        var cl = new SecureModuleClassLoader("SECURE-BOOTSTRAP", null, cfg, parent, oldcl == null ? List.of() : List.of(oldcl));
        var layer = bootlayer.defineModules(cfg, module -> cl);

        try {
            Thread.currentThread().setContextClassLoader(cl);
            var services = ServiceLoader.load(layer, BootstrapEntryPoint.class).stream().toList();

            if (services.isEmpty())
                throw new IllegalStateException("Could not find any " + BootstrapEntryPoint.class.getName() + " service providers");

            if (services.size() > 1) {
                throw new IllegalStateException("Found multiple " + BootstrapEntryPoint.class.getName() + " service providers: " +
                    services.stream().map(p -> p.get().name()).collect(Collectors.joining(", ")));
            }

            var loader = services.get(0).get();
            if (DEBUG) log("Starting: " + loader.getClass().getModule().getName() + '/' + loader.name());
            loader.main(args);
        } finally {
            Thread.currentThread().setContextClassLoader(oldcl);
        }
    }

    protected List<SecureJar> selectRuntimeModules(List<Path[]> classpath) {
        var jars = new ArrayList<SecureJar>();
        for (var paths : classpath)
            jars.add(secureJar(paths));

        var ret = new ArrayList<SecureJar>();
        var bootlayer = getClass().getModule().getLayer();
        int width = DEBUG
                ? jars.stream().mapToInt(j -> j.moduleDataProvider().name().length()).max().orElse(0) + 1
                : 0;

        if (DEBUG) log("Found classpath:");
        for (int x = 0; x < classpath.size(); x++) {
            var jar = jars.get(x);
            var name = jar.moduleDataProvider().name();
            Path[] paths = DEBUG ? classpath.get(x) : null;

            if (bootlayer.findModule(name).isPresent()) {
                log("  Bootstrap: ", width, name, paths);
                continue;
            }

            log("  Module:    ", width, name, paths);
            ret.add(jar);
        }

        return ret;
    }

    protected static String pad(int width, String str) {
        return str + " ".repeat(width - str.length());
    }

    protected static void log(String prefix, int width, String name, Path[] paths) {
        if (!DEBUG)
            return;
        log(prefix + pad(width, name) + paths[paths.length - 1]);
        prefix = " ".repeat(width + prefix.length());
        for (int x = paths.length - 2; x >= 0; x--)
            log(prefix + paths[x]);
    }

    protected SecureJar secureJar(Path[] paths) {
        var ordered = paths;
        if (paths.length > 1) {
            ordered = new Path[paths.length];
            // Securejar is last win instead of first win like the classpath
            for (int x = 0; x < paths.length; x++)
                ordered[x] = paths[paths.length - x - 1];
        }
        return SecureJar.from(ordered);
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... parameterTypes) {
        try {
            return cls.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException | SecurityException e) {
            if (cls.getSuperclass() != null)
                return findMethod(cls.getSuperclass(), name, parameterTypes);
        }
        return null;
    }

    private static List<Path> findAllClassPathEntries() throws IOException {
        var parts = System.getProperty("java.class.path").split(File.pathSeparator);
        var paths = new ArrayList<Path>();
        for (var part : parts) {
            var path = new File(part).getCanonicalFile().toPath();
            if (!Files.exists(path)) {
                //if (DEBUG) log("Skipping missing: " + path);
                continue;
            }
            if (Files.isDirectory(path) && Files.list(path).findAny().isEmpty()) {
                //if (DEBUG) log("Skipping empty:   " + path);
                continue;
            }
            paths.add(path);
        }
        return paths;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, R> R sneak(Exception exception) throws E {
        throw (E)exception;
    }
}
