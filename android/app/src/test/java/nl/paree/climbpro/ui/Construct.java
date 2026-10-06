package nl.paree.climbpro.ui;

import java.lang.reflect.Constructor;

/** Builds domain value objects whose constructors are package-private, for view inputs. */
public final class Construct {

    private Construct() {}

    @SuppressWarnings("unchecked")
    public static <T> T of(Class<T> type, Object... args) {
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length != args.length) continue;
            boolean ok = true;
            for (int i = 0; i < p.length && ok; i++) ok = fits(p[i], args[i]);
            if (!ok) continue;
            try {
                c.setAccessible(true);
                return (T) c.newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot construct " + type.getName(), e);
            }
        }
        throw new AssertionError("no matching constructor on " + type.getName());
    }

    private static boolean fits(Class<?> p, Object a) {
        if (a == null) return !p.isPrimitive();
        if (p.isInstance(a)) return true;
        if (!p.isPrimitive()) return false;
        if (p == int.class) return a instanceof Integer;
        if (p == long.class) return a instanceof Long || a instanceof Integer;
        if (p == double.class) return a instanceof Double;
        if (p == float.class) return a instanceof Float;
        if (p == boolean.class) return a instanceof Boolean;
        return false;
    }
}
