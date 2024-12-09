/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.bootstrap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import cpw.mods.jarhandling.SecureJar;

public class ForgeBootstrap extends Bootstrap {
    private static final String MODS_TOML = "META-INF/mods.toml";
    private static final String MINECRAFT = "net/minecraft/client/main/Main.class";
    private static final Attributes.Name MOD_TYPE = new Attributes.Name("FMLModType");

    public static void main(String[] args) throws Exception {
        new ForgeBootstrap().start(args);
    }

    @Override
    protected List<SecureJar> selectRuntimeModules(List<Path[]> classpath) {
        var jars = new ArrayList<SecureJar>(classpath.size());
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
            var meta = jar.moduleDataProvider();
            var name = meta.name();
            Path[] paths = DEBUG ? classpath.get(x) : null;

            if (bootlayer.findModule(name).isPresent()) {
                log("  Bootstrap: ", width, name, paths);
                continue;
            }

            // If it's a mod we'll find it later
            if (meta.findFile(MODS_TOML).isPresent() ||
                meta.findFile(MINECRAFT).isPresent() ||
                meta.getManifest().getMainAttributes().getValue(MOD_TYPE) != null) {
                log("  ModFile:   ", width, name, paths);
                continue;
            }

            log("  Module:    ", width, name, paths);
            ret.add(jar);
        }

        return ret;
    }
}
