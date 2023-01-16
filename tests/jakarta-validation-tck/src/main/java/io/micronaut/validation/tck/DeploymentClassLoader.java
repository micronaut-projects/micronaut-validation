package io.micronaut.validation.tck;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class DeploymentClassLoader extends URLClassLoader {
    static {
        ClassLoader.registerAsParallelCapable();
    }

    DeploymentClassLoader(DeploymentDir deploymentDir) throws IOException {
        super(findUrls(deploymentDir));
    }

    private static URL[] findUrls(DeploymentDir deploymentDir) throws IOException {
        List<URL> result = new ArrayList<>();

        result.add(deploymentDir.target.toUri().toURL());

        try (Stream<Path> stream = Files.walk(deploymentDir.lib)) {
            List<Path> jars = stream.filter(p -> p.toString().endsWith(".jar")).collect(Collectors.toList());
            for (Path jar : jars) {
                result.add(jar.toUri().toURL());
            }
        }

        return result.toArray(new URL[0]);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return super.getResourceAsStream(name);
    }

    @Override
    public URL getResource(String name) {
        return super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        return super.getResources(name);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> clazz = findLoadedClass(name);
            if (clazz != null) {
                return clazz;
            }

            try {
                clazz = findClass(name);
                if (resolve) {
                    resolveClass(clazz);
                }
                return clazz;
            } catch (ClassNotFoundException ignored) {
                return super.loadClass(name, resolve);
            }
        }
    }
}
