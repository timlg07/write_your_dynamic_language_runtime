package fr.umlv.smalljs.jvminterp;

import static fr.umlv.smalljs.rt.JSObject.UNDEFINED;
import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.foldArguments;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodType.methodType;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;

import fr.umlv.smalljs.rt.Failure;
import fr.umlv.smalljs.rt.JSObject;

public final class RT {
  private static final MethodHandle LOOKUP, REGISTER, TRUTH, GET_MH, METH_LOOKUP_MH, PARAMETER_COUNT_CHECK;
  static {
    var lookup = lookup();
    try {
      LOOKUP = lookup.findVirtual(JSObject.class, "lookup", methodType(Object.class, String.class));
      REGISTER = lookup.findVirtual(JSObject.class, "register", methodType(void.class, String.class, Object.class));
      TRUTH = lookup.findStatic(RT.class, "truth", methodType(boolean.class, Object.class));

      GET_MH = lookup.findVirtual(JSObject.class, "getMethodHandle", methodType(MethodHandle.class));
      METH_LOOKUP_MH = lookup.findStatic(RT.class, "lookupMethodHandle", methodType(MethodHandle.class, JSObject.class, String.class));
      PARAMETER_COUNT_CHECK = lookup.findStatic(RT.class, "parameterCountCheck", methodType(MethodHandle.class, MethodHandle.class, int.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  public static Object bsm_undefined(Lookup lookup, String name, Class<?> type) {
    return UNDEFINED;
  }

  public static Object bsm_const(Lookup lookup, String name, Class<?> type, int constant) {
    return constant;
  }

  private static MethodHandle parameterCountCheck(MethodHandle mh, int parameterCount) {
    var type = mh.type();
    if (!mh.isVarargsCollector() && type.parameterCount() != parameterCount) {
      throw new Failure("invalid parameter count: expected " + (parameterCount-1) + " but got " + (type.parameterCount()-1));
    }
    return mh;
  }

  /*
  public static CallSite bsm_funcall(Lookup lookup, String name, MethodType type) {
    // take GET_MH method handle
    var combiner = GET_MH;
    // check param count
    var parameterCountCheck = insertArguments(PARAMETER_COUNT_CHECK, 1, type.parameterCount()-1);
    combiner = filterReturnValue(combiner, parameterCountCheck);
    // make it accept an Object (not a JSObject) as first parameter
    combiner = combiner.asType(methodType(MethodHandle.class, Object.class));
    // create a generic invoker (MethodHandles.invoker()) on the parameter types without the qualifier
    var invoker = invoker(type.dropParameterTypes(0, 1));
    // drop the qualifier
    invoker = dropArguments(invoker, 1, Object.class);
    // use MethodHandles.foldArguments with GET_MH as combiner
    var target = foldArguments(invoker, combiner);
    // create a constant callsite
    return new ConstantCallSite(target);
  }
  */

  public static CallSite bsm_funcall(Lookup lookup, String name, MethodType type) {
    return new InliningCache(type);
  }

  private static class InliningCache extends MutableCallSite {
    private static final MethodHandle SLOW_PATH, POINTER_CHECK, FALLBACK_PATH;

    static {
      var lookup = lookup();
      try {
        SLOW_PATH = lookup.findVirtual(InliningCache.class, "slowPath", methodType(MethodHandle.class, Object.class, Object.class));
        POINTER_CHECK = lookup.findStatic(InliningCache.class, "pointerCheck", methodType(boolean.class, Object.class, JSObject.class));
        FALLBACK_PATH = lookup.findVirtual(InliningCache.class, "fallbackPath", methodType(MethodHandle.class, Object.class, Object.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    private static final int MAX_DEPTH = 3; // in java: 2
    private int depth;

    public InliningCache(MethodType type) {
      super(type);
      setTarget(foldArguments(exactInvoker(type), SLOW_PATH.bindTo(this)));
    }

    private static boolean pointerCheck(Object qualifier, JSObject expectedQualifier) {
      return qualifier == expectedQualifier;
    }

    private MethodHandle fallbackPath(Object qualifier, Object receiver) {
      var jsObject = (JSObject)qualifier;
      var mh = jsObject.getMethodHandle();
      MethodType t = type();

      System.err.println("slow path called with " + qualifier);

      parameterCountCheck(mh, t.parameterCount() - 1);

      return dropArguments(mh, 0, Object.class) // loses track of varargs and treats them as Object[]
              .withVarargs(mh.isVarargsCollector())  // restore varargs
              .asType(t);
    }

    private MethodHandle slowPath(Object qualifier, Object receiver) {
      var jsObject = (JSObject)qualifier;
      var target = fallbackPath(qualifier, receiver);

      if (++depth <= MAX_DEPTH) {
        // check if the type is correct
        var check = insertArguments(POINTER_CHECK, 1, jsObject);
        var guard = guardWithTest(check, target, getTarget());
        setTarget(guard);
      } else {
        // give up on inlining, otherwise the assembly code will get too big
        setTarget(foldArguments(exactInvoker(type()), FALLBACK_PATH.bindTo(this)));
      }

      return target;
    }
  }

  public static CallSite bsm_lookup(Lookup lookup, String name, MethodType type, String functionName) {
    var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
    var globalEnv = classLoader.getGlobal();
    // get the LOOKUP method handle
    // use the global environment as first argument and the functionName as second argument
    var target = insertArguments(LOOKUP, 0, globalEnv, functionName);
    // create a constant callsite
    return new ConstantCallSite(target);
  }

  public static Object bsm_fun(Lookup lookup, String name, Class<?> type, int funId) {
    var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
    var globalEnv = classLoader.getGlobal();
    var fun = classLoader.getDictionary().lookupAndClear(funId);
    return ByteCodeRewriter.createFunction(fun.optName().orElse("lambda"), fun.parameters(), fun.body(), globalEnv);
  }

  public static CallSite bsm_register(Lookup lookup, String name, MethodType type, String functionName) {
    var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
    var globalEnv = classLoader.getGlobal();
    //get the REGISTER method handle
    // use the global environment as first argument and the functionName as second argument
    var target = insertArguments(REGISTER, 0, globalEnv, functionName);
    // create a constant callsite
    return new ConstantCallSite(target);
  }

  @SuppressWarnings("unused")  // used by a method handle
  private static boolean truth(Object o) {
    return o != null && o != UNDEFINED && o != Boolean.FALSE;
  }
  public static CallSite bsm_truth(Lookup lookup, String name, MethodType type) {
    // get the TRUTH method handle
    var target = TRUTH;
    // create a constant callsite
    return new ConstantCallSite(target);
  }

  public static CallSite bsm_get(Lookup lookup, String name, MethodType type, String fieldName) {
    throw new UnsupportedOperationException("TODO bsm_get");
    // get the LOOKUP method handle
    // use the fieldName as second argument
    // make it accept an Object (not a JSObject) as first parameter
    // create a constant callsite
  }

  public static CallSite bsm_set(Lookup lookup, String name, MethodType type, String fieldName) {
    throw new UnsupportedOperationException("TODO bsm_set");
    // get the REGISTER method handle
    // use the fieldName as second argument
    // make it accept an Object (not a JSObject) as first parameter
    // create a constant callsite
  }

  @SuppressWarnings("unused")  // used by a method handle
  private static MethodHandle lookupMethodHandle(JSObject receiver, String fieldName) {
    var function = (JSObject) receiver.lookup(fieldName);
    return function.getMethodHandle();
  }

  public static CallSite bsm_methodcall(Lookup lookup, String name, MethodType type) {
    throw new UnsupportedOperationException("TODO bsm_methodcall");
    //var combiner = insertArguments(METH_LOOKUP_MH, 1, name).asType(methodType(MethodHandle.class, Object.class));
    //var target = foldArguments(invoker(type), combiner);
    //return new ConstantCallSite(target);
  }
}
