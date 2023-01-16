package io.micronaut.validation.tck;

final class ClassLoading {
    static Class<?>[] convertToTCCL(Class<?>[] classes) throws ClassNotFoundException {
        return convertToCL(classes, Thread.currentThread().getContextClassLoader());
    }

    static Class<?>[] convertToCL(Class<?>[] classes, ClassLoader classLoader) throws ClassNotFoundException {
        Class<?>[] result = new Class<?>[classes.length];
        for (int i = 0; i < classes.length; i++) {
            if (classes[i].getClassLoader() != classLoader) {
                result[i] = classLoader.loadClass(classes[i].getName());
            } else {
                result[i] = classes[i];
            }
        }
        return result;
    }
}
